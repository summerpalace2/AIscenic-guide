package com.ai.guide.infrastructure.planner.taxonomy;

import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;
import com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort;
import com.ai.guide.domain.attraction.gateway.AttractionAliasTaxonomyPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads validated assets as one immutable snapshot; failed reloads retain the last good snapshot. */
@Component
@Primary
public class JsonTaxonomyAdapter implements TaxonomyPort, DynamicShortTripPolicyPort, CandidateRankingPolicyPort, AttractionAliasTaxonomyPort, com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort {
    private static final Logger log = LoggerFactory.getLogger(JsonTaxonomyAdapter.class);
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path directory;
    private volatile Snapshot snapshot;
    private volatile long snapshotRevision;
    private volatile long nextCheck;
    private record Snapshot(Set<String> brands, Map<String, MealWindow> windows,
                            Map<String, CityCuisineProfile> cities, Map<String,List<String>> hints,
                            List<FlavorAlias> aliases, Map<String,String> cityAliases,
                            List<MemoryFacet> memoryFacets, List<String> memoryPresentationWords,
                            List<AdjustmentWindow> adjustmentWindows, Map<String, CategoryPolicyData> categoryPolicies,
                            List<QueryAliasGroup> queryAliasGroups, List<QuerySceneGroup> querySceneGroups,
                            List<InterestKeyword> interestKeywords, List<InterestKeyword> dietPreferenceKeywords,
                            Map<String,String> cityStrategyMappings, DiningSearchPolicy diningSearchPolicy,
                            CandidateRankingPolicyPort.Policy candidateRankingPolicy,
                            com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy poiIdentityPolicy,
                             List<IntentRadarRule> intentRadarRules,
                              DynamicShortTripPolicyPort.Policy dynamicShortTripPolicy,
                              Map<String,String> attractionAliases, Map<String,String> attractionCanonicalNames,
                              Map<String,List<String>> suburbanDistrictsByCity) {}

    public JsonTaxonomyAdapter() { this(""); }
    @Autowired
    public JsonTaxonomyAdapter(@Value("${planner.taxonomy.directory:}") String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
        reload();
    }
    private JsonNode load(String file) throws Exception {
        Path external = directory == null ? null : directory.resolve(file);
        try (InputStream input = external != null && Files.exists(external)
                ? Files.newInputStream(external) : new ClassPathResource("taxonomy/" + file).getInputStream()) {
            return mapper.readTree(input);
        }
    }
    /** Safe operational reload; malformed external configuration never replaces working policy. */
    public synchronized boolean reload() {
        try {
            JsonNode data = load("dining_taxonomy.json");
            Set<String> brands = new LinkedHashSet<>(strings(load("brand_dictionary.json").path("brands")));
            if (brands.isEmpty()) throw new IllegalArgumentException("brands must not be empty");
            Map<String,MealWindow> windows = new LinkedHashMap<>();
            data.path("mealWindows").fields().forEachRemaining(e -> {
                JsonNode v=e.getValue();
                for (String field : List.of("startMinute","endMinute","idealMinute"))
                    if (!v.path(field).isIntegralNumber()) throw new IllegalArgumentException("invalid meal minute");
                int start=v.path("startMinute").asInt(), end=v.path("endMinute").asInt(), ideal=v.path("idealMinute").asInt();
                if (start < 0 || end > 1440 || start >= end || ideal < start || ideal >= end)
                    throw new IllegalArgumentException("invalid meal window: "+e.getKey());
                windows.put(e.getKey(),new MealWindow(start,end,ideal,v.path("label").asText()));
            });
            for (String slot: List.of("BREAKFAST","LUNCH","DINNER","TEA_BREAK","NIGHT_SNACK"))
                if (!windows.containsKey(slot)) throw new IllegalArgumentException("missing window: "+slot);
            Map<String,CityCuisineProfile> cities=new LinkedHashMap<>();
            data.path("cityCuisineProfiles").fields().forEachRemaining(e -> cities.put(e.getKey(),
                    new CityCuisineProfile(e.getKey(),e.getValue().path("defaultBreakfast").asText(),
                            strings(e.getValue().path("signatureDishes")),strings(e.getValue().path("breakfastKeywords")))));
            if (!cities.containsKey("DEFAULT")) throw new IllegalArgumentException("missing DEFAULT city profile");
            Map<String,List<String>> hints = new LinkedHashMap<>();
            data.path("legacyHints").fields().forEachRemaining(e -> hints.put(e.getKey(),strings(e.getValue())));
            Map<String, Integer> plannerLimits = requiredPositiveIntMap(hints.getOrDefault("plannerLimits", List.of()));
            for (String required : List.of("maxTripDays", "maxDailyScheduleMinutes", "maxReplanNonDiningStops", "maxRepairableScenicStops", "maxSingleTransferMinutes"))
                if (!plannerLimits.containsKey(required)) throw new IllegalArgumentException("missing planner limit: " + required);
            Set<String> scoreKeys = new HashSet<>();
            for (String weight : hints.getOrDefault("plannerScoreWeights", List.of())) {
                String[] parts = weight.split("=", 2);
                if (parts.length != 2 || parts[0].isBlank() || !scoreKeys.add(parts[0]))
                    throw new IllegalArgumentException("invalid or duplicate planner score weight");
                try {
                    if (!Double.isFinite(Double.parseDouble(parts[1]))) throw new IllegalArgumentException("planner score weight must be finite");
                } catch (NumberFormatException invalidWeight) {
                    throw new IllegalArgumentException("planner score weight must be numeric", invalidWeight);
                }
            }
            if (scoreKeys.isEmpty()) throw new IllegalArgumentException("plannerScoreWeights must not be empty");
            requirePolicyKeys("plannerScoreWeights", scoreKeys, PlannerTaxonomyRequirements.PLANNER_SCORE_WEIGHTS);
            Set<String> runtimeScoreKeys = new HashSet<>();
            for (String parameter : hints.getOrDefault("runtimeScoringParameters", List.of())) {
                String[] parts = parameter.split("=", 2);
                if (parts.length != 2 || parts[0].isBlank() || !runtimeScoreKeys.add(parts[0]))
                    throw new IllegalArgumentException("invalid or duplicate runtime scoring parameter");
                try {
                    if (!Double.isFinite(Double.parseDouble(parts[1]))) throw new IllegalArgumentException("runtime scoring parameter must be finite");
                } catch (NumberFormatException invalidParameter) {
                    throw new IllegalArgumentException("runtime scoring parameter must be numeric", invalidParameter);
                }
            }
            if (runtimeScoreKeys.isEmpty()) throw new IllegalArgumentException("runtimeScoringParameters must not be empty");
            requirePolicyKeys("runtimeScoringParameters", runtimeScoreKeys, PlannerTaxonomyRequirements.RUNTIME_SCORING_PARAMETERS);
            requirePolicyKeys("plannerLimits", plannerLimits.keySet(), PlannerTaxonomyRequirements.PLANNER_LIMITS);
            List<String> configuredDurationDays = hints.getOrDefault("defaultDurationDays", List.of());
            if (configuredDurationDays.size() != 1) throw new IllegalArgumentException("defaultDurationDays must contain exactly one value");
            try {
                int days = Integer.parseInt(configuredDurationDays.get(0));
                if (days < 1 || days > plannerLimits.get("maxTripDays"))
                    throw new IllegalArgumentException("defaultDurationDays exceeds configured maxTripDays");
            } catch (NumberFormatException invalidDays) {
                throw new IllegalArgumentException("defaultDurationDays must be an integer", invalidDays);
            }
            for (String key : List.of("runtimeNatureInterestFeatures", "runtimeHistoryInterestFeatures",
                    "plannerHistoryCategories", "plannerFoodCategories", "plannerNatureCategories",
                    "runtimeThermalInterestFeatures", "runtimeLocalCultureInterestFeatures", "runtimeProminentPoiFeatures",
                    "runtimeSecondaryPoiFeatures", "runtimeHistoricPoiFeatures", "runtimeFamilyFriendlyFeatures",
                    "runtimeStrenuousFeatures", "poiCategorySquareNameTokens", "poiCategoryLandmarkNameTokens",
                    "poiCategoryLandmarkTypeTokens", "poiCategoryHistoryNameTokens", "poiCategoryHistoryTypeTokens",
                    "poiCategoryCommercialNameTokens", "poiCategoryCommercialTypeTokens", "poiCategoryReligionNameTokens",
                    "poiCategoryReligionNameSuffixes", "poiCategoryReligionTypeTokens", "poiCategoryNatureNameTokens",
                    "poiCategoryNatureTypeTokens", "plannerPreferenceIndoorTerms", "plannerPreferenceLowWalkingTerms",
                    "plannerPreferenceAccessibilityTerms", "plannerPreferenceHistoryTerms", "plannerPreferenceNightViewTerms",
                    "plannerPreferenceNatureTerms", "plannerPreferenceFamilyTerms", "plannerPreferenceSameDistrictTerms",
                    "plannerPreferenceNicheTerms", "plannerPreferenceTheaterTerms", "plannerPreferenceShoppingTerms",
                    "plannerPreferenceReduceDensityTerms", "plannerReplanLowWalkingTerms",
                    "plannerConditionRainTerms", "plannerConditionHighTemperatureTerms", "diningProximityPromptTerms",
                    "diningBreakfastFlavorTerms", "diningNightSnackFlavorTerms", "diningDinnerFlavorTerms",
                    "teaVenueCategoryKeywords", "defaultDiningPreference", "defaultDiningDisplayLabel",
                    "nightSnackCategoryLabel", "teaBreakCategoryLabel", "genericDiningCategoryLabel",
                    "dinnerTagLabel", "supportedMealSlots", "diningStopCategories", "diningStopIcons", "defaultStopCategory", "mealRelativeAfterLunch", "mealRelativeBeforeLunch", "breakfastCategoryLabel",
                    "mealDisplayPrefixBreakfast", "mealDisplayPrefixLunch", "mealDisplayPrefixDinner",
                    "mealDisplayPrefixTeaBreak", "mealDisplayPrefixNightSnack",
                    "searchCategoryCoreLandmark", "searchCategoryScenicLandmark", "searchCategoryWebNews",
                    "chatNearbySearchPrefix", "chatNearbyAttractionSuffix", "chatNearbyDiningSuffix",
                    "runtimePoiFallbackSearchKeyword", "diningFallbackCandidateLimit",
                    "diningReplacementSupplementThreshold", "diningReplacementSupplementLimit",
                    "diningDefaultResultLimit", "diningAmapMaxResults", "slotBreakfast", "slotLunch",
                    "slotDinner", "slotNightSnack", "slotTeaBreak", "runtimeScenicTypeCode",
                    "runtimeDefaultStartTime", "runtimeDefaultMorningTime", "runtimeDefaultNoonTime",
                    "runtimeDefaultAfternoonTime", "runtimeDefaultEveningTime", "runtimeDefaultNightTime",
                    "runtimeActiveWindow", "runtimeLatestNightStartTime", "runtimeNightKeywords",
                    "runtimeTimeZone", "runtimeTimeRoundingMinutes")) {
                if (hints.getOrDefault(key, List.of()).isEmpty()) throw new IllegalArgumentException("missing planner vocabulary: " + key);
            }
            for (String key : List.of("diningFallbackCandidateLimit", "diningReplacementSupplementThreshold",
                    "diningReplacementSupplementLimit", "diningDefaultResultLimit", "diningAmapMaxResults")) {
                List<String> configured = hints.get(key);
                if (configured.size() != 1 || Integer.parseInt(configured.get(0)) < 1)
                    throw new IllegalArgumentException("invalid positive dining candidate limit: " + key);
            }
            validateRuntimeTimeHints(hints);
            Map<String,List<String>> suburbanDistrictsByCity = new LinkedHashMap<>();
            data.path("suburbanDistrictsByCity").fields().forEachRemaining(e -> {
                List<String> districts = strings(e.getValue());
                if (e.getKey().isBlank() || districts.isEmpty()) throw new IllegalArgumentException("invalid suburban district configuration");
                suburbanDistrictsByCity.put(e.getKey(), districts);
            });
            String defaultMealSlot = hints.getOrDefault("defaultMealSlot", List.of()).stream().findFirst().orElse("");
            if (!Set.of("BREAKFAST", "LUNCH", "DINNER", "TEA_BREAK", "NIGHT_SNACK").contains(defaultMealSlot.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("defaultMealSlot must name a supported dining slot");
            if (!hints.getOrDefault("supportedMealSlots", List.of()).contains(defaultMealSlot.toUpperCase(Locale.ROOT)))
                throw new IllegalArgumentException("supportedMealSlots must contain defaultMealSlot");
            if (!hints.getOrDefault("genericFlavors", List.of()).containsAll(hints.getOrDefault("defaultDiningPreference", List.of())))
                throw new IllegalArgumentException("defaultDiningPreference must be a configured generic flavor");
            List<FlavorAlias> aliases = new ArrayList<>();
            for (JsonNode v : data.path("flavorAliases")) {
                String searchKeyword = v.has("searchKeyword") ? v.path("searchKeyword").asText("").trim() : null;
                if (searchKeyword != null && (!v.path("searchKeyword").isTextual() || searchKeyword.isBlank()))
                    throw new IllegalArgumentException("invalid flavor search keyword");
                aliases.add(new FlavorAlias(v.path("label").asText(), strings(v.path("keywords")), searchKeyword,
                        v.has("poiTypeKeywords") ? strings(v.path("poiTypeKeywords")) : List.of()));
            }
            Map<String,String> cityAliases = new LinkedHashMap<>();
            for (String city : strings(load("city_names.json").path("cities"))) {
                String shortName = city.replaceAll("市$", "");
                if (!city.endsWith("市") || city.length() < 2) throw new IllegalArgumentException("invalid city name");
                String canonical = shortName.length() >= 2 ? shortName : city;
                cityAliases.put(city, canonical);
                if (shortName.length() >= 2) cityAliases.put(shortName, canonical);
            }
            List<MemoryFacet> memoryFacets = new ArrayList<>();
            Set<String> facetKeys = new HashSet<>();
            for (JsonNode facet : data.path("memoryProjection").path("facets")) {
                String category = facet.path("category").asText("").trim();
                String key = facet.path("key").asText("").trim();
                String summary = facet.path("summary").asText("").trim();
                List<String> facetAliases = strings(facet.path("aliases"));
                if (category.isBlank() || key.isBlank() || summary.isBlank() || facetAliases.isEmpty()
                        || !facetKeys.add(category + ":" + key)) throw new IllegalArgumentException("invalid memory facet");
                memoryFacets.add(new MemoryFacet(category, key, summary, facetAliases));
            }
            List<String> memoryWords = data.path("memoryProjection").has("presentationWords")
                    ? strings(data.path("memoryProjection").path("presentationWords")) : List.of();
            List<AdjustmentWindow> adjustmentWindows = new ArrayList<>();
            for (JsonNode window : data.path("adjustmentWindows")) {
                int start = window.path("startMinute").asInt(-1), end = window.path("endMinute").asInt(-1);
                String key = window.path("key").asText("");
                List<String> names = strings(window.path("aliases"));
                if (!window.path("startMinute").isIntegralNumber() || !window.path("endMinute").isIntegralNumber()
                        || start < 0 || end > 1440 || start >= end || key.isBlank() || names.isEmpty())
                    throw new IllegalArgumentException("invalid adjustment window");
                adjustmentWindows.add(new AdjustmentWindow(key, start, end, names));
            }
            Map<String, CategoryPolicyData> categoryPolicies = new LinkedHashMap<>();
            JsonNode policyRoot = load("poi_category_policies.json").path("categories");
            policyRoot.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                int min = v.path("minDurationMinutes").asInt(-1), max = v.path("maxDurationMinutes").asInt(-1);
                int duration = v.path("defaultDurationMinutes").asInt(-1), maxPerDay = v.path("maxOccurrencesPerDay").asInt(-1);
                int minInterval = v.path("minIntervalMinutesBetweenSameCategory").asInt(-1);
                if (min < 0 || max < min || duration < min || duration > max || maxPerDay < 1 || minInterval < 0
                        || !v.path("allowedAsFirstStop").isBoolean() || !v.path("allowedAsLastStop").isBoolean())
                    throw new IllegalArgumentException("invalid category policy: " + e.getKey());
                List<CategoryTimeWindow> preferred = new ArrayList<>();
                for (JsonNode w : v.path("preferredTimeWindows")) {
                    int start = w.path("startMinute").asInt(-1), end = w.path("endMinute").asInt(-1);
                    String label = w.path("label").asText("").trim();
                    if (start < 0 || end > 1440 || start >= end || label.isBlank())
                        throw new IllegalArgumentException("invalid category time window: " + e.getKey());
                    preferred.add(new CategoryTimeWindow(start, end, label));
                }
                if (preferred.isEmpty()) throw new IllegalArgumentException("missing category time windows: " + e.getKey());
                categoryPolicies.put(e.getKey(), new CategoryPolicyData(min, max, duration, preferred,
                        v.path("allowedAsFirstStop").asBoolean(), v.path("allowedAsLastStop").asBoolean(), maxPerDay, minInterval));
            });
            if (categoryPolicies.isEmpty()) throw new IllegalArgumentException("category policies must not be empty");
            JsonNode queryExpansion = load("rag_query_expansion.json");
            List<QueryAliasGroup> queryAliasGroups = new ArrayList<>();
            for (JsonNode group : queryExpansion.path("entities")) {
                String canonical = group.path("canonical").asText("").trim();
                List<String> values = strings(group.path("aliases"));
                if (canonical.isBlank() || values.isEmpty()) throw new IllegalArgumentException("invalid RAG entity alias group");
                queryAliasGroups.add(new QueryAliasGroup(canonical, values));
            }
            List<QuerySceneGroup> querySceneGroups = new ArrayList<>();
            for (JsonNode group : queryExpansion.path("scenes")) {
                String canonical = group.path("canonical").asText("").trim();
                List<String> values = strings(group.path("aliases"));
                if (canonical.isBlank() || values.isEmpty()) throw new IllegalArgumentException("invalid RAG scene alias group");
                querySceneGroups.add(new QuerySceneGroup(canonical, values));
            }
            if (queryAliasGroups.isEmpty() || querySceneGroups.isEmpty()) throw new IllegalArgumentException("RAG query expansion asset must not be empty");
            List<InterestKeyword> interestKeywords = new ArrayList<>();
            for (JsonNode item : data.path("interestKeywords")) {
                String label = item.path("label").asText("").trim();
                List<String> values = strings(item.path("aliases"));
                if (label.isBlank() || values.isEmpty()) throw new IllegalArgumentException("invalid interest keyword");
                interestKeywords.add(new InterestKeyword(label, values));
            }
            if (interestKeywords.isEmpty()) throw new IllegalArgumentException("interest keywords must not be empty");
            List<InterestKeyword> dietPreferenceKeywords = new ArrayList<>();
            for (JsonNode item : data.path("dietPreferenceKeywords")) {
                String label = item.path("label").asText("").trim();
                List<String> values = strings(item.path("aliases"));
                if (label.isBlank() || values.isEmpty()) throw new IllegalArgumentException("invalid diet preference keyword");
                dietPreferenceKeywords.add(new InterestKeyword(label, values));
            }
            if (dietPreferenceKeywords.isEmpty()) throw new IllegalArgumentException("diet preference keywords must not be empty");
            JsonNode search = data.path("diningSearch");
            int proximityRadius = positive(search, "proximityRadiusMeters");
            int nearbyRadius = positive(search, "nearbyRadiusMeters");
            int expansionStep = positive(search, "expansionStepMeters");
            int fallbackRadius = positive(search, "fallbackRadiusMeters");
            JsonNode bands = search.path("distanceBandScores");
            List<Integer> distanceBandScores = new ArrayList<>();
            if (!bands.isArray() || bands.size() != 4) throw new IllegalArgumentException("distanceBandScores must contain four scores");
            for (JsonNode score : bands) {
                if (!score.isIntegralNumber() || score.asInt() < 0) throw new IllegalArgumentException("invalid dining distance score");
                distanceBandScores.add(score.asInt());
            }
            Map<String,Integer> mealDurations = new LinkedHashMap<>();
            JsonNode durations = search.path("mealDurationsMinutes");
            durations.fields().forEachRemaining(e -> {
                if (!e.getValue().isIntegralNumber() || e.getValue().asInt() <= 0)
                    throw new IllegalArgumentException("invalid dining duration: " + e.getKey());
                mealDurations.put(e.getKey(), e.getValue().asInt());
            });
            for (String slot : List.of("BREAKFAST", "LUNCH", "DINNER", "NIGHT_SNACK", "TEA_BREAK"))
                if (!mealDurations.containsKey(slot)) throw new IllegalArgumentException("missing dining duration: " + slot);
            DiningSearchPolicy diningSearchPolicy = new DiningSearchPolicy(proximityRadius, nearbyRadius, expansionStep, fallbackRadius,
                    requiredText(search, "breakfastTypes"), requiredText(search, "mealTypes"),
                    requiredText(search, "teaBreakTypes"), requiredText(search, "genericMealKeyword"), requiredText(search, "genericTeaBreakKeyword"),
                    requiredText(search, "fallbackPoiType"), requiredText(search, "diningTypePrefix"), strings(search.path("nonMealTypeKeywords")),
                    positive(search, "proximityTimeBudgetMinutes"), positiveDouble(search, "proximityMaxDistanceKm"),
                    positiveDouble(search, "sameDistrictMaxDistanceKm"), positiveDouble(search, "suburbanMaxDistanceKm"),
                    positiveDouble(search, "crossDistrictMaxDistanceKm"), positiveDouble(search, "localFallbackMaxDistanceKm"),
                    positive(search, "veryNearDistanceMeters"), positiveDouble(search, "nearDistanceKm"), positiveDouble(search, "mediumDistanceKm"),
                    positive(search, "districtMatchScore"), positive(search, "exactFlavorScore"), positive(search, "citySignatureScore"),
                    positive(search, "mismatchPenalty"), distanceBandScores, positive(search, "elderFriendlyBonus"),
                    positive(search, "lowWalkingBonus"), positiveDouble(search, "lowWalkingDistanceKm"), Map.copyOf(mealDurations));
            JsonNode ranking = load("candidate_ranking_policy.json").path("candidateRanking");
            CandidateRankingPolicyPort.Policy candidateRankingPolicy = new CandidateRankingPolicyPort.Policy(
                    positiveDouble(ranking, "compactDistanceKm"), positiveDouble(ranking, "localDistanceKm"),
                    nonNegative(ranking, "compactDistanceBonus"), nonNegative(ranking, "localDistanceBonus"),
                    positiveDouble(ranking, "distancePenaltyPerKm"), positive(ranking, "shortTripBudgetMaximumMinutes"),
                    positiveDouble(ranking, "shortTripFarDistanceKm"), nonNegative(ranking, "shortTripFarPenalty"),
                    positiveDouble(ranking, "shortTripExtremeDistanceKm"), nonNegative(ranking, "shortTripExtremePenalty"),
                    nonNegative(ranking, "unknownLocationPenalty"), positiveDouble(ranking, "contextAverageWeight"),
                    positiveDouble(ranking, "contextNearestWeight"));
            if (!candidateRankingPolicy.configured()) throw new IllegalArgumentException("invalid candidate ranking policy");
            JsonNode poiIdentity = load("poi_identity_policy.json").path("poiIdentity");
            var poiIdentityPolicy = new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(
                    positive(poiIdentity, "exactNameScore"), positive(poiIdentity, "partialNameScore"),
                    nonNegative(poiIdentity, "coordinateBonus"), nonNegative(poiIdentity, "poiIdBonus"),
                    positive(poiIdentity, "minimumAcceptableScore"));
            if (!poiIdentityPolicy.configured()) throw new IllegalArgumentException("invalid POI identity policy");
            List<IntentRadarRule> intentRadarRules = new ArrayList<>();
            for (JsonNode rule : load("intent_radar_rules.json").path("rules")) {
                String domain = requiredText(rule, "domain");
                List<String> keywords = strings(rule.path("keywords"));
                String trait = requiredText(rule, "trait");
                double confidence = rule.path("confidence").asDouble(Double.NaN);
                if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("invalid intent radar confidence");
                List<IntentRadarSuggestion> suggestions = new ArrayList<>();
                for (JsonNode item : rule.path("suggestions")) suggestions.add(new IntentRadarSuggestion(
                        requiredText(item, "action"), requiredText(item, "label"), requiredText(item, "icon"), requiredText(item, "payload")));
                if (suggestions.isEmpty()) throw new IllegalArgumentException("intent radar rule requires suggestions");
                intentRadarRules.add(new IntentRadarRule(domain, keywords, trait, confidence, suggestions));
            }
            if (intentRadarRules.isEmpty()) throw new IllegalArgumentException("intent radar rules must not be empty");
            JsonNode dynamicPolicy = load("dynamic_short_trip_policy.json");
            List<DynamicShortTripPolicyPort.VisitDurationRule> visitRules = new ArrayList<>();
            for (JsonNode rule : dynamicPolicy.path("visitDurationRules")) {
                List<String> nameKeywords = strings(rule.path("nameKeywords"));
                List<String> typeKeywords = strings(rule.path("typeKeywords"));
                int minimumRemaining = nonNegative(rule, "minimumRemainingMinutes");
                int duration = positive(rule, "durationMinutes");
                if (nameKeywords.isEmpty() && typeKeywords.isEmpty()) throw new IllegalArgumentException("visit duration rule requires a matcher");
                visitRules.add(new DynamicShortTripPolicyPort.VisitDurationRule(nameKeywords, typeKeywords, minimumRemaining, duration));
            }
            visitRules.add(new DynamicShortTripPolicyPort.VisitDurationRule(List.of(), List.of(), 0,
                    positive(dynamicPolicy, "unknownStopDurationMinutes")));
            DynamicShortTripPolicyPort.Policy dynamicShortTripPolicy = new DynamicShortTripPolicyPort.Policy(
                    positive(dynamicPolicy, "diningMinimumBudgetMinutes"), positive(dynamicPolicy, "stopBudgetBaseMinutes"),
                    positive(dynamicPolicy, "minutesPerStop"), positive(dynamicPolicy, "minimumStops"),
                    positive(dynamicPolicy, "maximumStops"), positive(dynamicPolicy, "longParkBudgetMinutes"),
                    positive(dynamicPolicy, "shortTripMaxParks"), positive(dynamicPolicy, "longTripMaxParks"),
                    positive(dynamicPolicy, "minimumStopDurationMinutes"), positive(dynamicPolicy, "unknownStopDurationMinutes"),
                    positive(dynamicPolicy, "unknownTravelDurationMinutes"),
                    positive(dynamicPolicy, "minimumRemainingForStopMinutes"), positive(dynamicPolicy, "minimumVisitRemainderMinutes"),
                    positive(dynamicPolicy, "diningUrgencyMinimumRemainingMinutes"), positive(dynamicPolicy, "diningUrgencyMaximumRemainingMinutes"),
                    positive(dynamicPolicy, "explicitFoodDiningLeadMinutes"), positive(dynamicPolicy, "minimumRemainingToAddDiningMinutes"),
                    positiveDouble(dynamicPolicy, "transitPreferredDistanceKm"), positiveDouble(dynamicPolicy, "estimateTravelMinutesPerKm"),
                    positive(dynamicPolicy, "minimumEstimatedTravelMinutes"), positiveDouble(dynamicPolicy, "duplicateCandidateDistanceKm"),
                    positiveIntMap(dynamicPolicy.path("categoryOccurrenceLimits")),
                    List.copyOf(visitRules), readRouteFallback(dynamicPolicy.path("routeFallback")));
            if (dynamicShortTripPolicy.minimumStops() > dynamicShortTripPolicy.maximumStops())
                throw new IllegalArgumentException("invalid short trip stop bounds");
            if (!dynamicShortTripPolicy.routeFallbackPolicy().configured())
                throw new IllegalArgumentException("invalid route fallback policy bounds or relationships");
            Map<String,String> cityStrategyMappings = new LinkedHashMap<>();
            load("city_strategy_mappings.json").fields().forEachRemaining(entry -> {
                String city = entry.getKey().trim();
                String strategy = entry.getValue().asText("").trim();
                if (city.isBlank() || strategy.isBlank()) throw new IllegalArgumentException("invalid city strategy mapping");
                cityStrategyMappings.put(city, strategy.toUpperCase(Locale.ROOT));
            });
            JsonNode attractionAliasAsset = load("attraction_aliases.json");
            Map<String,String> attractionAliases = stringObjectMap(attractionAliasAsset.path("aliases"));
            Map<String,String> attractionCanonicalNames = stringObjectMap(attractionAliasAsset.path("canonicalNames"));
            snapshot = new Snapshot(Set.copyOf(brands),Map.copyOf(windows),Map.copyOf(cities),Map.copyOf(hints),List.copyOf(aliases),Map.copyOf(cityAliases),List.copyOf(memoryFacets),memoryWords,List.copyOf(adjustmentWindows),Map.copyOf(categoryPolicies),List.copyOf(queryAliasGroups),List.copyOf(querySceneGroups),List.copyOf(interestKeywords),List.copyOf(dietPreferenceKeywords),Map.copyOf(cityStrategyMappings), diningSearchPolicy, candidateRankingPolicy, poiIdentityPolicy, List.copyOf(intentRadarRules), dynamicShortTripPolicy, Map.copyOf(attractionAliases), Map.copyOf(attractionCanonicalNames), Map.copyOf(suburbanDistrictsByCity));
            snapshotRevision++;
            return true;
        } catch (Exception e) {
            if (snapshot == null) throw new IllegalStateException("Cannot initialize taxonomy assets",e);
            log.warn("Taxonomy reload rejected; keeping previous snapshot: {}",e.getMessage());
            return false;
        }
    }
    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Expected string array");
        List<String> values=new ArrayList<>();
        for (JsonNode value: array) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Expected nonblank string");
            values.add(value.asText().trim());
        }
        return List.copyOf(values);
    }
    private static Map<String,String> stringObjectMap(JsonNode object) {
        if (!object.isObject()) throw new IllegalArgumentException("Expected object of strings");
        Map<String,String> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            if (entry.getKey().isBlank() || !entry.getValue().isTextual() || entry.getValue().asText().isBlank())
                throw new IllegalArgumentException("Expected nonblank string map entry");
            values.put(entry.getKey().trim(), entry.getValue().asText().trim());
        });
        return Map.copyOf(values);
    }
    private static int positive(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || value.asInt() <= 0) throw new IllegalArgumentException("invalid positive dining search value: " + field);
        return value.asInt();
    }
    private static DynamicShortTripPolicyPort.RouteFallbackPolicy readRouteFallback(JsonNode node) {
        return new DynamicShortTripPolicyPort.RouteFallbackPolicy(
                positiveDouble(node, "distanceSafetyMultiplier"),
                positiveDouble(node, "nearbyDistanceKm"), positive(node, "nearbyMinimumMinutes"), positiveDouble(node, "nearbySpeedKmh"),
                positiveDouble(node, "sameDistrictTransitMaximumKm"), positive(node, "sameDistrictWalkingMeters"),
                positive(node, "sameDistrictMinimumMinutes"), positiveDouble(node, "sameDistrictSpeedKmh"), nonNegative(node, "sameDistrictOverheadMinutes"),
                positiveDouble(node, "farDistrictThresholdKm"), positive(node, "farDistrictWalkingMeters"),
                positive(node, "farDistrictMinimumMinutes"), positiveDouble(node, "farDistrictSpeedKmh"), nonNegative(node, "farDistrictOverheadMinutes"),
                positive(node, "crossDistrictWalkingMeters"), positive(node, "crossDistrictMinimumMinutes"),
                positiveDouble(node, "crossDistrictSpeedKmh"), nonNegative(node, "crossDistrictOverheadMinutes"));
    }
    private static int nonNegative(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || value.asInt() < 0) throw new IllegalArgumentException("invalid non-negative planner value: " + field);
        return value.asInt();
    }
    private static Map<String, Integer> positiveIntMap(JsonNode object) {
        if (!object.isObject()) throw new IllegalArgumentException("planner categoryOccurrenceLimits must be an object");
        Map<String, Integer> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            String key = entry.getKey().trim();
            if (key.isBlank() || !entry.getValue().isIntegralNumber() || entry.getValue().asInt() <= 0)
                throw new IllegalArgumentException("invalid planner category occurrence limit");
            values.put(key, entry.getValue().asInt());
        });
        return Map.copyOf(values);
    }
    private static Map<String, Integer> requiredPositiveIntMap(List<String> entries) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || values.containsKey(parts[0]))
                throw new IllegalArgumentException("invalid or duplicate planner limit");
            try {
                int limit = Integer.parseInt(parts[1]);
                if (limit <= 0) throw new IllegalArgumentException("planner limit must be positive");
                values.put(parts[0], limit);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("planner limit must be an integer", invalid);
            }
        }
        return values;
    }
    private static void requirePolicyKeys(String policyName, Set<String> actual, Set<String> required) {
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) throw new IllegalArgumentException(policyName + " missing required keys: " + missing);
    }
    private static void validateRuntimeTimeHints(Map<String, List<String>> hints) {
        for (String key : List.of("runtimeDefaultStartTime", "runtimeDefaultMorningTime", "runtimeDefaultNoonTime",
                "runtimeDefaultAfternoonTime", "runtimeDefaultEveningTime", "runtimeDefaultNightTime",
                "runtimeLatestNightStartTime")) {
            List<String> values = hints.getOrDefault(key, List.of());
            if (values.size() != 1) throw new IllegalArgumentException(key + " must contain one clock value");
            java.time.LocalTime.parse(values.get(0));
        }
        List<String> windows = hints.getOrDefault("runtimeActiveWindow", List.of());
        if (windows.size() != 1) throw new IllegalArgumentException("runtimeActiveWindow must contain one interval");
        String[] bounds = windows.get(0).split("\\|", -1);
        if (bounds.length != 2 || !java.time.LocalTime.parse(bounds[0]).isBefore(java.time.LocalTime.parse(bounds[1])))
            throw new IllegalArgumentException("runtimeActiveWindow must be an increasing clock interval");
        List<String> zones = hints.getOrDefault("runtimeTimeZone", List.of());
        if (zones.size() != 1) throw new IllegalArgumentException("runtimeTimeZone must contain one zone");
        java.time.ZoneId.of(zones.get(0));
        List<String> roundingValues = hints.getOrDefault("runtimeTimeRoundingMinutes", List.of());
        if (roundingValues.size() != 1) throw new IllegalArgumentException("runtimeTimeRoundingMinutes must contain one value");
        int rounding = Integer.parseInt(roundingValues.get(0));
        if (rounding < 1 || rounding > 60) throw new IllegalArgumentException("runtimeTimeRoundingMinutes out of range");
    }
    private static double positiveDouble(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= 0)
            throw new IllegalArgumentException("invalid positive taxonomy value: " + field);
        return value.asDouble();
    }
    private static String requiredText(JsonNode object, String field) {
        String value = object.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException("missing dining search value: " + field);
        return value;
    }
    private Snapshot current() {
        if (directory != null && System.currentTimeMillis() >= nextCheck) {
            synchronized(this) {
                if (System.currentTimeMillis() >= nextCheck) {
                    nextCheck=System.currentTimeMillis()+5000;
                    reload();
                }
            }
        }
        return snapshot;
    }
    @Override public DynamicShortTripPolicyPort.Policy getPolicy() { return current().dynamicShortTripPolicy(); }
    @Override public boolean isKnownBrand(String name) { return name != null && current().brands().contains(name.trim()); }
    @Override public Set<String> getBrands() { return current().brands(); }
    @Override public MealWindow getMealWindow(String slot) {
        Snapshot data=current();
        return data.windows().getOrDefault(slot == null ? "LUNCH" : slot.toUpperCase(Locale.ROOT),data.windows().get("LUNCH"));
    }
    @Override public CityCuisineProfile getCityProfile(String city) {
        Snapshot data=current();
        String normalized=city == null ? "" : city.trim().replaceAll("市$","");
        return data.cities().entrySet().stream().filter(e -> !e.getKey().equals("DEFAULT")
                && e.getKey().replaceAll("市$","").equals(normalized)).map(Map.Entry::getValue)
                .findFirst().orElse(data.cities().get("DEFAULT"));
    }
    @Override public List<String> getHints(String category) { return current().hints().getOrDefault(category,List.of()); }
    @Override public List<FlavorAlias> getFlavorAliases() { return current().aliases(); }
    @Override public Map<String,String> getCityAliases() { return current().cityAliases(); }
    @Override public List<AdjustmentWindow> getAdjustmentWindows() { return current().adjustmentWindows(); }
    @Override public List<MemoryFacet> getMemoryFacets() { return current().memoryFacets(); }
    @Override public List<String> getMemoryPresentationWords() { return current().memoryPresentationWords(); }
    @Override public List<QueryAliasGroup> getQueryAliasGroups() { return current().queryAliasGroups(); }
    @Override public List<QuerySceneGroup> getQuerySceneGroups() { return current().querySceneGroups(); }
    @Override public List<String> getRagStopwords() { return getHints("ragStopwords"); }
    @Override public List<String> getSlotFoodInterestKeywords() { return getHints("slotInterestFoodKeywords"); }
    @Override public List<String> getAreaNames() { return getHints("areaNames"); }
    @Override public List<InterestKeyword> getInterestKeywords() { return current().interestKeywords(); }
    @Override public List<InterestKeyword> getDietPreferenceKeywords() { return current().dietPreferenceKeywords(); }
    @Override public DiningSearchPolicy getDiningSearchPolicy() { return current().diningSearchPolicy(); }
    @Override public CandidateRankingPolicyPort.Policy getCandidateRankingPolicy() { return current().candidateRankingPolicy(); }
    @Override public com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy getPoiIdentityPolicy() { return current().poiIdentityPolicy(); }
    @Override public List<String> getSuburbanDistricts(String cityName) {
        String normalized = cityName == null ? "" : cityName.trim().replaceAll("市$", "");
        return current().suburbanDistrictsByCity().entrySet().stream()
                .filter(entry -> entry.getKey().replaceAll("市$", "").equals(normalized))
                .map(Map.Entry::getValue).findFirst().orElse(List.of());
    }
    @Override public List<IntentRadarRule> getIntentRadarRules() { return current().intentRadarRules(); }
    @Override public Map<String,String> getCityStrategyMappings() { return current().cityStrategyMappings(); }
    @Override public Map<String, CategoryPolicyData> getCategoryPolicies() { return current().categoryPolicies(); }
    @Override public Map<String,String> aliases() { return current().attractionAliases(); }
    @Override public Map<String,String> canonicalNames() { return current().attractionCanonicalNames(); }
    @Override public long revision() { current(); return snapshotRevision; }
}

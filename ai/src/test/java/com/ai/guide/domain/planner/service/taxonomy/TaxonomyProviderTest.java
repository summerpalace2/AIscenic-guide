package com.ai.guide.domain.planner.service.taxonomy;

import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class JsonTaxonomyAdapterTest {
    @TempDir Path directory;
    @Test void cityProfilesMatchExactlyRatherThanAnySubstring() {
        Path productionPolicy = Path.of("src/main/resources/taxonomy/dining_taxonomy.json");
        assertDoesNotThrow(() -> Files.copy(productionPolicy, directory.resolve("dining_taxonomy.json")));
        var provider=new JsonTaxonomyAdapter(directory.toString());
        assertEquals("重庆", provider.getDefaultCity());
        assertEquals(2, provider.getDefaultDurationDays());
        assertEquals("LUNCH", provider.getHints("defaultMealSlot").get(0));
        assertEquals("西安市",provider.getCityProfile("西安").cityName());
        assertEquals("DEFAULT",provider.getCityProfile("安").cityName());
        assertEquals("DEFAULT",provider.getCityProfile("武汉市").cityName());
    }

    @Test void suburbanDistanceExpansionIsScopedToConfiguredCity() {
        var provider = new JsonTaxonomyAdapter();
        assertTrue(provider.getSuburbanDistricts("重庆市").contains("武隆"));
        assertTrue(provider.getSuburbanDistricts("北京市").isEmpty());
    }
    @Test void poiCategoryPoliciesAreLoadedAsValidatedExternalAssets() {
        var provider = new JsonTaxonomyAdapter();
        assertEquals(6, provider.getCategoryPolicies().size());
        assertEquals(75, provider.getCategoryPolicies().get("DINING").defaultDurationMinutes());
        assertEquals(5, provider.getCategoryPolicies().get("DINING").preferredTimeWindows().size());
    }
    @Test void candidateRankingThresholdsAreLoadedFromExternalPolicy() {
        var policy = new JsonTaxonomyAdapter().getCandidateRankingPolicy();
        assertTrue(policy.configured());
        assertEquals(3.5, policy.compactDistanceKm());
        assertEquals(6.0, policy.localDistanceKm());
        assertEquals(360, policy.shortTripBudgetMaximumMinutes());
        assertEquals(0.6, policy.contextAverageWeight());
        assertEquals(0.4, policy.contextNearestWeight());
    }
    @Test void poiIdentityAcceptancePolicyIsLoadedAndValidatedFromExternalAsset() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var before = provider.getPoiIdentityPolicy();
        assertEquals(100, before.exactNameScore());
        assertEquals(60, before.partialNameScore());
        assertEquals(20, before.minimumAcceptableScore());
        var policy = new ObjectMapper().readTree(getClass().getResourceAsStream("/taxonomy/poi_identity_policy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) policy.path("poiIdentity")).put("partialNameScore", 110);
        Files.writeString(directory.resolve("poi_identity_policy.json"), policy.toString());
        assertFalse(provider.reload());
        assertEquals(before, provider.getPoiIdentityPolicy());
    }
    @Test void externalReloadIsAtomicAndRejectsBrokenWindows() throws Exception {
        var provider=new JsonTaxonomyAdapter(directory.toString());
        var old=provider.getMealWindow("LUNCH");
        var mapper=new ObjectMapper();
        var tree=mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mealWindows").path("LUNCH")).put("startMinute",750).put("idealMinute",760);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertTrue(provider.reload());
        assertEquals(750,provider.getMealWindow("LUNCH").startMinute());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mealWindows").path("LUNCH")).put("endMinute",700);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertFalse(provider.reload());
        assertEquals(750,provider.getMealWindow("LUNCH").startMinute());
        assertNotEquals(old,provider.getMealWindow("LUNCH"));
    }
    @Test void missingChatPoiSuffixPolicyCannotReplaceLastGoodTaxonomySnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var mapper = new ObjectMapper();
        var tree = mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("legacyHints")).remove("chatNearbyAttractionSuffix");
        Files.writeString(directory.resolve("dining_taxonomy.json"), tree.toString());

        assertFalse(provider.reload());
        assertEquals(java.util.List.of("附近景点"), provider.getHints("chatNearbyAttractionSuffix"));
    }
    @Test void flavorSearchPolicyReloadsAtomicallyAndRejectsMalformedKeyword() throws Exception {
        var provider=new JsonTaxonomyAdapter(directory.toString());
        var mapper=new ObjectMapper();
        var tree=mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        com.fasterxml.jackson.databind.node.ObjectNode dessert=null;
        for (var alias:tree.path("flavorAliases")) {
            if (alias.path("label").asText().equals("特色甜品糖水")) dessert=(com.fasterxml.jackson.databind.node.ObjectNode)alias;
        }
        assertNotNull(dessert);
        dessert.put("searchKeyword","糖水");
        dessert.putArray("poiTypeKeywords").add("甜品店");
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertTrue(provider.reload());
        var policy=provider.getFlavorAliases().stream().filter(alias->alias.label().equals("特色甜品糖水")).findFirst().orElseThrow();
        assertEquals("糖水",policy.searchKeyword());
        assertEquals(java.util.List.of("甜品店"),policy.poiTypeKeywords());
        assertThrows(UnsupportedOperationException.class,()->policy.poiTypeKeywords().clear());
        dessert.put("searchKeyword",42);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertFalse(provider.reload());
        assertEquals(policy,provider.getFlavorAliases().stream().filter(alias->alias.label().equals("特色甜品糖水")).findFirst().orElseThrow());
    }

    @Test void brokenBrandAssetDoesNotPartiallyInstallMealChange() throws Exception {
        var provider=new JsonTaxonomyAdapter(directory.toString());
        var window=provider.getMealWindow("LUNCH");
        Files.writeString(directory.resolve("brand_dictionary.json"),"{\"brands\":[42]}");
        assertFalse(provider.reload());
        assertEquals(window,provider.getMealWindow("LUNCH"));
        assertTrue(provider.isKnownBrand("肯德基"));
        assertThrows(UnsupportedOperationException.class,()->provider.getBrands().clear());
    }

    @Test void duplicateTaxonomyKeysAreRejectedWithoutReplacingTheLastGoodSnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var before = provider.getDiningSearchPolicy();
        String source = Files.readString(Path.of(getClass().getResource("/taxonomy/dining_taxonomy.json").toURI()));
        String duplicate = source.replaceFirst("\\{", "{\\\"mealWindows\\\":{},");
        Files.writeString(directory.resolve("dining_taxonomy.json"), duplicate);

        assertFalse(provider.reload());
        assertEquals(before, provider.getDiningSearchPolicy());
    }

    @Test void diningSearchThresholdsAndDurationsAreValidatedExternalAssets() {
        var policy = new JsonTaxonomyAdapter().getDiningSearchPolicy();
        assertTrue(policy.configured());
        assertEquals(600, policy.proximityTimeBudgetMinutes());
        assertEquals(2.8, policy.proximityMaxDistanceKm());
        assertEquals(60, policy.mealDurationsMinutes().get("LUNCH"));
        assertThrows(UnsupportedOperationException.class, () -> policy.mealDurationsMinutes().put("LUNCH", 1));
    }

    @Test void dynamicShortTripDurationsAndStopBudgetsComeFromPlannerAsset() {
        var policy = new JsonTaxonomyAdapter().getPolicy();
        assertEquals(60, policy.visitDuration("长城", "风景名胜;长城", 180));
        assertEquals(45, policy.visitDuration("地方博物馆", "科教文化服务;文博院馆", 180));
        assertEquals(90, policy.diningMinimumBudgetMinutes());
        assertEquals(240, policy.longParkBudgetMinutes());
        assertEquals(7, policy.maximumStops());
    }

    @Test void routeFallbackEstimationThresholdsAndSpeedsComeFromPlannerAsset() {
        var route = new JsonTaxonomyAdapter().getPolicy().routeFallbackPolicy();
        assertTrue(route.configured());
        assertEquals(1.35, route.distanceSafetyMultiplier());
        assertEquals(1.2, route.nearbyDistanceKm());
        assertEquals(30.0, route.sameDistrictSpeedKmh());
        assertEquals(55.0, route.farDistrictSpeedKmh());
        assertEquals(22.0, route.crossDistrictSpeedKmh());
    }

    @Test void routeFallbackRejectsExtremeOperationalValuesAndKeepsLastGoodSnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var before = provider.getPolicy().routeFallbackPolicy();
        var mapper = new ObjectMapper();
        var tree = mapper.readTree(getClass().getResourceAsStream("/taxonomy/dynamic_short_trip_policy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("routeFallback")).put("sameDistrictSpeedKmh", 1.0e-300);
        Files.writeString(directory.resolve("dynamic_short_trip_policy.json"), tree.toString());

        assertFalse(provider.reload());
        assertEquals(before, provider.getPolicy().routeFallbackPolicy());
    }

    @Test void invalidDefaultDurationCannotReplaceLastGoodPolicySnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var before = provider.getDefaultDurationDays();
        var mapper = new ObjectMapper();
        var tree = mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("legacyHints")).putArray("defaultDurationDays").add("99");
        Files.writeString(directory.resolve("dining_taxonomy.json"), tree.toString());

        assertFalse(provider.reload());
        assertEquals(before, provider.getDefaultDurationDays());
    }

    @Test void incompleteScoringPolicyCannotReplaceLastGoodSnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var before = provider.getHints("plannerScoreWeights");
        var mapper = new ObjectMapper();
        var tree = mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        var weights = (com.fasterxml.jackson.databind.node.ArrayNode) tree.path("legacyHints").path("plannerScoreWeights");
        for (int i = weights.size() - 1; i >= 0; i--) {
            if (weights.get(i).asText().startsWith("routeTravelPenaltyPerMinute=")) weights.remove(i);
        }
        Files.writeString(directory.resolve("dining_taxonomy.json"), tree.toString());

        assertFalse(provider.reload());
        assertEquals(before, provider.getHints("plannerScoreWeights"));
    }

    @Test void missingRuntimeTaxonomyAndMalformedClockCannotReplaceLastGoodSnapshot() throws Exception {
        var provider = new JsonTaxonomyAdapter(directory.toString());
        var beforeMealAliases = provider.getHints("slotLunch");
        var beforeScenicType = provider.getHints("runtimeScenicTypeCode");
        var beforeRuntimeClock = provider.getHints("runtimeDefaultNightTime");
        var mapper = new ObjectMapper();
        var resource = getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json");
        var baseline = mapper.readTree(resource);

        var missingMealAliases = baseline.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingMealAliases.path("legacyHints")).remove("slotLunch");
        Files.writeString(directory.resolve("dining_taxonomy.json"), missingMealAliases.toString());
        assertFalse(provider.reload());
        assertEquals(beforeMealAliases, provider.getHints("slotLunch"));

        var missingScenicType = baseline.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missingScenicType.path("legacyHints")).remove("runtimeScenicTypeCode");
        Files.writeString(directory.resolve("dining_taxonomy.json"), missingScenicType.toString());
        assertFalse(provider.reload());
        assertEquals(beforeScenicType, provider.getHints("runtimeScenicTypeCode"));

        var malformedClock = baseline.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) malformedClock.path("legacyHints").path("runtimeDefaultNightTime")).set(0, "not-a-time");
        Files.writeString(directory.resolve("dining_taxonomy.json"), malformedClock.toString());
        assertFalse(provider.reload());
        assertEquals(beforeRuntimeClock, provider.getHints("runtimeDefaultNightTime"));
    }
}

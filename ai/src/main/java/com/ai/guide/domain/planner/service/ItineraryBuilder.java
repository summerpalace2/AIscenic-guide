package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinates;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.attraction.service.AttractionPoiIdentityMatcher;
import com.ai.guide.common.context.OperationDeadline;
import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.planner.engine.MealGapSolver;
import com.ai.guide.domain.planner.engine.PlannerScoringPolicy;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import com.ai.guide.domain.planner.service.util.ChineseDateTimeParser;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 行程结构化组装与候选景点管理服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：负责 24 景候选召回、景点标签匹配、游玩时长推算及多天行程 JSON 结构的标准化组装。
 */
@Service
public class ItineraryBuilder implements ItineraryBuilderPort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ItineraryBuilder.class);

    @org.springframework.beans.factory.annotation.Value("${planner.catalog-geocode.max-poi-lookups:12}")
    private int maxCatalogGeocodeLookups = 12;

    private final AttractionService attractionService;
    private final RouteAwarePlanner routeAwarePlanner;
    private final PlanVerifier planVerifier;
    private final LocalPlanRepairer localPlanRepairer;
    private final PlanNarrativeService planNarrativeService;
    private final com.ai.guide.domain.attraction.service.AttractionAliasMatcher aliasMatcher;

    private GeoRouteGatewayPort geoRouteGateway;

    @Autowired(required = false)
    public void setGeoRouteGateway(GeoRouteGatewayPort geoRouteGateway) {
        this.geoRouteGateway = geoRouteGateway;
    }

    @Autowired(required = false)
    private com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired
    private DiningVenueService diningVenueService;

    @Autowired
    private PlannerTaxonomyPort taxonomy = TaxonomyPort.empty();

    @Autowired
    private MealGapSolver mealGapSolver;

    public void setDiningScheduling(DiningVenueService diningVenueService, PlannerTaxonomyPort taxonomy, MealGapSolver mealGapSolver) {
        this.diningVenueService = diningVenueService;
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
        this.mealGapSolver = mealGapSolver;
    }

    @Autowired
    public ItineraryBuilder(AttractionService attractionService,
                            RouteAwarePlanner routeAwarePlanner,
                            PlanVerifier planVerifier,
                            LocalPlanRepairer localPlanRepairer,
                            PlanNarrativeService planNarrativeService,
                            @Autowired(required = false) com.ai.guide.domain.attraction.service.AttractionAliasMatcher aliasMatcher) {
        this.attractionService = attractionService;
        this.routeAwarePlanner = routeAwarePlanner;
        this.planVerifier = planVerifier;
        this.localPlanRepairer = localPlanRepairer;
        this.planNarrativeService = planNarrativeService;
        this.aliasMatcher = aliasMatcher != null ? aliasMatcher : new com.ai.guide.domain.attraction.service.AttractionAliasMatcher(attractionService);
    }

    public Map<String, Object> build(int version, TravelConstraints constraints) {
        String destination = normalizedCity(constraints.getDestination());
        boolean configuredBaselineCity = isBaselineCity(destination);
        List<Attraction> catalog = new ArrayList<>(attractionService.list(null, null).stream()
                .filter(attraction -> belongsToCity(attraction, destination)).toList());
        catalog.sort(Comparator.comparingInt(a -> catalogRank(a.getId())));
        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction attraction : catalog) byId.put(attraction.getId(), attraction);
        List<String> explicitIds = explicitIds(constraints.getRawPrompt(), byId);
        List<Attraction> lookupOrder = new ArrayList<>(catalog);
        lookupOrder.sort(Comparator.comparingInt((Attraction attraction) ->
                explicitIds.contains(attraction.getId()) ? 0
                        : constraints.getMustVisit() != null && constraints.getMustVisit().stream()
                            .anyMatch(name -> name != null && !name.isBlank()
                                    && attraction.getName() != null && attraction.getName().contains(name)) ? 1 : 2)
                .thenComparingInt(attraction -> catalogRank(attraction.getId())));
        Map<String, Attraction> resolved = new LinkedHashMap<>();
        int lookups = 0;
        for (Attraction attraction : lookupOrder) {
            if (GeoCoordinates.parse(attraction.getLocation()) != null) continue;
            if (lookups >= Math.max(0, maxCatalogGeocodeLookups) || OperationDeadline.expired()) break;
            lookups++;
            resolved.put(attraction.getId(), resolveCoordinate(attraction, destination));
        }
        catalog.replaceAll(attraction -> resolved.getOrDefault(attraction.getId(), attraction));
        byId.clear();
        for (Attraction attraction : catalog) byId.put(attraction.getId(), attraction);
        int requestedDays = constraints.getDurationDays();
        if (requestedDays <= 0) throw new IllegalArgumentException("durationDays must be confirmed or supplied by planner policy");
        int maxDays = PlannerScoringPolicy.limit(taxonomy, "maxTripDays");
        int dayCount = Math.max(1, maxDays > 0 ? Math.min(maxDays, requestedDays) : requestedDays);
        boolean hasStartPlace = constraints.getStartPlace() != null
                && !constraints.getStartPlace().isBlank()
                && !"未提供".equals(constraints.getStartPlace());
        List<String> requiredBaselineIds = taxonomy == null ? List.of() : taxonomy.getHints("baselineRequiredAttractionIds");
        boolean isGoldenBaseline = configuredBaselineCity && explicitIds.isEmpty() && dayCount == 2 && !hasStartPlace
                && !requiredBaselineIds.isEmpty() && byId.keySet().containsAll(requiredBaselineIds);

        List<Map<String, Object>> days = isGoldenBaseline
                ? routeAwarePlanner.resolveAndVerifyDays(buildBaselineDays(dayCount, constraints, byId), byId, constraints)
                : routeAwarePlanner.planDays(dayCount, constraints, byId, explicitIds, this);

        enrichDaysWithDining(days, constraints, byId);
        annotateStops(days, constraints);

        String cityLabel = destination.isBlank() ? "目的地" : destination;
        String title = isGoldenBaseline
                ? destination + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·城市漫游线"
                : hasStartPlace
                ? "从" + constraints.getStartPlace() + "出发·" + cityLabel + dayCount + "天专属方案"
                : cityLabel + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·专属定制方案";
        String subtitle = ("低".equals(constraints.getWalkingTolerance()) ? "少走路优先" : "按约束排序")
                + " · " + constraints.getCompanions() + " · " + String.join(" + ", constraints.getInterests().stream().limit(2).toList());

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-plan-" + version);
        trip.put("city", constraints.getDestination() == null ? "" : constraints.getDestination());
        trip.put("version", version);
        trip.put("title", title);
        trip.put("subtitle", subtitle);
        trip.put("constraints", constraints.asMap());
        trip.put("sourceMode", "演示回退模式");
        trip.put("factPolicy", "动态 / 未知 / 冲突事实显式标注");
        trip.put("days", days);
        trip.put("planContext", planContext(constraints));
        trip.put("summary", summary(dayCount));
        trip.put("citations", citations());
        trip.put("retrieval", retrievalStatus());
        trip.put("sourceStatus", sourceStatus());
        trip.put("qualityMetrics", qualityMetrics(constraints, requestedDays, days));
        trip.put("versionHistory", List.of(versionEntry(version, "初始规划", List.of(), "")));

        // V1 Version metadata and explanation source
        PlannerVersionMetadata versionMeta = PlannerVersionMetadata.defaultV1("ESTIMATED", false, List.of());
        trip.put("plannerVersion", versionMeta.getPlannerVersion());
        trip.put("policyVersion", versionMeta.getPolicyVersion());
        trip.put("explanationSource", versionMeta.getExplanationSource());
        trip.put("degraded", versionMeta.isDegraded());
        trip.put("degradationReasons", versionMeta.getDegradationReasons());

        if (catalog.isEmpty()) {
            trip.put("sourceMode", "当地景点数据待检索");
            trip.put("routeDataStatus", "UNAVAILABLE");
            trip.put("degraded", true);
            trip.put("degradationReasons", List.of("CITY_CATALOG_UNAVAILABLE"));
            trip.put("summary", Map.of("confirmed", List.of(), "dynamic", List.of(),
                    "unknown", List.of("当地景点与路线待检索"), "conflict", List.of()));
            trip.put("sourceStatus", Map.of("poi", "当地景点数据缺失", "route", "未生成", "weather", "待查询", "fallback", true));
            trip.put("retrieval", Map.of("mode", "java-planner-local", "status", "当地目录无匹配景点", "facts", List.of(), "citations", List.of()));
            trip.put("citations", List.of());
        }
        if (!catalog.isEmpty()) {
            boolean missingCandidateCoordinates = catalog.stream()
                    .anyMatch(attraction -> GeoCoordinates.parse(attraction.getLocation()) == null);
            boolean underfilled = !isGoldenBaseline && java.util.stream.IntStream.range(0, days.size()).anyMatch(index -> {
                Object rawStops = days.get(index).get("stops");
                long attractions = rawStops instanceof List<?> stops ? stops.stream()
                        .filter(stop -> stop instanceof Map<?, ?> entry && byId.containsKey(String.valueOf(entry.get("venueId"))))
                        .count() : 0;
                return attractions < routeAwarePlanner.expectedAttractionSlots(index + 1, dayCount, constraints);
            });
            List<String> selectedNames = days.stream().filter(day -> day.get("stops") instanceof List<?>)
                    .flatMap(day -> ((List<?>) day.get("stops")).stream())
                    .filter(stop -> stop instanceof Map<?, ?>)
                    .map(stop -> String.valueOf(((Map<?, ?>) stop).get("name"))).toList();
            boolean mustVisitMissing = constraints.getMustVisit() != null && constraints.getMustVisit().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .anyMatch(name -> selectedNames.stream().noneMatch(selected -> selected.contains(name)));
            boolean selectedCoordinateMissing = days.stream()
                    .filter(day -> day.get("stops") instanceof List<?>)
                    .flatMap(day -> ((List<?>) day.get("stops")).stream())
                    .anyMatch(stop -> stop instanceof Map<?, ?> entry
                            && GeoCoordinates.parse(String.valueOf(entry.get("location"))) == null);
            List<String> reasons = new ArrayList<>();
            if (selectedCoordinateMissing) reasons.add("POI_COORDINATES_UNVERIFIED");
            if (missingCandidateCoordinates && (underfilled || mustVisitMissing))
                reasons.add("CATALOG_CANDIDATES_UNRESOLVED");
            if (!reasons.isEmpty()) {
                trip.put("degraded", true);
                trip.put("degradationReasons", reasons);
            }
        }
        if (!configuredBaselineCity) {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) trip.get("planContext");
            context.put("foodGuidance", "饮食策略：依据用户风味偏好检索当地餐厅，营业状态与路线待核验。");
        }

        return trip;
    }

    @Override
    public Map<String, Object> buildLegacy(int version, TravelConstraints constraints) {
        if (!isBaselineCity(normalizedCity(constraints.getDestination()))) {
            Map<String, Object> trip = build(version, constraints);
            trip.put("plannerVersion", "legacy-v0");
            trip.put("explanationSource", "LEGACY_STATIC");
            trip.put("degraded", true);
            return trip;
        }
        List<Attraction> catalog = attractionService.list(null, null);
        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction attraction : catalog) byId.put(attraction.getId(), attraction);
        int requestedDays = constraints.getDurationDays();
        if (requestedDays <= 0) throw new IllegalArgumentException("durationDays must be confirmed or supplied by planner policy");
        int dayCount = Math.max(1, Math.min(7, requestedDays));

        // Legacy path uses pure baseline template without RouteAwarePlanner or route resolution
        List<Map<String, Object>> days = buildBaselineDays(dayCount, constraints, byId);

        String title = normalizedCity(constraints.getDestination()) + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·传统基准方案";
        String subtitle = "传统规划引擎 · 演示基准";

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-plan-" + version);
        trip.put("city", constraints.getDestination());
        trip.put("version", version);
        trip.put("title", title);
        trip.put("subtitle", subtitle);
        trip.put("constraints", constraints.asMap());
        trip.put("sourceMode", "演示回退模式");
        trip.put("factPolicy", "静态回退");
        trip.put("days", days);
        trip.put("planContext", planContext(constraints));
        trip.put("summary", summary(dayCount));
        trip.put("citations", citations());
        trip.put("retrieval", retrievalStatus());
        trip.put("sourceStatus", sourceStatus());
        trip.put("qualityMetrics", qualityMetrics(constraints, requestedDays, days));
        trip.put("versionHistory", List.of(versionEntry(version, "初始规划", List.of(), "")));

        trip.put("plannerVersion", "legacy-v0");
        trip.put("policyVersion", "legacy-2026.07");
        trip.put("explanationSource", "LEGACY_STATIC");
        trip.put("degraded", true);
        trip.put("degradationReasons", List.of("LEGACY_PLANNER_ACTIVE"));

        return trip;
    }

    private static String normalizedCity(String city) {
        if (city == null) return "";
        String value = city.trim();
        return value.endsWith("市") && value.length() > 2 ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public boolean belongsToCity(Attraction attraction, String city) {
        city = normalizedCity(city);
        if (attraction == null || city.isBlank()) return false;
        if (attraction.getCity() != null && !attraction.getCity().isBlank())
            return city.equals(normalizedCity(attraction.getCity()));
        Object configuredCity = attraction.getAmapQuery() == null ? null : attraction.getAmapQuery().get("city");
        if (configuredCity != null && !String.valueOf(configuredCity).isBlank()) {
            return city.equals(normalizedCity(String.valueOf(configuredCity)));
        }
        // The legacy catalog predates its city field. This compatibility applies
        // only to its documented entries, never to arbitrary unclassified POIs.
        return isBaselineCity(city) && taxonomy != null && taxonomy.getHints("catalogOrder").contains(attraction.getId());
    }

    /** Resolve at most one exact catalog identity per missing coordinate before route selection. */
    private Attraction resolveCoordinate(Attraction attraction, String city) {
        if (GeoCoordinates.parse(attraction.getLocation()) != null || geoRouteGateway == null
                || !geoRouteGateway.isConfigured() || OperationDeadline.expired()
                || city.isBlank() || attraction.getName() == null || attraction.getName().isBlank()) return attraction;
        try {
            var outcome = geoRouteGateway.searchPoi(attraction.getName(), city);
            if (OperationDeadline.expired() || outcome == null || outcome.status() != GeoOutcomeStatus.SUCCESS
                    || outcome.candidates() == null) {
                log.info("[CATALOG_GEOCODE] attractionId={} status={}", attraction.getId(),
                        OperationDeadline.expired() ? "DEADLINE_EXCEEDED" : outcome == null ? "NO_RESPONSE" : outcome.status());
                return attraction;
            }
            var acceptedNames = AttractionPoiIdentityMatcher.acceptableExactNames(attraction);
            var exact = outcome.candidates().stream().filter(poi ->
                    AttractionPoiIdentityMatcher.isValidExactIdentity(poi, acceptedNames, city)).toList();
            if (exact.size() == 1) {
                Attraction resolved = new Attraction();
                BeanUtils.copyProperties(attraction, resolved);
                resolved.setLocation(exact.get(0).coordinate().longitude() + "," + exact.get(0).coordinate().latitude());
                resolved.setResolvedPoiId(exact.get(0).poiId());
                return resolved;
            }
            log.info("[CATALOG_GEOCODE] attractionId={} status={} matchingIdentities={}", attraction.getId(),
                    exact.isEmpty() ? "NO_EXACT_MATCH" : "AMBIGUOUS_IDENTITY", exact.size());
        } catch (RuntimeException failure) {
            // A failed provider lookup never supplies coordinates or a fabricated route.
            log.info("[CATALOG_GEOCODE] attractionId={} status=FAILED exceptionType={}",
                    attraction.getId(), failure.getClass().getSimpleName());
        }
        return attraction;
    }

    public Map<String, Object> createStop(Attraction attraction, String stopId, String time,
                                          String duration, String summary, String detail) {
        return createStop(attraction, stopId, time, duration, summary, detail, null);
    }

    public Map<String, Object> createStop(Attraction attraction, String stopId, String time,
                                          String duration, String summary, String detail,
                                          ScoreBreakdown scoreBreakdown) {
        String effectiveDuration = duration == null || duration.isBlank() ? attraction.getDuration() : duration;
        String effectiveWalk = attraction.getWalk() == null ? "路线待计算" : attraction.getWalk();
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", stopId);
        stop.put("stableStopId", stopId);
        stop.put("entityId", attraction.getId());
        stop.put("venueId", attraction.getId());
        stop.put("name", attraction.getName());
        stop.put("displayName", attraction.getDisplayName());
        stop.put("district", attraction.getDistrict());
        stop.put("time", time);
        stop.put("startTime", time);
        stop.put("duration", effectiveDuration);
        stop.put("icon", attraction.getIcon());
        stop.put("tone", attraction.getTone());
        stop.put("summary", summary == null || summary.isBlank() ? attraction.getSummary() : summary);
        stop.put("walk", effectiveWalk);
        stop.put("walkDifficulty", attraction.getWalkDifficulty());
        stop.put("walkingDifficulty", attraction.getWalkDifficulty());
        stop.put("indoor", attraction.getIndoor());
        stop.put("ticket", attraction.getTicket());
        stop.put("bestTime", attraction.getBestTime());
        stop.put("openingHours", attraction.getOpeningHours());
        stop.put("estimatedCost", "未知·需用户确认");
        stop.put("costSummary", "费用待确认");
        String reason = (detail != null && !detail.isBlank()) ? detail
                : (planNarrativeService != null) ? planNarrativeService.buildStopReason(attraction, scoreBreakdown, null, null)
                : (attraction.getFit() != null && !attraction.getFit().isBlank() ? attraction.getFit() : attraction.getIntro());
        stop.put("recommendationReason", reason);
        stop.put("detail", detail == null || detail.isBlank() ? attraction.getIntro() : detail);
        stop.put("matchedConstraints", new ArrayList<>());
        stop.put("routePreference", "walking");
        stop.put("walkingInfo", Map.of("summary", effectiveWalk, "status", "未知"));
        stop.put("address", "");
        stop.put("location", attraction.getLocation());
        stop.put("amapPoiId", attraction.getResolvedPoiId() == null ? "" : attraction.getResolvedPoiId());
        if (attraction.getResolvedPoiId() != null && !attraction.getResolvedPoiId().isBlank())
            stop.put("preselectedPoiId", attraction.getResolvedPoiId());
        stop.put("mapContext", mapContext(attraction.getLocation()));
        stop.put("facts", defaultFacts(attraction));
        stop.put("citations", officialSummaryCitation(attraction));
        if (scoreBreakdown != null) {
            stop.put("scoreBreakdown", scoreBreakdown.asMap());
        }
        return stop;
    }

    @Override
    public Map<String, Object> createDiningStop(com.ai.guide.domain.dining.model.DiningOption dining, String stopId, String time, String district) {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", stopId);
        stop.put("stableStopId", stopId);
        stop.put("entityId", dining.id());
        stop.put("venueId", dining.id());
        stop.put("name", dining.name());
        stop.put("displayName", dining.name());
        stop.put("district", district == null ? "" : district);
        stop.put("time", time);
        stop.put("startTime", time);
        stop.put("duration", dining.duration());
        boolean isTeaBreakStop = "TEA_BREAK".equalsIgnoreCase(dining.diningType())
                || (taxonomy != null && (taxonomy.getHints("teaBreakDiningTypes").stream()
                        .anyMatch(type -> type.equalsIgnoreCase(dining.diningType()))
                        || taxonomy.getHints("teaVenueNameKeywords").stream()
                        .anyMatch(keyword -> dining.name() != null && dining.name().contains(keyword))));
        stop.put("type", "DINING");
        stop.put("category", isTeaBreakStop ? "茶歇" : "美食");
        stop.put("icon", isTeaBreakStop ? "茶" : "餐");
        stop.put("tone", dining.tone());
        stop.put("summary", dining.summary());
        stop.put("detail", dining.specialtyDish() + " · " + dining.summary());
        stop.put("specialtyDish", dining.specialtyDish());
        stop.put("diningType", dining.diningType());
        stop.put("ticket", dining.averageCost());
        stop.put("costSummary", dining.averageCost());
        stop.put("estimatedCost", dining.averageCost());
        stop.put("walk", "交通待核验");
        stop.put("walkDifficulty", "低");
        stop.put("walkingDifficulty", "低");
        stop.put("indoor", true);
        stop.put("bestTime", "营业时间待核验");
        stop.put("recommendationReason", dining.recommendationReason());
        stop.put("location", dining.location());
        stop.put("address", (district != null && !district.isBlank() ? district + " · " : "") + dining.distanceFromAttraction() + "即达");
        stop.put("routePreference", "walking");
        stop.put("walkingInfo", Map.of("summary", dining.distanceFromAttraction() + "即达", "status", "已知"));
        RouteCost unknownRoute = RouteCost.unavailable("餐饮站点尚未计算相邻路线");
        stop.put("resolvedRouteCost", unknownRoute.asMap());
        stop.put("estimatedRouteCost", unknownRoute.asMap());
        stop.put("routeDataStatus", "UNAVAILABLE");
        return stop;
    }

    public Attraction attraction(String id) {
        if (id != null && id.startsWith("amap-") && runtimeAttractionDetailService != null) {
            Attraction dynamic = runtimeAttractionDetailService.toAttraction(id);
            if (dynamic != null) return dynamic;
        }
        return attractionService.get(id);
    }

    public List<Attraction> attractionServiceList() {
        return attractionService.list(null, null);
    }

    @Override
    public int catalogRank(String attractionId) {
        int index = taxonomy == null ? -1 : taxonomy.getHints("catalogOrder").indexOf(attractionId);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    private boolean isBaselineCity(String city) {
        return taxonomy != null && taxonomy.getHints("baselineCity").stream()
                .map(ItineraryBuilder::normalizedCity).anyMatch(candidate -> candidate.equals(normalizedCity(city)));
    }

    private List<Map<String, Object>> buildBaselineDays(int dayCount, TravelConstraints constraints,
                                                          Map<String, Attraction> byId) {
        List<List<Spec>> templates = new ArrayList<>();
        List<String> labels = taxonomy == null ? List.of() : taxonomy.getHints("baselineDayLabels");
        for (int templateIndex = 1; taxonomy != null; templateIndex++) {
            List<String> encoded = taxonomy.getHints("baselineDay" + templateIndex);
            if (encoded.isEmpty()) break;
            List<Spec> specs = new ArrayList<>();
            for (String value : encoded) {
                String[] parts = value.split("\\|", 3);
                if (parts.length != 3) throw new IllegalStateException("Invalid city baseline stop template");
                specs.add(new Spec(parts[0], parts[1], parts[2]));
            }
            templates.add(List.copyOf(specs));
        }
        if (templates.isEmpty()) return List.of();
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 1; day <= dayCount; day++) {
            List<Spec> specs = templates.get((day - 1) % templates.size());
            List<Map<String, Object>> stops = new ArrayList<>();
            for (Spec spec : specs) {
                Attraction attraction = byId.get(spec.attractionId());
                if (attraction != null) {
                    boolean shouldAvoid = false;
                    if (constraints.getAvoid() != null) {
                        for (String av : constraints.getAvoid()) {
                            if (attraction.getName().contains(av) || attraction.getId().contains(av)
                                    || (attraction.getTags() != null && attraction.getTags().contains(av))
                                    || av.equals(attraction.getCategory())) {
                                shouldAvoid = true;
                                break;
                            }
                        }
                    }
                    if (!shouldAvoid) {
                        stops.add(createStop(attraction, spec.id().replaceFirst("day\\d+", "day" + day), spec.time(), attraction.getDuration(), attraction.getSummary(), attraction.getFit()));
                    }
                }
            }
            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", day);
            String itineraryDate = itineraryDate(constraints, day);
            dayMap.put("date", itineraryDate == null ? dateLabel(constraints, day) : itineraryDate);
            dayMap.put("dateLabel", itineraryDate == null
                    ? "第" + day + "天 · " + (labels.isEmpty() ? "城市游览" : labels.get((day - 1) % labels.size()))
                    : itineraryDate + " · 第" + day + "天 · " + (labels.isEmpty() ? "城市游览" : labels.get((day - 1) % labels.size())));
            dayMap.put("weather", weather());
            dayMap.put("stops", stops);
            days.add(dayMap);
        }
        annotateStops(days, constraints);
        return days;
    }

    private Scored selectBestCandidateScored(List<Scored> scored, Set<String> used, Predicate<Attraction> preferred) {
        return scored.stream()
                .filter(s -> !used.contains(s.attraction().getId()) && preferred.test(s.attraction()))
                .findFirst()
                .orElseGet(() -> scored.stream()
                        .filter(s -> !used.contains(s.attraction().getId()))
                        .findFirst()
                        .orElse(null));
    }

    private List<Scored> scoreAttractions(Collection<Attraction> attractions, TravelConstraints constraints, Set<String> explicitIds) {
        List<Scored> scored = new ArrayList<>();
        for (Attraction attraction : attractions) {
            ScoreBreakdown breakdown = score(attraction, constraints, explicitIds);
            scored.add(new Scored(attraction, breakdown.totalScore(), breakdown));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt(item -> catalogRank(item.attraction().getId()))
                .thenComparing(item -> item.attraction().getId()));
        return scored;
    }

    public ScoreBreakdown score(Attraction a, TravelConstraints c, Set<String> explicitIds) {
        List<String> reasonCodes = new ArrayList<>();
        int explicitBonus = explicitIds != null && explicitIds.contains(a.getId()) ? scoreWeight("builderExplicitMention") : 0;
        if (explicitBonus > 0) reasonCodes.add("EXPLICIT_MENTION");

        int mustVisitScore = (c.getMustVisit() != null && c.getMustVisit().stream().anyMatch(m -> a.getName().contains(m) || a.getId().contains(m))) ? scoreWeight("builderMustVisit") : 0;
        if (mustVisitScore > 0) reasonCodes.add("MUST_VISIT");

        int avoidPenalty = (c.getAvoid() != null && c.getAvoid().stream().anyMatch(av -> a.getName().contains(av) || a.getId().contains(av) || av.equals(a.getCategory()))) ? scoreWeight("builderAvoidPenalty") : 0;
        if (avoidPenalty < 0) reasonCodes.add("AVOID_PENALTY");

        int interestScore = 0;
        for (String interest : c.getInterests()) {
            if (taxonomy.getHints("plannerNatureInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerNatureCategories", "plannerNatureFeatures")) { interestScore += scoreWeight("builderInterestNature"); reasonCodes.add("INTEREST_NATURE"); }
            } else if (taxonomy.getHints("plannerHistoryInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerHistoryCategories", "plannerHistoryFeatures")) { interestScore += scoreWeight("builderInterestHistory"); reasonCodes.add("INTEREST_HISTORY"); }
            } else if (taxonomy.getHints("chongqingTerrainInterestLabels").contains(interest) && isBaselineCity(c.getDestination())) {
                if (hasFeatures(a, "chongqingTerrainInterestFeatures")) { interestScore += scoreWeight("builderInterestTerrain"); reasonCodes.add("INTEREST_8D_MAGIC"); }
            } else if (taxonomy.getHints("plannerNightInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerNightCategories", "plannerNightScoringFeatures")) { interestScore += scoreWeight("builderInterestNight"); reasonCodes.add("INTEREST_NIGHT_VIEW"); }
            } else if (taxonomy.getHints("plannerThermalInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerThermalCategories", "plannerThermalFeatures") || nameMatches(a, "plannerThermalNameTerms")) { interestScore += scoreWeight("builderInterestThermal"); reasonCodes.add("INTEREST_HOTSPRING"); }
            } else if (taxonomy.getHints("plannerLocalInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerLocalCategories", "plannerLocalCultureScoringFeatures")) { interestScore += scoreWeight("builderInterestLocal"); reasonCodes.add("INTEREST_LOCAL_LIFE"); }
            } else if (a.matchesAnyTagOrFeature(interest) || interest.equals(a.getCategory()) || a.getName().contains(interest)) {
                interestScore += scoreWeight("builderInterestGeneric"); reasonCodes.add("INTEREST_TAG_MATCH");
            }
        }

        int walkingScore = evaluateWalkingScore(a, c.getWalkingTolerance(), reasonCodes);
        int companionScore = evaluateCompanionScore(a, c.getCompanions(), c.getDestination(), reasonCodes);
        int diningScore = evaluateDiningScore(a, c.getDietPreference(), reasonCodes);
        int totalScore = explicitBonus + mustVisitScore + avoidPenalty + interestScore + walkingScore + companionScore + diningScore;
        return new ScoreBreakdown(interestScore, companionScore, walkingScore, diningScore, mustVisitScore, avoidPenalty, explicitBonus, totalScore, reasonCodes);
    }

    private int evaluateDiningScore(Attraction attraction, String dietPreference, List<String> reasonCodes) {
        boolean avoidsSpicy = dietPreference != null && taxonomy != null
                && taxonomy.getHints("nonSpicyFlavors").stream().anyMatch(dietPreference::contains);
        if (avoidsSpicy) {
            boolean peacefulCategory = matchesCategory(attraction, "plannerDiningPeacefulCategories")
                    || "INDOOR".equals(attraction.getEffectiveEnvironment())
                    || hasFeatures(attraction, "plannerDiningPeacefulFeatures");
            if (peacefulCategory && taxonomy.getHints("spicyFlavors").stream().noneMatch(attraction::matchesAnyTagOrFeature)) {
                reasonCodes.add("DINING_NON_SPICY_FIT"); return scoreWeight("builderDiningPeaceful");
            }
            if (taxonomy.getHints("spicyFlavors").stream().anyMatch(attraction::matchesAnyTagOrFeature)) {
                reasonCodes.add("DINING_SPICY_PENALTY"); return scoreWeight("builderDiningSpicyPenalty");
            }
        } else if (matchesConfiguredDiningFlavor(attraction, dietPreference)
                || (taxonomy != null && taxonomy.getHints("genericFlavors").contains(dietPreference))) {
            if (matchesConfigured(attraction, "plannerFoodCategories", "foodTags")) {
                reasonCodes.add("DINING_PREFERENCE_FIT"); return scoreWeight("builderDiningPreference");
            }
        } else if (!taxonomy.getHints("missingPreferenceValues").contains(dietPreference)) {
            if (matchesConfigured(attraction, "plannerFoodCategories", "foodTags")) {
                reasonCodes.add("DINING_FOOD_FIT"); return scoreWeight("builderDiningFood");
            }
        }
        return 0;
    }

    private boolean matchesConfiguredDiningFlavor(Attraction attraction, String preference) {
        if (attraction == null || preference == null || preference.isBlank() || taxonomy == null) return false;
        return taxonomy.getFlavorAliases().stream()
                .filter(alias -> alias.label().equals(preference) || alias.keywords().contains(preference))
                .flatMap(alias -> alias.keywords().stream())
                .anyMatch(keyword -> attraction.matchesAnyTagOrFeature(keyword));
    }

    private int evaluateWalkingScore(Attraction attraction, String walkingTolerance, List<String> reasonCodes) {
        if (taxonomy.getHints("walkingLowAliases").contains(walkingTolerance)) {
            int delta = 0;
            if (taxonomy.getHints("walkingEasyLevels").contains(attraction.getWalkDifficulty()) || taxonomy.getHints("supportedAccessibilityLevels").contains(attraction.getEffectiveAccessibility())) {
                delta += scoreWeight("builderWalkEasy"); reasonCodes.add("WALKING_EASY");
            }
            if (hasFeatures(attraction, "plannerWalkingExtendedFeatures")) { delta += scoreWeight("builderWalkAccessible"); reasonCodes.add("WALKING_ACCESSIBLE"); }
            if (taxonomy.getHints("walkingHardLevels").contains(attraction.getWalkDifficulty()) || taxonomy.getHints("unsupportedAccessibilityLevels").contains(attraction.getEffectiveAccessibility())) {
                delta += scoreWeight("builderWalkHard"); reasonCodes.add("WALKING_DIFFICULT_PENALTY");
            }
            if (taxonomy.getHints("walkingMediumLevels").contains(attraction.getWalkDifficulty()) || taxonomy.getHints("partialAccessibilityLevels").contains(attraction.getEffectiveAccessibility())) delta += scoreWeight("builderWalkMedium");
            return delta;
        }
        if (taxonomy.getHints("walkingHighAliases").contains(walkingTolerance)
                && (hasFeatures(attraction, "plannerWalkingActiveFeatures") || taxonomy.getHints("walkingHardLevels").contains(attraction.getWalkDifficulty()))) {
            reasonCodes.add("WALKING_HIGH_TOLERANCE"); return scoreWeight("builderWalkActive");
        }
        return 0;
    }

    private int evaluateCompanionScore(Attraction attraction, String companions, String destination, List<String> reasonCodes) {
        if (taxonomy.getHints("plannerCompanionChildAliases").contains(companions)) {
            int delta = indoor(attraction) ? scoreWeight("builderCompanionIndoor") : 0;
            if (hasFeatures(attraction, "plannerCompanionChildExtendedFeatures")
                    || (indoor(attraction) && matchesCategory(attraction, "plannerHistoryCategories"))) { delta += scoreWeight("builderChildEducation"); reasonCodes.add("COMPANION_KIDS_EDUCATION"); }
            if (matchesConfigured(attraction, "plannerNatureCategories", "plannerCompanionKidNatureExtendedFeatures")) { delta += scoreWeight("builderChildNature"); reasonCodes.add("COMPANION_KIDS_NATURE"); }
            if (isBaselineCity(destination) && hasFeatures(attraction, "chongqingTerrainInterestFeatures")) { delta += scoreWeight("builderChildTerrain"); reasonCodes.add("COMPANION_KIDS_MAGIC"); }
            return delta;
        }
        if (taxonomy.getHints("plannerCompanionSeniorAliases").contains(companions)) {
            int delta = indoor(attraction) ? scoreWeight("builderCompanionIndoor") : 0;
            if (hasFeatures(attraction, "plannerCompanionSeniorExtendedFeatures")) { delta += scoreWeight("builderSeniorFriendly"); reasonCodes.add("COMPANION_SENIOR_FRIENDLY"); }
            if (taxonomy.getHints("walkingEasyLevels").contains(attraction.getWalkDifficulty())
                    && (hasFeatures(attraction, "plannerCompanionSeniorWalkingFeatures") || indoor(attraction))) { delta += scoreWeight("builderSeniorLowWalking"); reasonCodes.add("COMPANION_SENIOR_EASY"); }
            if (taxonomy.getHints("walkingHardLevels").contains(attraction.getWalkDifficulty())) { delta += scoreWeight("builderSeniorHard"); reasonCodes.add("COMPANION_SENIOR_STEEP_PENALTY"); }
            return delta;
        }
        if (taxonomy.getHints("plannerCompanionCoupleAliases").contains(companions)) {
            int delta = 0;
            if (matchesConfigured(attraction, "plannerNightCategories", "plannerCompanionCoupleRomanceExtendedFeatures")) { delta += scoreWeight("builderCoupleRomance"); reasonCodes.add("COMPANION_COUPLE_ROMANTIC"); }
            if (hasFeatures(attraction, "plannerCompanionCoupleThermalFeatures") || nameMatches(attraction, "plannerThermalNameTerms")) { delta += scoreWeight("builderCoupleThermal"); reasonCodes.add("COMPANION_COUPLE_SPA"); }
            if (hasFeatures(attraction, "plannerCompanionCoupleScenicExtendedFeatures")) { delta += scoreWeight("builderCoupleScenic"); reasonCodes.add("COMPANION_COUPLE_SCENIC"); }
            return delta;
        }
        if (taxonomy.getHints("plannerCompanionFriendsAliases").contains(companions)) {
            if ((isBaselineCity(destination) && hasFeatures(attraction, "chongqingTerrainInterestFeatures"))
                    || matchesCategory(attraction, "plannerCompanionFriendsCategories")) { reasonCodes.add("COMPANION_FRIENDS_VIBRANT"); return scoreWeight("builderFriendsActive"); }
            return 0;
        }
        if (taxonomy.getHints("plannerCompanionSoloAliases").contains(companions)
                && (hasFeatures(attraction, "plannerCompanionSoloExtendedFeatures") || matchesCategory(attraction, "plannerCreativeCategories"))) {
            reasonCodes.add("COMPANION_SOLO_CALM"); return scoreWeight("builderSoloCalm");
        }
        return 0;
    }

    private int scoreWeight(String key) { return PlannerScoringPolicy.weight(taxonomy, key); }
    private boolean hasFeatures(Attraction attraction, String key) { return attraction != null && attraction.matchesAnyTagOrFeature(taxonomy.getHints(key).toArray(String[]::new)); }
    private boolean matchesCategory(Attraction attraction, String key) { return attraction != null && taxonomy.getHints(key).contains(attraction.getCategory()); }
    private boolean matchesConfigured(Attraction attraction, String categoryKey, String featureKey) { return matchesCategory(attraction, categoryKey) || hasFeatures(attraction, featureKey); }
    private boolean nameMatches(Attraction attraction, String key) { return attraction != null && taxonomy.getHints(key).stream().anyMatch(attraction.getName()::contains); }
    private boolean indoor(Attraction attraction) { return taxonomy.getHints("indoorEnvironmentValues").contains(attraction.getEffectiveEnvironment()); }

    private void enrichDaysWithDining(List<Map<String, Object>> days, TravelConstraints constraints, Map<String, Attraction> byId) {
        if (days == null || days.isEmpty() || diningVenueService == null || taxonomy == null || mealGapSolver == null) return;
        Set<String> usedDiningNames = new HashSet<>();
        for (Map<String, Object> day : days) {
            for (Map<String, Object> stop : PlanAdjustmentHelper.dayStops(day)) {
                if (PlanAdjustmentHelper.isDiningStop(stop, taxonomy)) usedDiningNames.add(PlanAdjustmentHelper.safe(stop.get("name")));
            }
        }
        for (Map<String, Object> day : days) {
            List<Map<String, Object>> stops = new ArrayList<>(PlanAdjustmentHelper.dayStops(day));
            if (stops.isEmpty()) continue;
            int dayNumber = day.get("day") instanceof Number number ? number.intValue() : 1;
            List<String> diagnostics = new ArrayList<>();
            for (String slotType : List.of("LUNCH", "DINNER")) {
                var configured = taxonomy.getMealWindow(slotType);
                if (configured == null || hasMeal(stops, slotType, configured)) continue;
                int start = configured.startMinute();
                int end = configured.endMinute();
                // Initial enrichment covers the planned day, not an unrequested period before its first activity.
                Integer dayStart = ChineseDateTimeParser.extractExplicitTimeMinutes(PlanAdjustmentHelper.safe(stops.get(0).get("time")));
                if (dayStart != null) start = Math.max(start, dayStart);
                String arrival = constraints == null ? "" : PlanAdjustmentHelper.safe(constraints.getArrivalAt());
                String departure = constraints == null ? "" : PlanAdjustmentHelper.safe(constraints.getDepartureAt());
                if (dayNumber == 1) {
                    Integer time = ChineseDateTimeParser.extractExplicitTimeMinutes(arrival);
                    if (time != null) start = Math.max(start, time);
                    else if (("LUNCH".equals(slotType) && (arrival.contains("下午") || arrival.contains("晚上") || arrival.contains("夜间")))
                            || ("DINNER".equals(slotType) && (arrival.contains("晚上") || arrival.contains("夜间")))) continue;
                }
                if (dayNumber == days.size()) {
                    Integer time = ChineseDateTimeParser.extractExplicitTimeMinutes(departure);
                    if (time != null) end = Math.min(end, time);
                    else if (departure.contains("上午") || departure.contains("早晨")
                            || ("DINNER".equals(slotType) && (departure.contains("中午") || departure.contains("下午")))) continue;
                }
                if (start >= end) continue;
                var window = new com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow(start, end, configured.idealMinute(), configured.defaultLabel());
                Map<String, Object> anchorStop = null;
                int closest = Integer.MAX_VALUE;
                for (Map<String, Object> stop : stops) {
                    if (PlanAdjustmentHelper.isDiningStop(stop, taxonomy)) continue;
                    Integer time = ChineseDateTimeParser.extractExplicitTimeMinutes(PlanAdjustmentHelper.safe(stop.get("time")));
                    if (time != null && Math.abs(time - window.idealMinute()) < closest) {
                        anchorStop = stop;
                        closest = Math.abs(time - window.idealMinute());
                    }
                }
                if (anchorStop == null) continue;
                Attraction anchor = byId.get(PlanAdjustmentHelper.safe(anchorStop.get("venueId")));
                if (anchor == null) anchor = PlanAdjustmentHelper.resolveStopAnchor(anchorStop, this);
                var dining = diningVenueService.resolveNearbyDining(anchor, slotType, PlannerDiningContextMapper.from(constraints), usedDiningNames);
                if (dining == null || dining.name() == null || usedDiningNames.contains(dining.name())
                        || !PlanAdjustmentHelper.isValidCoordinate(dining.location())) {
                    diagnostics.add(slotType + ":DINING_POI_UNRESOLVED");
                    continue;
                }
                String district = anchor == null ? "" : PlanAdjustmentHelper.safe(anchor.getDistrict());
                Attraction meal = Attraction.builder().id(dining.id()).name(dining.name()).district(district).location(dining.location()).build();
                var placement = mealGapSolver.findGap(stops, meal, new ItineraryTimeEngine().parseDurationMinutes(dining.duration()),
                        window, constraints == null ? null : constraints.getTransportPreference(), this);
                if (placement.isEmpty()) {
                    diagnostics.add(slotType + ":NO_FEASIBLE_MEAL_GAP");
                    continue;
                }
                String time = new ItineraryTimeEngine().formatTime(placement.get().startMinute());
                Map<String, Object> mealStop = createDiningStop(dining, "day" + dayNumber + "-" + slotType.toLowerCase(java.util.Locale.ROOT), time, district);
                mealStop.put("mealSlot", slotType);
                stops.add(placement.get().index(), mealStop);
                mealGapSolver.applyRouteMetadata(stops, placement.get());
                usedDiningNames.add(dining.name());
            }
            day.put("stops", stops);
            if (!diagnostics.isEmpty()) day.put("diningDiagnostics", diagnostics);
        }
    }

    private boolean hasMeal(List<Map<String, Object>> stops, String slotType, com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow window) {
        for (Map<String, Object> stop : stops) {
            if (!PlanAdjustmentHelper.isDiningStop(stop, taxonomy)) continue;
            if (slotType.equals(stop.get("mealSlot")) || slotType.equals(stop.get("diningType"))) return true;
            Integer time = ChineseDateTimeParser.extractExplicitTimeMinutes(PlanAdjustmentHelper.safe(stop.get("time")));
            if (time != null && time >= window.startMinute() && time < window.endMinute()) return true;
        }
        return false;
    }

    private void annotateStops(List<Map<String, Object>> days, TravelConstraints constraints) {
        List<String> matches = new ArrayList<>();
        if (!"未提供".equals(constraints.getCompanions()) && constraints.originOf("companions") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("同行人：" + constraints.getCompanions());
        }
        if (constraints.originOf("walkingTolerance") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT && !"正常".equals(constraints.getWalkingTolerance())) {
            matches.add("步行：" + constraints.getWalkingTolerance());
        }
        if (!"未提供".equals(constraints.getBudget()) && constraints.originOf("budget") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("预算：" + constraints.getBudget());
        }
        if (!"未提供".equals(constraints.getTransportPreference()) && constraints.originOf("transportPreference") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("交通：" + constraints.getTransportPreference());
        }
        if (constraints.originOf("interests") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            for (String interest : constraints.getInterests()) matches.add("兴趣：" + interest);
        }
        String route = routePreference(constraints);
        for (Map<String, Object> day : days) {
            String departureContext;
            if (constraints.getStartPlace() != null && !constraints.getStartPlace().isBlank() && !"未提供".equals(constraints.getStartPlace())) {
                String sp = constraints.getStartPlace();
                departureContext = "已确认行程起点【" + sp + "】。首段优先衔接起点附近、符合用户偏好的站点，并按实时路线规划交通。";
            } else if (!"未提供".equals(constraints.getStayArea())) {
                departureContext = "已将“" + constraints.getStayArea() + "”作为出发衔接参考，具体酒店地址仍需高德解析。";
            } else {
                departureContext = "未指定住宿区域，路线从首个景点开始计算。";
            }
            day.put("departureContext", departureContext);
            day.put("mealGuidance", mealGuidance(constraints));
            Object stopsValue = day.get("stops");
            if (!(stopsValue instanceof List<?> stops)) continue;
            int dayNum = day.get("day") instanceof Number num ? num.intValue() : 1;
            for (int sIdx = 0; sIdx < stops.size(); sIdx++) {
                Object value = stops.get(sIdx);
                if (!(value instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked") Map<String, Object> stop = (Map<String, Object>) raw;
                stop.put("matchedConstraints", new ArrayList<>(matches));
                stop.put("routePreference", route);

                // Day 1 First stop departure marker
                if (dayNum == 1 && sIdx == 0 && constraints.getStartPlace() != null && !constraints.getStartPlace().isBlank() && !"未提供".equals(constraints.getStartPlace())) {
                    String sp = constraints.getStartPlace();
                    stop.put("departureLabel", "行程起点：" + sp);
                    stop.put("startPlace", sp);
                    if (!stop.containsKey("routeFromPrevious")) {
                        stop.put("routeFromPrevious", Map.of("selectedMode", "接驳待核验",
                                "summary", "从【" + sp + "】出发，接驳路线与耗时待核验"));
                    }
                }

                // If this is a dining stop, preserve its dining recommendation reason and attributes
                if ("DINING".equals(stop.get("type")) || "餐".equals(stop.get("icon"))) {
                    continue;
                }

                stop.put("walkingInfo", Map.of("summary", stop.get("walk") == null ? "路线待计算" : stop.get("walk"), "status", "未知", "preference", route));
                Attraction attraction = attractionService.get(String.valueOf(stop.get("venueId")));
                ScoreBreakdown breakdown = scoreBreakdown(stop.get("scoreBreakdown"));
                RouteCost routeCost = RouteCost.fromMap(stop.get("resolvedRouteCost"));
                if (attraction != null && planNarrativeService != null) {
                    stop.put("recommendationReason", planNarrativeService.buildStopReason(attraction, breakdown, routeCost, constraints));
                } else {
                    stop.put("recommendationReason", recommendationReason(stop, constraints));
                }
                stop.put("explanationSource", "TEMPLATE");
                stop.put("costSummary", "有限".equals(constraints.getBudget()) ? "预算友好优先 · 费用需确认" : "费用待确认");
                stop.put("estimatedCost", stop.get("costSummary"));
            }
        }
    }

    private String recommendationReason(Map<String, Object> stop, TravelConstraints constraints) {
        List<String> parts = new ArrayList<>();
        if ("低".equals(constraints.getWalkingTolerance())) parts.add("优先减少连续步行");
        if ("有限".equals(constraints.getBudget())) parts.add("纳入预算有限策略");
        if (!"未提供".equals(constraints.getTransportPreference())) parts.add("交通偏好：" + constraints.getTransportPreference());
        return parts.isEmpty() ? String.valueOf(stop.get("detail")) : String.join("，", parts) + "。";
    }

    private Map<String, Object> planContext(TravelConstraints c) {
        String route = routePreference(c);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startingArea", c.getStayArea());
        result.put("startingAreaStatus", "未提供".equals(c.getStayArea()) ? "未指定住宿区域，路线从首个景点开始计算。" : "已将“" + c.getStayArea() + "”作为每日出发衔接参考，具体酒店地址仍需高德解析。");
        result.put("routePreference", route);
        result.put("routeStrategy", "transit".equals(route) ? "公共交通优先；路线服务不可用时保留静态回退。" : "按步行路线计算，必要时结合公交衔接。");
        result.put("budgetStrategy", "有限".equals(c.getBudget()) ? "预算有限：优先公共空间与低消费段，费用出发前确认。" : "费用以动态核验为准。");
        result.put("foodGuidance", mealGuidance(c));
        return result;
    }

    private String routePreference(TravelConstraints c) {
        String transport = c.getTransportPreference();
        // Explicit/current transport always wins over the low-walking compatibility fallback.
        if ("打车优先".equals(transport)) return "taxi";
        if ("地铁优先".equals(transport) || "公共交通优先".equals(transport)
                || "公交优先".equals(transport) || "综合交通优先".equals(transport)) return "transit";
        if ("步行优先".equals(transport)) return "walking";
        // The provider has no bicycle/driving implementation in this phase. Keep
        // the preference visible while using a conservative static walking route.
        if ("驾车优先".equals(transport) || "骑行优先".equals(transport)) return "unsupported";
        if ("低".equals(c.getWalkingTolerance())) return "transit";
        return "walking";
    }

    private String mealGuidance(TravelConstraints c) {
        String preference = c.getDietPreference() == null ? "" : c.getDietPreference().trim();
        String defaultPreference = taxonomy.getHints("defaultDiningPreference").stream().findFirst().orElse("");
        String defaultLabel = taxonomy.getHints("defaultDiningDisplayLabel").stream().findFirst().orElse("");
        if (!preference.isBlank() && !defaultPreference.equals(preference)) {
            return "饮食策略：按用户饮食偏好“" + preference + "”筛选实时餐饮候选，核实营业、菜单与位置后就近安排。";
        }
        if (defaultPreference.equals(preference) && !defaultPreference.isBlank()) return "饮食策略：结合" + (c.getDestination() == null || c.getDestination().isBlank() ? "目的地" : c.getDestination()) + "的本地风味与行程动线筛选餐饮候选，营业信息出发前核验。";
        return "饮食策略：结合景区周边" + defaultLabel + "，午晚两餐就近步行可达。";
    }

    private Map<String, Object> qualityMetrics(TravelConstraints c, int requestedDays, List<Map<String, Object>> days) {
        List<Map<String, Object>> checks = new ArrayList<>();
        int maxDays = PlannerScoringPolicy.limit(taxonomy, "maxTripDays");
        boolean withinDayLimit = maxDays <= 0 || requestedDays <= maxDays;
        checks.add(Map.of("key", "durationDays", "satisfied", withinDayLimit,
                "note", withinDayLimit ? "排程覆盖请求天数" : "请求天数超过配置的排程上限 " + maxDays + " 天"));
        checks.add(Map.of("key", "duplicateVenues", "satisfied", noDuplicates(days), "note", "全行程景点不重复"));

        List<String> stopNames = days.stream()
                .filter(d -> d.get("stops") instanceof List<?>)
                .flatMap(d -> ((List<?>) d.get("stops")).stream())
                .filter(s -> s instanceof Map<?, ?>)
                .map(s -> String.valueOf(((Map<?, ?>) s).get("name")))
                .toList();

        if (c.getMustVisit() != null && !c.getMustVisit().isEmpty()) {
            boolean allMustSatisfied = true;
            for (String must : c.getMustVisit()) {
                if (stopNames.stream().noneMatch(name -> name.contains(must))) {
                    allMustSatisfied = false;
                    break;
                }
            }
            checks.add(Map.of("key", "mustVisit", "satisfied", allMustSatisfied, "note", allMustSatisfied ? "必游景点已排入" : "部分必游景点未排入"));
        }

        if (c.getAvoid() != null && !c.getAvoid().isEmpty()) {
            boolean avoidSatisfied = true;
            for (String av : c.getAvoid()) {
                if (stopNames.stream().anyMatch(name -> name.contains(av))) {
                    avoidSatisfied = false;
                    break;
                }
            }
            checks.add(Map.of("key", "avoidance", "satisfied", avoidSatisfied, "note", avoidSatisfied ? "避开项未包含在行程中" : "行程中包含了避开项"));
        }

        long satisfied = checks.stream().filter(item -> Boolean.TRUE.equals(item.get("satisfied"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("constraintSatisfaction", Map.of("score", checks.isEmpty() ? 0 : (double) satisfied / checks.size(), "satisfied", satisfied, "total", checks.size(), "checks", checks));
        result.put("poiCoverage", 0.0);
        result.put("routeCoverage", 0.0);
        result.put("weatherCoverage", 0.0);
        result.put("dataCoverage", 0.0);
        result.put("unknownFacts", 1);
        result.put("dynamicFacts", 1);
        return result;
    }

    private boolean noDuplicates(List<Map<String, Object>> days) {
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> day : days) if (day.get("stops") instanceof List<?> stops) for (Object item : stops) if (item instanceof Map<?, ?> stop) if (!ids.add(stop.get("venueId"))) return false;
        return true;
    }

    private Map<String, Object> sourceStatus() { return Map.of("poi", "待查询", "route", "待查询", "weather", "待查询", "fallback", true); }
    private Map<String, Object> retrievalStatus() { return Map.of("mode", "java-planner-local", "status", "未执行外部检索", "facts", List.of(), "citations", List.of()); }
    private Map<String, Object> summary(int dayCount) { return Map.of("confirmed", List.of("路线骨架", "景点顺序", dayCount + "日节奏"), "dynamic", List.of("天气", "实时人流", "公交与步行耗时"), "unknown", List.of("门票与预约", "营业时间", "最终消费"), "conflict", List.of("热门景点高峰期停留时长需现场决策")); }
    private Map<String, Object> weather() { return fact("天气", "未知·待查询", "未知", "天气必须在出行前再次确认，当前尚未取得动态结果。", List.of(citation("高德天气接口", "/v3/weather/weatherInfo"))); }

    /** Returns an ISO date resolved from the request, defaulting to today when unspecified. */
    private String itineraryDate(TravelConstraints constraints, int day) {
        return TravelDateResolver.resolveItineraryDate(constraints, day);
    }

    private String dateLabel(TravelConstraints c, int day) { return "未提供".equals(c.getArrivalAt()) ? "旅行第" + day + "天" : c.getArrivalAt() + "起第" + day + "天"; }

    private List<Map<String, Object>> defaultFacts(Attraction a) {
        List<Map<String, Object>> facts = new ArrayList<>();
        if (a.getSummary() != null && !a.getSummary().isBlank())
            facts.add(fact("景点简介", a.getSummary(), officialSummaryCitation(a).isEmpty() ? "未核验" : "来源已核验",
                    "仅景点静态简介适用此来源；开放、票价和无障碍信息需另行确认。", officialSummaryCitation(a)));
        facts.addAll(List.of(fact("开放与营业", "未知·需实时确认", "未知", "当前规划未取得开放状态。", List.of(citation("高德 POI 详情接口", "/v5/place/detail"))),
                fact("门票与消费", "未知·需用户确认", "未知", "价格与预约规则可能变化。", List.of(citation("用户确认项", "用户输入"))),
                fact("到达方式", a.getWalk(), "未知", "静态目录中的到达提示仅作回退，路线需由高德接口或出发前确认。", List.of(citation("高德路径规划接口", "/v5/direction/walking")))));
        return facts;
    }

    private List<Map<String, Object>> officialSummaryCitation(Attraction a) {
        if (a.getVerifiedFields() == null || !a.getVerifiedFields().contains("summary") || !safeSourceUrl(a.getSourceUrl())) return List.of();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("title", "景点简介官方来源");
        source.put("publisher", "景点目录官方来源");
        source.put("url", a.getSourceUrl());
        source.put("verifiedAt", a.getVerifiedAt() == null ? "" : a.getVerifiedAt());
        source.put("verifiedFields", List.of("summary"));
        source.put("status", "来源已核验");
        source.put("note", "仅支持景点简介，不支持实时开放、票价、路线或无障碍结论。");
        return List.of(source);
    }

    private boolean safeSourceUrl(String raw) {
        try {
            var uri = java.net.URI.create(raw);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (RuntimeException ignored) { return false; }
    }

    private List<Map<String, Object>> citations() { return List.of(citation("高德 POI 搜索接口", "/v5/place/text"), citation("高德步行路线接口", "/v5/direction/walking"), citation("高德公交路线接口", "/v5/direction/transit/integrated")); }
    private Map<String, Object> citation(String title, String endpoint) { return citation(title, endpoint, "待查询"); }
    private Map<String, Object> citation(String title, String endpoint, String status) { return Map.of("title", title, "publisher", "高德开放平台", "endpoint", endpoint, "status", status, "note", "当前为规划阶段的保守来源标记。"); }
    private Map<String, Object> fact(String label, Object value, String status, String note, List<Map<String, Object>> citations) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("label", label != null ? label : "");
        f.put("value", value != null ? value : "未知·需确认");
        f.put("status", status != null ? status : "未知");
        f.put("note", note != null ? note : "");
        f.put("citations", citations != null ? citations : List.of());
        return f;
    }
    private Map<String, Object> mapContext(String location) {
        List<Double> coordinates = new ArrayList<>();
        if (location != null && location.contains(",")) try { coordinates.add(Double.parseDouble(location.split(",")[0])); coordinates.add(Double.parseDouble(location.split(",")[1])); } catch (NumberFormatException ignored) { }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinates", coordinates.isEmpty() ? null : coordinates);
        result.put("polyline", List.of());
        result.put("routeFromPrevious", null);
        return result;
    }
    private Map<String, Object> versionEntry(int version, String label, List<String> changed, String reason) { return Map.of("version", version, "label", label, "changedSegments", changed, "reason", reason, "createdAt", Instant.now().toString()); }

    private ScoreBreakdown scoreBreakdown(Object value) {
        if (value instanceof ScoreBreakdown sb) return sb;
        if (value instanceof Map<?, ?> map) {
            int interest = (int) number(map.get("interest"));
            int companion = (int) number(map.get("companion"));
            int walking = (int) number(map.get("walking"));
            int dining = (int) number(map.get("dining"));
            int mustVisit = (int) number(map.get("mustVisit"));
            int avoid = (int) number(map.get("avoid"));
            int explicit = (int) number(map.get("explicit"));
            int total = (int) number(map.get("total"));
            List<String> codes = new ArrayList<>();
            if (map.get("reasonCodes") instanceof List<?> list) {
                for (Object item : list) if (item != null) codes.add(String.valueOf(item));
            }
            return new ScoreBreakdown(interest, companion, walking, dining, mustVisit, avoid, explicit, total, codes);
        }
        return ScoreBreakdown.zero();
    }

    private long number(Object value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> explicitIds(String prompt, Map<String, Attraction> byId) {
        if (aliasMatcher != null) {
            return aliasMatcher.findExplicitAttractionIds(prompt, byId != null ? byId.keySet() : null);
        }
        return List.of();
    }

    private record Spec(String id, String attractionId, String time) { }
    private record Scored(Attraction attraction, int score, ScoreBreakdown breakdown) { }
}

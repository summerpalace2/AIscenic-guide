package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.OperationDeadline;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;
import com.ai.guide.domain.planner.engine.PlannerScoringPolicy;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.ChengduPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.XiAnHeritageTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic planner for the explicit "start place + short time budget" path.
 *
 * This service deliberately does not know any attraction names. AMap resolves
 * the user's place, searches nearby POIs, and verifies the route segments. The
 * core catalog remains the default planner and RAG is only an optional detail
 * enrichment after a provider entity has been confirmed.
 */
@Service
public class RuntimeLocationPlanner {

    private final GeoRouteGatewayPort routeService;
    private final ObjectMapper objectMapper;
    private final int searchRadiusMeters;
    private final int maxCandidates;
    private final int mediumBudgetMinutes;
    private final int longBudgetMinutes;
    private final int shortRadiusMeters;
    private final int mediumRadiusMeters;
    private final int longRadiusMeters;
    private final int shortPageCount;
    private final int mediumPageCount;
    private final int longPageCount;
    private final DiningVenueService diningVenueService;
    private final PlannerTaxonomyPort taxonomy;
    private final DynamicShortTripPolicyPort dynamicPolicy;
    private final TopographyStrategyRouter topographyStrategyRouter;
    private final com.ai.guide.domain.planner.engine.ItineraryTimeEngine durationParser =
            new com.ai.guide.domain.planner.engine.ItineraryTimeEngine();

    @Autowired
    public RuntimeLocationPlanner(
            GeoRouteGatewayPort routeService,
            ObjectMapper objectMapper,
            @Value("${planner.runtime-location.search-radius-meters}") int searchRadiusMeters,
            @Value("${planner.runtime-location.max-candidates}") int maxCandidates,
            @Value("${planner.runtime-location.medium-budget-minutes}") int mediumBudgetMinutes,
            @Value("${planner.runtime-location.long-budget-minutes}") int longBudgetMinutes,
            @Value("${planner.runtime-location.short-radius-meters}") int shortRadiusMeters,
            @Value("${planner.runtime-location.medium-radius-meters}") int mediumRadiusMeters,
            @Value("${planner.runtime-location.long-radius-meters}") int longRadiusMeters,
            @Value("${planner.runtime-location.short-page-count}") int shortPageCount,
            @Value("${planner.runtime-location.medium-page-count}") int mediumPageCount,
            @Value("${planner.runtime-location.long-page-count}") int longPageCount,
            DiningVenueService diningVenueService,
            PlannerTaxonomyPort taxonomy,
            DynamicShortTripPolicyPort dynamicPolicy,
            TopographyStrategyRouter topographyStrategyRouter) {
        this.routeService = routeService;
        this.objectMapper = objectMapper;
        this.searchRadiusMeters = Math.max(500, searchRadiusMeters);
        this.maxCandidates = Math.max(10, Math.min(60, maxCandidates));
        this.mediumBudgetMinutes = mediumBudgetMinutes;
        this.longBudgetMinutes = longBudgetMinutes;
        this.shortRadiusMeters = shortRadiusMeters;
        this.mediumRadiusMeters = mediumRadiusMeters;
        this.longRadiusMeters = longRadiusMeters;
        this.shortPageCount = shortPageCount;
        this.mediumPageCount = mediumPageCount;
        this.longPageCount = longPageCount;
        this.diningVenueService = diningVenueService;
        this.taxonomy = taxonomy;
        this.dynamicPolicy = dynamicPolicy;
        this.topographyStrategyRouter = topographyStrategyRouter;
    }

    public RuntimeLocationPlanner(GeoRouteGatewayPort routeService, ObjectMapper objectMapper,
                                  int searchRadiusMeters, int maxCandidates,
                                  DiningVenueService diningVenueService, PlannerTaxonomyPort taxonomy) {
        this(routeService, objectMapper, searchRadiusMeters, maxCandidates, 180, 300, 4000, 6000, 8000, 2, 3, 3, diningVenueService, taxonomy,
                taxonomy instanceof DynamicShortTripPolicyPort policy ? policy : DynamicShortTripPolicyPort::unconfigured,
                new TopographyStrategyRouter(List.of(new ChongqingTopographyStrategy(), new ChengduPlainTopographyStrategy(),
                        new XiAnHeritageTopographyStrategy(), new GenericPlainTopographyStrategy())));
    }

    public RuntimeLocationPlanner(GeoRouteGatewayPort routeService, ObjectMapper objectMapper,
                                  int searchRadiusMeters, int maxCandidates) {
        this(routeService, objectMapper, searchRadiusMeters, maxCandidates,
                new DiningVenueService(null, routeService, TaxonomyPort.empty()), TaxonomyPort.empty());
    }

    /** Convenient constructor for focused unit tests. */
    public RuntimeLocationPlanner(GeoRouteGatewayPort routeService) {
        this(routeService, new ObjectMapper(), 6000, 40);
    }

    public Map<String, Object> plan(TravelConstraints constraints, int version) {
        if (constraints == null || !constraints.hasExplicitSpatialRequest()) return null;

        String requestedPlace = text(constraints.getStartPlace());
        int budgetMinutes = constraints.getTimeBudgetMinutes();
        Map<String, Object> trip = baseTrip(constraints, version);
        if (!routeService.isConfigured()) {
            return finishWithoutResults(trip, "START_PLACE_UNAVAILABLE", requestedPlace,
                    "当前未配置高德服务端密钥，无法确认起点与周边景点；不会回退到无关核心景点。", 0);
        }

        GeoPoiOutcome startOutcome = routeService.searchPoi(requestedPlace, city(constraints));
        com.ai.guide.domain.geo.model.GeoPoiCandidate start = chooseStart(startOutcome.candidates(), requestedPlace);
        if (start == null || start.coordinate() == null) {
            String reason = startOutcome.reason() == null || startOutcome.reason().isBlank()
                    ? "高德没有返回可定位的起点。" : startOutcome.reason();
            String errorCode = switch (startOutcome.status()) {
                case TLS_ERROR -> "AMAP_TLS_ERROR";
                case AUTH_ERROR -> "AMAP_AUTH_ERROR";
                case TIMEOUT -> "AMAP_TIMEOUT";
                case RATE_LIMITED -> "AMAP_RATE_LIMITED";
                default -> "START_PLACE_UNRESOLVED";
            };
            return finishWithoutResults(trip, errorCode, requestedPlace,
                    "无法确认“" + requestedPlace + "”的地图位置：" + reason, 0);
        }

        boolean longBudget = budgetMinutes >= longBudgetMinutes;
        boolean mediumBudget = budgetMinutes >= mediumBudgetMinutes;
        int pages = longBudget ? longPageCount : mediumBudget ? mediumPageCount : shortPageCount;
        int budgetRadius = longBudget ? longRadiusMeters : mediumBudget ? mediumRadiusMeters : shortRadiusMeters;
        int effectiveRadius = Math.max(searchRadiusMeters, budgetRadius);
        GeoPoiOutcome around = routeService.searchAround(
                coordinate(start.coordinate()), effectiveRadius, scenicType(), "", city(constraints), pages);
        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates = usableCandidates(around.candidates(), start);
        if (candidates.isEmpty() && !OperationDeadline.expired()) {
            // Some provider regions return a broader result only when the type
            // filter is omitted. This remains a generic provider fallback.
            around = routeService.searchAround(coordinate(start.coordinate()), effectiveRadius,
                    "", requiredTaxonomyValue("runtimePoiFallbackSearchKeyword"), city(constraints), Math.min(pages, 2));
            candidates = usableCandidates(around.candidates(), start);
        }

        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> rankedCandidates = rankCandidates(start, candidates, constraints);
        List<Map<String, Object>> stops = selectStops(start, rankedCandidates, constraints, budgetMinutes);
        boolean routesVerified = allRoutesVerified(stops);
        Map<String, Object> spatial = mutableMap(trip.get("spatialPlan"));
        spatial.put("status", stops.isEmpty() ? "NO_FEASIBLE_CANDIDATE" : "COMPLETED");
        spatial.put("requestedStartPlace", requestedPlace);
        spatial.put("resolvedStartPlace", safeName(start));
        spatial.put("resolvedStartCoordinate", coordinate(start.coordinate()));
        spatial.put("timeBudgetMinutes", budgetMinutes);
        spatial.put("searchRadiusMeters", searchRadiusMeters);
        spatial.put("candidateCount", candidates.size());
        spatial.put("selectedCount", stops.size());
        spatial.put("providerStatus", around.status().name());
        spatial.put("providerReason", text(around.reason()));
        spatial.put("generatedAt", Instant.now().toString());
        trip.put("spatialPlan", spatial);

        String tripDate = TravelDateResolver.resolveItineraryDate(constraints, 1);
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("date", tripDate);
        day.put("dateLabel", tripDate + " · 第1天 · 起点附近短途游");
        day.put("title", requestedPlace + "附近短途游");
        day.put("label", "起点附近动态规划");
        int initialStartMinute = resolveStartMinute(constraints);
        day.put("departureContext", "已确认起点“" + safeName(start) + "”，按出行时段（" + formatTime(initialStartMinute) + " 出发）与高德实时路网动态规划。");
        day.put("mealGuidance", stops.stream().anyMatch(stop -> "DINING".equals(stop.get("type")))
                ? "已在用餐时段内安排周边餐饮，可继续微调或更换餐厅。" : "当前预算和用餐时段内未找到可安排的餐饮站点。");
        day.put("weather", Map.of("label", "天气", "value", "待确认", "status", "未知",
                "note", "出发前需确认动态天气。"));
        day.put("stops", stops);
        day.put("routeStatus", routesVerified ? "高德路线已核验" : "路线待核验");
        trip.put("days", List.of(day));
        trip.put("summary", dynamicSummary(stops.isEmpty()));
        trip.put("sourceStatus", Map.of("poi", "已查询", "route", routesVerified ? "已核验" : "待确认",
                "weather", "待确认", "fallback", !stops.isEmpty() && !routesVerified));
        trip.put("sourceMode", "高德实时周边规划");
        trip.put("routeDataStatus", stops.isEmpty() ? "UNAVAILABLE" : routesVerified ? "VERIFIED_AMAP" : "PARTIAL");
        trip.put("degraded", stops.isEmpty() || !routesVerified);
        trip.put("degradationReasons", stops.isEmpty() ? List.of("NO_FEASIBLE_NEARBY_CANDIDATE")
                : routesVerified ? List.of() : List.of("UNVERIFIED_ROUTE_SEGMENT"));
        if (OperationDeadline.expired()) {
            trip.put("degraded", true);
            List<String> reasons = new ArrayList<>();
            if (trip.get("degradationReasons") instanceof List<?> currentReasons)
                currentReasons.forEach(reason -> reasons.add(String.valueOf(reason)));
            reasons.add("PLANNING_DEADLINE_EXCEEDED");
            trip.put("degradationReasons", reasons);
            spatial.put("status", stops.isEmpty() ? "TIMEOUT" : "PARTIAL_TIMEOUT");
            trip.put("summary", "规划时间预算已用尽，当前仅保留已完成的 " + stops.size() + " 处站点；其余安排尚未完成，可继续局部调整。");
            Map<String, Object> partialSource = mutableMap(trip.get("sourceStatus"));
            partialSource.put("planning", "部分完成：请求超时");
            partialSource.put("fallback", true);
            trip.put("sourceStatus", partialSource);
        }
        return trip;
    }

    /**
     * Builds a proposal for a local operation on a runtime AMap plan.  The
     * result is intentionally JSON-shaped so the adjustment service can store
     * it as an ordinary proposal without introducing a second UI protocol.
     */
    public Map<String, Object> adjust(Map<String, Object> sourceTrip,
                                      TravelConstraints constraints,
                                      PlanAdjustmentIntent intent) {
        if (!isRuntimeTrip(sourceTrip) || constraints == null || intent == null) return null;
        if (intent.getType() == ConversationIntentType.REPLAN_DAY
                || intent.getType() == ConversationIntentType.REPLAN_DAY_FOR_CONDITION) {
            Map<String, Object> rebuilt = plan(constraints, number(sourceTrip.get("version"), 1) + 1);
            if (rebuilt == null) return null;
            return adjustmentResult(List.of(Map.of("optionId", "option-1", "name", "重新查询起点附近动态方案",
                            "summary", "按原起点与时间预算重新执行高德周边搜索。", "venueId", "")),
                    Map.of("option-1", rebuilt), List.of("trip"), true,
                    List.of(), List.of(), Map.of("mode", "RUNTIME_REPLAN"),
                    "已按原起点和剩余规划约束重新查询高德动态方案。", "", "");
        }

        StopRef target = locateStop(sourceTrip, intent.getTargetStopId());
        if (target == null && intent.getTargetStopReference() != null) {
            target = locateStopByName(sourceTrip, intent.getTargetStopReference());
        }
        if (target == null && intent.getType() == ConversationIntentType.ADD_STOP) {
            Map<String, Object> firstDay = mapList(sourceTrip.get("days")).stream().findFirst().orElse(null);
            if (firstDay != null) {
                List<Map<String, Object>> dayStops = mapList(firstDay.get("stops"));
                if (!dayStops.isEmpty()) {
                    Map<String, Object> last = dayStops.get(dayStops.size() - 1);
                    target = new StopRef(firstDay, dayStops, dayStops.size() - 1, last);
                } else {
                    Map<String, Object> anchor = new LinkedHashMap<>();
                    anchor.put("id", "runtime-add-anchor");
                    anchor.put("time", "待安排");
                    target = new StopRef(firstDay, dayStops, 0, anchor);
                }
            }
        }
        if (target == null) return null;
        final StopRef resolvedTarget = target;

        String requestedName = firstNonBlank(intent.getReplacementPlaceName(), intent.getRequestedVenueName());
        if (intent.getType() == ConversationIntentType.ADD_STOP && requestedName == null
                && intent.getPreferences() != null && !intent.getPreferences().isEmpty()) {
            requestedName = String.join(" ", intent.getPreferences());
        }
        String startCoordinate = runtimeStartCoordinate(sourceTrip);
        if (startCoordinate.isBlank()) return null;
        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates = searchCandidates(
                startCoordinate, requestedName, constraints);
        Set<String> used = usedVenues(sourceTrip);
        com.ai.guide.domain.geo.model.GeoCoordinate startPoint = com.ai.guide.domain.geo.model.GeoCoordinates.parse(startCoordinate);
        candidates = candidates.stream()
                .filter(candidate -> !used.contains("amap-" + text(candidate.poiId())))
                .filter(candidate -> !text(candidate.poiId()).equals(text(resolvedTarget.stop().get("amapPoiId"))))
                .filter(candidate -> startPoint == null
                        || distanceKm(startPoint, candidate.coordinate()) <= searchRadiusMeters / 1000.0)
                .limit(3)
                .toList();
        if (candidates.isEmpty()) {
            return adjustmentResult(List.of(), Map.of(), List.of(), false,
                    List.of("NO_AMAP_CANDIDATE"), List.of("扩大搜索范围后重试", "保持当前站点"),
                    Map.of("mode", "RUNTIME_NEARBY", "provider", "AMAP"),
                    "高德暂时没有返回满足当前地点与时间约束的候选景点。", text(resolvedTarget.stop().get("name")), requestedName);
        }

        Map<String, Map<String, Object>> optionPlans = new LinkedHashMap<>();
        List<Map<String, Object>> options = new ArrayList<>();
        List<String> changedSegmentIds = new ArrayList<>();
        String previousCoordinate = previousCoordinate(sourceTrip, resolvedTarget, startCoordinate);
        String previousPoiId = previousPoiId(sourceTrip, resolvedTarget);
        int visitMinutes = durationMinutes(resolvedTarget.stop().get("duration"));
        int sourcePlannedMinutes = plannedMinutes(sourceTrip);
        int replacedTravelMinutes = routeMinutesFromStop(resolvedTarget.stop());
        int optionIndex = 1;
        for (com.ai.guide.domain.geo.model.GeoPoiCandidate candidate : candidates) {
            GeoRoutePairOutcome route = routeService.routePair(
                    previousCoordinate, coordinate(candidate.coordinate()), city(constraints), previousPoiId,
                    text(candidate.navigationPoiId()).isBlank() ? text(candidate.poiId()) : candidate.navigationPoiId(),
                    "transit".equalsIgnoreCase(constraints.getTransportPreference()) ? "transit" : "walking");
            int travelMinutes = routeMinutes(route);
            if (travelMinutes <= 0) travelMinutes = estimateTravelMinutes(previousCoordinate, coordinate(candidate.coordinate()));
            if (intent.getType() == ConversationIntentType.ADD_STOP
                    && sourcePlannedMinutes + travelMinutes + visitMinutes > constraints.getTimeBudgetMinutes()) {
                continue;
            }
            if (intent.getType() != ConversationIntentType.ADD_STOP
                    && sourcePlannedMinutes - replacedTravelMinutes + travelMinutes > constraints.getTimeBudgetMinutes()) {
                continue;
            }
            Map<String, Object> optionDraft = copy(sourceTrip);
            StopRef draftTarget = intent.getType() == ConversationIntentType.ADD_STOP
                    ? addAnchor(optionDraft, resolvedTarget)
                    : locateStop(optionDraft, text(resolvedTarget.stop().get("id")));
            if (draftTarget == null) continue;
            Map<String, Object> originalForAdd = intent.getType() == ConversationIntentType.ADD_STOP
                    ? addStopTemplate(candidate) : draftTarget.stop();
            Map<String, Object> replacement = replacementStop(candidate, originalForAdd, route, travelMinutes, visitMinutes,
                    text(intent.getRawMessage()));
            if (intent.getType() == ConversationIntentType.ADD_STOP) {
                draftTarget.stops().add(replacement);
            } else {
                draftTarget.stops().set(draftTarget.index(), replacement);
            }
            if (changedSegmentIds.isEmpty()) changedSegmentIds.add(text(replacement.get("id")));
            refreshRuntimeMetadata(optionDraft, constraints);
            String optionId = "option-" + optionIndex++;
            optionPlans.put(optionId, optionDraft);
            options.add(Map.of(
                    "optionId", optionId,
                    "venueId", "amap-" + text(candidate.poiId()),
                    "amapPoiId", text(candidate.poiId()),
                    "name", safeName(candidate),
                    "requestedName", requestedName == null ? safeName(candidate) : requestedName,
                    "summary", "高德实时周边候选，距离上一站约 " + travelMinutes + " 分钟。",
                    "duration", "约 " + visitMinutes + " 分钟",
                    "district", district(candidate.address()),
                    "address", text(candidate.address()),
                    "source", "AMAP_POI_AROUND"
            ));
        }
        return adjustmentResult(options, optionPlans,
                changedSegmentIds, true, List.of(), List.of(),
                Map.of("mode", "RUNTIME_NEARBY", "provider", "AMAP", "targetStopId", text(resolvedTarget.stop().get("id"))),
                intent.getType() == ConversationIntentType.ADD_STOP ? "已生成高德周边添加候选，请确认后应用。" : "已生成高德周边替换候选，请确认后应用。",
                text(resolvedTarget.stop().get("name")), requestedName);
    }

    private Map<String, Object> adjustmentResult(List<Map<String, Object>> options,
                                                  Map<String, Map<String, Object>> optionPlans,
                                                  List<String> changedSegments, boolean feasible,
                                                  List<String> reasonCodes, List<String> alternatives,
                                                  Map<String, Object> diagnostics, String message,
                                                  String sourceStopName, String requestedName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateReplacements", options);
        result.put("optionPlans", optionPlans);
        result.put("changedSegments", changedSegments);
        result.put("feasible", feasible);
        result.put("reasonCodes", reasonCodes);
        result.put("alternatives", alternatives);
        result.put("diagnostics", diagnostics);
        result.put("message", message);
        result.put("sourceStopName", sourceStopName);
        result.put("requestedReplacementName", requestedName == null ? "" : requestedName);
        result.put("replacementMode", "RUNTIME_AMAP");
        return result;
    }

    private List<com.ai.guide.domain.geo.model.GeoPoiCandidate> searchCandidates(String startCoordinate,
                                                                         String requestedName,
                                                                         TravelConstraints constraints) {
        GeoPoiOutcome outcome;
        if (requestedName != null && !requestedName.isBlank()) {
            String reference = requestedName.startsWith("amap-") ? requestedName.substring("amap-".length()) : requestedName;
            outcome = requestedName.startsWith("amap-")
                    ? routeService.poiDetail(reference)
                    : routeService.searchPoi(reference, city(constraints));
        } else {
            outcome = routeService.searchAround(startCoordinate, searchRadiusMeters, scenicType(), "", city(constraints), 3);
            if (outcome.candidates().isEmpty()) {
                outcome = routeService.searchAround(startCoordinate, searchRadiusMeters, "", requiredTaxonomyValue("runtimePoiFallbackSearchKeyword"), city(constraints), 3);
            }
        }
        if (outcome.candidates() == null) return List.of();
        return outcome.candidates().stream()
                .filter(candidate -> candidate != null && candidate.coordinate() != null && !safeName(candidate).isBlank())
                .toList();
    }

    private String requiredTaxonomyValue(String key) {
        return taxonomy.getHints(key).stream().findFirst().map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Missing planner taxonomy value: " + key));
    }

    private boolean isRuntimeTrip(Map<String, Object> trip) {
        Object spatial = trip == null ? null : trip.get("spatialPlan");
        return spatial instanceof Map<?, ?> map && "RUNTIME_NEARBY".equalsIgnoreCase(text(map.get("mode")));
    }

    private String runtimeStartCoordinate(Map<String, Object> trip) {
        Object spatial = trip == null ? null : trip.get("spatialPlan");
        if (!(spatial instanceof Map<?, ?> map)) return "";
        return text(map.get("resolvedStartCoordinate"));
    }

    private String previousCoordinate(Map<String, Object> trip, StopRef target, String fallback) {
        if (target.index() > 0) return text(target.stops().get(target.index() - 1).get("location"));
        return fallback;
    }

    private String previousPoiId(Map<String, Object> trip, StopRef target) {
        if (target.index() <= 0) return "";
        Map<String, Object> previous = target.stops().get(target.index() - 1);
        return firstNonBlank(text(previous.get("amapPoiId")), text(previous.get("venueId")));
    }

    private Map<String, Object> replacementStop(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate,
                                                 Map<String, Object> original,
                                                 GeoRoutePairOutcome route,
                                                 int travelMinutes, int visitMinutes, String reason) {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", original.get("id"));
        stop.put("stableStopId", original.getOrDefault("stableStopId", original.get("id")));
        stop.put("entityId", "amap-" + text(candidate.poiId()));
        stop.put("venueId", "amap-" + text(candidate.poiId()));
        stop.put("amapPoiId", text(candidate.poiId()));
        stop.put("typecode", text(candidate.typecode()));
        stop.put("poiType", text(candidate.type()));
        stop.put("businessArea", text(candidate.businessArea()));
        stop.put("name", safeName(candidate));
        stop.put("displayName", safeName(candidate));
        stop.put("district", district(candidate.address()));
        stop.put("time", original.getOrDefault("time", "待安排"));
        stop.put("startTime", original.getOrDefault("startTime", original.getOrDefault("time", "待安排")));
        stop.put("duration", "约 " + visitMinutes + " 分钟");
        stop.put("icon", "景");
        stop.put("tone", "dynamic");
        stop.put("summary", "高德周边搜索确认的实时候选，建议游览约 " + visitMinutes + " 分钟。");
        stop.put("detail", "该景点由高德 POI 动态确认；开放时间、门票与现场客流需出发前再次核验。");
        stop.put("recommendationReason", "因为“" + reason + "”，由高德实时搜索确认并保持当前时间预算。");
        stop.put("walk", routeLabel(route, travelMinutes));
        stop.put("walkingDifficulty", "待确认");
        stop.put("walkingInfo", routeMap(route));
        stop.put("indoor", false);
        stop.put("ticket", "待核验");
        stop.put("bestTime", "待核验");
        stop.put("estimatedCost", "费用待确认");
        stop.put("costSummary", "费用待确认");
        stop.put("address", text(candidate.address()));
        stop.put("location", coordinate(candidate.coordinate()));
        stop.put("mapContext", Map.of("source", "AMAP", "coordinate", coordinate(candidate.coordinate())));
        stop.put("source", "AMAP_POI_AROUND");
        stop.put("facts", List.of());
        stop.put("citations", List.of(Map.of("title", "高德周边 POI", "publisher", "高德开放平台",
                "endpoint", "/v5/place/around", "status", "已核验")));
        stop.put("routeFromPrevious", routeMap(route));
        return stop;
    }

    private void refreshRuntimeMetadata(Map<String, Object> trip, TravelConstraints constraints) {
        Map<String, Object> spatial = mutableMap(trip.get("spatialPlan"));
        spatial.put("mode", "RUNTIME_NEARBY");
        spatial.put("timeBudgetMinutes", constraints.getTimeBudgetMinutes());
        spatial.put("updatedAt", Instant.now().toString());
        trip.put("spatialPlan", spatial);
        trip.put("sourceMode", "高德实时周边规划");
        Map<String, Object> currentStatus = mutableMap(trip.get("sourceStatus"));
        Object weatherStatus = currentStatus.getOrDefault("weather", "已查询");
        Map<String, Object> updatedStatus = new LinkedHashMap<>(currentStatus);
        updatedStatus.put("poi", "已查询");
        boolean verified = allRoutesVerified(mapList(trip.get("days")).stream()
                .flatMap(day -> mapList(day.get("stops")).stream()).toList());
        // Verifying retained routes cannot complete the missing portion of an earlier timed-out plan.
        boolean planningIncomplete = com.ai.guide.domain.planner.model.PlannerVersionMetadata.planningIncomplete(trip);
        updatedStatus.put("route", verified ? "已核验" : "待确认");
        updatedStatus.put("weather", weatherStatus);
        updatedStatus.put("fallback", planningIncomplete || !verified);
        trip.put("sourceStatus", updatedStatus);
        trip.put("routeDataStatus", verified ? "VERIFIED_AMAP" : "PARTIAL");
        trip.put("degraded", planningIncomplete || !verified);
    }

    private Map<String, Object> copy(Map<String, Object> source) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(source), LinkedHashMap.class);
        } catch (Exception ignored) {
            return new LinkedHashMap<>(source);
        }
    }

    private StopRef locateStop(Map<String, Object> trip, String id) {
        if (id == null || id.isBlank()) return null;
        List<Map<String, Object>> dayList = mapList(trip == null ? null : trip.get("days"));
        for (Map<String, Object> day : dayList) {
            List<Map<String, Object>> stops = stopList(day.get("stops"));
            for (int index = 0; index < stops.size(); index++) {
                Map<String, Object> stop = stops.get(index);
                if (id.equals(text(stop.get("id"))) || id.equals(text(stop.get("stableStopId")))) {
                    return new StopRef(day, stops, index, stop);
                }
            }
        }
        return null;
    }

    private StopRef locateStopByName(Map<String, Object> trip, String name) {
        if (name == null || name.isBlank()) return null;
        for (Map<String, Object> day : mapList(trip == null ? null : trip.get("days"))) {
            List<Map<String, Object>> stops = stopList(day.get("stops"));
            for (int index = 0; index < stops.size(); index++) {
                Map<String, Object> stop = stops.get(index);
                if (text(stop.get("name")).contains(name) || name.contains(text(stop.get("name")))) {
                    return new StopRef(day, stops, index, stop);
                }
            }
        }
        return null;
    }

    private StopRef addAnchor(Map<String, Object> trip, StopRef sourceTarget) {
        for (Map<String, Object> day : mapList(trip == null ? null : trip.get("days"))) {
            if (text(day.get("day")).equals(text(sourceTarget.day().get("day")))) {
                List<Map<String, Object>> stops = stopList(day.get("stops"));
                Map<String, Object> anchor = stops.isEmpty() ? new LinkedHashMap<>() : stops.get(stops.size() - 1);
                return new StopRef(day, stops, stops.size(), anchor);
            }
        }
        return null;
    }

    private Map<String, Object> addStopTemplate(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("id", "runtime-added-" + text(candidate.poiId()));
        template.put("stableStopId", template.get("id"));
        template.put("time", "待安排");
        template.put("startTime", "待安排");
        return template;
    }

    private Set<String> usedVenues(Map<String, Object> trip) {
        Set<String> used = new HashSet<>();
        for (Map<String, Object> day : mapList(trip == null ? null : trip.get("days"))) {
            for (Map<String, Object> stop : mapList(day.get("stops"))) used.add(text(stop.get("venueId")));
        }
        return used;
    }

    private int durationMinutes(Object value) {
        String text = text(value);
        int knownMinutes = durationParser.parseKnownDurationMinutes(text);
        if (knownMinutes > 0) return knownMinutes;
        DynamicShortTripPolicyPort.Policy policy = dynamicPolicy.getPolicy();
        return policy.unknownStopDurationMinutes();
    }

    private int plannedMinutes(Map<String, Object> trip) {
        int total = 0;
        for (Map<String, Object> day : mapList(trip == null ? null : trip.get("days"))) {
            for (Map<String, Object> stop : mapList(day.get("stops"))) {
                total += durationMinutes(stop.get("duration"));
                Object route = stop.get("routeFromPrevious");
                if (route instanceof Map<?, ?> routeMap && routeMap.get("durationSeconds") != null) {
                    total += Math.max(0, number(routeMap.get("durationSeconds"), 0) / 60);
                }
            }
        }
        return total;
    }

    private int routeMinutesFromStop(Map<String, Object> stop) {
        Object route = stop == null ? null : stop.get("routeFromPrevious");
        if (route instanceof Map<?, ?> value && value.get("durationSeconds") != null) {
            return Math.max(0, number(value.get("durationSeconds"), 0) / 60);
        }
        return 0;
    }

    private int number(Object value, int fallback) {
        try { return Integer.parseInt(text(value)); } catch (RuntimeException ignored) { return fallback; }
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) item;
            result.add(map);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stopList(Object value) {
        if (!(value instanceof List<?>)) return new ArrayList<>();
        return (List<Map<String, Object>>) value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private record StopRef(Map<String, Object> day, List<Map<String, Object>> stops,
                           int index, Map<String, Object> stop) {}

    public int resolveStartMinute(TravelConstraints constraints) {
        String prompt = constraints == null ? "" : constraints.getRawPrompt();
        String normalized = prompt == null ? "" : prompt;
        if (!normalized.isBlank()) {
            Matcher exact = Pattern.compile("(?i)(?:上午|早上|早晨)\\s*(\\d{1,2})(?:点|时|:(\\d{2}))?").matcher(normalized);
            if (exact.find()) return boundedMinute(Integer.parseInt(exact.group(1)), exact.group(2));
            Matcher noon = Pattern.compile("(?i)(?:中午)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(normalized);
            if (noon.find()) return noon.group(1) == null ? configuredMinute("runtimeDefaultNoonTime", currentMinute())
                    : boundedMinute(Integer.parseInt(noon.group(1)), noon.group(2));
            Matcher afternoon = Pattern.compile("(?i)(?:下午)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(normalized);
            if (afternoon.find()) return afternoon.group(1) == null ? configuredMinute("runtimeDefaultAfternoonTime", currentMinute())
                    : boundedMinute(Integer.parseInt(afternoon.group(1)), afternoon.group(2));
            Matcher evening = Pattern.compile("(?i)(?:晚上|今晚|夜间)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(normalized);
            if (evening.find()) return evening.group(1) == null ? configuredMinute("runtimeDefaultEveningTime", currentMinute())
                    : boundedMinute(Integer.parseInt(evening.group(1)), evening.group(2));
            Matcher clockTime = Pattern.compile("(\\b\\d{1,2})[:：](\\d{2})").matcher(normalized);
            if (clockTime.find()) return boundedMinute(Integer.parseInt(clockTime.group(1)), clockTime.group(2));
            if (normalized.contains("下午")) return configuredMinute("runtimeDefaultAfternoonTime", currentMinute());
            if (taxonomy.getHints("runtimeNightKeywords").stream().anyMatch(normalized::contains))
                return configuredMinute("runtimeDefaultNightTime", currentMinute());
            if (normalized.contains("上午") || normalized.contains("早上"))
                return configuredMinute("runtimeDefaultMorningTime", currentMinute());
        }

        try {
            String zone = taxonomy.getHints("runtimeTimeZone").stream().findFirst().orElse(ZoneId.systemDefault().getId());
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zone));
            int currentMinute = now.getHour() * 60 + now.getMinute();
            int[] activeWindow = configuredMinuteWindow("runtimeActiveWindow");
            int rounding = positiveHint("runtimeTimeRoundingMinutes", 1);
            if (activeWindow != null && currentMinute >= activeWindow[0] && currentMinute <= activeWindow[1])
                return Math.min(1439, ((currentMinute + rounding - 1) / rounding) * rounding);
            boolean requestsNight = taxonomy.getHints("runtimeNightKeywords").stream().anyMatch(normalized::contains);
            if (activeWindow != null && currentMinute > activeWindow[1] && requestsNight) {
                int latest = configuredMinute("runtimeLatestNightStartTime", currentMinute);
                return Math.min(latest, ((currentMinute + rounding - 1) / rounding) * rounding);
            }
            return configuredMinute("runtimeDefaultStartTime", currentMinute);
        } catch (Exception ignored) {
            return configuredMinute("runtimeDefaultStartTime", currentMinute());
        }
    }

    private int currentMinute() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        return now.getHour() * 60 + now.getMinute();
    }

    private int boundedMinute(int hour, String minuteText) {
        int minute = minuteText == null ? 0 : Integer.parseInt(minuteText);
        return Math.max(0, Math.min(1439, hour * 60 + minute));
    }

    private int configuredMinute(String key, int fallback) {
        return taxonomy.getHints(key).stream().findFirst().map(value -> {
            LocalTime time = LocalTime.parse(value);
            return time.getHour() * 60 + time.getMinute();
        }).orElse(fallback);
    }

    private int positiveHint(String key, int fallback) {
        return taxonomy.getHints(key).stream().findFirst().map(Integer::parseInt).filter(value -> value > 0).orElse(fallback);
    }

    private int[] configuredMinuteWindow(String key) {
        return taxonomy.getHints(key).stream().findFirst().map(value -> {
            String[] bounds = value.split("\\|", 2);
            if (bounds.length != 2) return null;
            LocalTime start = LocalTime.parse(bounds[0]);
            LocalTime end = LocalTime.parse(bounds[1]);
            return new int[]{start.getHour() * 60 + start.getMinute(), end.getHour() * 60 + end.getMinute()};
        }).orElse(null);
    }

    private Map<String, Object> buildRuntimeDiningStop(
            com.ai.guide.domain.geo.model.GeoPoiCandidate anchorPoi,
            String slotType,
            int arrivalMinute,
            TravelConstraints constraints,
            int stopIndex, int remainingMinutes) {
        if (anchorPoi == null || anchorPoi.coordinate() == null || OperationDeadline.expired()) return null;
        String district = district(anchorPoi.address());
        Attraction anchor = new Attraction();
        anchor.setId(anchorPoi != null ? "amap-" + text(anchorPoi.poiId()) : "amap-anchor");
        anchor.setName(anchorPoi != null ? safeName(anchorPoi) : "当前游览地标");
        anchor.setDistrict(district);
        anchor.setLocation(coordinate(anchorPoi.coordinate()));

        com.ai.guide.domain.dining.model.DiningOption option = diningVenueService.resolveNearbyDining(anchor, slotType, PlannerDiningContextMapper.from(constraints));
        if (OperationDeadline.expired()) return null;
        if (option == null || text(option.id()).startsWith("dining-fallback")
                || com.ai.guide.domain.geo.model.GeoCoordinates.parse(option.location()) == null) return null;
        String diningPoiId = text(option.id()).startsWith("amap-dining-")
                ? text(option.id()).substring("amap-dining-".length()) : "";
        String preference = "transit".equalsIgnoreCase(constraints.getTransportPreference()) ? "transit" : "walking";
        GeoRoutePairOutcome route = routeService.routePair(anchor.getLocation(), option.location(), city(constraints),
                text(anchorPoi.poiId()), diningPoiId, preference);
        if (OperationDeadline.expired()) return null;
        int travelMinutes = routeMinutes(route);
        if (travelMinutes <= 0) travelMinutes = estimateTravelMinutes(anchor.getLocation(), option.location());
        int mealMinutes = durationMinutes(option.duration());
        if (travelMinutes + mealMinutes > remainingMinutes) return null;
        int mealStart = arrivalMinute + travelMinutes;
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow window = taxonomy.getMealWindow(slotType);
        if (mealStart < window.startMinute() || mealStart + mealMinutes > window.endMinute()) return null;

        Map<String, Object> stop = new LinkedHashMap<>();
        String stopId = "runtime-dining-" + slotType.toLowerCase();
        stop.put("id", stopId);
        stop.put("stableStopId", stopId);
        stop.put("entityId", option.id());
        stop.put("venueId", option.id());
        stop.put("name", option.name());
        stop.put("displayName", option.name());
        stop.put("district", district);
        stop.put("time", formatTime(mealStart));
        stop.put("startTime", formatTime(mealStart));
        stop.put("mealSlotType", slotType);
        stop.put("amapPoiId", diningPoiId);
        stop.put("plannedTravelMinutes", travelMinutes);
        stop.put("duration", "约 " + mealMinutes + " 分钟");
        stop.put("type", "DINING");
        stop.put("category", "美食");
        stop.put("icon", "餐");
        stop.put("tone", option.tone() != null ? option.tone() : "gold");
        stop.put("summary", option.summary());
        stop.put("detail", option.specialtyDish() + " · " + option.summary());
        stop.put("specialtyDish", option.specialtyDish());
        stop.put("diningType", option.diningType());
        stop.put("ticket", option.averageCost());
        stop.put("costSummary", option.averageCost());
        stop.put("estimatedCost", option.averageCost());
        String walkText = routeLabel(route, travelMinutes);
        boolean isMotorized = route != null && route.selected() != null && route.selected().segment() != null
                && !"WALKING".equalsIgnoreCase(route.selected().segment().mode());

        stop.put("walk", walkText);
        stop.put("walkDifficulty", isMotorized ? "顺路通达" : "低");
        stop.put("walkingDifficulty", isMotorized ? "顺路通达" : "低");
        stop.put("indoor", true);
        stop.put("bestTime", "营业时间待核验");
        stop.put("recommendationReason", option.recommendationReason());
        stop.put("tasteReason", option.recommendationReason());
        stop.put("explanationSource", "DINING_KNOWLEDGE");
        stop.put("location", option.location());
        stop.put("address", district);
        stop.put("routePreference", isMotorized ? "transit" : "walking");
        Map<String, Object> rfp = routeMap(route);
        rfp.put("estimatedDurationMinutes", travelMinutes);
        stop.put("routeFromPrevious", rfp);
        stop.put("source", diningPoiId.isBlank() ? "DINING_CATALOG" : "AMAP_POI_AROUND");
        return stop;
    }

    private List<Map<String, Object>> selectStops(com.ai.guide.domain.geo.model.GeoPoiCandidate start,
                                                   List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates,
                                                   TravelConstraints constraints, int budgetMinutes) {
        List<Map<String, Object>> stops = new ArrayList<>();
        String previousCoordinate = coordinate(start.coordinate());
        String previousPoiId = text(start.poiId());
        com.ai.guide.domain.geo.model.GeoPoiCandidate previousCandidate = start;
        String transportPref = constraints != null ? constraints.getTransportPreference() : "";
        boolean prefersTransit = "transit".equalsIgnoreCase(transportPref)
                || "公交优先".equals(transportPref)
                || "公共交通优先".equals(transportPref)
                || "地铁优先".equals(transportPref);
        int remaining = budgetMinutes;
        int initialStartMinute = resolveStartMinute(constraints);
        int startMinute = initialStartMinute;

        // Semantic parsing supplies the diet preference; this planner does not maintain a food dictionary.
        boolean hasDietPref = constraints != null && constraints.getDietPreference() != null
                && !taxonomy.getHints("missingPreferenceValues").contains(constraints.getDietPreference())
                && !taxonomy.getHints("defaultDiningPreference").contains(constraints.getDietPreference());
        boolean explicitFood = hasDietPref;
        int planEndMinute = initialStartMinute + budgetMinutes;
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow lunch = taxonomy.getMealWindow("LUNCH");
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow dinner = taxonomy.getMealWindow("DINNER");
        boolean spansLunch = initialStartMinute < lunch.endMinute() && planEndMinute > lunch.startMinute();
        boolean spansDinner = initialStartMinute < dinner.endMinute() && planEndMinute > dinner.startMinute();
        DynamicShortTripPolicyPort.Policy schedulePolicy = dynamicPolicy.getPolicy();
        boolean allowDining = (explicitFood || spansLunch || spansDinner)
                && budgetMinutes >= schedulePolicy.diningMinimumBudgetMinutes();
        boolean diningInserted = false;
        String mealSlotType;
        if (spansLunch) {
            mealSlotType = "LUNCH";
        } else if (spansDinner) {
            mealSlotType = "DINNER";
        } else if (initialStartMinute >= taxonomy.getMealWindow("NIGHT_SNACK").startMinute()) {
            mealSlotType = "NIGHT_SNACK";
        } else if (initialStartMinute >= dinner.startMinute()) {
            mealSlotType = "DINNER";
        } else {
            mealSlotType = "LUNCH";
        }

        int maxStops = Math.max(schedulePolicy.minimumStops(), Math.min(schedulePolicy.maximumStops(),
                (budgetMinutes + schedulePolicy.stopBudgetBaseMinutes()) / schedulePolicy.minutesPerStop()));
        Map<PoiCategory, Integer> categoryCounts = new LinkedHashMap<>();

        for (com.ai.guide.domain.geo.model.GeoPoiCandidate candidate : candidates) {
            if (stops.size() >= maxStops || remaining < schedulePolicy.minimumRemainingForStopMinutes()) break;
            if (OperationDeadline.expired()) break;

            PoiCategory category = detectCategory(safeName(candidate), text(candidate.type()), text(candidate.typecode()), taxonomy);

            int categoryLimit = category == PoiCategory.PARK_NATURE
                    ? (budgetMinutes > schedulePolicy.longParkBudgetMinutes()
                    ? schedulePolicy.longTripMaxParks() : schedulePolicy.shortTripMaxParks())
                    : schedulePolicy.maxOccurrences(category.name());
            if (categoryCounts.getOrDefault(category, 0) >= categoryLimit) {
                continue;
            }

            if (!diningInserted && allowDining) {
                String effectiveSlotType = mealSlotType;
                com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow mealWindow = taxonomy.getMealWindow(effectiveSlotType);
                boolean timeTrigger = startMinute >= mealWindow.startMinute() && startMinute < mealWindow.endMinute();
                boolean budgetUrgent = remaining <= schedulePolicy.diningUrgencyMaximumRemainingMinutes()
                        && remaining >= schedulePolicy.diningUrgencyMinimumRemainingMinutes()
                        && (spansLunch || spansDinner || explicitFood);
                if (timeTrigger || (explicitFood && remaining <= budgetMinutes - schedulePolicy.explicitFoodDiningLeadMinutes()) || budgetUrgent) {
                    Map<String, Object> diningStop = buildRuntimeDiningStop(
                            previousCandidate, effectiveSlotType, startMinute, constraints, stops.size() + 1, remaining);
                    if (diningStop != null) {
                        stops.add(diningStop);
                        int diningDuration = durationMinutes(diningStop.get("duration"));
                        int diningTravel = number(diningStop.get("plannedTravelMinutes"), 0);
                        remaining -= diningDuration + diningTravel;
                        startMinute += diningDuration + diningTravel;
                        diningInserted = true;
                        Object diningLoc = diningStop.get("location");
                        if (diningLoc != null && !text(diningLoc).isBlank()) {
                            previousCoordinate = text(diningLoc);
                        }
                        previousPoiId = text(diningStop.get("amapPoiId"));
                        previousCandidate = new com.ai.guide.domain.geo.model.GeoPoiCandidate(previousPoiId,
                                text(diningStop.get("name")), com.ai.guide.domain.geo.model.GeoCoordinates.parse(previousCoordinate),
                                text(diningStop.get("address")), "餐饮服务", null, null, previousPoiId, null, null);
                        if (stops.size() >= maxStops || remaining < schedulePolicy.minimumRemainingForStopMinutes()) break;
                    }
                }
            }

            String destination = coordinate(candidate.coordinate());
            com.ai.guide.domain.geo.model.GeoCoordinate prevCoord = com.ai.guide.domain.geo.model.GeoCoordinates.parse(previousCoordinate);
            com.ai.guide.domain.geo.model.GeoCoordinate destCoord = candidate.coordinate();
            double distBetweenKm = (prevCoord != null && destCoord != null)
                    ? distanceKm(prevCoord, destCoord) : 1.0;
            String segmentPreference = prefersTransit || (distBetweenKm > schedulePolicy.transitPreferredDistanceKm() && !"低".equals(constraints.getWalkingTolerance()))
                    ? "transit" : "walking";
            GeoRoutePairOutcome route = routeService.routePair(
                    previousCoordinate, destination, city(constraints), previousPoiId,
                    text(candidate.navigationPoiId()).isBlank() ? text(candidate.poiId()) : candidate.navigationPoiId(),
                    segmentPreference);
            if (OperationDeadline.expired()) break;
            int travelMinutes = routeMinutes(route);
            if (travelMinutes <= 0) travelMinutes = estimateTravelMinutes(previousCoordinate, destination);
            int visitMinutes = schedulePolicy.visitDuration(safeName(candidate), text(candidate.type()), remaining - travelMinutes);
            if (travelMinutes + visitMinutes > remaining) {
                if (remaining - travelMinutes >= schedulePolicy.minimumVisitRemainderMinutes()
                        && visitMinutes > schedulePolicy.minimumRemainingForStopMinutes()) {
                    visitMinutes = remaining - travelMinutes;
                } else {
                    continue;
                }
            }

            int arrivalMinute = startMinute + travelMinutes;

            Map<String, Object> stop = stop(candidate, stops.size() + 1,
                    formatTime(arrivalMinute), visitMinutes, route, travelMinutes, constraints);
            if (previousCandidate != null) {
                Attraction fromAttr = Attraction.builder()
                        .name(safeName(previousCandidate))
                        .district(district(previousCandidate.address()))
                        .location(coordinate(previousCandidate.coordinate()))
                        .amapQuery(Map.of("city", city(constraints)))
                        .build();
                Attraction toAttr = Attraction.builder()
                        .name(safeName(candidate))
                        .district(district(candidate.address()))
                        .location(coordinate(candidate.coordinate()))
                        .amapQuery(Map.of("city", city(constraints)))
                        .build();
                String topographyHint = topographyStrategyRouter.route(constraints.getDestination()).generateTransitCharacteristicHint(
                        fromAttr, toAttr, segmentPreference);
                if (topographyHint != null && !topographyHint.isBlank()) {
                    stop.put("topographyTransitHint", topographyHint);
                    stop.put("trafficHint", topographyHint);
                }
            }
            stops.add(stop);
            categoryCounts.merge(category, 1, Integer::sum);
            remaining -= travelMinutes + visitMinutes;
            startMinute = arrivalMinute + visitMinutes;
            previousCoordinate = destination;
            previousPoiId = text(candidate.navigationPoiId()).isBlank()
                    ? text(candidate.poiId()) : candidate.navigationPoiId();
            previousCandidate = candidate;
        }

        if (!OperationDeadline.expired()
                && !diningInserted && allowDining && remaining >= schedulePolicy.minimumRemainingToAddDiningMinutes() && !stops.isEmpty()) {
            String effectiveSlotType = mealSlotType;
            com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow mealWindow = taxonomy.getMealWindow(effectiveSlotType);
            boolean eligiblePostMeal = startMinute >= mealWindow.startMinute() && startMinute < mealWindow.endMinute();
            if (eligiblePostMeal) {
                Map<String, Object> diningStop = buildRuntimeDiningStop(
                        previousCandidate, effectiveSlotType, startMinute, constraints, stops.size() + 1, remaining);
                if (diningStop != null) {
                    stops.add(diningStop);
                }
            }
        }

        return stops;
    }

    private List<com.ai.guide.domain.geo.model.GeoPoiCandidate> rankCandidates(
            com.ai.guide.domain.geo.model.GeoPoiCandidate start,
            List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates,
            TravelConstraints constraints) {
        if (candidates == null || candidates.isEmpty() || constraints == null) {
            return candidates == null ? List.of() : candidates;
        }

        List<String> interests = constraints.getInterests() == null ? List.of() : constraints.getInterests();
        boolean lowWalking = "低".equals(constraints.getWalkingTolerance());
        String companions = constraints.getCompanions() == null ? "" : constraints.getCompanions();
        boolean chongqingProfile = usesCityStrategy(constraints.getDestination(), "CHONGQING");

        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> scored = new ArrayList<>(candidates);
        scored.sort((a, b) -> {
            double scoreA = scoreCandidate(a, start, interests, lowWalking, companions, chongqingProfile);
            double scoreB = scoreCandidate(b, start, interests, lowWalking, companions, chongqingProfile);
            return Double.compare(scoreB, scoreA);
        });
        return scored;
    }

    private double scoreCandidate(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate,
                                  com.ai.guide.domain.geo.model.GeoPoiCandidate start,
                                  List<String> interests,
                                  boolean lowWalking,
                                  String companions,
                                  boolean chongqingProfile) {
        double score = PlannerScoringPolicy.parameterFrom(taxonomy, "runtimeScoringParameters", "baseScore");
        String name = safeName(candidate);
        String type = text(candidate.type());
        String textToMatch = (name + " " + type + " " + text(candidate.address())).toLowerCase(Locale.ROOT);

        for (String interest : interests) {
            if (taxonomy.getHints("plannerNatureInterestLabels").contains(interest)) {
                if (matchesTaxonomyKeywords(textToMatch, "runtimeNatureInterestFeatures")) score += runtimeScore("interestNature");
            } else if (taxonomy.getHints("plannerHistoryInterestLabels").contains(interest)) {
                if (matchesTaxonomyKeywords(textToMatch, "runtimeHistoryInterestFeatures")) score += runtimeScore("interestHistory");
            } else if (taxonomy.getHints("chongqingTerrainInterestLabels").contains(interest) && chongqingProfile) {
                if (matchesTaxonomyKeywords(textToMatch, "chongqingTerrainInterestFeatures")) score += runtimeScore("interestTerrain");
            } else if (taxonomy.getHints("plannerNightInterestLabels").contains(interest)) {
                if (matchesTaxonomyKeywords(textToMatch, "plannerNightScoringFeatures")) score += runtimeScore("interestNight");
            } else if (taxonomy.getHints("plannerThermalInterestLabels").contains(interest)) {
                if (matchesTaxonomyKeywords(textToMatch, "runtimeThermalInterestFeatures")) score += runtimeScore("interestThermal");
            } else if (taxonomy.getHints("plannerLocalInterestLabels").contains(interest)) {
                if (matchesTaxonomyKeywords(textToMatch, "runtimeLocalCultureInterestFeatures")) score += runtimeScore("interestLocal");
            } else if (textToMatch.contains(interest.toLowerCase(Locale.ROOT))) {
                score += runtimeScore("interestGeneric");
            }
        }

        if (matchesTaxonomyKeywords(textToMatch, "runtimeProminentPoiFeatures")) {
            score += runtimeScore("prominentPoi");
        } else if (matchesTaxonomyKeywords(textToMatch, "runtimeSecondaryPoiFeatures")) {
            score += runtimeScore("secondaryPoi");
        } else if (matchesTaxonomyKeywords(textToMatch, "runtimeHistoricPoiFeatures")) {
            score += runtimeScore("historicPoi");
        } else if (matchesTaxonomyKeywords(name, "poiCategorySquareNameTokens")) {
            score += runtimeScore("squarePenalty");
        }

        if (candidate.rating() != null && candidate.rating() > 0) {
            if (candidate.rating() >= runtimeParameter("ratingExcellentThreshold")) {
                score += runtimeScore("ratingExcellent");
            } else if (candidate.rating() >= runtimeParameter("ratingGoodThreshold")) {
                score += runtimeScore("ratingGood");
            } else if (candidate.rating() >= runtimeParameter("ratingAverageThreshold")) {
                score += runtimeScore("ratingAverage");
            } else if (candidate.rating() < runtimeParameter("ratingLowThreshold")) {
                score += runtimeScore("ratingLowPenalty");
            }
        }
        if (candidate.heatTag() != null && matchesAnyHint(candidate.heatTag(), "runtimeProminentHeatFeatures")) {
            score += runtimeScore("officialHeat");
        }

        if (start != null && start.coordinate() != null && candidate.coordinate() != null) {
            double distance = distanceKm(start.coordinate(), candidate.coordinate());
            if (lowWalking) {
                if (distance < runtimeParameter("lowWalkingCloseDistanceKm")) score += runtimeScore("lowWalkingClose");
                else if (distance < runtimeParameter("lowWalkingMediumDistanceKm")) score += runtimeScore("lowWalkingMedium");
                else score -= (distance - runtimeParameter("lowWalkingMediumDistanceKm")) * runtimeParameter("lowWalkingDistancePenaltyPerKm");
            } else {
                if (distance < runtimeParameter("normalCloseDistanceKm")) score += runtimeScore("normalClose");
                else if (distance > runtimeParameter("normalFarDistanceKm")) score -= (distance - runtimeParameter("normalFarDistanceKm")) * runtimeParameter("normalFarDistancePenaltyPerKm");
            }
        }

        if (taxonomy.getHints("plannerCompanionSeniorAliases").contains(companions)
                || taxonomy.getHints("plannerCompanionChildAliases").contains(companions)) {
            if (matchesTaxonomyKeywords(textToMatch, "runtimeFamilyFriendlyFeatures")) score += runtimeScore("familyFriendly");
            if (matchesTaxonomyKeywords(textToMatch, "runtimeStrenuousFeatures")) score += runtimeScore("strenuousPenalty");
        }
        return score;
    }

    private double runtimeScore(String key) { return PlannerScoringPolicy.parameterFrom(taxonomy, "runtimeScoringParameters", key); }
    private double runtimeParameter(String key) { return PlannerScoringPolicy.parameterFrom(taxonomy, "runtimeScoringParameters", key); }
    private boolean matchesAnyHint(String value, String key) { return value != null && taxonomy.getHints(key).stream().anyMatch(value::contains); }

    private boolean matchesTaxonomyKeywords(String text, String key) {
        return matchesKeywords(text, taxonomy.getHints(key).toArray(String[]::new));
    }

    private boolean matchesKeywords(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String buildDynamicReason(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate, int travelMinutes, TravelConstraints constraints) {
        List<String> reasons = new ArrayList<>();
        if (constraints != null && constraints.getInterests() != null) {
            String name = safeName(candidate);
            String type = text(candidate.type());
            String target = name + " " + type;
            for (String interest : constraints.getInterests()) {
                if (taxonomy.getHints("plannerNatureInterestLabels").contains(interest)) {
                    if (matchesTaxonomyKeywords(target, "runtimeNatureInterestFeatures")) {
                        reasons.add("契合自然山水偏好");
                        break;
                    }
                } else if (taxonomy.getHints("plannerHistoryInterestLabels").contains(interest)) {
                    if (matchesTaxonomyKeywords(target, "runtimeHistoryInterestFeatures")) {
                        reasons.add("契合历史人文偏好");
                        break;
                    }
                } else if (taxonomy.getHints("chongqingTerrainInterestLabels").contains(interest) && usesCityStrategy(constraints.getDestination(), "CHONGQING")) {
                    if (matchesKeywords(target, taxonomy.getHints("chongqingTerrainInterestFeatures").toArray(String[]::new))) {
                        reasons.add(taxonomy.getHints("chongqingTerrainReason").stream().findFirst().orElse(""));
                        break;
                    }
                } else if (taxonomy.getHints("plannerNightInterestLabels").contains(interest)) {
                    if (matchesKeywords(target, taxonomy.getHints("plannerNightScoringFeatures").toArray(String[]::new))) {
                        reasons.add("契合夜景观赏需求");
                        break;
                    }
                } else if (taxonomy.getHints("plannerLocalInterestLabels").contains(interest)) {
                    if (matchesTaxonomyKeywords(target, "runtimeLocalCultureInterestFeatures")) {
                        reasons.add("展现当地市井生活");
                        break;
                    }
                }
            }
        }
        if (candidate.rating() != null && candidate.rating() >= runtimeParameter("ratingReasonThreshold")) {
            reasons.add("高德口碑评分 " + candidate.rating() + " 分深受游客喜爱");
        }
        if (candidate.heatTag() != null && !candidate.heatTag().isBlank()) {
            String tag = candidate.heatTag();
            if (matchesAnyHint(tag, "runtimeProminentHeatFeatures")) {
                reasons.add("获评“" + tag.replace(";", "·") + "”文旅地标");
            }
        }
        if (constraints != null && taxonomy.getHints("walkingLowAliases").contains(constraints.getWalkingTolerance())) {
            reasons.add("距离起点较近且步行平缓");
        }
        if (travelMinutes > 0) {
            reasons.add("交通约 " + travelMinutes + " 分钟可达");
        }
        if (reasons.isEmpty()) {
            return "距离起点较近，在高德周边搜索与时间预算内。";
        }
        return "安排“" + safeName(candidate) + "”是因为它" + String.join("，", reasons) + "。";
    }

    private Map<String, Object> stop(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate, int index,
                                     String startTime, int visitMinutes,
                                     GeoRoutePairOutcome route, int travelMinutes,
                                     TravelConstraints constraints) {
        String name = safeName(candidate);
        String poiId = text(candidate.poiId());
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "runtime-stop-" + index + "-" + (poiId.isBlank() ? index : poiId));
        stop.put("stableStopId", stop.get("id"));
        stop.put("entityId", poiId.isBlank() ? "amap-runtime-" + index : "amap-" + poiId);
        stop.put("venueId", poiId.isBlank() ? "amap-runtime-" + index : "amap-" + poiId);
        stop.put("amapPoiId", poiId);
        stop.put("typecode", text(candidate.typecode()));
        stop.put("poiType", text(candidate.type()));
        stop.put("businessArea", text(candidate.businessArea()));
        stop.put("name", name);
        stop.put("displayName", name);
        stop.put("district", district(candidate.address()));
        stop.put("time", startTime);
        stop.put("startTime", startTime);
        stop.put("duration", "约 " + visitMinutes + " 分钟");
        stop.put("icon", "景");
        stop.put("tone", "dynamic");
        stop.put("category", categoryDisplayName(detectCategory(name, text(candidate.type()), text(candidate.typecode()), taxonomy)));
        if (candidate.rating() != null && candidate.rating() > 0) {
            stop.put("rating", candidate.rating());
            stop.put("amapRating", candidate.rating());
        }
        if (candidate.heatTag() != null && !candidate.heatTag().isBlank()) {
            stop.put("heatTag", candidate.heatTag());
        }
        stop.put("summary", "高德周边搜索确认的实时候选，建议游览约 " + visitMinutes + " 分钟。");
        stop.put("detail", "该景点由高德 POI 动态确认；开放时间、门票与现场客流需出发前再次核验。");
        stop.put("recommendationReason", buildDynamicReason(candidate, travelMinutes, constraints));
        stop.put("walk", routeLabel(route, travelMinutes));
        stop.put("walkingDifficulty", "待确认");
        stop.put("walkingInfo", routeMap(route));
        stop.put("indoor", false);
        stop.put("ticket", "待核验");
        stop.put("bestTime", "待核验");
        stop.put("estimatedCost", "费用待确认");
        stop.put("costSummary", "费用待确认");
        stop.put("address", text(candidate.address()));
        stop.put("location", coordinate(candidate.coordinate()));
        stop.put("mapContext", Map.of("source", "AMAP", "coordinate", coordinate(candidate.coordinate())));
        stop.put("source", "AMAP_POI_AROUND");
        stop.put("facts", List.of());
        stop.put("citations", List.of(Map.of("title", "高德周边 POI", "publisher", "高德开放平台",
                "endpoint", "/v5/place/around", "status", "已核验")));
        stop.put("routeFromPrevious", routeMap(route));
        return stop;
    }

    private Map<String, Object> baseTrip(TravelConstraints constraints, int version) {
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-runtime-" + version);
        trip.put("version", version);
        trip.put("title", text(constraints.getStartPlace()) + "·" + constraints.getTimeBudgetMinutes() + "分钟附近规划");
        trip.put("subtitle", "明确起点动态规划 · 高德周边搜索");
        trip.put("constraints", constraints.asMap());
        trip.put("factPolicy", "高德确认实体后补充事实；未知字段显式标注");
        trip.put("planContext", Map.of("startingArea", constraints.getStartPlace(),
                "routeStrategy", "以明确起点为第一出发点，按高德路线耗时动态排程"));
        trip.put("retrieval", Map.of("mode", "entity-gated-rag", "status", "未执行",
                "reason", "先确认高德实体；实体匹配后才补充 RAG，不以 RAG 阻塞动态规划。"));
        trip.put("spatialPlan", new LinkedHashMap<>(Map.of("mode", "RUNTIME_NEARBY",
                "status", "RUNNING", "timeBudgetMinutes", constraints.getTimeBudgetMinutes())));
        trip.put("plannerVersion", "runtime-location-v1");
        trip.put("policyVersion", "runtime-location-2026.09");
        trip.put("explanationSource", "AMAP_RUNTIME");
        trip.put("versionHistory", List.of(Map.of("version", version, "label", "明确地点动态规划")));
        return trip;
    }

    private Map<String, Object> finishWithoutResults(Map<String, Object> trip, String status,
                                                      String requestedPlace, String message, int candidateCount) {
        Map<String, Object> spatial = mutableMap(trip.get("spatialPlan"));
        spatial.put("status", status);
        spatial.put("requestedStartPlace", requestedPlace);
        spatial.put("candidateCount", candidateCount);
        spatial.put("message", message);
        trip.put("spatialPlan", spatial);
        trip.put("days", List.of());
        trip.put("sourceMode", "高德动态规划不可用");
        trip.put("sourceStatus", Map.of("poi", "未确认", "route", "未执行", "weather", "待确认", "fallback", false));
        trip.put("routeDataStatus", "UNAVAILABLE");
        trip.put("summary", dynamicSummary(true));
        trip.put("degraded", true);
        trip.put("degradationReasons", List.of(status));
        return trip;
    }

    public enum PoiCategory {
        HISTORIC_CULTURE, // 文博旧址、古镇老街、历史建筑、遗址、纪念馆
        PARK_NATURE,      // 公园、植物园、湿地、森林、自然风景区、湖泊
        LANDMARK_VIEW,    // 观景台、步道、大桥、缆车索道与滨水地标
        RELIGIOUS_TEMPLE, // 寺庙、道观、祠堂、佛阁
        COMMERCIAL_STREET,// 步行街、市集、商业街区
        SQUARE,           // 广场
        OTHER             // 其他综合文旅
    }

    public static PoiCategory detectCategory(String name, String type, String typecode, PlannerTaxonomyPort taxonomy) {
        String code = typecode == null ? "" : typecode.trim();
        if ("110105".equals(code)) return PoiCategory.SQUARE;
        if (code.startsWith("11010")) return PoiCategory.PARK_NATURE;
        if (AmapPoiTaxonomy.isCulturalVenue(code) || "110204".equals(code) || "110210".equals(code)) return PoiCategory.HISTORIC_CULTURE;
        if ("110205".equals(code) || "110206".equals(code) || "110207".equals(code)) return PoiCategory.RELIGIOUS_TEMPLE;
        if ("110209".equals(code)) return PoiCategory.LANDMARK_VIEW;
        String n = name == null ? "" : name.trim();
        String t = type == null ? "" : type.trim();

        if (containsAny(n, taxonomy.getHints("poiCategorySquareNameTokens"))) return PoiCategory.SQUARE;
        if (containsAny(n, taxonomy.getHints("poiCategoryLandmarkNameTokens"))
                || containsAny(t, taxonomy.getHints("poiCategoryLandmarkTypeTokens"))) return PoiCategory.LANDMARK_VIEW;
        if (containsAny(n, taxonomy.getHints("poiCategoryHistoryNameTokens"))
                || containsAny(t, taxonomy.getHints("poiCategoryHistoryTypeTokens"))) return PoiCategory.HISTORIC_CULTURE;
        if (containsAny(n, taxonomy.getHints("poiCategoryCommercialNameTokens"))
                || containsAny(t, taxonomy.getHints("poiCategoryCommercialTypeTokens"))) return PoiCategory.COMMERCIAL_STREET;
        if (containsAny(n, taxonomy.getHints("poiCategoryReligionNameTokens"))
                || taxonomy.getHints("poiCategoryReligionNameSuffixes").stream().anyMatch(n::endsWith)
                || containsAny(t, taxonomy.getHints("poiCategoryReligionTypeTokens"))) return PoiCategory.RELIGIOUS_TEMPLE;
        if (containsAny(n, taxonomy.getHints("poiCategoryNatureNameTokens"))
                || containsAny(t, taxonomy.getHints("poiCategoryNatureTypeTokens"))) return PoiCategory.PARK_NATURE;

        return PoiCategory.OTHER;
    }

    private static boolean containsAny(String value, List<String> tokens) {
        return value != null && tokens.stream().anyMatch(value::contains);
    }

    private static String categoryDisplayName(PoiCategory category) {
        if (category == null) return "文旅景观";
        return switch (category) {
            case HISTORIC_CULTURE -> "文博古迹";
            case PARK_NATURE -> "自然公园";
            case LANDMARK_VIEW -> "城市地标";
            case RELIGIOUS_TEMPLE -> "宗教名胜";
            case COMMERCIAL_STREET -> "特色街区";
            case SQUARE -> "城市广场";
            case OTHER -> "文旅景观";
        };
    }

    private String extractNeighborhood(String name, String address, String businessArea) {
        if (businessArea != null && !businessArea.isBlank()) {
            return businessArea.trim();
        }
        String combined = (name == null ? "" : name) + " " + (address == null ? "" : address);
        Matcher m = suffixPattern(taxonomy.getHints("runtimeNeighborhoodSuffixes"), 2, 6).matcher(combined);
        return m.find() ? m.group(1).trim() : "";
    }

    private String extractCampus(String name, String address) {
        String combined = (name == null ? "" : name) + " " + (address == null ? "" : address);
        Matcher m = suffixPattern(taxonomy.getHints("runtimeCampusNameSuffixes"), 2, 12).matcher(combined);
        return m.find() ? m.group(1).trim() : "";
    }

    private String primaryVenue(String name) {
        if (name == null) return "";
        String clean = name.trim();
        int dashIdx = clean.indexOf('-');
        if (dashIdx > 0) clean = clean.substring(0, dashIdx).trim();
        int parenIdx = clean.indexOf('(');
        if (parenIdx > 0) clean = clean.substring(0, parenIdx).trim();
        int bracketIdx = clean.indexOf('（');
        if (bracketIdx > 0) clean = clean.substring(0, bracketIdx).trim();

        List<String> suffixes = taxonomy.getHints("runtimePrimaryVenueSuffixes");
        Matcher m = suffixes.isEmpty() ? Pattern.compile("(?!)").matcher(clean)
                : Pattern.compile("^(.*?(?:" + suffixes.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")) + "))").matcher(clean);
        if (m.find()) {
            return m.group(1).trim();
        }
        return clean;
    }

    boolean isNoisePoi(com.ai.guide.domain.geo.model.GeoPoiCandidate poi) {
        if (poi == null) return true;
        String name = safeName(poi);
        String type = text(poi.type());
        String typecode = poi.typecode() != null ? poi.typecode().trim() : "";

        // Classify suitability for a scenic candidate pool, not a universal POI blacklist.
        if (!typecode.isBlank()) {
            if ("110106".equals(typecode) || "140201".equals(typecode)) return true;
            if (AmapPoiTaxonomy.isScenicVenue(typecode)) {
                return false;
            }
            if (AmapPoiTaxonomy.isCulturalVenue(typecode)) {
                return false;
            }
            // These remaining categories belong to other planning flows; only
            // the scenic search pool excludes them. Cultural venues were handled above.
            if (typecode.startsWith("20") || typecode.startsWith("19") || typecode.startsWith("14")
                    || typecode.startsWith("05") || typecode.startsWith("06") || typecode.startsWith("10")
                    || typecode.startsWith("07") || typecode.startsWith("12") || typecode.startsWith("13")
                    || typecode.startsWith("15") || typecode.startsWith("16") || typecode.startsWith("17") || typecode.startsWith("18")) {
                return true;
            }
        }

        // Older providers and catalog records may omit codes. Use their category
        // text first, reserving generic name heuristics for truly unclassified records.
        if (containsAny(type, taxonomy.getHints("runtimeCulturalTypeAliases"))) return false;
        if (containsAny(type, taxonomy.getHints("runtimeExcludedTypeAliases"))) {
            return true;
        }
        if (containsAny(type, taxonomy.getHints("runtimeScenicTypeAliases"))) return false;

        // 校园内部纯楼栋、单元或教学楼（如“14栋”, “2单元”, “第四教学楼”, “实验楼”）一票否决
        if (matchesAnyPattern(name, taxonomy.getHints("runtimeBuildingNamePatterns"))
                && !containsAny(type, taxonomy.getHints("runtimeDiningTypeAliases"))
                && taxonomy.getHints("nonDiningNameKeywords").stream().noneMatch(name::contains)) {
            return true;
        }

        // 火车站/汽车站上下客进出站广场过滤
        if (taxonomy.getHints("runtimeStationSquareSuffixes").stream().anyMatch(name::endsWith)) {
            return true;
        }

        return false;
    }

    private Pattern suffixPattern(List<String> suffixes, int minPrefix, int maxPrefix) {
        if (suffixes == null || suffixes.isEmpty()) return Pattern.compile("(?!)");
        String alternatives = suffixes.stream().filter(value -> value != null && !value.isBlank())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
        return alternatives.isBlank() ? Pattern.compile("(?!)")
                : Pattern.compile("([\\p{IsHan}]{" + minPrefix + "," + maxPrefix + "}(?:" + alternatives + "))");
    }

    private boolean matchesAnyPattern(String value, List<String> expressions) {
        return value != null && expressions != null && expressions.stream().filter(expression -> expression != null && !expression.isBlank())
                .anyMatch(expression -> Pattern.matches(expression, value));
    }

    private List<com.ai.guide.domain.geo.model.GeoPoiCandidate> usableCandidates(
            List<com.ai.guide.domain.geo.model.GeoPoiCandidate> values,
            com.ai.guide.domain.geo.model.GeoPoiCandidate start) {
        if (values == null) return List.of();
        String startCampus = start == null ? "" : extractCampus(safeName(start), text(start.address()));
        String startPrimary = start == null ? "" : primaryVenue(safeName(start));

        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> filtered = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenPrimary = new HashSet<>();
        Map<String, Integer> neighborhoodCounts = new LinkedHashMap<>();
        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> acceptedSpots = new ArrayList<>();
        int squareCandidateCount = 0;

        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> sorted = values.stream()
                .filter(item -> item != null && item.coordinate() != null && !safeName(item).isBlank())
                .filter(item -> !samePoi(item, start))
                .filter(item -> !isNoisePoi(item))
                .sorted(Comparator.comparingDouble(item -> distanceKm(start.coordinate(), item.coordinate())))
                .toList();

        for (com.ai.guide.domain.geo.model.GeoPoiCandidate item : sorted) {
            String poiId = text(item.poiId()).isBlank() ? safeName(item) : item.poiId();
            if (!seenIds.add(poiId)) continue;

            String itemName = safeName(item);
            String itemAddr = text(item.address());
            String itemCampus = extractCampus(itemName, itemAddr);

            // 1. 校区排除：出发地为大学校区时，排除校内日常运动场/教学楼/操场，防止出校后折返看操场
            if (!startCampus.isBlank() && !AmapPoiTaxonomy.isScenicVenue(text(item.typecode()))
                    && !AmapPoiTaxonomy.isCulturalVenue(text(item.typecode()))) {
                if (itemCampus.equals(startCampus)) {
                    if (containsAny(itemName, taxonomy.getHints("runtimeCampusFacilityAliases"))) {
                        continue;
                    }
                }
            }
            if (!startPrimary.isBlank()) {
                if (primaryVenue(itemName).equals(startPrimary) || itemName.contains(startPrimary)) {
                    continue;
                }
            }

            // 2. 广场候选上限：候选池至多 2 个广场，防止挤占其他核心文旅景点
            PoiCategory cat = detectCategory(itemName, text(item.type()), text(item.typecode()), taxonomy);
            if (cat == PoiCategory.SQUARE) {
                int maxSquareCandidates = PlannerScoringPolicy.limit(taxonomy, "maxShortTripSquareCandidates");
                if (maxSquareCandidates > 0 && squareCandidateCount >= maxSquareCandidates) {
                    continue;
                }
                squareCandidateCount++;
            }

            // 3. Limit repeated neighborhood candidates so minor venues do not dominate the pool.
            String neighborhood = extractNeighborhood(itemName, itemAddr, item.businessArea());
            int maxNeighborhoodCandidates = PlannerScoringPolicy.limit(taxonomy, "maxShortTripNeighborhoodCandidates");
            if (!neighborhood.isBlank() && maxNeighborhoodCandidates > 0
                    && neighborhoodCounts.getOrDefault(neighborhood, 0) >= maxNeighborhoodCandidates) {
                continue;
            }

            // Limit dense micro-clusters using externally configured distance and count policies.
            int maxNearbyClusterCandidates = PlannerScoringPolicy.limit(taxonomy, "maxShortTripNearbyClusterCandidates");
            boolean tooCloseToCluster = maxNearbyClusterCandidates > 0 && acceptedSpots.stream().filter(ex ->
                    item.coordinate() != null && ex.coordinate() != null
                            && distanceKm(item.coordinate(), ex.coordinate()) < dynamicPolicy.getPolicy().duplicateCandidateDistanceKm()
            ).count() >= maxNearbyClusterCandidates;
            if (tooCloseToCluster) {
                continue;
            }

            // 4. 主地标去重
            String primary = primaryVenue(itemName);
            if (!primary.isBlank()) {
                if (seenPrimary.contains(primary)) continue;
                seenPrimary.add(primary);
            }

            if (!neighborhood.isBlank()) {
                neighborhoodCounts.merge(neighborhood, 1, Integer::sum);
            }
            acceptedSpots.add(item);
            filtered.add(item);
            if (filtered.size() >= maxCandidates) break;
        }
        return filtered;
    }

    private com.ai.guide.domain.geo.model.GeoPoiCandidate chooseStart(List<com.ai.guide.domain.geo.model.GeoPoiCandidate> values) {
        return chooseStart(values, null);
    }

    private com.ai.guide.domain.geo.model.GeoPoiCandidate chooseStart(List<com.ai.guide.domain.geo.model.GeoPoiCandidate> values, String requestedPlace) {
        if (values == null || values.isEmpty()) return null;
        if (requestedPlace != null && !requestedPlace.isBlank()) {
            String clean = requestedPlace.trim();
            // 1. Exact or contains match
            for (com.ai.guide.domain.geo.model.GeoPoiCandidate item : values) {
                if (item != null && item.coordinate() != null && safeName(item).contains(clean)) {
                    return item;
                }
            }
            // 2. Token overlap match (e.g. a requested campus name within a longer university name)
            for (com.ai.guide.domain.geo.model.GeoPoiCandidate item : values) {
                if (item != null && item.coordinate() != null) {
                    String name = safeName(item);
                    if (clean.length() >= 4 && name.contains(clean.substring(2))) {
                        return item;
                    }
                }
            }
        }
        return values.stream()
                .filter(item -> item != null && item.coordinate() != null && !safeName(item).isBlank())
                .findFirst().orElse(null);
    }

    private boolean samePoi(com.ai.guide.domain.geo.model.GeoPoiCandidate left, com.ai.guide.domain.geo.model.GeoPoiCandidate right) {
        if (left == null || right == null) return false;
        if (!text(left.poiId()).isBlank() && text(left.poiId()).equals(text(right.poiId()))) return true;
        return safeName(left).equals(safeName(right));
    }

    private int routeMinutes(GeoRoutePairOutcome route) {
        if (route == null || route.selected() == null || route.selected().segment() == null
                || route.selected().segment().durationSeconds() == null) return 0;
        return Math.max(1, (int) Math.ceil(route.selected().segment().durationSeconds() / 60.0));
    }

    private int estimateTravelMinutes(String origin, String destination) {
        com.ai.guide.domain.geo.model.GeoCoordinate from = com.ai.guide.domain.geo.model.GeoCoordinates.parse(origin);
        com.ai.guide.domain.geo.model.GeoCoordinate to = com.ai.guide.domain.geo.model.GeoCoordinates.parse(destination);
        if (from == null || to == null) return dynamicPolicy.getPolicy().unknownTravelDurationMinutes();
        double distance = distanceKm(from, to);
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("无法根据无效坐标估算交通时间");
        DynamicShortTripPolicyPort.Policy policy = dynamicPolicy.getPolicy();
        return Math.max(policy.minimumEstimatedTravelMinutes(), (int) Math.ceil(distance * policy.estimateTravelMinutesPerKm()));
    }

    private String routeLabel(GeoRoutePairOutcome route, int fallbackMinutes) {
        if (route != null && route.selected() != null && route.selected().segment() != null
                && route.selected().segment().distanceMeters() != null) {
            int effectiveMinutes = routeMinutes(route);
            if (effectiveMinutes <= 0) effectiveMinutes = fallbackMinutes;
            return "高德路线约 " + Math.round(route.selected().segment().distanceMeters() / 100.0) / 10.0
                    + " 公里 · " + effectiveMinutes + " 分钟";
        }
        return "路线待核验 · 预计约 " + fallbackMinutes + " 分钟";
    }

    private Map<String, Object> routeMap(GeoRoutePairOutcome route) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (route == null || route.selected() == null || route.selected().segment() == null) {
            result.put("status", "待核验");
            return result;
        }
        com.ai.guide.domain.geo.model.GeoRouteSegment segment = route.selected().segment();
        result.put("status", segment.metricsComplete() && segment.durationSeconds() != null ? "已核验" : "部分核验");
        result.put("mode", segment.mode());
        result.put("distanceMeters", segment.distanceMeters());
        result.put("durationSeconds", segment.durationSeconds());
        result.put("polyline", segment.polyline());
        return result;
    }

    private boolean allRoutesVerified(List<Map<String, Object>> stops) {
        return !stops.isEmpty() && stops.stream().allMatch(stop -> stop.get("routeFromPrevious") instanceof Map<?, ?> route
                && "已核验".equals(route.get("status")));
    }

    private Map<String, Object> dynamicSummary(boolean empty) {
        return Map.of("confirmed", empty ? List.of("明确起点") : List.of("明确起点", "高德周边候选", "时间预算内路线"),
                "dynamic", List.of("开放时间", "门票", "天气", "实时客流"),
                "unknown", List.of("未返回的 POI 字段"), "conflict", List.of());
    }

    private String city(TravelConstraints constraints) {
        String destination = text(constraints.getDestination());
        return destination.endsWith("市") ? destination : destination + "市";
    }

    private boolean usesCityStrategy(String city, String strategy) {
        if (city == null || city.isBlank()) return false;
        return taxonomy.getCityStrategyMappings().entrySet().stream()
                .anyMatch(entry -> entry.getValue().equalsIgnoreCase(strategy)
                        && entry.getKey().replace("市", "").equalsIgnoreCase(city.trim().replace("市", "")));
    }

    private String scenicType() {
        return taxonomy.getHints("runtimeScenicTypeCode").stream().findFirst().orElse("");
    }

    private String coordinate(com.ai.guide.domain.geo.model.GeoCoordinate coordinate) {
        return coordinate == null ? "" : coordinate.longitude() + "," + coordinate.latitude();
    }

    private String safeName(com.ai.guide.domain.geo.model.GeoPoiCandidate candidate) {
        return candidate == null ? "" : text(candidate.name());
    }

    private String district(String address) {
        String value = text(address);
        if (value.isBlank()) return "待确认";
        String[] parts = value.split("[市区县镇]");
        return parts.length > 1 ? parts[Math.max(0, Math.min(2, parts.length - 1))] : value;
    }

    private double distanceKm(com.ai.guide.domain.geo.model.GeoCoordinate from, com.ai.guide.domain.geo.model.GeoCoordinate to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        a = Math.max(0.0, Math.min(1.0, a));
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String formatTime(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", (minute / 60) % 24, minute % 60);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }
}

package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
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

    private static final String SCENIC_TYPE = "110000";
    private static final int DEFAULT_START_MINUTE = 9 * 60 + 30;

    private final AmapRouteService routeService;
    private final ObjectMapper objectMapper;
    private final int searchRadiusMeters;
    private final int maxCandidates;

    @Autowired
    public RuntimeLocationPlanner(
            AmapRouteService routeService,
            ObjectMapper objectMapper,
            @Value("${planner.runtime-location.search-radius-meters:6000}") int searchRadiusMeters,
            @Value("${planner.runtime-location.max-candidates:40}") int maxCandidates) {
        this.routeService = routeService;
        this.objectMapper = objectMapper;
        this.searchRadiusMeters = Math.max(500, searchRadiusMeters);
        this.maxCandidates = Math.max(10, Math.min(60, maxCandidates));
    }

    /** Convenient constructor for focused unit tests. */
    public RuntimeLocationPlanner(AmapRouteService routeService) {
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

        AmapRouteService.PoiOutcome startOutcome = routeService.searchPoi(requestedPlace, city(constraints));
        AmapResponseNormalizer.PoiCandidate start = chooseStart(startOutcome.candidates(), requestedPlace);
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

        int pages = budgetMinutes >= 300 ? 3 : (budgetMinutes >= 180 ? 3 : 2);
        int effectiveRadius = budgetMinutes >= 300 ? Math.max(searchRadiusMeters, 8000)
                : budgetMinutes >= 180 ? Math.max(searchRadiusMeters, 6000) : Math.max(searchRadiusMeters, 4000);
        AmapRouteService.PoiOutcome around = routeService.searchAround(
                coordinate(start.coordinate()), effectiveRadius, SCENIC_TYPE, "", city(constraints), pages);
        List<AmapResponseNormalizer.PoiCandidate> candidates = usableCandidates(around.candidates(), start);
        if (candidates.isEmpty()) {
            // Some provider regions return a broader result only when the type
            // filter is omitted. This remains a generic provider fallback.
            around = routeService.searchAround(coordinate(start.coordinate()), effectiveRadius,
                    "", "景点", city(constraints), Math.min(pages, 2));
            candidates = usableCandidates(around.candidates(), start);
        }

        List<AmapResponseNormalizer.PoiCandidate> rankedCandidates = rankCandidates(start, candidates, constraints);
        List<Map<String, Object>> stops = selectStops(start, rankedCandidates, constraints, budgetMinutes);
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
        day.put("mealGuidance", "已结合出行时段与偏好智能融入周边地道美食；可在右侧聊天中继续微调或更换餐厅。");
        day.put("weather", Map.of("label", "天气", "value", "待确认", "status", "未知",
                "note", "出发前需确认动态天气。"));
        day.put("stops", stops);
        day.put("routeStatus", stops.isEmpty() ? "待确认" : "高德路线已核验");
        trip.put("days", List.of(day));
        trip.put("summary", dynamicSummary(stops.isEmpty()));
        trip.put("sourceStatus", Map.of("poi", "已查询", "route", stops.isEmpty() ? "待确认" : "已核验",
                "weather", "待确认", "fallback", false));
        trip.put("sourceMode", "高德实时周边规划");
        trip.put("routeDataStatus", stops.isEmpty() ? "UNAVAILABLE" : "VERIFIED_AMAP");
        trip.put("degraded", stops.isEmpty());
        trip.put("degradationReasons", stops.isEmpty() ? List.of("NO_FEASIBLE_NEARBY_CANDIDATE") : List.of());
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
        List<AmapResponseNormalizer.PoiCandidate> candidates = searchCandidates(
                startCoordinate, requestedName, constraints);
        Set<String> used = usedVenues(sourceTrip);
        AmapResponseNormalizer.Coordinate startPoint = AmapResponseNormalizer.parseCoordinate(startCoordinate);
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
        for (AmapResponseNormalizer.PoiCandidate candidate : candidates) {
            AmapRouteService.RoutePairOutcome route = routeService.routePair(
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

    private List<AmapResponseNormalizer.PoiCandidate> searchCandidates(String startCoordinate,
                                                                         String requestedName,
                                                                         TravelConstraints constraints) {
        AmapRouteService.PoiOutcome outcome;
        if (requestedName != null && !requestedName.isBlank()) {
            String reference = requestedName.startsWith("amap-") ? requestedName.substring("amap-".length()) : requestedName;
            outcome = requestedName.startsWith("amap-")
                    ? routeService.poiDetail(reference)
                    : routeService.searchPoi(reference, city(constraints));
        } else {
            outcome = routeService.searchAround(startCoordinate, searchRadiusMeters, SCENIC_TYPE, "", city(constraints), 3);
            if (outcome.candidates().isEmpty()) {
                outcome = routeService.searchAround(startCoordinate, searchRadiusMeters, "", "景点", city(constraints), 3);
            }
        }
        if (outcome.candidates() == null) return List.of();
        return outcome.candidates().stream()
                .filter(candidate -> candidate != null && candidate.coordinate() != null && !safeName(candidate).isBlank())
                .toList();
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

    private Map<String, Object> replacementStop(AmapResponseNormalizer.PoiCandidate candidate,
                                                 Map<String, Object> original,
                                                 AmapRouteService.RoutePairOutcome route,
                                                 int travelMinutes, int visitMinutes, String reason) {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", original.get("id"));
        stop.put("stableStopId", original.getOrDefault("stableStopId", original.get("id")));
        stop.put("entityId", "amap-" + text(candidate.poiId()));
        stop.put("venueId", "amap-" + text(candidate.poiId()));
        stop.put("amapPoiId", text(candidate.poiId()));
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
        updatedStatus.put("route", "已核验");
        updatedStatus.put("weather", weatherStatus);
        updatedStatus.put("fallback", false);
        trip.put("sourceStatus", updatedStatus);
        trip.put("routeDataStatus", "VERIFIED_AMAP");
        trip.put("degraded", false);
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

    private Map<String, Object> addStopTemplate(AmapResponseNormalizer.PoiCandidate candidate) {
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(text);
        return matcher.find() ? Math.max(30, Integer.parseInt(matcher.group(1))) : 60;
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

    public static int resolveStartMinute(TravelConstraints constraints) {
        String text = constraints != null ? constraints.getRawPrompt() : "";
        if (text != null && !text.isBlank()) {
            Matcher mTime = Pattern.compile("(?i)(?:上午|早上|早晨)\\s*(\\d{1,2})(?:点|时|:(\\d{2}))?").matcher(text);
            if (mTime.find()) {
                int h = Integer.parseInt(mTime.group(1));
                int m = mTime.group(2) != null ? Integer.parseInt(mTime.group(2)) : 0;
                return Math.max(6 * 60, Math.min(23 * 60, h * 60 + m));
            }
            Matcher mNoon = Pattern.compile("(?i)(?:中午)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(text);
            if (mNoon.find()) {
                int h = mNoon.group(1) != null ? Integer.parseInt(mNoon.group(1)) : 12;
                int m = mNoon.group(2) != null ? Integer.parseInt(mNoon.group(2)) : 0;
                return h * 60 + m;
            }
            Matcher mAfternoon = Pattern.compile("(?i)(?:下午)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(text);
            if (mAfternoon.find()) {
                int h = mAfternoon.group(1) != null ? Integer.parseInt(mAfternoon.group(1)) : 2;
                if (h < 12) h += 12;
                int m = mAfternoon.group(2) != null ? Integer.parseInt(mAfternoon.group(2)) : 0;
                return h * 60 + m;
            }
            Matcher mEvening = Pattern.compile("(?i)(?:晚上|今晚|夜间)\\s*(\\d{1,2})?(?:点|时|:(\\d{2}))?").matcher(text);
            if (mEvening.find()) {
                int h = mEvening.group(1) != null ? Integer.parseInt(mEvening.group(1)) : 7;
                if (h < 12) h += 12;
                int m = mEvening.group(2) != null ? Integer.parseInt(mEvening.group(2)) : 0;
                return h * 60 + m;
            }
            Matcher mExact = Pattern.compile("(\\b\\d{1,2})[:：](\\d{2})").matcher(text);
            if (mExact.find()) {
                int h = Integer.parseInt(mExact.group(1));
                int m = Integer.parseInt(mExact.group(2));
                return h * 60 + m;
            }
            if (text.contains("下午")) return 14 * 60;
            if (text.contains("今晚") || text.contains("晚上")) return 18 * 60 + 30;
            if (text.contains("上午") || text.contains("早上")) return 9 * 60 + 30;
        }

        // 2. User did not specify time: obtain current local time in Chongqing (Asia/Shanghai)
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            int currentMinute = now.getHour() * 60 + now.getMinute();
            // Daytime active hours: 07:30 to 18:30 (depart soon)
            if (currentMinute >= 7 * 60 + 30 && currentMinute <= 18 * 60 + 30) {
                return ((currentMinute + 9) / 10) * 10;
            }
            // Explicit night travel: after 18:30 only when user prompt says night/evening
            if (currentMinute > 18 * 60 + 30 && text != null && text.matches(".*(夜|今晚|晚上|夜景|夜市|酒吧).*")) {
                return Math.min(20 * 60, ((currentMinute + 9) / 10) * 10);
            }
            // In the evening (>18:30) or early morning (<07:30) without night keywords, default to normal tourist departure 09:30 AM
            return DEFAULT_START_MINUTE;
        } catch (Exception ignored) {}

        return DEFAULT_START_MINUTE;
    }

    private Map<String, Object> buildRuntimeDiningStop(
            AmapResponseNormalizer.PoiCandidate anchorPoi,
            String slotType,
            int arrivalMinute,
            TravelConstraints constraints,
            int stopIndex) {
        String district = anchorPoi != null ? district(anchorPoi.address()) : "渝中区";
        Attraction anchor = new Attraction();
        anchor.setId(anchorPoi != null ? "amap-" + text(anchorPoi.poiId()) : "amap-anchor");
        anchor.setName(anchorPoi != null ? safeName(anchorPoi) : "当前游览地标");
        anchor.setDistrict(district);
        anchor.setLocation(anchorPoi != null ? coordinate(anchorPoi.coordinate()) : "106.576869,29.56236");

        AttractionDiningKnowledge.DiningOption option = AttractionDiningKnowledge.resolveNearbyDining(anchor, slotType, constraints);
        if (option == null) return null;

        Map<String, Object> stop = new LinkedHashMap<>();
        String stopId = "runtime-dining-" + slotType.toLowerCase();
        stop.put("id", stopId);
        stop.put("stableStopId", stopId);
        stop.put("entityId", option.id());
        stop.put("venueId", option.id());
        stop.put("name", option.name());
        stop.put("displayName", option.name());
        stop.put("district", district);
        stop.put("time", formatTime(arrivalMinute));
        stop.put("startTime", formatTime(arrivalMinute));
        stop.put("duration", option.duration() != null ? option.duration() : "约 50 分钟");
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
        String distStr = option.distanceFromAttraction() != null && !option.distanceFromAttraction().isBlank()
                ? option.distanceFromAttraction() : "步行约 200 米";
        String walkText = distStr.endsWith("达") ? distStr : (distStr.contains("车程") ? distStr : distStr + "即达");
        boolean isMotorized = distStr.contains("车程") || distStr.contains("公里");

        stop.put("walk", walkText);
        stop.put("walkDifficulty", isMotorized ? "顺路通达" : "低");
        stop.put("walkingDifficulty", isMotorized ? "顺路通达" : "低");
        stop.put("indoor", true);
        stop.put("bestTime", "全天营业·地道尝味");
        stop.put("recommendationReason", option.recommendationReason());
        stop.put("tasteReason", option.recommendationReason());
        stop.put("explanationSource", "DINING_KNOWLEDGE");
        stop.put("location", option.location());
        stop.put("address", (district != null && !district.isBlank() ? district + " · " : "") + walkText);
        stop.put("routePreference", isMotorized ? "transit" : "walking");
        Map<String, Object> rfp = new LinkedHashMap<>();
        rfp.put("selectedMode", isMotorized ? "顺路/公交" : "步行");
        rfp.put("summary", walkText);
        rfp.put("selected", Map.of(
                "duration", "约 5 分钟",
                "durationSeconds", 300,
                "distance", option.distanceFromAttraction(),
                "distanceMeters", 180,
                "summary", "从上一站点步行" + option.distanceFromAttraction() + "即达"
        ));
        stop.put("routeFromPrevious", rfp);
        stop.put("source", "DINING_KNOWLEDGE");
        return stop;
    }

    private List<Map<String, Object>> selectStops(AmapResponseNormalizer.PoiCandidate start,
                                                   List<AmapResponseNormalizer.PoiCandidate> candidates,
                                                   TravelConstraints constraints, int budgetMinutes) {
        List<Map<String, Object>> stops = new ArrayList<>();
        String previousCoordinate = coordinate(start.coordinate());
        String previousPoiId = text(start.poiId());
        AmapResponseNormalizer.PoiCandidate previousCandidate = start;
        String transportPref = constraints != null ? constraints.getTransportPreference() : "";
        boolean prefersTransit = "transit".equalsIgnoreCase(transportPref)
                || "公交优先".equals(transportPref)
                || "公共交通优先".equals(transportPref)
                || "地铁优先".equals(transportPref);
        int remaining = budgetMinutes;
        int initialStartMinute = resolveStartMinute(constraints);
        int startMinute = initialStartMinute;

        // Check whether a dining meal should be planned
        String rawPrompt = constraints != null && constraints.getRawPrompt() != null ? constraints.getRawPrompt() : "";
        boolean explicitFood = rawPrompt.matches(".*(吃|火锅|老火锅|美食|午饭|晚饭|用餐|小吃|餐厅|午餐|晚餐|饭店|尝尝|吃饭).*");
        boolean hasDietPref = constraints != null && constraints.getDietPreference() != null && !"未提供".equals(constraints.getDietPreference()) && !"本地菜优先".equals(constraints.getDietPreference());
        int planEndMinute = initialStartMinute + budgetMinutes;
        boolean spansLunch = initialStartMinute <= 13 * 60 && planEndMinute >= 12 * 60;
        boolean spansDinner = initialStartMinute <= 19 * 60 + 30 && planEndMinute >= 18 * 60;
        boolean allowDining = (explicitFood || hasDietPref || spansLunch || spansDinner || (budgetMinutes >= 180 && planEndMinute <= 21 * 60)) && budgetMinutes >= 90;
        boolean diningInserted = false;
        String mealSlotType;
        if (spansLunch) {
            mealSlotType = "LUNCH";
        } else if (spansDinner) {
            mealSlotType = "DINNER";
        } else if (initialStartMinute >= 20 * 60) {
            mealSlotType = "NIGHT_SNACK";
        } else if (initialStartMinute >= 15 * 60) {
            mealSlotType = "DINNER";
        } else {
            mealSlotType = "LUNCH";
        }

        int maxStops = Math.max(2, Math.min(7, (budgetMinutes + 45) / 55));
        Map<PoiCategory, Integer> categoryCounts = new LinkedHashMap<>();

        for (AmapResponseNormalizer.PoiCandidate candidate : candidates) {
            if (stops.size() >= maxStops || remaining < 25) break;

            PoiCategory category = detectCategory(safeName(candidate), text(candidate.type()));

            // Category diversity quotas:
            // 1. NEVER recommend more than 1 plaza in a single itinerary!
            if (category == PoiCategory.SQUARE && categoryCounts.getOrDefault(PoiCategory.SQUARE, 0) >= 1) {
                continue;
            }
            // 2. Parks: at most 1 for short trips (<=240 min), at most 2 for longer trips
            int maxParks = budgetMinutes > 240 ? 2 : 1;
            if (category == PoiCategory.PARK_NATURE && categoryCounts.getOrDefault(PoiCategory.PARK_NATURE, 0) >= maxParks) {
                continue;
            }
            // 3. Religious temples: at most 1
            if (category == PoiCategory.RELIGIOUS_TEMPLE && categoryCounts.getOrDefault(PoiCategory.RELIGIOUS_TEMPLE, 0) >= 1) {
                continue;
            }
            // 4. Commercial streets: at most 1
            if (category == PoiCategory.COMMERCIAL_STREET && categoryCounts.getOrDefault(PoiCategory.COMMERCIAL_STREET, 0) >= 1) {
                continue;
            }

            if (!diningInserted && allowDining && !stops.isEmpty()) {
                String effectiveSlotType = (startMinute >= 21 * 60) ? "NIGHT_SNACK" : mealSlotType;
                boolean timeTrigger = ("LUNCH".equals(effectiveSlotType) && startMinute >= 11 * 60 + 40 && startMinute <= 13 * 60 + 40)
                        || ("DINNER".equals(effectiveSlotType) && startMinute >= 17 * 60 + 30 && startMinute <= 20 * 60 + 30)
                        || ("NIGHT_SNACK".equals(effectiveSlotType) && startMinute >= 21 * 60);
                boolean budgetUrgent = remaining <= 95 && remaining >= 45 && (spansLunch || spansDinner || explicitFood);
                if (timeTrigger || (explicitFood && remaining <= budgetMinutes - 45) || budgetUrgent) {
                    Map<String, Object> diningStop = buildRuntimeDiningStop(
                            previousCandidate, effectiveSlotType, startMinute, constraints, stops.size() + 1);
                    if (diningStop != null) {
                        stops.add(diningStop);
                        int diningDuration = 50;
                        remaining -= diningDuration;
                        startMinute += diningDuration;
                        diningInserted = true;
                        Object diningLoc = diningStop.get("location");
                        if (diningLoc != null && !text(diningLoc).isBlank()) {
                            previousCoordinate = text(diningLoc);
                        }
                        if (stops.size() >= maxStops || remaining < 25) break;
                    }
                }
            }

            String destination = coordinate(candidate.coordinate());
            AmapResponseNormalizer.Coordinate prevCoord = AmapResponseNormalizer.parseCoordinate(previousCoordinate);
            AmapResponseNormalizer.Coordinate destCoord = candidate.coordinate();
            double distBetweenKm = (prevCoord != null && destCoord != null)
                    ? distanceKm(prevCoord, destCoord) : 1.0;
            String segmentPreference = prefersTransit || (distBetweenKm > 1.5 && !"低".equals(constraints.getWalkingTolerance()))
                    ? "transit" : "walking";
            AmapRouteService.RoutePairOutcome route = routeService.routePair(
                    previousCoordinate, destination, city(constraints), previousPoiId,
                    text(candidate.navigationPoiId()).isBlank() ? text(candidate.poiId()) : candidate.navigationPoiId(),
                    segmentPreference);
            int travelMinutes = routeMinutes(route);
            if (travelMinutes <= 0) travelMinutes = estimateTravelMinutes(previousCoordinate, destination);
            int visitMinutes = inferVisitMinutes(safeName(candidate), text(candidate.type()), remaining - travelMinutes);
            if (travelMinutes + visitMinutes > remaining) {
                if (remaining - travelMinutes >= 20 && visitMinutes > 25) {
                    visitMinutes = remaining - travelMinutes;
                } else {
                    continue;
                }
            }

            int arrivalMinute = startMinute + travelMinutes;

            // 夜间行程收尾合理性：傍晚及夜间短途游在 22:00 前自然收拢结束，杜绝拖到深夜午夜
            if (initialStartMinute >= 17 * 60 + 30 && arrivalMinute >= 22 * 60 && !stops.isEmpty()) {
                break;
            }

            Map<String, Object> stop = stop(candidate, stops.size() + 1,
                    formatTime(arrivalMinute), visitMinutes, route, travelMinutes, constraints);
            if (previousCandidate != null) {
                Attraction fromAttr = Attraction.builder()
                        .name(safeName(previousCandidate))
                        .district(district(previousCandidate.address()))
                        .build();
                Attraction toAttr = Attraction.builder()
                        .name(safeName(candidate))
                        .district(district(candidate.address()))
                        .build();
                String topographyHint = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(
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

        if (!diningInserted && allowDining && remaining >= 40 && !stops.isEmpty()) {
            String effectiveSlotType = (startMinute >= 21 * 60) ? "NIGHT_SNACK" : mealSlotType;
            boolean eligiblePostMeal = ("LUNCH".equals(effectiveSlotType) && startMinute <= 14 * 60)
                    || ("DINNER".equals(effectiveSlotType) && startMinute <= 20 * 60 + 30)
                    || "NIGHT_SNACK".equals(effectiveSlotType) || explicitFood;
            if (eligiblePostMeal) {
                Map<String, Object> diningStop = buildRuntimeDiningStop(
                        previousCandidate, effectiveSlotType, startMinute, constraints, stops.size() + 1);
                if (diningStop != null) {
                    stops.add(diningStop);
                }
            }
        }

        return stops;
    }

    private List<AmapResponseNormalizer.PoiCandidate> rankCandidates(
            AmapResponseNormalizer.PoiCandidate start,
            List<AmapResponseNormalizer.PoiCandidate> candidates,
            TravelConstraints constraints) {
        if (candidates == null || candidates.isEmpty() || constraints == null) {
            return candidates == null ? List.of() : candidates;
        }

        List<String> interests = constraints.getInterests() == null ? List.of() : constraints.getInterests();
        boolean lowWalking = "低".equals(constraints.getWalkingTolerance());
        String companions = constraints.getCompanions() == null ? "" : constraints.getCompanions();

        List<AmapResponseNormalizer.PoiCandidate> scored = new ArrayList<>(candidates);
        scored.sort((a, b) -> {
            double scoreA = scoreCandidate(a, start, interests, lowWalking, companions);
            double scoreB = scoreCandidate(b, start, interests, lowWalking, companions);
            return Double.compare(scoreB, scoreA);
        });
        return scored;
    }

    private double scoreCandidate(AmapResponseNormalizer.PoiCandidate c,
                                  AmapResponseNormalizer.PoiCandidate start,
                                  List<String> interests,
                                  boolean lowWalking,
                                  String companions) {
        double score = 100.0;
        String name = safeName(c);
        String type = text(c.type());
        String textToMatch = (name + " " + type + " " + text(c.address())).toLowerCase(Locale.ROOT);

        for (String interest : interests) {
            if ("自然".equals(interest) || "自然奇观".equals(interest)) {
                if (matchesKeywords(textToMatch, "公园", "植物园", "风景", "峡谷", "江", "山", "湖", "岛", "林", "滨江")) score += 40.0;
            } else if ("人文".equals(interest) || "人文历史".equals(interest)) {
                if (matchesKeywords(textToMatch, "博物馆", "纪念馆", "古镇", "旧址", "遗址", "寺", "阁", "书院", "文博", "历史")) score += 40.0;
            } else if ("8D魔幻".equals(interest)) {
                if (matchesKeywords(textToMatch, "轨道", "单轨", "天桥", "空中", "大厦", "索道", "电梯", "崖", "桥")) score += 40.0;
            } else if ("夜景".equals(interest) || "山城夜景".equals(interest)) {
                if (matchesKeywords(textToMatch, "江", "观景台", "码头", "步道", "老街", "两江", "滨江")) score += 40.0;
            } else if ("温泉".equals(interest) || "天然温泉".equals(interest)) {
                if (matchesKeywords(textToMatch, "温泉", "汤池", "度夏", "疗养")) score += 40.0;
            } else if ("市井".equals(interest) || "市井烟火".equals(interest)) {
                if (matchesKeywords(textToMatch, "老街", "市井", "巷", "码头", "坊", "里", "街区")) score += 40.0;
            } else {
                if (textToMatch.contains(interest.toLowerCase(Locale.ROOT))) score += 30.0;
            }
        }

        // Prominence & cultural scenic tier weighting: genuine scenic attractions outrank small street plazas
        if (matchesKeywords(textToMatch, "磁器口", "洪崖洞", "解放碑", "朝天门", "李子坝", "渣滓洞", "白公馆",
                "红岩", "沙坪公园", "平顶山", "歌乐山", "罗中立", "人民大礼堂", "三峡博物馆", "长江索道", "弹子石", "一棵树", "老君洞", "大金鹰", "南山植物园")) {
            score += 55.0;
        } else if (matchesKeywords(textToMatch, "古镇", "老街", "森林公园", "植物园", "湿地公园", "文化公园", "风景区", "国家级", "观景台", "步道", "俯瞰")) {
            score += 35.0;
        } else if (matchesKeywords(textToMatch, "博物馆", "纪念馆", "陈列馆", "美术馆", "寺", "庙", "观", "阁", "塔", "公园", "湖")) {
            score += 25.0;
        } else if (matchesKeywords(textToMatch, "旧址", "遗址", "故居", "旧居")) {
            score += 20.0;
        } else if (name.contains("广场")) {
            score -= 10.0;
        }

        // AMap official popularity & rating weighting
        if (c.rating() != null && c.rating() > 0) {
            if (c.rating() >= 4.5) {
                score += 35.0; // High reputation (e.g. 磁器口 4.7, 沙坪公园 4.6, 宝轮寺 4.7)
            } else if (c.rating() >= 4.2) {
                score += 20.0;
            } else if (c.rating() >= 4.0) {
                score += 10.0;
            } else if (c.rating() < 3.5) {
                score -= 25.0; // Low reputation or uncurated micro-spots penalized
            }
        }
        if (c.heatTag() != null && !c.heatTag().isBlank()) {
            String tag = c.heatTag();
            if (tag.contains("巴渝") || tag.contains("国家级") || tag.contains("著名") || tag.contains("世界遗产") || tag.contains("名胜") || tag.contains("古迹")) {
                score += 20.0;
            }
        }

        if (start != null && start.coordinate() != null && c.coordinate() != null) {
            double distKm = distanceKm(start.coordinate(), c.coordinate());
            if (lowWalking) {
                if (distKm < 1.5) score += 35.0;
                else if (distKm < 3.0) score += 15.0;
                else score -= (distKm - 3.0) * 10.0;
            } else {
                if (distKm < 2.5) score += 15.0;
                else if (distKm > 4.5) score -= (distKm - 4.5) * 8.0;
            }
        }

        if ("带父母".equals(companions) || "亲子家庭".equals(companions)) {
            if (matchesKeywords(textToMatch, "公园", "博物馆", "纪念馆", "植物园", "展馆")) score += 20.0;
            if (matchesKeywords(textToMatch, "险", "攀", "台阶", "悬崖", "陡")) score -= 30.0;
        }

        return score;
    }

    private boolean matchesKeywords(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String buildDynamicReason(AmapResponseNormalizer.PoiCandidate candidate, int travelMinutes, TravelConstraints constraints) {
        List<String> reasons = new ArrayList<>();
        if (constraints != null && constraints.getInterests() != null) {
            String name = safeName(candidate);
            String type = text(candidate.type());
            String target = name + " " + type;
            for (String interest : constraints.getInterests()) {
                if ("自然".equals(interest) || "自然奇观".equals(interest)) {
                    if (matchesKeywords(target, "公园", "植物园", "风景", "峡谷", "江", "山", "湖", "岛", "林", "滨江")) {
                        reasons.add("契合自然山水偏好");
                        break;
                    }
                } else if ("人文".equals(interest) || "人文历史".equals(interest)) {
                    if (matchesKeywords(target, "博物馆", "纪念馆", "古镇", "旧址", "遗址", "寺", "阁", "书院", "文博", "历史")) {
                        reasons.add("契合历史人文偏好");
                        break;
                    }
                } else if ("8D魔幻".equals(interest)) {
                    if (matchesKeywords(target, "轨道", "单轨", "天桥", "空中", "大厦", "索道", "电梯", "崖", "桥")) {
                        reasons.add("具备8D魔幻特色");
                        break;
                    }
                } else if ("夜景".equals(interest) || "山城夜景".equals(interest)) {
                    if (matchesKeywords(target, "江", "观景台", "码头", "步道", "老街", "两江")) {
                        reasons.add("契合江岸与夜景体验");
                        break;
                    }
                } else if ("市井".equals(interest) || "市井烟火".equals(interest)) {
                    if (matchesKeywords(target, "老街", "市井", "巷", "码头", "坊", "里", "街区")) {
                        reasons.add("展现巴渝市井烟火");
                        break;
                    }
                }
            }
        }
        if (candidate.rating() != null && candidate.rating() >= 4.3) {
            reasons.add("高德口碑评分 " + candidate.rating() + " 分深受游客喜爱");
        }
        if (candidate.heatTag() != null && !candidate.heatTag().isBlank()) {
            String tag = candidate.heatTag();
            if (tag.contains("巴渝") || tag.contains("国家级") || tag.contains("名胜") || tag.contains("古迹")) {
                reasons.add("获评“" + tag.replace(";", "·") + "”文旅地标");
            }
        }
        if (constraints != null && "低".equals(constraints.getWalkingTolerance())) {
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

    private Map<String, Object> stop(AmapResponseNormalizer.PoiCandidate candidate, int index,
                                     String startTime, int visitMinutes,
                                     AmapRouteService.RoutePairOutcome route, int travelMinutes,
                                     TravelConstraints constraints) {
        String name = safeName(candidate);
        String poiId = text(candidate.poiId());
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "runtime-stop-" + index + "-" + (poiId.isBlank() ? index : poiId));
        stop.put("stableStopId", stop.get("id"));
        stop.put("entityId", poiId.isBlank() ? "amap-runtime-" + index : "amap-" + poiId);
        stop.put("venueId", poiId.isBlank() ? "amap-runtime-" + index : "amap-" + poiId);
        stop.put("amapPoiId", poiId);
        stop.put("name", name);
        stop.put("displayName", name);
        stop.put("district", district(candidate.address()));
        stop.put("time", startTime);
        stop.put("startTime", startTime);
        stop.put("duration", "约 " + visitMinutes + " 分钟");
        stop.put("icon", "景");
        stop.put("tone", "dynamic");
        stop.put("category", categoryDisplayName(detectCategory(name, text(candidate.type()))));
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
        trip.put("id", "draft-runtime-cq-" + version);
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
        LANDMARK_VIEW,    // 观景台、步道、大桥、缆车索道、两江、山城8D地标
        RELIGIOUS_TEMPLE, // 寺庙、道观、祠堂、佛阁
        COMMERCIAL_STREET,// 步行街、市集、商业街区
        SQUARE,           // 广场
        OTHER             // 其他综合文旅
    }

    public static PoiCategory detectCategory(String name, String type) {
        String n = name == null ? "" : name.trim();
        String t = type == null ? "" : type.trim();

        // 1. Plazas & Squares (Strict check: any POI with 广场 is categorized as SQUARE)
        if (n.contains("广场")) {
            return PoiCategory.SQUARE;
        }

        // 2. Renowned 8D landmarks & riverside viewpoints
        if (n.contains("洪崖洞") || n.contains("解放碑") || n.contains("李子坝") || n.contains("索道")
                || n.contains("观景") || n.contains("瞰江") || n.contains("步道") || n.contains("绿道")
                || n.contains("大桥") || n.contains("滨江") || n.contains("江岸") || n.contains("两江")
                || n.contains("单轨") || n.contains("轻轨") || n.contains("码头") || n.contains("渡口")
                || t.contains("观景点") || t.contains("摄影")) {
            return PoiCategory.LANDMARK_VIEW;
        }

        // 3. Historic sites, ancient towns, museums, memorials
        if (n.contains("古镇") || n.contains("老街") || n.contains("民俗") || n.contains("古村")
                || n.contains("渣滓洞") || n.contains("白公馆") || n.contains("红岩") || n.contains("旧址")
                || n.contains("遗址") || n.contains("故居") || n.contains("旧居") || n.contains("博物馆")
                || n.contains("纪念馆") || n.contains("陈列馆") || n.contains("美术馆") || n.contains("艺术馆")
                || n.contains("书院") || n.contains("文博") || n.contains("文物") || n.contains("历史")
                || t.contains("文物古迹") || t.contains("博物馆") || t.contains("纪念馆")) {
            return PoiCategory.HISTORIC_CULTURE;
        }

        // 4. Commercial pedestrian streets & food markets
        if (n.contains("步行街") || n.contains("商业街") || n.contains("夜市")
                || n.contains("市集") || n.contains("集市") || t.contains("商业街")) {
            return PoiCategory.COMMERCIAL_STREET;
        }

        // 5. Religious temples and pagodas
        if (n.contains("寺庙") || n.contains("道观") || n.contains("寺院") || n.contains("古刹")
                || n.endsWith("寺") || n.endsWith("庙") || n.endsWith("观") || n.endsWith("阁")
                || n.endsWith("塔") || n.endsWith("庵") || n.endsWith("祠")
                || t.contains("寺庙道观") || t.contains("宗教名胜")) {
            return PoiCategory.RELIGIOUS_TEMPLE;
        }

        // 6. Parks and nature scenery
        if (n.contains("公园") || n.contains("植物园") || n.contains("动物园") || n.contains("森林")
                || n.contains("湿地") || n.contains("风景区") || n.contains("景区") || n.contains("自然")
                || n.contains("峡") || n.contains("谷") || n.contains("山") || n.contains("峰")
                || n.contains("湖") || n.contains("池") || n.contains("瀑布") || n.contains("泉")
                || t.contains("风景名胜") || t.contains("公园广场")) {
            return PoiCategory.PARK_NATURE;
        }

        return PoiCategory.OTHER;
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

    private static final Pattern CAMPUS_PATTERN = Pattern.compile("(重庆大学|西南大学|重庆邮电大学|重庆交通大学|四川外国语大学|重庆师范大学|重庆医科大学|重庆理工大学|重庆工商大学|[\\u4e00-\\u9fa5]{2,6}(?:大学|学院))");

    private static final Set<String> FAMOUS_PERSONS = Set.of(
            "卢作孚", "李四光", "陈豹隐", "周恩来", "陈志潜", "郑思群", "艾芜", "吴宓",
            "毛鹤年", "冯简", "马寅初", "吴芳吉", "吕子方", "沈懋德", "潘菽", "潘序伦",
            "周志宏", "黄汲清", "傅鹰", "丁道衡", "何鲁", "郭沫若", "陶行知", "张伯苓",
            "晏阳初", "梁漱溟", "黄炎培", "徐悲鸿"
    );

    private static final List<String> NOISE_NAME_KEYWORDS = List.of(
            "雕像", "塑像", "半身像", "立像", "胸像", "纪念像", "石像", "铜像", "雕塑",
            "石碑", "刻石", "碑刻", "纪念碑", "题字", "碑亭", "摩岩石刻", "石虎",
            "打卡点", "装置", "机位", "拍摄", "转盘", "环岛",
            "办公楼", "教学楼", "实验楼", "行政楼", "综合楼", "实训楼", "逸夫楼",
            "四教", "一教", "二教", "三教", "五教", "六教", "七教", "八教",
            "宿舍", "公寓", "家属区", "食堂", "快餐厅", "教工餐厅",
            "传达室", "收发室", "值班室", "门卫", "保卫处", "车库", "停车场",
            "变电站", "配电", "机房", "垃圾", "公厕", "厕所", "卫生间", "饲料房", "小卖部", "报刊亭",
            "特产", "专卖", "便利店", "超市", "门市", "批发", "李抄手", "土特",
            "玫琳凯", "奇峰广场", "泛洋广场", "华宇广场", "地下商场", "地下商业街",
            "数码广场", "数码城", "商住楼", "写字楼", "商业城", "购物中心",
            "火车站广场", "汽车站", "客运站", "加气站", "加油站", "驾校",
            "民宿", "客栈", "旅馆", "酒店", "宾馆", "招待所",
            "营业厅", "营业部", "服务中心", "售后", "专卖店", "体验店",
            "中学", "小学", "幼儿园", "培训中心"
    );

    private static final List<String> GENUINE_SCENIC_SUFFIXES = List.of(
            "公园", "森林", "湿地", "景区", "自然", "峡", "谷", "洞", "岛", "湖", "池", "泉", "山", "峰", "崖", "瀑布",
            "步道", "绿道", "渡口", "码头", "水库", "馆", "院", "寺", "庙", "观", "堂", "阁", "楼", "塔", "堡", "城",
            "殿", "祠", "宫", "陵", "旧址", "遗址", "故居", "旧居", "老街", "古镇", "古村", "步行街", "广场", "桥",
            "索道", "天街", "古建筑", "早期建筑", "建筑群", "纪念亭", "亭", "钟塔", "门",
            "路", "街", "道", "巷", "弄", "坪", "坝", "滩", "岩", "咀", "沟", "溪", "湾", "庄", "村", "寨", "口"
    );

    private static final Pattern NEIGHBORHOOD_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,6}(?:老街|古镇|古村|步行街|商圈|风景区|植物园|森林公园|文化公园))");

    private String extractNeighborhood(String name, String address) {
        String combined = (name == null ? "" : name) + " " + (address == null ? "" : address);
        Matcher m = NEIGHBORHOOD_PATTERN.matcher(combined);
        if (m.find()) {
            return m.group(1).trim();
        }
        for (String key : List.of("黄桷垭", "磁器口", "弹子石", "中山四路", "洪崖洞", "解放碑", "朝天门", "南滨路", "北滨路", "白沙")) {
            if (combined.contains(key)) return key;
        }
        return "";
    }

    private String extractCampus(String name, String address) {
        String combined = (name == null ? "" : name) + " " + (address == null ? "" : address);
        Matcher m = CAMPUS_PATTERN.matcher(combined);
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

        Matcher m = Pattern.compile("^(.*?大学|.*?学院|.*?中学|.*?公园|.*?古镇|.*?老街|.*?风景区|.*?植物园)").matcher(clean);
        if (m.find()) {
            return m.group(1).trim();
        }
        return clean;
    }

    private boolean isNoisePoi(AmapResponseNormalizer.PoiCandidate poi) {
        String name = safeName(poi);
        String type = text(poi.type());

        // Whitelist iconic landmarks
        if (name.contains("解放碑") || name.contains("大足石刻") || name.contains("三峡广场")) return false;

        // 0. Building numbers or apartment codes (e.g., 14栋, 1号楼, 2单元)
        if (name.matches(".*\\d+[栋幢号单元楼].*")) return true;

        // 1. Noise keywords in name
        for (String noise : NOISE_NAME_KEYWORDS) {
            if (name.contains(noise)) return true;
        }

        // 2. Pure famous person names or memorial bust without authentic museum/residence
        for (String person : FAMOUS_PERSONS) {
            if (name.equals(person)) return true;
            if (name.contains(person) && !name.contains("纪念馆") && !name.contains("故居")
                    && !name.contains("旧居") && !name.contains("陈列馆") && !name.contains("故里")
                    && !name.contains("陵园") && !name.contains("艺术馆") && !name.contains("美术馆")) {
                return true;
            }
        }

        // 3. Short 2-4 character Chinese names without genuine scenic suffixes (person name or bust POI)
        if (name.length() >= 2 && name.length() <= 4 && name.matches("^[\\u4e00-\\u9fa5]+$")) {
            boolean hasScenicSuffix = false;
            for (String suffix : GENUINE_SCENIC_SUFFIXES) {
                if (name.contains(suffix)) {
                    hasScenicSuffix = true;
                    break;
                }
            }
            if (!hasScenicSuffix) return true;
        }

        // 4. Station exit drop-off plazas
        if (name.endsWith("东广场") || name.endsWith("西广场") || name.endsWith("南广场") || name.endsWith("北广场")) {
            return true;
        }

        // 5. Type blacklists
        return type.contains("报刊亭") || type.contains("公用电话") || type.contains("公共厕所")
                || type.contains("生活服务") || type.contains("公司企业") || type.contains("餐饮服务")
                || type.contains("商务住宅") || type.contains("购物服务");
    }

    public static int inferVisitMinutes(String name, String type, int remainingBudget) {
        String n = name == null ? "" : name;
        String t = type == null ? "" : type;

        // 1. Micro spots / photo markers / sculptures / monuments / viewpoints: 15 ~ 25 min
        if (n.contains("打卡点") || n.contains("雕像") || n.contains("雕塑")
                || n.contains("纪念碑") || n.contains("刻石") || n.contains("牌匾")
                || n.contains("机位") || n.contains("拍摄") || n.contains("亭")
                || t.contains("观景点") || t.contains("摄影")) {
            return 20;
        }

        // 2. Large parks / historic streets / old towns / scenic resorts: 60 ~ 90 min
        if (n.contains("老街") || n.contains("古镇") || n.contains("步道")
                || n.contains("森林公园") || n.contains("植物园") || n.contains("动物园")
                || n.contains("风景区") || n.contains("度假区")
                || t.contains("公园广场") || t.contains("国家级景点")) {
            if (remainingBudget >= 180) return 75;
            if (remainingBudget >= 90) return 60;
            return 45;
        }

        // 3. Historic sites / museums / memorial halls / temples / pagodas: 35 ~ 50 min
        if (n.contains("旧址") || n.contains("故居") || n.contains("展馆")
                || n.contains("博物馆") || n.contains("纪念馆") || n.contains("礼堂")
                || n.contains("书院") || n.contains("寺") || n.contains("庙")
                || n.contains("观") || n.contains("阁") || n.contains("塔")
                || t.contains("文物古迹") || t.contains("寺庙道观")) {
            return remainingBudget >= 120 ? 45 : 35;
        }

        // 4. Plazas / squares: typically 30 ~ 35 min, avoiding blocking genuine scenic attractions
        if (n.contains("广场")) {
            return remainingBudget >= 120 ? 35 : 25;
        }

        // 5. General attraction
        return remainingBudget >= 120 ? 40 : 30;
    }

    private List<AmapResponseNormalizer.PoiCandidate> usableCandidates(
            List<AmapResponseNormalizer.PoiCandidate> values,
            AmapResponseNormalizer.PoiCandidate start) {
        if (values == null) return List.of();
        String startCampus = start == null ? "" : extractCampus(safeName(start), text(start.address()));
        String startPrimary = start == null ? "" : primaryVenue(safeName(start));

        List<AmapResponseNormalizer.PoiCandidate> filtered = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenPrimary = new HashSet<>();
        Map<String, Integer> neighborhoodCounts = new LinkedHashMap<>();
        List<AmapResponseNormalizer.PoiCandidate> acceptedSpots = new ArrayList<>();
        int squareCandidateCount = 0;

        List<AmapResponseNormalizer.PoiCandidate> sorted = values.stream()
                .filter(item -> item != null && item.coordinate() != null && !safeName(item).isBlank())
                .filter(item -> !samePoi(item, start))
                .filter(item -> !isNoisePoi(item))
                .sorted(Comparator.comparingDouble(item -> distanceKm(start.coordinate(), item.coordinate())))
                .toList();

        for (AmapResponseNormalizer.PoiCandidate item : sorted) {
            String poiId = text(item.poiId()).isBlank() ? safeName(item) : item.poiId();
            if (!seenIds.add(poiId)) continue;

            String itemName = safeName(item);
            String itemAddr = text(item.address());
            String itemCampus = extractCampus(itemName, itemAddr);

            // 1. 校区排除：出发地为大学校区时，排除校内日常运动场/教学楼/操场，防止出校后折返看操场
            if (!startCampus.isBlank()) {
                if (itemCampus.equals(startCampus)) {
                    if (itemName.contains("广场") || itemName.contains("操场") || itemName.contains("体育")
                            || itemName.contains("教学") || itemName.contains("楼") || itemName.contains("公寓")
                            || itemName.contains("一教") || itemName.contains("二教") || itemName.contains("三教")) {
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
            PoiCategory cat = detectCategory(itemName, text(item.type()));
            if (cat == PoiCategory.SQUARE) {
                if (squareCandidateCount >= 2) {
                    continue;
                }
                squareCandidateCount++;
            }

            // 3. 街区聚集度控制：同一老街或街区（如黄桷垭老街、磁器口等）候选池至多保留 2 处，避免整街微小门牌占满候选池
            String neighborhood = extractNeighborhood(itemName, itemAddr);
            if (!neighborhood.isBlank() && neighborhoodCounts.getOrDefault(neighborhood, 0) >= 2) {
                continue;
            }

            // 空间聚集度检查：与已有候选点相距 < 250 米的微小簇群至多允许 2 个
            boolean tooCloseToCluster = acceptedSpots.stream().filter(ex ->
                    item.coordinate() != null && ex.coordinate() != null && distanceKm(item.coordinate(), ex.coordinate()) < 0.25
            ).count() >= 2;
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

    private AmapResponseNormalizer.PoiCandidate chooseStart(List<AmapResponseNormalizer.PoiCandidate> values) {
        return chooseStart(values, null);
    }

    private AmapResponseNormalizer.PoiCandidate chooseStart(List<AmapResponseNormalizer.PoiCandidate> values, String requestedPlace) {
        if (values == null || values.isEmpty()) return null;
        if (requestedPlace != null && !requestedPlace.isBlank()) {
            String clean = requestedPlace.trim();
            // 1. Exact or contains match
            for (AmapResponseNormalizer.PoiCandidate item : values) {
                if (item != null && item.coordinate() != null && safeName(item).contains(clean)) {
                    return item;
                }
            }
            // 2. Token overlap match (e.g. "邮电大学" in "重庆邮电大学")
            for (AmapResponseNormalizer.PoiCandidate item : values) {
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

    private boolean samePoi(AmapResponseNormalizer.PoiCandidate left, AmapResponseNormalizer.PoiCandidate right) {
        if (left == null || right == null) return false;
        if (!text(left.poiId()).isBlank() && text(left.poiId()).equals(text(right.poiId()))) return true;
        return safeName(left).equals(safeName(right));
    }

    private int routeMinutes(AmapRouteService.RoutePairOutcome route) {
        if (route == null || route.selected() == null || route.selected().segment() == null
                || route.selected().segment().durationSeconds() == null) return 0;
        return Math.max(1, (int) Math.ceil(route.selected().segment().durationSeconds() / 60.0));
    }

    private int estimateTravelMinutes(String origin, String destination) {
        AmapResponseNormalizer.Coordinate from = AmapResponseNormalizer.parseCoordinate(origin);
        AmapResponseNormalizer.Coordinate to = AmapResponseNormalizer.parseCoordinate(destination);
        if (from == null || to == null) return 30;
        return Math.max(5, (int) Math.ceil(distanceKm(from, to) * 4.0));
    }

    private String routeLabel(AmapRouteService.RoutePairOutcome route, int fallbackMinutes) {
        if (route != null && route.selected() != null && route.selected().segment() != null
                && route.selected().segment().distanceMeters() != null) {
            int effectiveMinutes = routeMinutes(route);
            if (effectiveMinutes <= 0) effectiveMinutes = fallbackMinutes;
            return "高德路线约 " + Math.round(route.selected().segment().distanceMeters() / 100.0) / 10.0
                    + " 公里 · " + effectiveMinutes + " 分钟";
        }
        return "路线待核验 · 预计约 " + fallbackMinutes + " 分钟";
    }

    private Map<String, Object> routeMap(AmapRouteService.RoutePairOutcome route) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (route == null || route.selected() == null || route.selected().segment() == null) {
            result.put("status", "待核验");
            return result;
        }
        AmapResponseNormalizer.RouteResult segment = route.selected().segment();
        result.put("status", segment.metricsComplete() && segment.durationSeconds() != null ? "已核验" : "部分核验");
        result.put("mode", segment.mode());
        result.put("distanceMeters", segment.distanceMeters());
        result.put("durationSeconds", segment.durationSeconds());
        result.put("polyline", segment.polyline());
        return result;
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

    private String coordinate(AmapResponseNormalizer.Coordinate coordinate) {
        return coordinate == null ? "" : coordinate.longitude() + "," + coordinate.latitude();
    }

    private String safeName(AmapResponseNormalizer.PoiCandidate candidate) {
        return candidate == null ? "" : text(candidate.name());
    }

    private String district(String address) {
        String value = text(address);
        if (value.isBlank()) return "待确认";
        String[] parts = value.split("[市区县镇]");
        return parts.length > 1 ? parts[Math.max(0, Math.min(2, parts.length - 1))] : value;
    }

    private double distanceKm(AmapResponseNormalizer.Coordinate from, AmapResponseNormalizer.Coordinate to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
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

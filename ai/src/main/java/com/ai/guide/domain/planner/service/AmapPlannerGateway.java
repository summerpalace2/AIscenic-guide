package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.rag.pipeline.AmapWebServiceClient;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 高德地图路径规划网关实现
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：调用高德 Web Service 路线计算 API，支持多出行方式路线下发，内建二级缓存以避免重复耗时调用。
 */
@Service
public class AmapPlannerGateway implements RouteGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(AmapPlannerGateway.class);

    private final AmapRouteService routeService;
    private final AttractionService attractionService;
    private final int maxPoiKeywords;
    private final Executor hydrationExecutor;

    @Autowired
    public AmapPlannerGateway(AmapRouteService routeService,
                              AttractionService attractionService,
                              @Value("${amap.web-service.poi-max-keywords:3}") int maxPoiKeywords,
                              @Qualifier("amapHydrationExecutor") Executor hydrationExecutor) {
        this.routeService = routeService;
        this.attractionService = attractionService;
        this.maxPoiKeywords = Math.max(1, maxPoiKeywords);
        this.hydrationExecutor = hydrationExecutor;
    }

    /** Compatibility constructor for focused tests and older callers. */
    public AmapPlannerGateway(AmapRouteService routeService,
                              AttractionService attractionService,
                              int maxPoiKeywords) {
        this(routeService, attractionService, maxPoiKeywords, Runnable::run);
    }

    /** Compatibility constructor for focused tests and callers passing ItineraryBuilderPort. */
    public AmapPlannerGateway(AmapRouteService routeService, ItineraryBuilderPort itineraryBuilder, int maxPoiKeywords) {
        this(routeService, new AttractionService(null, null) {
            @Override
            public Attraction get(String id) {
                return itineraryBuilder == null ? null : itineraryBuilder.attraction(id);
            }
        }, maxPoiKeywords, Runnable::run);
    }

    /** Compatibility constructor allowing tests to assert the batch executor boundary. */
    AmapPlannerGateway(AmapRouteService routeService, ItineraryBuilderPort itineraryBuilder,
                       int maxPoiKeywords, Executor hydrationExecutor) {
        this(routeService, new AttractionService(null, null) {
            @Override
            public Attraction get(String id) {
                return itineraryBuilder == null ? null : itineraryBuilder.attraction(id);
            }
        }, maxPoiKeywords, hydrationExecutor);
    }

    /** Compatibility constructor for focused tests and older callers. */
    public AmapPlannerGateway(AmapRouteService routeService, ItineraryBuilderPort itineraryBuilder) {
        this(routeService, itineraryBuilder, 3);
    }

    /** Compatibility constructor for focused tests and older callers. */
    public AmapPlannerGateway(AmapWebServiceClient client, ItineraryBuilderPort itineraryBuilder) {
        this(new AmapRouteService(client), itineraryBuilder, 3);
    }

    /**
     * Hydrates a generated plan when a runtime key exists. A missing key or a
     * provider failure never removes the static catalog projection.
     */
    public HydrationResult hydrate(Map<String, Object> trip) {
        if (trip == null) return new HydrationResult("演示回退模式", 0, 0, 0, true);
        if (!routeService.isConfigured()) {
            refreshStatus(trip, 0, 0, 0);
            return new HydrationResult("演示回退模式", 0, 0, 0, true);
        }

        int poiSuccess = hydratePois(trip);
        int weatherSuccess = hydrateWeather(trip);
        int routeSuccess = hydrateRoutes(trip);
        refreshStatus(trip, poiSuccess, routeSuccess, weatherSuccess);
        Map<String, Object> status = mutableMap(trip.get("sourceStatus"));
        boolean fallback = Boolean.TRUE.equals(status.get("fallback"));
        boolean hasDynamicResult = poiSuccess > 0 || routeSuccess > 0 || weatherSuccess > 0;
        String mode = !fallback ? "高德实时接口"
                : hasDynamicResult ? "高德接口部分可用" : "演示回退模式";
        trip.put("sourceMode", mode);
        return new HydrationResult(mode, poiSuccess, routeSuccess, weatherSuccess, fallback);
    }

    private int hydratePois(Map<String, Object> trip) {
        int successes = 0;
        for (Map<String, Object> stop : allStops(trip)) {
            Attraction attraction = attractionService.get(text(stop.get("venueId")));
            if (attraction == null || attraction.getAmapQuery() == null) continue;
            List<String> rawKeywords = new ArrayList<>();
            addStrings(rawKeywords, attraction.getAmapQuery().get("keywords"));
            addStrings(rawKeywords, attraction.getAmapQuery().get("alternatives"));
            List<String> keywords = normalizeKeywords(rawKeywords);
            AmapResponseNormalizer.PoiCandidate selected = null;
            String lastReason = "";
            for (String keyword : keywords) {
                AmapRouteService.PoiOutcome outcome = routeService.searchPoi(keyword, "重庆市");
                lastReason = outcome.reason();
                selected = select(outcome.candidates(), attraction);
                if (selected != null) break;
            }
            if (selected == null) {
                if (!lastReason.isBlank()) stop.put("amapPoiStatus", "未解析：" + lastReason);
                continue;
            }
            applyPoi(stop, selected);
            successes++;
        }
        return successes;
    }

    private int hydrateWeather(Map<String, Object> trip) {
        List<Map<String, Object>> days = dayMaps(trip);
        boolean hasMatchableDate = days.stream().anyMatch(day -> isoDate(day.get("date")) != null);
        AmapRouteService.WeatherOutcome outcome = hasMatchableDate
                ? routeService.weather("500000")
                : new AmapRouteService.WeatherOutcome(AmapRouteService.OutcomeStatus.UNAVAILABLE,
                        null, "行程没有可匹配的 ISO 日期，跳过高德天气查询。", Instant.now());
        String providerReason = outcome.reason() == null || outcome.reason().isBlank()
                ? "高德天气接口未返回可用结果。" : outcome.reason();
        List<AmapResponseNormalizer.WeatherCast> casts = outcome.weather() == null
                ? List.of() : outcome.weather().casts();

        int resolved = 0;
        for (Map<String, Object> day : days) {
            // Always replace the previous value: copied/replanned trips must not
            // keep yesterday's weather after a later provider failure.
            String date = isoDate(day.get("date"));
            AmapResponseNormalizer.WeatherCast cast = casts.stream()
                    .filter(item -> item != null && date != null && date.equals(item.date()))
                    .findFirst().orElse(null);
            if (outcome.status() == AmapRouteService.OutcomeStatus.SUCCESS && cast != null) {
                String value = weatherText(cast);
                if (!value.isBlank()) {
                    day.put("weather", fact("天气", value, "动态",
                            "高德天气查询时间：" + text(outcome.weather().reportTime())
                                    + "；预报日期：" + date,
                            List.of(citation("高德天气接口", "/v3/weather/weatherInfo", "已核验"))));
                    resolved++;
                    continue;
                }
            }
            String reason = date == null
                    ? "行程日期不是 ISO 日期，无法安全匹配高德预报。"
                    : outcome.status() != AmapRouteService.OutcomeStatus.SUCCESS
                        ? providerReason
                        : casts.isEmpty() ? "高德没有返回预报列表。"
                        : "高德预报范围内没有日期 " + date + "。";
            day.put("weather", fact("天气", "不可用·待确认", "未知", reason,
                    List.of(citation("高德天气接口", "/v3/weather/weatherInfo", "未返回"))));
        }
        log.info("[AMAP_WEATHER_REFRESH] days={}, status={}, casts={}, resolved={}, reason={}",
                days.size(), outcome.status(), casts.size(), resolved,
                outcome.status() == AmapRouteService.OutcomeStatus.SUCCESS ? "" : providerReason);
        return resolved;
    }

    private String isoDate(Object value) {
        String candidate = text(value);
        if (candidate.matches("20\\d{2}-\\d{2}-\\d{2}")) return candidate;
        return null;
    }

    private record RouteTask(Map<String, Object> previous, Map<String, Object> current,
                             String origin, String destination, String preference,
                             String originPoi, String destinationPoi) {}

    private int hydrateRoutes(Map<String, Object> trip) {
        List<RouteTask> tasks = new ArrayList<>();
        for (Map<String, Object> day : dayMaps(trip)) {
            List<Map<String, Object>> stops = stops(day);
            for (int index = 1; index < stops.size(); index++) {
                Map<String, Object> previous = stops.get(index - 1);
                Map<String, Object> current = stops.get(index);
                tasks.add(new RouteTask(
                        previous, current,
                        text(previous.get("location")), text(current.get("location")),
                        text(current.get("routePreference")),
                        text(previous.get("amapPoiId")), text(current.get("amapPoiId"))
                ));
            }
        }

        List<java.util.concurrent.CompletableFuture<AmapRouteService.RoutePairOutcome>> futures = tasks.stream()
                .map(t -> java.util.concurrent.CompletableFuture.supplyAsync(() -> routeService.routePair(
                        t.origin(), t.destination(), "重庆市", t.originPoi(), t.destinationPoi(), t.preference()
                ), hydrationExecutor))
                .toList();

        int successes = 0;
        for (int i = 0; i < tasks.size(); i++) {
            RouteTask t = tasks.get(i);
            AmapRouteService.RoutePairOutcome pair;
            try {
                pair = futures.get(i).join();
            } catch (Exception e) {
                pair = new AmapRouteService.RoutePairOutcome(null, null, null, t.preference(), false);
            }
            Map<String, Object> walking = routeMap(pair.walking());
            Map<String, Object> transit = routeMap(pair.transit());
            Map<String, Object> selected = routeMap(pair.selected());
            boolean selectedSuccess = isSuccessful(pair.selected());
            String selectedMode = selectedSuccess && "TRANSIT".equals(pair.selected().segment().mode())
                    ? "公共交通" : selectedSuccess ? "步行" : "未返回";
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("from", text(t.previous().get("name")));
            route.put("to", text(t.current().get("name")));
            route.put("walking", walking);
            route.put("transit", transit);
            route.put("selected", selected);
            route.put("selectedMode", selectedMode);
            route.put("routeDataStatus", selectedSuccess ? "VERIFIED_AMAP" : "ESTIMATED");
            route.put("preference", t.preference().isBlank() ? "未指定" : t.preference());
            route.put("fallback", !selectedSuccess);
            route.put("taxiUnsupported", pair.taxiUnsupported());
            t.current().put("routeFromPrevious", route);
            Map<String, Object> context = mutableMap(t.current().get("mapContext"));
            context.put("coordinates", coordinates(t.current().get("location")));
            context.put("polyline", polyline(pair.selected()));
            context.put("routeFromPrevious", route);
            t.current().put("mapContext", context);
            if (selectedSuccess) {
                t.current().put("walk", selectedSummary(pair.selected()));
                t.current().put("walkingInfo", Map.of("summary", selectedSummary(pair.selected()),
                        "status", "动态", "preference", t.preference()));
                replaceFact(t.current(), "路线核验", selectedSummary(pair.selected()), "动态",
                        "路线来自本次高德路径查询。", List.of(
                                citation("高德步行路线接口", "/v5/direction/walking", "已核验"),
                                citation("高德公交路线接口", "/v5/direction/transit/integrated", "已核验")));
                successes++;
            } else {
                t.current().put("walkingInfo", Map.of("summary", text(t.current().get("walk")),
                        "status", "未知", "preference", t.preference()));
                String reason = text(pair.selected() == null ? null : pair.selected().reason());
                if (pair.taxiUnsupported()) reason = "暂未实现高德驾车/出租车路线";
                replaceFact(t.current(), "路线核验", "未知·暂未返回", "未知",
                        reason.isBlank() ? "当前路线接口没有返回可用路径。" : reason, List.of(
                                citation("高德路径规划接口", "/v5/direction/walking", "待查询")));
            }
        }
        return successes;
    }

    private void applyPoi(Map<String, Object> stop, AmapResponseNormalizer.PoiCandidate poi) {
        String liveName = text(poi.name());
        String displayName = text(stop.get("displayName"));
        stop.put("livePoiName", liveName);
        stop.put("name", displayName.isBlank() ? liveName : displayName);
        stop.put("address", text(poi.address()));
        stop.put("amapPoiId", text(poi.poiId()));
        stop.put("image", text(poi.photoUrl()));
        stop.put("imageSource", "高德 Web Service API");
        stop.put("imageStatus", text(poi.photoUrl()).isBlank() ? "无图片" : "已返回");
        stop.put("imageReason", text(poi.photoUrl()).isBlank()
                ? "高德本次没有返回可用 POI 图片。" : "图片来自本次高德 POI 查询。");
        if (poi.coordinate() != null) {
            String location = poi.coordinate().longitude() + "," + poi.coordinate().latitude();
            stop.put("location", location);
            Map<String, Object> context = mutableMap(stop.get("mapContext"));
            context.put("coordinates", List.of(poi.coordinate().longitude(), poi.coordinate().latitude()));
            stop.put("mapContext", context);
        }
        replaceFact(stop, "地址", text(poi.address()).isBlank() ? "未知·需确认" : text(poi.address()),
                text(poi.address()).isBlank() ? "未知" : "动态",
                text(poi.address()).isBlank() ? "高德本次没有返回地址。" : "地址来自本次高德 POI 查询。",
                List.of(citation("高德 POI 搜索接口", "/v5/place/text", "已核验")));
        replaceFact(stop, "到达方式", text(stop.get("walk")), "未知",
                "路线仍需由高德路径查询或出发前确认。",
                List.of(citation("高德路径规划接口", "/v5/direction/walking", "待查询")));
        stop.put("citations", List.of(
                citation("高德 POI 搜索接口", "/v5/place/text", "已核验"),
                citation("高德 POI 详情接口", "/v5/place/detail", "待查询")));
    }

    private AmapResponseNormalizer.PoiCandidate select(
            List<AmapResponseNormalizer.PoiCandidate> candidates, Attraction attraction) {
        List<String> matches = new ArrayList<>();
        if (attraction.getAmapQuery() != null) {
            addStrings(matches, attraction.getAmapQuery().get("matches"));
            addStrings(matches, attraction.getAmapQuery().get("keywords"));
            addStrings(matches, attraction.getAmapQuery().get("alternatives"));
        }
        return candidates.stream()
                .filter(candidate -> candidate != null && !text(candidate.poiId()).isBlank()
                        && candidate.coordinate() != null && !text(candidate.name()).isBlank())
                .map(candidate -> new ScoredPoi(candidate, poiScore(candidate, matches)))
                .filter(item -> item.score() >= 20)
                .max(Comparator.comparingInt(ScoredPoi::score)
                        .thenComparing(item -> text(item.candidate().poiId())))
                .map(ScoredPoi::candidate)
                .orElse(null);
    }

    private int poiScore(AmapResponseNormalizer.PoiCandidate candidate, List<String> matches) {
        String name = text(candidate.name()).toLowerCase();
        int score = 0;
        for (String match : matches) {
            String expected = text(match).toLowerCase();
            if (!expected.isBlank() && name.equals(expected)) score = Math.max(score, 100);
            else if (!expected.isBlank() && (name.contains(expected) || expected.contains(name))) score = Math.max(score, 60);
        }
        if (candidate.coordinate() != null) score += 10;
        if (!text(candidate.poiId()).isBlank()) score += 10;
        return score;
    }

    private void refreshStatus(Map<String, Object> trip, int poiSuccess, int routeSuccess, int weatherSuccess) {
        List<Map<String, Object>> stops = allStops(trip);
        int routePairs = dayMaps(trip).stream().mapToInt(day -> Math.max(0, stops(day).size() - 1)).sum();
        int poiTotal = stops.size();
        int routeResolved = (int) stops.stream().filter(stop -> {
            Object route = stop.get("routeFromPrevious");
            return route instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("fallback"));
        }).count();
        int weatherAvailable = weatherSuccess;
        int weatherTotal = dayMaps(trip).size();
        int available = poiSuccess + weatherAvailable + routeResolved;
        int expected = poiTotal + weatherTotal + routePairs;
        List<Map<?, ?>> facts = stops.stream().flatMap(stop -> factMaps(stop).stream()).toList();
        long unknownFacts = facts.stream().filter(fact -> "未知".equals(fact.get("status"))).count();
        long dynamicFacts = facts.stream().filter(fact -> "动态".equals(fact.get("status"))).count();
        long citedFacts = facts.stream().filter(fact -> fact.get("citations") instanceof List<?> list && !list.isEmpty()).count();
        double poiCoverage = poiTotal == 0 ? 1 : (double) poiSuccess / poiTotal;
        double routeCoverage = routePairs == 0 ? 1 : (double) routeResolved / routePairs;
        double weatherCoverage = weatherTotal == 0 ? 1 : round((double) weatherAvailable / weatherTotal);
        double dataCoverage = round((poiCoverage + routeCoverage + weatherCoverage) / 3);
        Map<String, Object> status = mutableMap(trip.get("sourceStatus"));
        status.put("provider", "高德 Web Service API");
        status.put("keyConfigured", routeService.isConfigured());
        status.put("poiResolved", poiSuccess);
        status.put("poiTotal", poiTotal);
        status.put("weatherAvailable", weatherAvailable > 0);
        status.put("weatherDaysResolved", weatherAvailable);
        status.put("weatherDaysTotal", weatherTotal);
        status.put("routeResolved", routeResolved);
        status.put("routePairs", routePairs);
        status.put("citationCount", listSize(trip.get("citations")));
        status.put("citedFactCount", citedFacts);
        status.put("totalFactCount", facts.size());
        status.put("citationCoverage", facts.isEmpty() ? 0 : round((double) citedFacts / facts.size()));
        status.put("poiCoverage", round(poiCoverage));
        status.put("routeCoverage", round(routeCoverage));
        status.put("weatherCoverage", weatherCoverage);
        status.put("dataCoverage", dataCoverage);
        status.put("unknownFactCount", unknownFacts);
        status.put("dynamicFactCount", dynamicFacts);
        status.put("fallback", available < expected || routeResolved < routePairs);
        trip.put("sourceStatus", status);
        trip.put("sourceMode", available == expected ? "高德实时接口" : available > 0 ? "高德接口部分可用" : "演示回退模式");
        Map<String, Object> quality = mutableMap(trip.get("qualityMetrics"));
        quality.put("citationCoverage", status.get("citationCoverage"));
        quality.put("dataCoverage", Map.of("overall", dataCoverage, "poi", round(poiCoverage),
                "routes", round(routeCoverage), "weather", weatherCoverage));
        quality.put("unknownFactCount", unknownFacts);
        quality.put("dynamicFactCount", dynamicFacts);
        quality.put("totalFactCount", facts.size());
        trip.put("qualityMetrics", quality);
        Map<String, Object> retrieval = mutableMap(trip.get("retrieval"));
        retrieval.put("mode", "AMap Web Service");
        retrieval.put("status", available > 0 ? "动态查询完成" : "未返回动态结果");
        retrieval.put("provider", "高德开放平台");
        trip.put("retrieval", retrieval);
    }

    private boolean isSuccessful(AmapRouteService.RouteOutcome outcome) {
        return outcome != null && outcome.status() == AmapRouteService.OutcomeStatus.SUCCESS
                && outcome.segment() != null && outcome.segment().metricsComplete();
    }

    private Map<String, Object> routeMap(AmapRouteService.RouteOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (outcome == null) {
            result.put("status", "未返回");
            result.put("fallback", true);
            return result;
        }
        result.put("status", isSuccessful(outcome) ? "动态" : "未返回");
        result.put("source", isSuccessful(outcome) ? "高德接口" : "演示回退");
        result.put("routeDataStatus", isSuccessful(outcome) ? "VERIFIED_AMAP" : "ESTIMATED");
        result.put("endpoint", outcome.endpoint());
        result.put("reason", outcome.reason());
        result.put("fallback", !isSuccessful(outcome));
        if (outcome.segment() != null) {
            AmapResponseNormalizer.RouteResult segment = outcome.segment();
            result.put("distance", segment.distanceMeters());
            result.put("duration", segment.durationSeconds());
            result.put("distanceMeters", segment.distanceMeters());
            result.put("durationSeconds", segment.durationSeconds());
            result.put("walkingDistance", segment.walkingDistanceMeters());
            result.put("walkingDistanceMeters", segment.walkingDistanceMeters());
            result.put("polyline", splitPolyline(segment.polyline()));
        } else {
            result.put("polyline", List.of());
        }
        return result;
    }

    private String selectedSummary(AmapRouteService.RouteOutcome outcome) {
        if (!isSuccessful(outcome)) return "动态·暂未查询";
        AmapResponseNormalizer.RouteResult segment = outcome.segment();
        int distance = segment.distanceMeters() == null ? 0 : segment.distanceMeters();
        int duration = segment.durationSeconds() == null ? 0 : segment.durationSeconds();
        String distanceText = distance >= 1000 ? String.format("%.1f 公里", distance / 1000.0) : distance + " 米";
        return distanceText + " · " + Math.max(1, Math.round(duration / 60.0f)) + " 分钟";
    }

    private List<String> polyline(AmapRouteService.RouteOutcome outcome) {
        return outcome == null || outcome.segment() == null ? List.of() : splitPolyline(outcome.segment().polyline());
    }

    private List<String> splitPolyline(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(";"));
    }

    private String weatherText(AmapResponseNormalizer.WeatherCast cast) {
        String weather = joinNonBlank(cast.dayWeather(), cast.nightWeather(), "转");
        String temperature = joinNonBlank(cast.dayTemperature(), cast.nightTemperature(), "°/");
        return joinNonBlank(weather, temperature.isBlank() ? "" : temperature + "°C", " · ");
    }

    private String joinNonBlank(String first, String second, String separator) {
        if (text(first).isBlank()) return text(second);
        if (text(second).isBlank()) return text(first);
        return text(first) + separator + text(second);
    }

    private List<Map<String, Object>> allStops(Map<String, Object> trip) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> day : dayMaps(trip)) result.addAll(stops(day));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dayMaps(Map<String, Object> trip) {
        Object value = trip.get("days");
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) {
            // Preserve the original map so hydration changes reach the trip;
            // do not allocate a discarded copy for every day.
            result.add((Map<String, Object>) raw);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stops(Map<String, Object> day) {
        Object value = day.get("stops");
        if (!(value instanceof List<?> list)) return List.of();
        return (List<Map<String, Object>>) (List<?>) list;
    }

    private List<Map<?, ?>> factMaps(Map<String, Object> stop) {
        Object value = stop.get("facts");
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(map);
        return result;
    }

    private Map<String, Object> mutableMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private List<Double> coordinates(Object value) {
        AmapResponseNormalizer.Coordinate coordinate = AmapResponseNormalizer.parseCoordinate(text(value));
        return coordinate == null ? null : List.of(coordinate.longitude(), coordinate.latitude());
    }

    private void replaceFact(Map<String, Object> stop, String label, Object value, String status,
                             String note, List<Map<String, Object>> citations) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<?, ?> existing : factMaps(stop)) if (!label.equals(existing.get("label"))) {
            Map<String, Object> copy = new LinkedHashMap<>();
            existing.forEach((key, item) -> copy.put(String.valueOf(key), item));
            facts.add(copy);
        }
        facts.add(fact(label, value, status, note, citations));
        stop.put("facts", facts);
    }

    private Map<String, Object> fact(String label, Object value, String status, String note,
                                     List<Map<String, Object>> citations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("value", value);
        result.put("status", status);
        result.put("note", note);
        result.put("citations", citations);
        return result;
    }

    private Map<String, Object> citation(String title, String endpoint, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("publisher", "高德开放平台");
        result.put("endpoint", endpoint);
        result.put("status", status);
        result.put("note", "当前为本次动态查询的来源标记，出发前仍需复核。");
        return result;
    }

    private List<String> normalizeKeywords(List<String> rawKeywords) {
        List<String> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : rawKeywords) {
            String keyword = text(raw);
            String identity = keyword.toLowerCase(java.util.Locale.ROOT);
            if (!keyword.isBlank() && seen.add(identity)) {
                result.add(keyword);
                if (result.size() >= maxPoiKeywords) break;
            }
        }
        return result;
    }

    private void addStrings(List<String> target, Object value) {
        if (value instanceof List<?> values) {
            for (Object item : values) {
                String text = text(item);
                if (!text.isBlank()) target.add(text);
            }
            return;
        }
        String text = text(value);
        if (!text.isBlank()) target.add(text);
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record HydrationResult(String mode, int poiSuccess, int routeSuccess,
                                  int weatherSuccess, boolean fallback) {
    }

    private record ScoredPoi(AmapResponseNormalizer.PoiCandidate candidate, int score) {
    }
}

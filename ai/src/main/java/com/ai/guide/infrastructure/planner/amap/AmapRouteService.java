package com.ai.guide.infrastructure.planner.amap;

import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;
import com.ai.guide.infrastructure.geo.amap.AmapResponseNormalizer;
import com.ai.guide.domain.search.gateway.PoiSearchGatewayPort;
import com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import com.ai.guide.common.context.OperationDeadline;

/**
 * 高德地图多模式路线计算与折线渲染辅助服务
 *
 * 架构定位：COLA 基础设施层实现 (Infrastructure Service)
 * 职责：实现 {@link PoiSearchGatewayPort} 与 {@link GeoRouteGatewayPort}，调用高德 Web Service 接口。
 */
@Service
public class AmapRouteService implements PoiSearchGatewayPort, GeoRouteGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(AmapRouteService.class);

    private final AmapWebServiceClient client;
    private final int requestTimeoutMs;
    private final int routeTimeoutMs;
    private final int maxConcurrency;
    private final ExecutorService executor;
    private final ExecutorService orchestrationExecutor;
    private final AmapRequestCache requestCache;
    private final boolean requestCacheEnabled;
    private final boolean requestDedupEnabled;
    private final long poiCacheTtlNanos;
    private final long routeCacheTtlNanos;
    @Value("${amap.web-service.request-cache.empty-route-ttl-ms:5000}")
    private long emptyRouteTtlMs = 5000;
    private final long weatherCacheTtlNanos;
    private final long qpsCooldownNanos;
    private final AtomicLong qpsBlockedUntilNanos = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public AmapRouteService(
            AmapWebServiceClient client,
            @Value("${amap.web-service.timeout-ms:5000}") int requestTimeoutMs,
            @Value("${amap.web-service.route-timeout-ms:4500}") int routeTimeoutMs,
            @Value("${amap.web-service.max-concurrency:16}") int maxConcurrency,
            @Value("${amap.web-service.request-cache.enabled:true}") boolean requestCacheEnabled,
            @Value("${amap.web-service.request-cache.dedup-enabled:true}") boolean requestDedupEnabled,
            @Value("${amap.web-service.request-cache.max-entries:2048}") int requestCacheMaxEntries,
            @Value("${amap.web-service.request-cache.poi-ttl-ms:1800000}") long poiCacheTtlMs,
            @Value("${amap.web-service.request-cache.route-ttl-ms:1800000}") long routeCacheTtlMs,
            @Value("${amap.web-service.request-cache.weather-ttl-ms:600000}") long weatherCacheTtlMs,
            @Value("${amap.web-service.qps-cooldown-ms:2000}") long qpsCooldownMs) {
        this(client, requestTimeoutMs, routeTimeoutMs, maxConcurrency,
                new AmapRequestCache(requestCacheMaxEntries), requestCacheEnabled,
                requestDedupEnabled, poiCacheTtlMs, routeCacheTtlMs, weatherCacheTtlMs,
                qpsCooldownMs);
    }

    /** Convenient constructor for focused unit tests. */
    public AmapRouteService(AmapWebServiceClient client) {
        this(client, 5000, 4500, 16, new AmapRequestCache(2048), true, true,
                1800000L, 1800000L, 600000L, 2000);
    }

    AmapRouteService(AmapWebServiceClient client,
                     int requestTimeoutMs,
                     int routeTimeoutMs,
                     int maxConcurrency,
                     AmapRequestCache requestCache,
                     boolean requestCacheEnabled,
                     boolean requestDedupEnabled,
                     long poiCacheTtlMs,
                     long routeCacheTtlMs,
                     long weatherCacheTtlMs) {
        this(client, requestTimeoutMs, routeTimeoutMs, maxConcurrency, requestCache,
                requestCacheEnabled, requestDedupEnabled, poiCacheTtlMs, routeCacheTtlMs,
                weatherCacheTtlMs, 2000);
    }

    public AmapRouteService(AmapWebServiceClient client,
                     int requestTimeoutMs,
                     int routeTimeoutMs,
                     int maxConcurrency,
                     AmapRequestCache requestCache,
                     boolean requestCacheEnabled,
                     boolean requestDedupEnabled,
                     long poiCacheTtlMs,
                     long routeCacheTtlMs,
                     long weatherCacheTtlMs,
                     long qpsCooldownMs) {
        this.client = client;
        this.requestTimeoutMs = Math.max(500, requestTimeoutMs);
        this.routeTimeoutMs = Math.max(500, routeTimeoutMs);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.executor = new ThreadPoolExecutor(
                this.maxConcurrency,
                this.maxConcurrency,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                daemonThreadFactory("amap-route-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        int orchestrationThreads = Math.max(2, this.maxConcurrency);
        this.orchestrationExecutor = new ThreadPoolExecutor(
                orchestrationThreads,
                orchestrationThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(4, orchestrationThreads * 4)),
                daemonThreadFactory("amap-orchestrator-"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.requestCache = requestCache;
        this.requestCacheEnabled = requestCacheEnabled;
        this.requestDedupEnabled = requestDedupEnabled;
        this.poiCacheTtlNanos = ttlNanos(poiCacheTtlMs);
        this.routeCacheTtlNanos = ttlNanos(routeCacheTtlMs);
        this.weatherCacheTtlNanos = ttlNanos(weatherCacheTtlMs);
        this.qpsCooldownNanos = ttlNanos(qpsCooldownMs);
    }

    public boolean isConfigured() {
        return client != null && client.isConfigured();
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    public GeoPoiOutcome searchPoi(String keywords, String city) {
        return searchPoi(keywords, city, null);
    }

    @Override
    public List<com.ai.guide.domain.geo.model.GeoPoiCandidate> searchPoiCandidates(String keywords, String city) {
        GeoPoiOutcome outcome = searchPoi(keywords, city);
        return (outcome != null && outcome.candidates() != null) ? outcome.candidates() : List.of();
    }

    public GeoPoiOutcome searchPoi(String keywords, String city, String types) {
        if (keywords == null || keywords.isBlank()) {
            return new GeoPoiOutcome(GeoOutcomeStatus.INVALID, List.of(), "POI 关键词为空", Instant.now());
        }
        if (!isConfigured()) {
            return new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("poi-text", "v5/place/text",
                keywords, city, types == null ? "" : types, "business,navi,photos", "true", "20");
        return cachedPoi(() -> requestCache.getOrLoad(key, poiCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadPoi(keywords, city, types), outcome -> outcome.status() == GeoOutcomeStatus.SUCCESS));
    }

    private GeoPoiOutcome cachedPoi(Supplier<GeoPoiOutcome> lookup) {
        try { return lookup.get(); }
        catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoPoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    private GeoPoiOutcome loadPoi(String keywords, String city, String types) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildPoiTextRequest(keywords, city, types, "business,navi,photos")),
                    requestTimeoutMs,
                    1);
            return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizePois(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoPoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    public GeoPoiOutcome searchAround(String location, int radiusMeters, String types,
                                   String keywords, String city) {
        return searchAround(location, radiusMeters, types, keywords, city, 1);
    }

    public GeoPoiOutcome searchAround(String location, int radiusMeters, String types, String keywords, String city, int pages) {
        if (client == null) {
            return searchAround(location, radiusMeters, types, keywords, city);
        }
        return executeSearchAround(location, radiusMeters, types, keywords, city, pages);
    }

    private GeoPoiOutcome executeSearchAround(String location, int radiusMeters, String types, String keywords, String city, int pages) {
        if (location == null || location.isBlank() || !validCoordinate(location)) {
            return new GeoPoiOutcome(GeoOutcomeStatus.INVALID, List.of(), "周边搜索坐标无效", Instant.now());
        }
        if (!isConfigured()) {
            return new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now());
        }
        int effectivePages = Math.max(1, Math.min(6, pages));
        AmapRequestCache.RequestKey key = key("poi-around", "v5/place/around",
                location, String.valueOf(radiusMeters), types == null ? "" : types,
                keywords == null ? "" : keywords, city == null ? "" : city, String.valueOf(effectivePages), "weight");
        return cachedPoi(() -> requestCache.getOrLoad(key, poiCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadPoiAround(location, radiusMeters, types, keywords, effectivePages),
                outcome -> outcome.status() == GeoOutcomeStatus.SUCCESS));
    }

    private GeoPoiOutcome loadPoiAround(String location, int radiusMeters, String types, String keywords) {
        return loadPoiAround(location, radiusMeters, types, keywords, 1);
    }

    private GeoPoiOutcome loadPoiAround(String location, int radiusMeters, String types, String keywords, int pages) {
        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> allCandidates = new ArrayList<>();
        Set<String> seenPoiIds = new HashSet<>();
        try {
            for (int page = 1; page <= pages; page++) {
                final int p = page;
                try {
                    JsonNode response = execute(
                            () -> client.get(client.buildPoiAroundRequest(location, radiusMeters, types, keywords, "business,navi,photos", p, "weight")),
                            requestTimeoutMs,
                            0);
                    List<com.ai.guide.domain.geo.model.GeoPoiCandidate> pageList = AmapResponseNormalizer.normalizePois(response);
                    if (pageList == null || pageList.isEmpty()) {
                        break;
                    }
                    for (com.ai.guide.domain.geo.model.GeoPoiCandidate c : pageList) {
                        String id = c.poiId() != null && !c.poiId().isBlank() ? c.poiId() : c.name();
                        if (id != null && seenPoiIds.add(id)) {
                            allCandidates.add(c);
                        }
                    }
                    if (pageList.size() < 25) {
                        break;
                    }
                } catch (RuntimeException pageFailure) {
                    if (!allCandidates.isEmpty()) {
                        log.warn("高德周边搜索第 {} 页异常 ({})，但前序页面已获取 {} 个有效候选，优雅降级返回已获取候选",
                                p, pageFailure.getMessage(), allCandidates.size());
                        break;
                    }
                    throw pageFailure;
                }
            }
            return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, allCandidates, "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoPoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    /**
     * Queries both route modes so the planner can expose the same transparent
     * walking/transit comparison as the Web baseline. A failed preferred mode
     * may still use the other successful mode as an explicit fallback.
     */
    public GeoRoutePairOutcome routePair(String origin, String destination, String city,
                                      String originPoi, String destinationPoi, String preference) {
        if (!validCoordinate(origin) || !validCoordinate(destination)) {
            GeoRouteOutcome invalid = new GeoRouteOutcome(GeoOutcomeStatus.INVALID, null,
                    "起点或终点坐标无效", "", Instant.now());
            return new GeoRoutePairOutcome(invalid, invalid, invalid, preference, false);
        }
        if (!isConfigured()) {
            GeoRouteOutcome unavailable = new GeoRouteOutcome(GeoOutcomeStatus.UNAVAILABLE, null,
                    "未配置高德服务端密钥", "", Instant.now());
            return new GeoRoutePairOutcome(unavailable, unavailable, unavailable, preference, false);
        }

        // Include orchestration queueing and both v5/v3 attempts in one pair budget.
        long deadline = System.nanoTime() + Math.min(OperationDeadline.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(4L * routeTimeoutMs + 500L));
        Future<GeoRouteOutcome> walkingFuture = submitRoute(() -> routeWalking(origin, destination));
        Future<GeoRouteOutcome> transitFuture = submitRoute(() -> routeTransit(origin, destination, city, originPoi, destinationPoi));

        GeoRouteOutcome walking = joinOutcome(walkingFuture, deadline);
        GeoRouteOutcome transit = joinOutcome(transitFuture, deadline);
        boolean taxiUnsupported = "taxi".equalsIgnoreCase(preference);
        GeoRouteOutcome selected;
        if (taxiUnsupported) {
            // No driving/taxi request builder exists yet; do not claim taxi data.
            selected = firstSuccess(walking, transit);
        } else if ("transit".equalsIgnoreCase(preference)) {
            selected = firstSuccess(transit, walking);
        } else {
            selected = firstSuccess(walking, transit);
        }
        return new GeoRoutePairOutcome(walking, transit, selected, preference, taxiUnsupported);
    }

    public GeoWeatherOutcome weather(String cityCode) {
        if (!isConfigured()) {
            return new GeoWeatherOutcome(GeoOutcomeStatus.UNAVAILABLE, null,
                    "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("weather", "v3/weather/weatherInfo", cityCode, "all");
        try {
            return requestCache.getOrLoad(key, weatherCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                    () -> loadWeather(cityCode), outcome -> outcome.status() == GeoOutcomeStatus.SUCCESS);
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoWeatherOutcome(classified.status, null, classified.getMessage(), Instant.now());
        }
    }

    private GeoWeatherOutcome loadWeather(String cityCode) {
        try {
            String resolvedCityCode = cityCode == null ? "" : cityCode.trim();
            if (!resolvedCityCode.matches("\\d{6}")) {
                JsonNode geocodeResponse = execute(
                        () -> client.get(client.buildGeocodeRequest(cityCode, cityCode)), requestTimeoutMs, 0);
                resolvedCityCode = AmapResponseNormalizer.normalizeGeocode(geocodeResponse).adcode();
            }
            if (resolvedCityCode == null || !resolvedCityCode.matches("\\d{6}")) {
                return new GeoWeatherOutcome(GeoOutcomeStatus.INVALID, null, "无法解析所在城市的天气行政区编码", Instant.now());
            }
            final String weatherCityCode = resolvedCityCode;
            JsonNode response = execute(
                    () -> client.get(client.buildWeatherRequest(weatherCityCode, "all")),
                    requestTimeoutMs,
                    0);
            return new GeoWeatherOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeWeather(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoWeatherOutcome(classified.status, null, classified.getMessage(), Instant.now());
        }
    }

    /** Fetches the provider detail payload for a previously matched POI. */
    public GeoPoiOutcome poiDetail(String poiId) {
        if (poiId == null || poiId.isBlank()) {
            return new GeoPoiOutcome(GeoOutcomeStatus.INVALID, List.of(), "POI 标识为空", Instant.now());
        }
        if (!isConfigured()) {
            return new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("poi-detail", "v5/place/detail",
                poiId, "business,navi,photos");
        return cachedPoi(() -> requestCache.getOrLoad(key, poiCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadPoiDetail(poiId), outcome -> outcome.status() == GeoOutcomeStatus.SUCCESS));
    }

    private GeoPoiOutcome loadPoiDetail(String poiId) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildPoiDetailRequest(poiId, "business,navi,photos")),
                    requestTimeoutMs,
                    1);
            return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizePois(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoPoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    private GeoRouteOutcome routeWalking(String origin, String destination) {
        AmapRequestCache.RequestKey key = key("route-walking", "v5/direction/walking",
                origin, destination, "cost,navi,polyline");
        return requestCache.getOrLoad(key, requestCacheEnabled, requestDedupEnabled,
                () -> loadWalking(origin, destination), result -> routeResultTtl(result, routeCacheTtlNanos));
    }

    private GeoRouteOutcome loadWalking(String origin, String destination) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildWalkingRequest(origin, destination, "cost,navi,polyline")),
                    routeTimeoutMs,
                    1);
            return new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeWalking(response), "", "v5/direction/walking", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new GeoRouteOutcome(classified.status, null, classified.getMessage(),
                    "v5/direction/walking", Instant.now());
        }
    }

    private GeoRouteOutcome routeTransit(String origin, String destination, String city,
                                      String originPoi, String destinationPoi) {
        // 增加 15 分钟时变桶因子，保障公交/地铁在末班车停运或高峰切换时动态刷新，杜绝长 TTL 导致停运误导
        long timeBucket = System.currentTimeMillis() / (15 * 60 * 1000L);
        AmapRequestCache.RequestKey key = key("route-transit", "v5+v3/direction/transit/integrated",
                origin, destination, city, "0", originPoi, destinationPoi, "cost,polyline", "slot-" + timeBucket);
        return requestCache.getOrLoad(key, requestCacheEnabled, requestDedupEnabled,
                () -> loadTransit(origin, destination, city, originPoi, destinationPoi), result -> routeResultTtl(result, Math.min(routeCacheTtlNanos, ttlNanos(15 * 60 * 1000L))));
    }

    private GeoRouteOutcome loadTransit(String origin, String destination, String city,
                                     String originPoi, String destinationPoi) {
        if (!isCityCode(city)) {
            if (city != null && city.trim().matches("\\d+")) {
                return new GeoRouteOutcome(GeoOutcomeStatus.INVALID, null,
                        "城市编码格式无效，不能作为城市名称查询", "v3/direction/transit/integrated", Instant.now());
            }
            return loadTransitV3(origin, destination, city,
                    "未提供可验证的 citycode，已使用 V3 城市名称兼容路线");
        }
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildTransitRequest(origin, destination, city, city, "0", originPoi, destinationPoi)),
                    routeTimeoutMs,
                    1);
            return new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeTransit(response), "",
                    "v5/direction/transit/integrated", Instant.now());
        } catch (RuntimeException v5Error) {
            ProviderFailure v5Failure = observe(asProviderFailure(v5Error));
            return new GeoRouteOutcome(v5Failure.status, null, v5Failure.getMessage(),
                    "v5/direction/transit/integrated", Instant.now());
        }
    }

    private GeoRouteOutcome loadTransitV3(String origin, String destination, String city, String reason) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildTransitFallbackRequest(origin, destination, city)), routeTimeoutMs, 1);
            return new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeTransit(response), reason,
                    "v3/direction/transit/integrated", Instant.now());
        } catch (RuntimeException v3Error) {
            ProviderFailure v3Failure = observe(asProviderFailure(v3Error));
            return new GeoRouteOutcome(v3Failure.status, null,
                    reason + "; V3 兼容路线失败: " + v3Failure.getMessage(),
                    "v3/direction/transit/integrated", Instant.now());
        }
    }

    private boolean isCityCode(String value) {
        return value != null && value.trim().matches("\\d{3,4}");
    }

    private GeoRouteOutcome firstSuccess(GeoRouteOutcome first, GeoRouteOutcome second) {
        if (successful(first)) return first;
        if (successful(second)) return second;
        return first != null ? first : second;
    }

    private boolean successful(GeoRouteOutcome outcome) {
        return outcome != null && outcome.status() == GeoOutcomeStatus.SUCCESS
                && outcome.segment() != null && outcome.segment().metricsComplete();
    }
    private long routeResultTtl(GeoRouteOutcome outcome, long successTtl) {
        if (successful(outcome)) return successTtl;
        // A successful provider response without a usable route may change shortly.
        // Authentication, timeout and provider errors are never cached as an empty route.
        return outcome != null && outcome.status() == GeoOutcomeStatus.SUCCESS ? ttlNanos(emptyRouteTtlMs) : 0L;
    }

    private JsonNode execute(Supplier<JsonNode> operation, int timeoutMs, int retries) {
        if (qpsCooldownActive()) {
            throw rateLimited("高德接口处于 QPS 冷却期");
        }
        ProviderFailure latest = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (OperationDeadline.expired()) throw new ProviderFailure(GeoOutcomeStatus.TIMEOUT, "规划请求预算已用尽", false);
            Supplier<JsonNode> boundedOperation = OperationDeadline.carry(() -> {
                if (OperationDeadline.expired()) throw new ProviderFailure(GeoOutcomeStatus.TIMEOUT, "规划请求预算已用尽", false);
                return operation.get();
            });
            Future<JsonNode> future;
            try { future = executor.submit(boundedOperation::get); }
            catch (RejectedExecutionException rejected) {
                throw new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, "高德请求队列已满", false);
            }
            try {
                return future.get(Math.min(OperationDeadline.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(timeoutMs)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                latest = new ProviderFailure(GeoOutcomeStatus.TIMEOUT, "高德接口超时");
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                latest = new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, "高德接口调用被中断");
            } catch (ExecutionException error) {
                future.cancel(true);
                Throwable cause = error.getCause() == null ? error : error.getCause();
                latest = asProviderFailure(cause);
                if (latest.status == GeoOutcomeStatus.RATE_LIMITED) {
                    activateQpsCooldown();
                    break;
                }
                if (latest.status == GeoOutcomeStatus.INVALID) break;
            }
            if (attempt >= retries || latest == null || !latest.retryable
                    || (latest.status != GeoOutcomeStatus.TIMEOUT && latest.status != GeoOutcomeStatus.UNAVAILABLE)) {
                break;
            }
            try {
                if (OperationDeadline.expired()) break;
                TimeUnit.NANOSECONDS.sleep(Math.min(OperationDeadline.remainingNanos(), TimeUnit.MILLISECONDS.toNanos(180L)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return fail(new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, "高德接口调用被中断", false));
            }
        }
        return fail(latest == null
                ? new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, "高德接口暂不可用")
                : latest);
    }

    private JsonNode fail(ProviderFailure failure) {
        throw failure;
    }

    private ProviderFailure asProviderFailure(Throwable failure) {
        if (failure instanceof ProviderFailure providerFailure) return providerFailure;
        return classify(failure);
    }

    private ProviderFailure observe(ProviderFailure failure) {
        if (failure.status == GeoOutcomeStatus.RATE_LIMITED) activateQpsCooldown();
        return failure;
    }

    private ProviderFailure classify(Throwable cause) {
        if (cause instanceof AmapResponseNormalizer.AmapResponseException responseFailure) {
            if (isRateLimited(responseFailure.info(), responseFailure.infocode(), responseFailure.getMessage())) {
                return new ProviderFailure(GeoOutcomeStatus.RATE_LIMITED, responseFailure.getMessage(), false, false);
            }
            if (isAuthError(responseFailure.info(), responseFailure.infocode(), responseFailure.getMessage())) {
                return new ProviderFailure(GeoOutcomeStatus.AUTH_ERROR, responseFailure.getMessage(), false, false);
            }
            boolean compatibility = isCompatibilityFailure(responseFailure.info(), responseFailure.getMessage());
            return new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, responseFailure.getMessage(), false, compatibility);
        }
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return new ProviderFailure(GeoOutcomeStatus.INVALID, cause.getMessage(), false, false);
        }
        Throwable cursor = cause;
        while (cursor != null) {
            if (cursor instanceof javax.net.ssl.SSLException
                    || cursor instanceof javax.net.ssl.SSLHandshakeException
                    || String.valueOf(cursor.getMessage()).contains("handshake")
                    || String.valueOf(cursor.getMessage()).contains("SSL")
                    || String.valueOf(cursor.getMessage()).contains("TLS")) {
                return new ProviderFailure(GeoOutcomeStatus.TLS_ERROR, "高德TLS握手失败: " + cursor.getMessage(), true, false);
            }
            if (cursor instanceof SocketTimeoutException || cursor instanceof TimeoutException
                    || String.valueOf(cursor.getMessage()).toLowerCase().contains("timeout")) {
                return new ProviderFailure(GeoOutcomeStatus.TIMEOUT, "高德接口超时", true, true);
            }
            cursor = cursor.getCause();
        }
        return new ProviderFailure(GeoOutcomeStatus.UNAVAILABLE, "高德接口暂不可用", true, true);
    }

    private boolean isAuthError(String info, String infocode, String message) {
        String text = (String.valueOf(info) + " " + String.valueOf(infocode) + " " + String.valueOf(message))
                .toUpperCase(java.util.Locale.ROOT);
        return text.contains("10001") || text.contains("10002") || text.contains("10008")
                || text.contains("10012") || text.contains("10013");
    }

    private boolean isRateLimited(String info, String infocode, String message) {
        String text = (String.valueOf(info) + " " + String.valueOf(infocode) + " " + String.valueOf(message))
                .toUpperCase(java.util.Locale.ROOT);
        return text.contains("10004") || text.contains("10014") || text.contains("10015")
                || text.contains("10019") || text.contains("10020") || text.contains("10021")
                || text.contains("QPS") || text.contains("CQPS") || text.contains("CKQPS")
                || text.contains("ACCESS_TOO_FREQUENT") || text.contains("访问过于频繁");
    }

    private boolean isCompatibilityFailure(String info, String message) {
        String text = (String.valueOf(info) + " " + String.valueOf(message)).toLowerCase(java.util.Locale.ROOT);
        return text.contains("兼容") || text.contains("unsupported") || text.contains("not support");
    }

    private ProviderFailure rateLimited(String reason) {
        return new ProviderFailure(GeoOutcomeStatus.RATE_LIMITED, reason, false, false);
    }

    private boolean qpsCooldownActive() {
        return qpsBlockedUntilNanos.get() > System.nanoTime();
    }

    private void activateQpsCooldown() {
        if (qpsCooldownNanos <= 0) return;
        long now = System.nanoTime();
        long until = qpsCooldownNanos >= Long.MAX_VALUE - now
                ? Long.MAX_VALUE : now + qpsCooldownNanos;
        qpsBlockedUntilNanos.accumulateAndGet(until, Math::max);
    }

    private AmapRequestCache.RequestKey key(String operation, String endpoint, String... dimensions) {
        List<String> values = new java.util.ArrayList<>(dimensions.length + 1);
        values.add(endpoint);
        values.addAll(java.util.Arrays.asList(dimensions));
        return new AmapRequestCache.RequestKey(operation, values);
    }

    private long ttlNanos(long ttlMs) {
        if (ttlMs <= 0) return 0;
        long maxMillis = Long.MAX_VALUE / 1_000_000L;
        return ttlMs >= maxMillis ? Long.MAX_VALUE : ttlMs * 1_000_000L;
    }

    private boolean validCoordinate(String value) {
        com.ai.guide.domain.geo.model.GeoCoordinate coordinate = com.ai.guide.domain.geo.model.GeoCoordinates.parse(value);
        return coordinate != null && Double.isFinite(coordinate.longitude())
                && Double.isFinite(coordinate.latitude())
                && coordinate.longitude() >= -180 && coordinate.longitude() <= 180
                && coordinate.latitude() >= -90 && coordinate.latitude() <= 90;
    }

    private <T> Future<T> submitRoute(Supplier<T> task) {
        FutureTask<T> futureTask = new FutureTask<>(OperationDeadline.carry(task)::get);
        if (OperationDeadline.expired()) { futureTask.cancel(false); return futureTask; }
        try {
            orchestrationExecutor.execute(futureTask);
        } catch (RejectedExecutionException rejected) {
            // Running rejected provider work inline bypasses the caller's wait budget.
            futureTask.cancel(false);
        }
        return futureTask;
    }

    private GeoRouteOutcome joinOutcome(Future<GeoRouteOutcome> future, long deadline) {
        try {
            return future.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new GeoRouteOutcome(GeoOutcomeStatus.TIMEOUT, null, "路线计算等待预算已用尽", "", Instant.now());
        } catch (java.util.concurrent.CancellationException rejected) {
            return new GeoRouteOutcome(GeoOutcomeStatus.UNAVAILABLE, null, "路线计算队列已满", "", Instant.now());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new GeoRouteOutcome(GeoOutcomeStatus.UNAVAILABLE, null, "路线计算被中断", "", Instant.now());
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            ProviderFailure failure = asProviderFailure(cause);
            return new GeoRouteOutcome(failure.status, null, failure.getMessage(), "", Instant.now());
        }
    }

    private ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }


    @PreDestroy
    public void close() {
        orchestrationExecutor.shutdownNow();
        executor.shutdownNow();
    }

    private static final class ProviderFailure extends RuntimeException {
        private final GeoOutcomeStatus status;
        private final boolean retryable;
        private final boolean fallbackAllowed;

        private ProviderFailure(GeoOutcomeStatus status, String message) {
            this(status, message, status == GeoOutcomeStatus.TIMEOUT, status == GeoOutcomeStatus.UNAVAILABLE);
        }

        private ProviderFailure(GeoOutcomeStatus status, String message, boolean retryable) {
            this(status, message, retryable, status == GeoOutcomeStatus.UNAVAILABLE);
        }

        private ProviderFailure(GeoOutcomeStatus status, String message,
                                boolean retryable, boolean fallbackAllowed) {
            super(message == null || message.isBlank() ? "高德接口调用失败" : message);
            this.status = status;
            this.retryable = retryable;
            this.fallbackAllowed = fallbackAllowed;
        }
    }
}

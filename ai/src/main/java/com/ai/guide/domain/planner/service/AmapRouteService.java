package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.rag.pipeline.AmapWebServiceClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
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

/**
 * 高德地图多模式路线计算与折线渲染辅助服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
@Service
public class AmapRouteService {

    public enum OutcomeStatus {
        SUCCESS, UNAVAILABLE, TIMEOUT, INVALID, UNSUPPORTED, RATE_LIMITED
    }

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
    private final long weatherCacheTtlNanos;
    private final long qpsCooldownNanos;
    private final AtomicLong qpsBlockedUntilNanos = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public AmapRouteService(
            AmapWebServiceClient client,
            @Value("${amap.web-service.timeout-ms:6000}") int requestTimeoutMs,
            @Value("${amap.web-service.route-timeout-ms:6500}") int routeTimeoutMs,
            @Value("${amap.web-service.max-concurrency:2}") int maxConcurrency,
            @Value("${amap.web-service.request-cache.enabled:true}") boolean requestCacheEnabled,
            @Value("${amap.web-service.request-cache.dedup-enabled:true}") boolean requestDedupEnabled,
            @Value("${amap.web-service.request-cache.max-entries:256}") int requestCacheMaxEntries,
            @Value("${amap.web-service.request-cache.poi-ttl-ms:60000}") long poiCacheTtlMs,
            @Value("${amap.web-service.request-cache.route-ttl-ms:10000}") long routeCacheTtlMs,
            @Value("${amap.web-service.request-cache.weather-ttl-ms:60000}") long weatherCacheTtlMs,
            @Value("${amap.web-service.qps-cooldown-ms:2000}") long qpsCooldownMs) {
        this(client, requestTimeoutMs, routeTimeoutMs, maxConcurrency,
                new AmapRequestCache(requestCacheMaxEntries), requestCacheEnabled,
                requestDedupEnabled, poiCacheTtlMs, routeCacheTtlMs, weatherCacheTtlMs,
                qpsCooldownMs);
    }

    /** Convenient constructor for focused unit tests. */
    public AmapRouteService(AmapWebServiceClient client) {
        this(client, 6000, 6500, 2, new AmapRequestCache(256), true, true,
                60000, 10000, 60000, 2000);
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

    AmapRouteService(AmapWebServiceClient client,
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
        this.executor = Executors.newFixedThreadPool(this.maxConcurrency, daemonThreadFactory("amap-route-"));
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

    public PoiOutcome searchPoi(String keywords, String city) {
        if (keywords == null || keywords.isBlank()) {
            return new PoiOutcome(OutcomeStatus.INVALID, List.of(), "POI 关键词为空", Instant.now());
        }
        if (!isConfigured()) {
            return new PoiOutcome(OutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("poi-text", "v5/place/text",
                keywords, city, "business,navi,photos", "true", "10");
        return requestCache.getOrLoad(key, poiCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadPoi(keywords, city), outcome -> outcome.status() == OutcomeStatus.SUCCESS);
    }

    private PoiOutcome loadPoi(String keywords, String city) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildPoiTextRequest(keywords, city, "business,navi,photos")),
                    requestTimeoutMs,
                    1);
            return new PoiOutcome(OutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizePois(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new PoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    /**
     * Queries both route modes so the planner can expose the same transparent
     * walking/transit comparison as the Web baseline. A failed preferred mode
     * may still use the other successful mode as an explicit fallback.
     */
    public RoutePairOutcome routePair(String origin, String destination, String city,
                                      String originPoi, String destinationPoi, String preference) {
        if (!validCoordinate(origin) || !validCoordinate(destination)) {
            RouteOutcome invalid = new RouteOutcome(OutcomeStatus.INVALID, null,
                    "起点或终点坐标无效", "", Instant.now());
            return new RoutePairOutcome(invalid, invalid, invalid, preference, false);
        }
        if (!isConfigured()) {
            RouteOutcome unavailable = new RouteOutcome(OutcomeStatus.UNAVAILABLE, null,
                    "未配置高德服务端密钥", "", Instant.now());
            return new RoutePairOutcome(unavailable, unavailable, unavailable, preference, false);
        }

        Future<RouteOutcome> walkingFuture = submitOrRun(() -> routeWalking(origin, destination));
        Future<RouteOutcome> transitFuture = submitOrRun(() -> routeTransit(origin, destination, city, originPoi, destinationPoi));

        RouteOutcome walking = joinOutcome(walkingFuture);
        RouteOutcome transit = joinOutcome(transitFuture);
        boolean taxiUnsupported = "taxi".equalsIgnoreCase(preference);
        RouteOutcome selected;
        if (taxiUnsupported) {
            // No driving/taxi request builder exists yet; do not claim taxi data.
            selected = firstSuccess(walking, transit);
        } else if ("transit".equalsIgnoreCase(preference)) {
            selected = firstSuccess(transit, walking);
        } else {
            selected = firstSuccess(walking, transit);
        }
        return new RoutePairOutcome(walking, transit, selected, preference, taxiUnsupported);
    }

    public WeatherOutcome weather(String cityCode) {
        if (!isConfigured()) {
            return new WeatherOutcome(OutcomeStatus.UNAVAILABLE, null,
                    "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("weather", "v3/weather/weatherInfo", cityCode, "all");
        return requestCache.getOrLoad(key, weatherCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadWeather(cityCode), outcome -> outcome.status() == OutcomeStatus.SUCCESS);
    }

    private WeatherOutcome loadWeather(String cityCode) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildWeatherRequest(cityCode, "all")),
                    requestTimeoutMs,
                    0);
            return new WeatherOutcome(OutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeWeather(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new WeatherOutcome(classified.status, null, classified.getMessage(), Instant.now());
        }
    }

    /** Fetches the provider detail payload for a previously matched POI. */
    public PoiOutcome poiDetail(String poiId) {
        if (poiId == null || poiId.isBlank()) {
            return new PoiOutcome(OutcomeStatus.INVALID, List.of(), "POI 标识为空", Instant.now());
        }
        if (!isConfigured()) {
            return new PoiOutcome(OutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now());
        }
        AmapRequestCache.RequestKey key = key("poi-detail", "v5/place/detail",
                poiId, "business,navi,photos");
        return requestCache.getOrLoad(key, poiCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadPoiDetail(poiId), outcome -> outcome.status() == OutcomeStatus.SUCCESS);
    }

    private PoiOutcome loadPoiDetail(String poiId) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildPoiDetailRequest(poiId, "business,navi,photos")),
                    requestTimeoutMs,
                    1);
            return new PoiOutcome(OutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizePois(response), "", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new PoiOutcome(classified.status, List.of(), classified.getMessage(), Instant.now());
        }
    }

    private RouteOutcome routeWalking(String origin, String destination) {
        AmapRequestCache.RequestKey key = key("route-walking", "v5/direction/walking",
                origin, destination, "cost,navi,polyline");
        return requestCache.getOrLoad(key, routeCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadWalking(origin, destination), this::successful);
    }

    private RouteOutcome loadWalking(String origin, String destination) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildWalkingRequest(origin, destination, "cost,navi,polyline")),
                    routeTimeoutMs,
                    1);
            return new RouteOutcome(OutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeWalking(response), "", "v5/direction/walking", Instant.now());
        } catch (RuntimeException failure) {
            ProviderFailure classified = observe(asProviderFailure(failure));
            return new RouteOutcome(classified.status, null, classified.getMessage(),
                    "v5/direction/walking", Instant.now());
        }
    }

    private RouteOutcome routeTransit(String origin, String destination, String city,
                                      String originPoi, String destinationPoi) {
        AmapRequestCache.RequestKey key = key("route-transit", "v5+v3/direction/transit/integrated",
                origin, destination, city, "0", originPoi, destinationPoi, "cost,polyline");
        return requestCache.getOrLoad(key, routeCacheTtlNanos, requestCacheEnabled, requestDedupEnabled,
                () -> loadTransit(origin, destination, city, originPoi, destinationPoi), this::successful);
    }

    private RouteOutcome loadTransit(String origin, String destination, String city,
                                     String originPoi, String destinationPoi) {
        try {
            JsonNode response = execute(
                    () -> client.get(client.buildTransitRequest(origin, destination, city, "0", originPoi, destinationPoi)),
                    routeTimeoutMs,
                    1);
            return new RouteOutcome(OutcomeStatus.SUCCESS,
                    AmapResponseNormalizer.normalizeTransit(response), "",
                    "v5/direction/transit/integrated", Instant.now());
        } catch (RuntimeException v5Error) {
            ProviderFailure v5Failure = observe(asProviderFailure(v5Error));
            if (!v5Failure.fallbackAllowed) {
                return new RouteOutcome(v5Failure.status, null, v5Failure.getMessage(),
                        "v5/direction/transit/integrated", Instant.now());
            }
            // v3 is the compatibility fallback used by the production adapter.
            try {
                JsonNode response = execute(
                        () -> client.get(client.buildTransitFallbackRequest(origin, destination, city)),
                        routeTimeoutMs,
                        1);
                return new RouteOutcome(OutcomeStatus.SUCCESS,
                        AmapResponseNormalizer.normalizeTransit(response),
                        "v5 transit fallback: " + v5Failure.getMessage(),
                        "v3/direction/transit/integrated", Instant.now());
            } catch (RuntimeException v3Error) {
                ProviderFailure v3Failure = observe(asProviderFailure(v3Error));
                return new RouteOutcome(v3Failure.status, null,
                        "v5: " + v5Failure.getMessage() + "; v3: " + v3Failure.getMessage(),
                        "v5/direction/transit/integrated", Instant.now());
            }
        }
    }

    private RouteOutcome firstSuccess(RouteOutcome first, RouteOutcome second) {
        if (successful(first)) return first;
        if (successful(second)) return second;
        return first != null ? first : second;
    }

    private boolean successful(RouteOutcome outcome) {
        return outcome != null && outcome.status == OutcomeStatus.SUCCESS
                && outcome.segment != null && outcome.segment.metricsComplete();
    }

    private JsonNode execute(Supplier<JsonNode> operation, int timeoutMs, int retries) {
        if (qpsCooldownActive()) {
            throw rateLimited("高德接口处于 QPS 冷却期");
        }
        ProviderFailure latest = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            Future<JsonNode> future = executor.submit(operation::get);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                latest = new ProviderFailure(OutcomeStatus.TIMEOUT, "高德接口超时");
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                latest = new ProviderFailure(OutcomeStatus.UNAVAILABLE, "高德接口调用被中断");
            } catch (ExecutionException error) {
                future.cancel(true);
                Throwable cause = error.getCause() == null ? error : error.getCause();
                latest = classify(cause);
                if (latest.status == OutcomeStatus.RATE_LIMITED) {
                    activateQpsCooldown();
                    break;
                }
                if (latest.status == OutcomeStatus.INVALID) break;
            }
            if (attempt >= retries || latest == null || !latest.retryable
                    || (latest.status != OutcomeStatus.TIMEOUT && latest.status != OutcomeStatus.UNAVAILABLE)) {
                break;
            }
            try {
                Thread.sleep(180L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return fail(new ProviderFailure(OutcomeStatus.UNAVAILABLE, "高德接口调用被中断", false));
            }
        }
        return fail(latest == null
                ? new ProviderFailure(OutcomeStatus.UNAVAILABLE, "高德接口暂不可用")
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
        if (failure.status == OutcomeStatus.RATE_LIMITED) activateQpsCooldown();
        return failure;
    }

    private ProviderFailure classify(Throwable cause) {
        if (cause instanceof AmapResponseNormalizer.AmapResponseException responseFailure) {
            if (isRateLimited(responseFailure.info(), responseFailure.infocode(), responseFailure.getMessage())) {
                return new ProviderFailure(OutcomeStatus.RATE_LIMITED, responseFailure.getMessage(), false, false);
            }
            boolean compatibility = isCompatibilityFailure(responseFailure.info(), responseFailure.getMessage());
            return new ProviderFailure(OutcomeStatus.UNAVAILABLE, responseFailure.getMessage(), false, compatibility);
        }
        if (cause instanceof IllegalArgumentException || cause instanceof IllegalStateException) {
            return new ProviderFailure(OutcomeStatus.INVALID, cause.getMessage(), false, false);
        }
        Throwable cursor = cause;
        while (cursor != null) {
            if (cursor instanceof SocketTimeoutException || cursor instanceof TimeoutException
                    || String.valueOf(cursor.getMessage()).toLowerCase().contains("timeout")) {
                return new ProviderFailure(OutcomeStatus.TIMEOUT, "高德接口超时", true, true);
            }
            cursor = cursor.getCause();
        }
        return new ProviderFailure(OutcomeStatus.UNAVAILABLE, "高德接口暂不可用", true, true);
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
        return new ProviderFailure(OutcomeStatus.RATE_LIMITED, reason, false, false);
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
        AmapResponseNormalizer.Coordinate coordinate = AmapResponseNormalizer.parseCoordinate(value);
        return coordinate != null && Double.isFinite(coordinate.longitude())
                && Double.isFinite(coordinate.latitude())
                && coordinate.longitude() >= -180 && coordinate.longitude() <= 180
                && coordinate.latitude() >= -90 && coordinate.latitude() <= 90;
    }

    private <T> Future<T> submitOrRun(Supplier<T> task) {
        FutureTask<T> futureTask = new FutureTask<>(task::get);
        try {
            orchestrationExecutor.execute(futureTask);
        } catch (RejectedExecutionException rejected) {
            futureTask.run();
        }
        return futureTask;
    }

    private RouteOutcome joinOutcome(Future<RouteOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new RouteOutcome(OutcomeStatus.UNAVAILABLE, null, "路线计算被中断", "", Instant.now());
        } catch (ExecutionException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            ProviderFailure failure = asProviderFailure(cause);
            return new RouteOutcome(failure.status, null, failure.getMessage(), "", Instant.now());
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

    public record PoiOutcome(OutcomeStatus status, List<AmapResponseNormalizer.PoiCandidate> candidates,
                             String reason, Instant fetchedAt) {
    }

    public record RouteOutcome(OutcomeStatus status, AmapResponseNormalizer.RouteResult segment,
                               String reason, String endpoint, Instant fetchedAt) {
    }

    public record RoutePairOutcome(RouteOutcome walking, RouteOutcome transit, RouteOutcome selected,
                                   String preference, boolean taxiUnsupported) {
    }

    public record WeatherOutcome(OutcomeStatus status, AmapResponseNormalizer.WeatherResult weather,
                                 String reason, Instant fetchedAt) {
    }

    private static final class ProviderFailure extends RuntimeException {
        private final OutcomeStatus status;
        private final boolean retryable;
        private final boolean fallbackAllowed;

        private ProviderFailure(OutcomeStatus status, String message) {
            this(status, message, status == OutcomeStatus.TIMEOUT, status == OutcomeStatus.UNAVAILABLE);
        }

        private ProviderFailure(OutcomeStatus status, String message, boolean retryable) {
            this(status, message, retryable, status == OutcomeStatus.UNAVAILABLE);
        }

        private ProviderFailure(OutcomeStatus status, String message,
                                boolean retryable, boolean fallbackAllowed) {
            super(message == null || message.isBlank() ? "高德接口调用失败" : message);
            this.status = status;
            this.retryable = retryable;
            this.fallbackAllowed = fallbackAllowed;
        }
    }
}

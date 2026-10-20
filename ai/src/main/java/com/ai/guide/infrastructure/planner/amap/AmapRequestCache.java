package com.ai.guide.infrastructure.planner.amap;

import com.ai.guide.common.context.OperationDeadline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * 高德路线与地理编码两级请求缓存
 *
 * 架构定位：COLA 基础设施层 (Infrastructure Layer) - 高德 API 调用二级缓存与防抖并发抑制。
 */
public final class AmapRequestCache {

    public record RequestKey(String operation, List<String> dimensions) {
        public RequestKey {
            operation = normalize(operation);
            dimensions = dimensions == null
                    ? List.of()
                    : dimensions.stream().map(AmapRequestCache::normalize).toList();
        }
    }

    private record CacheEntry(Object value, long expiresAtNanos) {
    }

    private final int maxEntries;
    private final LongSupplier ticker;
    private final ConcurrentHashMap<RequestKey, CacheEntry> completed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RequestKey, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    public AmapRequestCache(int maxEntries) {
        this(maxEntries, System::nanoTime);
    }

    public AmapRequestCache(int maxEntries, LongSupplier ticker) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    public <T> T getOrLoad(RequestKey key,
                    long ttlNanos,
                    boolean cacheEnabled,
                    boolean dedupEnabled,
                    Supplier<T> loader,
                    Predicate<T> cacheable) {
        return getOrLoad(key, cacheEnabled, dedupEnabled, loader, result -> cacheable.test(result) ? ttlNanos : 0L);
    }

    public <T> T getOrLoad(RequestKey key, boolean cacheEnabled, boolean dedupEnabled,
                          Supplier<T> loader, ToLongFunction<T> resultTtl) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resultTtl, "resultTtl");

        if (cacheEnabled) {
            CacheEntry cached = completed.get(key);
            if (cached != null) {
                if (cached.expiresAtNanos() > ticker.getAsLong()) {
                    return value(cached.value());
                }
                completed.remove(key, cached);
            }
        }

        if (!dedupEnabled) {
            T result = loader.get();
            cacheResult(key, result, cacheEnabled, resultTtl);
            return result;
        }

        CompletableFuture<Object> ownerFuture = new CompletableFuture<>();
        CompletableFuture<Object> sharedFuture = inFlight.putIfAbsent(key, ownerFuture);
        if (sharedFuture != null) return join(sharedFuture);

        try {
            T result = loader.get();
            cacheResult(key, result, cacheEnabled, resultTtl);
            ownerFuture.complete(result);
            return result;
        } catch (Throwable failure) {
            ownerFuture.completeExceptionally(failure);
            throw propagate(failure);
        } finally {
            inFlight.remove(key, ownerFuture);
        }
    }
    private <T> void cacheResult(RequestKey key, T result, boolean enabled, ToLongFunction<T> resultTtl) {
        if (!enabled) return;
        long ttl = resultTtl.applyAsLong(result);
        if (ttl > 0) {
            long now = ticker.getAsLong();
            long expiry = now > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : now + ttl;
            put(key, result, expiry);
        }
    }

    private void put(RequestKey key, Object value, long expiresAtNanos) {
        synchronized (completed) {
            long now = ticker.getAsLong();
            completed.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos() <= now);
            if (completed.size() >= maxEntries && !completed.containsKey(key)) {
                RequestKey oldest = null;
                long earliest = Long.MAX_VALUE;
                for (var entry : completed.entrySet()) {
                    if (entry.getValue().expiresAtNanos() < earliest) {
                        oldest = entry.getKey();
                        earliest = entry.getValue().expiresAtNanos();
                    }
                }
                if (oldest != null) completed.remove(oldest);
            }
            completed.put(key, new CacheEntry(value, expiresAtNanos));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T value(Object value) {
        return (T) value;
    }

    private static <T> T join(CompletableFuture<Object> future) {
        try {
            return value(future.get(Math.min(OperationDeadline.remainingNanos(), TimeUnit.SECONDS.toNanos(7)), TimeUnit.NANOSECONDS));
        } catch (TimeoutException failure) {
            throw new RuntimeException("高德接口请求防抖等待超时", failure);
        } catch (ExecutionException failure) {
            throw propagate(failure.getCause() == null ? failure : failure.getCause());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("高德接口请求防抖等待被中断", failure);
        } catch (CompletionException failure) {
            throw propagate(failure.getCause() == null ? failure : failure.getCause());
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException(failure);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}

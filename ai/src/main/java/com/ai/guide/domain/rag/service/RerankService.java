package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.RerankProviderPort;
import com.ai.guide.domain.rag.gateway.RerankCacheInvalidationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 百炼重排 Service（带超时控制 + 三级缓存）
 * L1: 本地内存精确缓存（JVM 内，最快）
 * L2: 本地内存归一化缓存（同义措辞匹配）
 * L3: 本地内存语义缓存（SimHash 指纹匹配）
 */
@Slf4j
@Service
public class RerankService implements RerankCacheInvalidationPort {

    private final RerankProviderPort provider;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    // === 三级缓存 ===
    private final ConcurrentHashMap<String, CacheEntry> l1Cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> l2Cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CacheEntry> l3Cache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_SECONDS = 1800; // 30 分钟
    private static final int MAX_CACHE_SIZE = 500;

    // === 缓存统计 ===
    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong l3Hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong apiCalls = new AtomicLong(0);

    public RerankService(@org.springframework.beans.factory.annotation.Qualifier("rerankExecutor") ExecutorService executor,
                         RerankProviderPort provider,
                         ObjectMapper objectMapper) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    // ==================== 核心入口 ====================

    public List<String> rerank(String query, List<String> documents, int topN, long timeoutMs, boolean useSemanticCache) {
        if (documents == null || documents.isEmpty()) {
            log.warn("【Rerank】候选文档为空，跳过重排");
            return Collections.emptyList();
        }

        topN = Math.max(1, Math.min(topN, documents.size()));
        String candidateScope;
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(documents);
            candidateScope = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception invalid) { throw new IllegalArgumentException("无法计算重排候选指纹", invalid); }
        String cacheKey = candidateScope + "|" + query + "|" + topN;

        // L1: 精确匹配
        CacheEntry l1Hit = l1Cache.get(cacheKey);
        if (l1Hit != null && !isExpired(l1Hit) && documents.containsAll(l1Hit.result)) {
            l1Hits.incrementAndGet();
            return new ArrayList<>(l1Hit.result);
        }

        // L2/L3 语义缓存（深度模式跳过，保证重排质量）
        if (useSemanticCache) {
            String normalized = QueryNormalizer.normalize(query);
            if (!normalized.isEmpty()) {
                CacheEntry l2Hit = l2Cache.get(candidateScope + "|" + normalized + "|" + topN);
                if (l2Hit != null && !isExpired(l2Hit) && documents.containsAll(l2Hit.result)) {
                    l2Hits.incrementAndGet();
                    putL1(cacheKey, l2Hit.result);
                    return new ArrayList<>(l2Hit.result);
                }
            }

            long fingerprint = SimHash.compute(query + "|" + topN);
            for (Map.Entry<Long, CacheEntry> entry : l3Cache.entrySet()) {
                if (SimHash.isSimilar(fingerprint, entry.getKey())) {
                    CacheEntry l3Hit = entry.getValue();
                    if (!isExpired(l3Hit) && candidateScope.equals(l3Hit.candidateScope) && documents.containsAll(l3Hit.result)) {
                        l3Hits.incrementAndGet();
                        putL1(cacheKey, l3Hit.result);
                        String normL2 = QueryNormalizer.normalize(query);
                        if (!normL2.isEmpty()) putL2(candidateScope + "|" + normL2 + "|" + topN, l3Hit.result);
                        return new ArrayList<>(l3Hit.result);
                    }
                }
            }
        }

        // 全部未命中 → 调用百炼 API
        misses.incrementAndGet();
        RerankOutcome outcome = callApiWithTimeout(query, documents, topN, timeoutMs);
        List<String> result = outcome.documents();

        if (outcome.successful() && result != null && !result.isEmpty()) {
            putL1(cacheKey, result);
            if (useSemanticCache) {
                String normL2 = QueryNormalizer.normalize(query);
                if (!normL2.isEmpty()) putL2(candidateScope + "|" + normL2 + "|" + topN, result);
                long fingerprint = SimHash.compute(query + "|" + topN);
                putL3(fingerprint, result, candidateScope);
            }
        }

        return result;
    }

    public List<String> rerank(String query, List<String> documents, int topN) {
        return rerank(query, documents, topN, 1500, true);
    }

    public List<String> rerank(String query, List<String> documents, int topN, long timeoutMs) {
        return rerank(query, documents, topN, timeoutMs, true);
    }

    /** 深度模式（跳过 L2/L3 语义缓存，保证重排质量） */
    public List<String> rerankDeep(String query, List<String> documents, int topN, long timeoutMs) {
        return rerank(query, documents, topN, timeoutMs, false);
    }


    private boolean isExpired(CacheEntry entry) {
        return (System.currentTimeMillis() - entry.timestamp) > (CACHE_TTL_SECONDS * 1000);
    }

    private void putL1(String key, List<String> result) {
        evictIfNeeded(l1Cache);
        l1Cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
    }

    private void putL2(String key, List<String> result) {
        evictIfNeeded(l2Cache);
        l2Cache.put(key, new CacheEntry(result, System.currentTimeMillis()));
    }

    private void putL3(long fingerprint, List<String> result, String candidateScope) {
        evictIfNeeded(l3Cache);
        l3Cache.put(fingerprint, new CacheEntry(result, System.currentTimeMillis(), candidateScope));
    }

    private <K> void evictIfNeeded(ConcurrentHashMap<K, CacheEntry> cache) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            List<Map.Entry<K, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
            entries.sort(Comparator.comparingLong(e -> e.getValue().timestamp));
            int removeCount = MAX_CACHE_SIZE / 5;
            for (int i = 0; i < removeCount; i++) {
                cache.remove(entries.get(i).getKey());
            }
        }
    }

    // ==================== API 调用 ====================

    private record RerankOutcome(List<String> documents, boolean successful) {}
    private RerankOutcome fallback(List<String> documents, int topN) {
        return new RerankOutcome(new ArrayList<>(documents.subList(0, Math.min(documents.size(), topN))), false);
    }
    private RerankOutcome callApiWithTimeout(String query, List<String> documents, int topN, long timeoutMs) {
        apiCalls.incrementAndGet();
        Future<RerankOutcome> future = null;
        try {
            future = executor.submit(() -> rerankApi(query, documents, topN));
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            log.warn("【Rerank】超时 {}ms，返回 Qdrant 原始排序兜底", timeoutMs);
            return fallback(documents, topN);
        } catch (InterruptedException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(documents, topN);
        } catch (Exception e) {
            log.error("【Rerank】异常: {}", e.getMessage());
            return fallback(documents, topN);
        }
    }

    private RerankOutcome rerankApi(String query, List<String> documents, int topN) {
        try {
            List<Integer> indices = provider.rerank(query, documents, topN);
            if (indices == null || indices.isEmpty()) return fallback(documents, topN);
            LinkedHashSet<String> reranked = new LinkedHashSet<>();
            for (Integer index : indices) {
                if (index == null || index < 0 || index >= documents.size()) return fallback(documents, topN);
                reranked.add(documents.get(index));
                if (reranked.size() == topN) break;
            }
            return reranked.isEmpty() ? fallback(documents, topN)
                    : new RerankOutcome(new ArrayList<>(reranked), true);
        } catch (Exception e) {
            log.error("【Rerank】provider 调用失败: {}", e.getMessage());
            return fallback(documents, topN);
        }
    }

    // ==================== 管理接口 ====================

    public void clearCache() {
        int l1 = l1Cache.size(), l2 = l2Cache.size(), l3 = l3Cache.size();
        l1Cache.clear();
        l2Cache.clear();
        l3Cache.clear();
        log.info("【Rerank 缓存】手动清空 -> L1:{}, L2:{}, L3:{}", l1, l2, l3);
    }

    @Override
    public void invalidateAll() {
        clearCache();
    }

    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("l1Size", l1Cache.size());
        stats.put("l2Size", l2Cache.size());
        stats.put("l3Size", l3Cache.size());
        stats.put("totalSize", l1Cache.size() + l2Cache.size() + l3Cache.size());
        stats.put("l1Hits", l1Hits.get());
        stats.put("l2Hits", l2Hits.get());
        stats.put("l3Hits", l3Hits.get());
        stats.put("totalHits", l1Hits.get() + l2Hits.get() + l3Hits.get());
        stats.put("misses", misses.get());
        stats.put("apiCalls", apiCalls.get());
        // 命中计数随 JVM 生命周期累计；缓存条目则按 30 分钟 TTL 独立过期，
        // 管理端必须分开呈现，不能将“仍有缓存条目”误解为“本次已有命中”。
        stats.put("metricScope", "JVM_STARTUP_CUMULATIVE");
        stats.put("cacheTtlMinutes", CACHE_TTL_SECONDS / 60);
        long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
        long total = totalHits + misses.get();
        stats.put("hitRate", total == 0 ? "0.0%" : String.format("%.1f%%", (totalHits * 100.0) / total));
        return stats;
    }

    private static class CacheEntry {
        final List<String> result;
        final long timestamp;
        final String candidateScope;

        CacheEntry(List<String> result, long timestamp) { this(result, timestamp, ""); }
        CacheEntry(List<String> result, long timestamp, String candidateScope) {
            this.candidateScope = candidateScope;
            this.result = new ArrayList<>(result);
            this.timestamp = timestamp;
        }
    }
}

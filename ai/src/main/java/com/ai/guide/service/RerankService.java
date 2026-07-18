package com.ai.guide.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestTemplate;

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
public class RerankService {

    // 使用 DashScope API Key（与 Embedding 共享同一个 key）
    @Setter
    @Value("${alibabacloud.dashscope.api-key:}")
    private String apiKey;

    @Setter
    @Value("${bailian.rerank-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}")
    private String rerankUrl;

    private final RestTemplate restTemplate;
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

    public RerankService(@Qualifier("rerankExecutor") ExecutorService executor) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.executor = executor;
    }

    // ==================== 核心入口 ====================

    public List<String> rerank(String query, List<String> documents, int topN, long timeoutMs, boolean useSemanticCache) {
        if (documents == null || documents.isEmpty()) {
            log.warn("【Rerank】候选文档为空，跳过重排");
            return Collections.emptyList();
        }

        String cacheKey = query + "|" + topN;

        // L1: 精确匹配
        CacheEntry l1Hit = l1Cache.get(cacheKey);
        if (l1Hit != null && !isExpired(l1Hit)) {
            l1Hits.incrementAndGet();
            return new ArrayList<>(l1Hit.result);
        }

        // L2/L3 语义缓存（深度模式跳过，保证重排质量）
        if (useSemanticCache) {
            String normalized = QueryNormalizer.normalize(query);
            if (!normalized.isEmpty()) {
                CacheEntry l2Hit = l2Cache.get(normalized + "|" + topN);
                if (l2Hit != null && !isExpired(l2Hit)) {
                    l2Hits.incrementAndGet();
                    putL1(cacheKey, l2Hit.result);
                    return new ArrayList<>(l2Hit.result);
                }
            }

            long fingerprint = SimHash.compute(query + "|" + topN);
            for (Map.Entry<Long, CacheEntry> entry : l3Cache.entrySet()) {
                if (SimHash.isSimilar(fingerprint, entry.getKey())) {
                    CacheEntry l3Hit = entry.getValue();
                    if (!isExpired(l3Hit)) {
                        l3Hits.incrementAndGet();
                        putL1(cacheKey, l3Hit.result);
                        String normL2 = QueryNormalizer.normalize(query);
                        if (!normL2.isEmpty()) putL2(normL2 + "|" + topN, l3Hit.result);
                        return new ArrayList<>(l3Hit.result);
                    }
                }
            }
        }

        // 全部未命中 → 调用百炼 API
        misses.incrementAndGet();
        List<String> result = callApiWithTimeout(query, documents, topN, timeoutMs);

        if (result != null && !result.isEmpty()) {
            putL1(cacheKey, result);
            if (useSemanticCache) {
                String normL2 = QueryNormalizer.normalize(query);
                if (!normL2.isEmpty()) putL2(normL2 + "|" + topN, result);
                long fingerprint = SimHash.compute(query + "|" + topN);
                putL3(fingerprint, result);
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

    private void putL3(long fingerprint, List<String> result) {
        evictIfNeeded(l3Cache);
        l3Cache.put(fingerprint, new CacheEntry(result, System.currentTimeMillis()));
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

    private List<String> callApiWithTimeout(String query, List<String> documents, int topN, long timeoutMs) {
        apiCalls.incrementAndGet();
        Future<List<String>> future = executor.submit(() -> rerankApi(query, documents, topN));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("【Rerank】超时 {}ms，返回 Qdrant 原始排序兜底", timeoutMs);
            return documents.subList(0, Math.min(documents.size(), topN));
        } catch (Exception e) {
            log.error("【Rerank】异常: {}", e.getMessage());
            return documents.subList(0, Math.min(documents.size(), topN));
        }
    }

    private List<String> rerankApi(String query, List<String> documents, int topN) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", topN);
        parameters.put("return_documents", true);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gte-rerank-v2");
        body.put("input", input);
        body.put("parameters", parameters);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            String response = restTemplate.postForObject(rerankUrl, request, String.class);
            if (response == null) {
                log.error("【Rerank】接口返回为空");
                return documents.subList(0, Math.min(documents.size(), topN));
            }
            JsonNode root = objectMapper.readTree(response);
            if (root == null || root.get("output") == null) {
                log.error("【Rerank】接口返回格式异常");
                return documents.subList(0, Math.min(documents.size(), topN));
            }
            JsonNode results = root.get("output").get("results");
            List<String> reranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                int index = results.get(i).get("index").asInt();
                if (index < documents.size()) reranked.add(documents.get(index));
            }
            return reranked;
        } catch (HttpStatusCodeException e) {
            log.error("【Rerank】HTTP 错误: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            return documents.subList(0, Math.min(documents.size(), topN));
        } catch (Exception e) {
            log.error("【Rerank】未知异常: {}", e.getMessage());
            return documents.subList(0, Math.min(documents.size(), topN));
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
        long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
        long total = totalHits + misses.get();
        stats.put("hitRate", total == 0 ? "0.0%" : String.format("%.1f%%", (totalHits * 100.0) / total));
        return stats;
    }

    private static class CacheEntry {
        final List<String> result;
        final long timestamp;

        CacheEntry(List<String> result, long timestamp) {
            this.result = new ArrayList<>(result);
            this.timestamp = timestamp;
        }
    }
}

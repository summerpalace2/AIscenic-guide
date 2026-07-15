package com.ai.guide.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Agentic RAG 并行检索服务
 * 
 * 核心职责：
 * 1. 接收子问题列表（来自 QueryDecompositionService）
 * 2. 并行检索每个子问题的知识碎片
 * 3. 交叉验证重排：多来源验证同一事实 -> 置信度高 -> 排前面
 * 4. 过滤纯结构性碎片（只保留包含实际数据的碎片）
 * 
 * 调用路径（Deep 模式）：
 *   ChatController -> QueryDecompositionService.decompose()
 *                 -> ParallelRagService.search(subQueries)
 *                 -> 返回合并后的上下文
 */
@Service
public class ParallelRagService {

    private static final Logger log = LoggerFactory.getLogger(ParallelRagService.class);

    @Autowired
    private ScenicDataImportService dataImportService;

    /** 并行检索的线程池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 每条子问题检索的 Qdrant limit */
    private static final int QDRANT_LIMIT = 25;

    /** 每条子问题 Rerank 后的 TopN */
    private static final int RERANK_TOPN = 15;

    /** 最终返回的最大碎片数 */
    private static final int FINAL_LIMIT = 12;

    /** Spring 容器销毁时关闭线程池，避免资源泄漏 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 并行检索多个子问题并交叉验证重排
     * 
     * @param subQueries 子问题列表
     * @return 合并后的上下文字符串（碎片间以 \n---\n 分隔）
     */
    public String search(List<String> subQueries) {
        if (subQueries == null || subQueries.isEmpty()) {
            return "";
        }

        long t0 = System.currentTimeMillis();

        // Step 1: 并行检索每个子问题
        List<Future<List<String>>> futures = new ArrayList<>();
        for (String subQuery : subQueries) {
            if (subQuery == null || subQuery.isBlank()) continue;
            futures.add(executor.submit(() ->
                dataImportService.searchFragmentsDeep(subQuery, QDRANT_LIMIT, RERANK_TOPN)
            ));
        }

        // Step 2: 收集所有子问题的检索结果，统计每个碎片的出现频次
        Map<String, Integer> fragmentCount = new HashMap<>();      // 频次
        Map<String, String> fragmentOriginal = new HashMap<>();    // 原始文本

        for (Future<List<String>> future : futures) {
            try {
                List<String> fragments = future.get(5, TimeUnit.SECONDS);
                for (String fragment : fragments) {
                    String normalized = normalize(fragment);
                    fragmentCount.merge(normalized, 1, Integer::sum);
                    fragmentOriginal.putIfAbsent(normalized, fragment);
                }
            } catch (Exception e) {
                log.warn("[ParallelRag] 子问题检索超时: {}", e.getMessage());
            }
        }

        if (fragmentCount.isEmpty()) {
            return "";
        }

        // Step 3: 交叉验证重排 -> 出现频次越高 = 置信度越高 = 排越前
        List<Map.Entry<String, Integer>> sorted = fragmentCount.entrySet().stream()
                .filter(e -> !isStructuralOnly(fragmentOriginal.get(e.getKey())))
                .sorted((a, b) -> {
                    int freqCompare = b.getValue().compareTo(a.getValue());  // 频次降序
                    if (freqCompare != 0) return freqCompare;
                    return a.getKey().length() - b.getKey().length();        // 长度升序（简洁优先）
                })
                .limit(FINAL_LIMIT)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[ParallelRag] 子问题数={}, 合并碎片数={}, 最终返回={}, 耗时={}ms",
                futures.size(), fragmentCount.size(), sorted.size(), elapsed);

        // Step 4: 返回原始文本（按置信度排序）
        return sorted.stream()
                .map(e -> fragmentOriginal.get(e.getKey()))
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 归一化碎片文本（用于去重计数）
     * 去除空白差异，保留核心内容用于比对
     */
    private String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 判断是否为纯结构性碎片（只保留结构标记，无实际数据）
     * 例如：只有表头行，没有实际景点数据
     */
    private boolean isStructuralOnly(String fragment) {
        if (fragment == null) return true;
        String trimmed = fragment.trim();
        if (trimmed.length() < 20) return true;

        // 以"表格头"为前缀的纯表头行
        if (trimmed.startsWith("【表格头】")) return true;
        if (trimmed.startsWith("【表格标题】")) return true;

        // Markdown 表头行（---|---）
        if (trimmed.matches("^\\|?[-\\s:|]+\\|?$")) return true;

        // 只有分隔符没有实质内容
        if (trimmed.matches("^[\\s\\-_|]+$")) return true;

        return false;
    }
}
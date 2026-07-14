package com.ai.guide.service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 查询归一化工具类
 * 将用户输入归一化为标准形式，用于 L2 缓存匹配。
 *
 * 原理：去除标点、分词、去停用词、排序去重 → 得到归一化 key
 * 例："灵山有什么好玩的？" 和 "有什么好玩的灵山" → 归一化后相同
 */
public class QueryNormalizer {

    /** 标点符号正则 */
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]+");
    /** 无意义停用词 */
    private static final Set<String> STOP_WORDS = new HashSet<>(List.of(
            "的", "了", "吗", "呢", "吧", "啊", "呀", "哦", "嗯", "哈",
            "么", "之", "与", "及", "或", "而", "但", "如", "果", "个",
            "一", "些", "下", "什", "怎", "少", "几", "哪", "是", "在",
            "有", "不", "也", "还", "都", "很", "更", "太", "非常"
    ));

    /**
     * 归一化查询文本
     * 1. 去除标点 → 2. 分词 → 3. 去停用词 → 4. 排序去重 → 5. 拼接
     */
    public static String normalize(String query) {
        if (query == null || query.isBlank()) return "";
        // 去除标点，转小写
        String cleaned = PUNCTUATION.matcher(query.toLowerCase()).replaceAll("").trim();
        if (cleaned.isEmpty()) return "";
        // 滑窗分词
        List<String> tokens = simpleTokenize(cleaned);
        // 去停用词 + 去重 + 排序 + 拼接
        return tokens.stream()
                .filter(t -> !STOP_WORDS.contains(t))
                .distinct()
                .sorted()
                .collect(Collectors.joining());
    }

    /**
     * 简易中文分词（滑窗法）
     * 2-4字滑窗 + 单字兜底，贪心优先长词
     */
    private static List<String> simpleTokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int len = text.length();
        boolean[] consumed = new boolean[len];
        for (int size = 4; size >= 2; size--) {
            for (int i = 0; i <= len - size; i++) {
 boolean free = true;
                for (int j = i; j < i + size; j++) {
                    if (consumed[j]) { free = false; break; }
                }
                if (!free) continue;
                tokens.add(text.substring(i, i + size));
                for (int j = i; j < i + size; j++) { consumed[j] = true; }
            }
        }
        for (int i = 0; i < len; i++) {
            if (!consumed[i]) tokens.add(String.valueOf(text.charAt(i)));
        }
        return tokens;
    }
}
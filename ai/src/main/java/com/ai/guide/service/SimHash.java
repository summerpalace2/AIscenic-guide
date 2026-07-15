package com.ai.guide.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * SimHash 语义指纹工具类
 *
 * 原理：将文本转化为 64-bit 指纹，语义相近的文本指纹 Hamming 距离小。
 * 用于 L3 缓存：用户问"旅游路线"和"旅行路线推荐"语义相似但字面不同，
 * 通过 Hamming 距离 ≤ 3 认为它们是同一个问题，命中缓存。
 *
 * 实现步骤：
 * 1. 分词得到 tokens
 * 2. 对每个 token 做 MD5 → 取前 16 hex → 64-bit hash
 * 3. 按位累加：该位是 1 则 +权重，是 0 则 -权重
 * 4. 结果向量正数位 → 1，负数位 → 0，得到 64-bit 指纹
 */
public class SimHash {

    /** 判定为"语义相同"的 Hamming 距离阈值 */
    public static final int HAMMING_THRESHOLD = 3;

    /**
     * 计算文本的 SimHash 64-bit 指纹
     */
    public static long compute(String text) {
        if (text == null || text.isBlank()) return 0L;
        // 简单分词（单字 + 2-gram）
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return 0L;
        // 向量累加
        int[] vector = new int[64];
        for (String token : tokens) {
            long hash = md5Partial64(token);
            for (int i = 0; i < 64; i++) {
                if (((hash >> i) & 1L) == 1L) {
                    vector[i] += 1;
                } else {
                    vector[i] -= 1;
                }
            }
        }
        // 正数位 → 1，负数位 → 0
        long fingerprint = 0L;
        for (int i = 0; i < 64; i++) {
            if (vector[i] >= 0) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
    }

    /**
     * 计算两个 64-bit 指纹的 Hamming 距离（不同位数）
     */
    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /**
     * 判断两个指纹是否"语义相同"（Hamming 距离 ≤ 3）
     */
    public static boolean isSimilar(long a, long b) {
        return hammingDistance(a, b) <= HAMMING_THRESHOLD;
    }

    /**
     * MD5 → 取前 16 hex chars → 64-bit long
     */
    private static long md5Partial64(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            long hash = 0L;
            // 取前 8 字节 → 64 bit
            for (int i = 0; i < 8; i++) {
                hash = (hash << 8) | (digest[i] & 0xFF);
            }
            return hash;
        } catch (NoSuchAlgorithmException e) {
            return input.hashCode() & 0xFFFFFFFFL;
        }
    }

    /**
     * 简易分词：单字 + 2-gram
     */
    private static List<String> tokenize(String text) {
        String cleaned = text.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase();
        List<String> tokens = new ArrayList<>();
        // 单字
        for (char c : cleaned.toCharArray()) {
            tokens.add(String.valueOf(c));
        }
        // 2-gram
        for (int i = 0; i < cleaned.length() - 1; i++) {
            tokens.add(cleaned.substring(i, i + 2));
        }
        return tokens;
    }
}
package com.ai.guide.domain.chat.service;

import java.util.regex.Pattern;

/** Explicit health and accessibility statements are retained apart from lossy summaries. */
public final class ChatContextBudgetPolicy {
    public static final int HISTORY_CHARS = 12_000;
    public static final int CRITICAL_UTF8_BYTES = 6_000;
    public static final int COMPRESSION_CHARS = 12_000;
    public static final int COMPRESSION_MESSAGES = 32;
    public static final int MODEL_HISTORY_MESSAGES = 24;
    private static final Pattern CRITICAL = Pattern.compile(
            "过敏|忌口|禁忌|不能吃|不能喝|禁止食用|不耐受|不耐症|轮椅|无障碍|行动不便|腿脚不便|拄拐|术后|心脏|哮喘|孕妇|怀孕|不能爬|不能走|不能上楼|" +
            "allerg|intoleran|wheelchair|accessib|cannot\\s+(?:eat|drink|walk|climb)|must\\s+avoid", Pattern.CASE_INSENSITIVE);
    private ChatContextBudgetPolicy() {}
    public static boolean isCritical(String text) { return text != null && CRITICAL.matcher(text).find(); }
    public static String boundedFact(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
        return clean.length() <= max ? clean : clean.substring(0, max) + "（资料已截短）";
    }
    public static final class ContextBudgetException extends RuntimeException {
        public ContextBudgetException(String message) { super(message); }
    }
    public static final class ContextReadException extends RuntimeException {
        public ContextReadException() { super("会话历史与安全限制暂时无法读取，请稍后重试；本次未生成新的建议，已有记录仍保留。"); }
    }
}

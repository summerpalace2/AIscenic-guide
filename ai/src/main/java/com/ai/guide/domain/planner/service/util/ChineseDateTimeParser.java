package com.ai.guide.domain.planner.service.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文与阿拉伯自然语言时间、时刻、钟点解析工具类
 */
public final class ChineseDateTimeParser {

    private ChineseDateTimeParser() {}

    /**
     * 解析中文数字或阿拉伯数字的小时数（如 "三" -> 3, "10" -> 10, "十一" -> 11）
     */
    public static int parseChineseOrArabicHour(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {}
        return switch (s.trim()) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            default -> -1;
        };
    }

    /**
     * 从自然语言文本中提取明确的时钟分钟数（0 ~ 1440）
     * 支持格式：
     * - "14:30", "9：00"
     * - "下午3点", "下午3点半", "晚上8点45分", "早上九点一刻"
     */
    public static Integer extractExplicitTimeMinutes(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher m1 = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(text);
        if (m1.find()) {
            int h = Integer.parseInt(m1.group(1));
            int m = Integer.parseInt(m1.group(2));
            if (h >= 0 && h <= 24 && m >= 0 && m < 60) {
                return h * 60 + m;
            }
        }

        Matcher m2 = Pattern.compile("(晚上|夜间|今晚|下午|上午|早上|早晨)?\\s*(十[一二]|[一二三四五六七八九十]|\\d{1,2})\\s*(?:点|时)(?:整)?\\s*(半|三刻|一刻|\\d{1,2}分)?").matcher(text);
        if (m2.find()) {
            String period = m2.group(1);
            String hourStr = m2.group(2);
            String minStr = m2.group(3);
            int hour = parseChineseOrArabicHour(hourStr);
            if (hour >= 0) {
                if ("晚上".equals(period) || "夜间".equals(period) || "今晚".equals(period) || "下午".equals(period)) {
                    if (hour < 12) {
                        hour += 12;
                    }
                }
                int minute = 0;
                if ("半".equals(minStr)) {
                    minute = 30;
                } else if ("一刻".equals(minStr)) {
                    minute = 15;
                } else if ("三刻".equals(minStr)) {
                    minute = 45;
                } else if (minStr != null && minStr.endsWith("分")) {
                    try {
                        minute = Integer.parseInt(minStr.replace("分", ""));
                    } catch (Exception ignored) {}
                }
                return hour * 60 + minute;
            }
        }
        return null;
    }
}

package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.TravelConstraints;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户出行日期解析与默认规则处理器
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：统一解析 Prompt / 约束中的具体日期（ISO格式）、中文月日、相对日期（今天/明天/后天）及星期；
 * 对于未明确指定日期的短途规划（如“我在重庆交通大学 为我规划一个3小时旅游方案”）或缺省需求，
 * 默认以当天 (LocalDate.now()) 作为第1天，以保证高德实时与预报天气查询能够安全匹配。
 */
public final class TravelDateResolver {

    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern CHINESE_MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})[日号]?");
    private static final Pattern WEEKDAY = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern TODAY = Pattern.compile("(?:今天|今日|现在|即刻|当天)");
    private static final Pattern TOMORROW = Pattern.compile("(?:明天|次日)");
    private static final Pattern DAY_AFTER_TOMORROW = Pattern.compile("后天");
    private static final Pattern DAY_AFTER_NEXT = Pattern.compile("大后天");

    private TravelDateResolver() {}

    /**
     * 解析指定行程天数（1-based）对应的 ISO 日期字符串（YYYY-MM-DD）。
     *
     * @param constraints 用户出行约束（可为 null）
     * @param day 行程天数索引，从 1 开始
     * @return ISO 日期字符串，当未指定日期时默认以今天作为第1天进行递推
     */
    public static String resolveItineraryDate(TravelConstraints constraints, int day) {
        int dayOffset = Math.max(0, day - 1);
        LocalDate today = LocalDate.now();
        if (constraints == null) return today.plusDays(dayOffset).toString();
        LocalDate arrival = parseDateEvidence(constraints.getArrivalAt(), today);
        if (arrival != null) return arrival.plusDays(dayOffset).toString();
        LocalDate departure = parseDateEvidence(constraints.getDepartureAt(), today);
        if (departure != null) return departure.minusDays(Math.max(1, constraints.getDurationDays()) - 1L)
                .plusDays(dayOffset).toString();
        LocalDate prompt = parseDateEvidence(constraints.getRawPrompt(), today);
        return (prompt == null ? today : prompt).plusDays(dayOffset).toString();
    }

    private static LocalDate parseDateEvidence(String source, LocalDate today) {
        if (source == null || source.isBlank()) return null;
        Matcher iso = ISO_DATE.matcher(source);
        if (iso.find()) {
            try { return LocalDate.parse(iso.group(1), DateTimeFormatter.ISO_LOCAL_DATE); }
            catch (DateTimeParseException ignored) {}
        }
        Matcher md = CHINESE_MONTH_DAY.matcher(source);
        if (md.find()) {
            try { return LocalDate.of(today.getYear(), Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2))); }
            catch (RuntimeException ignored) {}
        }
        // Longest relative date wins; "后天" is contained in "大后天".
        if (DAY_AFTER_NEXT.matcher(source).find()) return today.plusDays(3);
        if (DAY_AFTER_TOMORROW.matcher(source).find()) return today.plusDays(2);
        if (TOMORROW.matcher(source).find()) return today.plusDays(1);
        if (TODAY.matcher(source).find()) return today;
        Matcher weekday = WEEKDAY.matcher(source);
        if (weekday.find()) {
            int index = "一二三四五六日".indexOf(weekday.group(1));
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(index < 0 ? 7 : index + 1)));
        }
        return null;
    }

    /**
     * 判断当前约束或 Prompt 是否包含了显式的日期或星期描述。
     */
    public static boolean hasExplicitDate(TravelConstraints constraints) {
        if (constraints == null) return false;
        String source = String.join(" ",
                constraints.getArrivalAt() == null ? "" : constraints.getArrivalAt(),
                constraints.getRawPrompt() == null ? "" : constraints.getRawPrompt(),
                constraints.getDepartureAt() == null ? "" : constraints.getDepartureAt());
        return ISO_DATE.matcher(source).find()
                || CHINESE_MONTH_DAY.matcher(source).find()
                || TODAY.matcher(source).find()
                || TOMORROW.matcher(source).find()
                || DAY_AFTER_TOMORROW.matcher(source).find()
                || DAY_AFTER_NEXT.matcher(source).find()
                || WEEKDAY.matcher(source).find();
    }
}

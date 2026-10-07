package com.ai.guide.domain.planner.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程时间轴计算与多米诺骨牌级联顺延引擎
 */
@Component
public class ItineraryTimeEngine {

    /**
     * 解析时间字符串为当日分钟数（如 "14:30" -> 870）
     */
    public int parseTimeMinutes(String timeStr) {
        Integer parsed = parseExplicitTimeMinutes(timeStr);
        return parsed == null ? 720 : parsed;
    }

    /** Null means a clock was not supplied; callers must not treat the noon fallback as evidence. */
    public Integer parseExplicitTimeMinutes(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
        try {
            Matcher m = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(timeStr);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                if (h > 23 || min > 59) return null;
                Matcher offset = Pattern.compile("\\(\\+(\\d+)d\\)").matcher(timeStr);
                int days = offset.find() ? Integer.parseInt(offset.group(1)) : 0;
                return Math.addExact(Math.multiplyExact(days, 1440), h * 60 + min);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 解析游玩或就餐停留时长（分钟）
     */
    public int parseDurationMinutes(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) return 50;
        try {
            String normalized = durationStr.toLowerCase(Locale.ROOT)
                    .replace("一个半小时", "1.5小时")
                    .replaceAll("(\\d+(?:\\.\\d+)?)\\s*小时半", "$1小时30分钟")
                    .replace("半小时", "0.5小时");
            normalized = normalized.replaceAll("(\\d+(?:\\.\\d+)?)\\s*[-–~～至到]\\s*(\\d+(?:\\.\\d+)?)\\s*(小时|hours?|h|分钟|minutes?|mins?)", "$2$3");
            Matcher units = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|hours?|h|分钟|minutes?|mins?)").matcher(normalized);
            double total = 0;
            boolean found = false;
            while (units.find()) {
                found = true;
                double value = Double.parseDouble(units.group(1));
                String unit = units.group(2);
                total += value * (unit.equals("小时") || unit.startsWith("h") ? 60 : 1);
            }
            if (found && Double.isFinite(total) && total <= Integer.MAX_VALUE) return (int) Math.ceil(total);
            if (normalized.matches("\\d+")) return Integer.parseInt(normalized);
        } catch (Exception ignored) {}
        return 50;
    }

    /**
     * 格式化分钟数为 "HH:mm" 格式
     */
    public String formatTime(int totalMinutes) {
        if (totalMinutes >= 24 * 60) {
            int overDays = totalMinutes / (24 * 60);
            int rem = totalMinutes % (24 * 60);
            return String.format(Locale.ROOT, "%02d:%02d(+%dd)", rem / 60, rem % 60, overDays);
        }
        int norm = ((totalMinutes % (24 * 60)) + (24 * 60)) % (24 * 60);
        int h = norm / 60;
        int m = norm % 60;
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    /**
     * 级联推迟后序站点时间（Ripple Timing Recalculation），彻底消除时钟倒流与时间重叠。
     * 从 startIndex 开始，依据前序结束时间和已解析的路线耗时顺延；无路线数据时使用明确的估算回退。
     */
    public void rippleRecalculateStopTimes(List<Map<String, Object>> stops, int startIndex) {
        if (stops == null || startIndex <= 0 || startIndex >= stops.size()) {
            return;
        }
        // This is a finite ordered list, not a graph: repeated venue/stop IDs cannot form a loop.
        for (int i = startIndex; i < stops.size(); i++) {
            Map<String, Object> prev = stops.get(i - 1);
            Map<String, Object> curr = stops.get(i);
            String prevTimeStr = String.valueOf(prev.getOrDefault("time", ""));
            if (prevTimeStr.isBlank()) {
                prevTimeStr = String.valueOf(prev.getOrDefault("startTime", ""));
            }
            int prevStart = parseTimeMinutes(prevTimeStr);
            int prevDur = parseDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
            if (prevDur <= 0) prevDur = 50;
            int nextStart = prevStart + prevDur + transferMinutes(prev, curr);
            String newSlotTime = formatTime(nextStart);
            curr.put("time", newSlotTime);
            curr.put("startTime", newSlotTime);
        }
    }

    public int transferMinutes(Map<String, Object> previous, Map<String, Object> current) {
        String previousVenue = String.valueOf(previous.getOrDefault("venueId", ""));
        if (!previousVenue.isBlank() && !"null".equals(previousVenue) && previousVenue.equals(String.valueOf(current.getOrDefault("venueId", "")))) return 0;
        for (String key : List.of("resolvedRouteCost", "estimatedRouteCost")) {
            if (current.get(key) instanceof Map<?, ?> map && !"UNAVAILABLE".equals(map.get("routeDataStatus"))) {
                if (map.get("durationSeconds") instanceof Number seconds)
                    return (int) Math.ceil(Math.max(0, seconds.doubleValue()) / 60.0);
                if (map.get("durationMinutes") instanceof Number minutes)
                    return (int) Math.ceil(Math.max(0, minutes.doubleValue()));
            }
        }
        if (current.get("routeFromPrevious") instanceof Map<?, ?> route && route.get("selected") instanceof Map<?, ?> selected) {
            Object seconds = selected.get("durationSeconds") != null ? selected.get("durationSeconds") : selected.get("duration");
            if (seconds instanceof Number n) return (int) Math.ceil(Math.max(0, n.doubleValue()) / 60);
        }
        // Explicit fallback for unresolved edges; zero-cost verified routes are retained above.
        return 15;
    }

    public boolean isEveningSlot(String time) {
        if (time == null) return false;
        Integer minute = parseExplicitTimeMinutes(time);
        if (minute != null) return Math.floorMod(minute, 1440) >= 18 * 60;
        return time.contains("18:") || time.contains("19:") || time.contains("20:")
                || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    public boolean isMorningSlot(String time) {
        if (time == null) return false;
        Integer minute = parseExplicitTimeMinutes(time);
        if (minute != null) return Math.floorMod(minute, 1440) < 12 * 60;
        return time.contains("07:") || time.contains("08:") || time.contains("09:")
                || time.contains("早上") || time.contains("早晨") || time.contains("晨间");
    }
}

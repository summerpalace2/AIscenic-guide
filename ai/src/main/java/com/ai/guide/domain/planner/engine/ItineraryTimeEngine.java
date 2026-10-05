package com.ai.guide.domain.planner.engine;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        if (timeStr == null || timeStr.isBlank()) return 720;
        try {
            Matcher m = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(timeStr);
            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                return h * 60 + min;
            }
        } catch (Exception ignored) {}
        return 720;
    }

    /**
     * 解析游玩或就餐停留时长（分钟）
     */
    public int parseDurationMinutes(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) return 50;
        try {
            Matcher m = Pattern.compile("(\\d+)").matcher(durationStr);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
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
     * 从 startIndex 开始，依据前序站点的结束时间 + 缓冲时长（默认 15 分钟），依次顺延调整后续站点的 time 与 startTime。
     */
    public void rippleRecalculateStopTimes(List<Map<String, Object>> stops, int startIndex) {
        if (stops == null || startIndex <= 0 || startIndex >= stops.size()) {
            return;
        }
        int maxIterations = Math.min(stops.size(), 50);
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < startIndex; i++) {
            String sId = String.valueOf(stops.get(i).getOrDefault("id", ""));
            if (!sId.isBlank()) seenIds.add(sId);
        }
        for (int i = startIndex; i < stops.size() && i < maxIterations; i++) {
            Map<String, Object> prev = stops.get(i - 1);
            Map<String, Object> curr = stops.get(i);
            String currId = String.valueOf(curr.getOrDefault("id", ""));
            if (!currId.isBlank() && !seenIds.add(currId)) {
                // 侦测到环形重复节点，截断顺延防止拓扑死锁循环
                break;
            }
            String prevTimeStr = String.valueOf(prev.getOrDefault("time", ""));
            if (prevTimeStr.isBlank()) {
                prevTimeStr = String.valueOf(prev.getOrDefault("startTime", ""));
            }
            int prevStart = parseTimeMinutes(prevTimeStr);
            int prevDur = parseDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
            if (prevDur <= 0) prevDur = 50;
            int nextStart = prevStart + prevDur + 15;
            String newSlotTime = formatTime(nextStart);
            curr.put("time", newSlotTime);
            curr.put("startTime", newSlotTime);
        }
    }

    public boolean isEveningSlot(String time) {
        if (time == null) return false;
        return time.contains("18:") || time.contains("19:") || time.contains("20:")
                || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    public boolean isMorningSlot(String time) {
        if (time == null) return false;
        return time.contains("07:") || time.contains("08:") || time.contains("09:")
                || time.contains("早上") || time.contains("早晨") || time.contains("晨间");
    }
}

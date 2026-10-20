package com.ai.guide.domain.planner.engine;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;

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
    private volatile int unknownTransferEstimateMinutes = -1;

    /** Install the configured conservative estimate used only when an edge has no route evidence. */
    public void setUnknownTransferEstimateMinutes(int minutes) {
        unknownTransferEstimateMinutes = minutes > 0 ? minutes : -1;
    }

    @Autowired(required = false)
    public void configureUnknownTransferEstimate(DynamicShortTripPolicyPort policyPort) {
        if (policyPort != null && policyPort.getPolicy() != null) {
            setUnknownTransferEstimateMinutes(policyPort.getPolicy().unknownTravelDurationMinutes());
        }
    }

    /**
     * 解析时间字符串为当日分钟数（如 "14:30" -> 870）
     */
    public int parseTimeMinutes(String timeStr) {
        Integer parsed = parseExplicitTimeMinutes(timeStr);
        if (parsed == null) throw new IllegalArgumentException("A valid explicit clock time is required");
        return parsed;
    }

    /** Null means a clock was not supplied; callers must not treat an invented hour as evidence. */
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
        return parseKnownDurationMinutes(durationStr);
    }

    /** Parses only duration evidence; zero means the input did not contain a usable value. */
    public int parseKnownDurationMinutes(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) return 0;
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
        return 0;
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
            Integer prevStart = parseExplicitTimeMinutes(prevTimeStr);
            int prevDur = parseKnownDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
            if (prevDur <= 0 || prevStart == null) break;
            int transfer = transferMinutes(prev, curr);
            if (transfer < 0) break;
            int nextStart = prevStart + prevDur + transfer;
            String newSlotTime = formatTime(nextStart);
            curr.put("time", newSlotTime);
            curr.put("startTime", newSlotTime);
        }
    }

    /** Recalculates only from explicit schedule facts; unknown time/duration stops the cascade. */
    public void rippleRecalculateStopTimesFromExplicitFacts(List<Map<String, Object>> stops, int startIndex) {
        recalculateFromExplicitFacts(stops, startIndex, false);
    }

    /** Delays only conflicts introduced by an edited edge; existing free gaps and earlier stops remain intact. */
    public List<String> delayConflictingStopTimesFromExplicitFacts(List<Map<String, Object>> stops, int startIndex) {
        return recalculateFromExplicitFacts(stops, startIndex, true);
    }

    private List<String> recalculateFromExplicitFacts(List<Map<String, Object>> stops, int startIndex, boolean onlyDelay) {
        var changed = new java.util.ArrayList<String>();
        if (stops == null || startIndex <= 0 || startIndex >= stops.size()) return changed;
        for (int i = startIndex; i < stops.size(); i++) {
            Map<String, Object> previous = stops.get(i - 1);
            Map<String, Object> current = stops.get(i);
            Integer start = parseExplicitTimeMinutes(explicitClock(previous));
            int duration = parseKnownDurationMinutes(String.valueOf(previous.getOrDefault("duration", "")));
            if (start == null || duration <= 0) break;
            int transfer = transferMinutes(previous, current);
            if (transfer < 0) break;
            long minimum = (long) start + duration + transfer;
            if (minimum > Integer.MAX_VALUE) break;
            Integer existing = parseExplicitTimeMinutes(explicitClock(current));
            if (onlyDelay && existing != null && existing >= minimum) continue;
            String next = formatTime((int) minimum);
            if (!next.equals(explicitClock(current))) changed.add(String.valueOf(current.getOrDefault("id", "")));
            current.put("time", next);
            current.put("startTime", next);
        }
        return changed;
    }

    /** A placeholder in one compatibility field must not hide an explicit clock in the other. */
    public String explicitClock(Map<String, Object> stop) {
        Object raw = stop.get("time");
        String value = raw == null ? "" : String.valueOf(raw);
        if (parseExplicitTimeMinutes(value) != null) return value;
        Object alternate = stop.get("startTime");
        String fallback = alternate == null ? "" : String.valueOf(alternate);
        if (parseExplicitTimeMinutes(fallback) != null) return fallback;
        return value.isBlank() ? fallback : value;
    }

    public int transferMinutes(Map<String, Object> previous, Map<String, Object> current) {
        int known = explicitTransferMinutes(previous, current);
        return known >= 0 ? known : unknownTransferEstimateMinutes;
    }

    /** Makes the configured conservative reserve visible; it is never labelled as a verified route. */
    public void materializeConfiguredTransferEstimates(List<Map<String, Object>> stops, int startIndex) {
        if (stops == null || unknownTransferEstimateMinutes <= 0) return;
        for (int i = Math.max(1, startIndex); i < stops.size(); i++) {
            var previous = stops.get(i - 1);
            var current = stops.get(i);
            if (explicitTransferMinutes(previous, current) >= 0) continue;
            current.put("estimatedRouteCost", Map.of("durationMinutes", unknownTransferEstimateMinutes,
                    "durationSeconds", unknownTransferEstimateMinutes * 60L,
                    "routeDataStatus", "ESTIMATED", "status", "ESTIMATED", "source", "CONFIGURED_UNKNOWN_TRANSFER",
                    "reason", "未取得接驳耗时，采用明确配置的保守预留；不是实时路线证据"));
            current.put("routeDataStatus", "ESTIMATED");
        }
    }

    private int explicitTransferMinutes(Map<String, Object> previous, Map<String, Object> current) {
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
        return -1;
    }

    public boolean isEveningSlot(String time, PlannerTaxonomyPort taxonomy) {
        return matchesConfiguredWindow(time, taxonomy, List.of("EVENING", "NIGHT"));
    }

    public boolean isMorningSlot(String time, PlannerTaxonomyPort taxonomy) {
        return matchesConfiguredWindow(time, taxonomy, List.of("MORNING"));
    }

    private boolean matchesConfiguredWindow(String time, PlannerTaxonomyPort taxonomy, List<String> windowKeys) {
        if (time == null || time.isBlank() || taxonomy == null) return false;
        Integer minute = parseExplicitTimeMinutes(time);
        for (TaxonomyPort.AdjustmentWindow window : taxonomy.getAdjustmentWindows()) {
            if (!windowKeys.contains(window.key())) continue;
            if (minute != null && Math.floorMod(minute, 1440) >= window.startMinute()
                    && Math.floorMod(minute, 1440) < window.endMinute()) return true;
            if (minute == null && window.aliases().stream().anyMatch(time::contains)) return true;
        }
        return false;
    }
}

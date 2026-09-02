package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程规划约束完整性核验器
 *
 * 所属领域：domain.planner.engine（规划引擎核心算法层）
 * 架构职责：校验多天行程是否存在硬约束冲突（如开闭园时段不合、雨天室外活动、跨江过桥交通超时等）。
 */
@Service
public class PlanVerifier {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)\\s*分钟");

    public record PlanViolation(
            String type,
            int dayNumber,
            String stopId,
            String venueId,
            String message,
            boolean isHardConstraint
    ) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("dayNumber", dayNumber);
            map.put("stopId", stopId == null ? "" : stopId);
            map.put("venueId", venueId == null ? "" : venueId);
            map.put("message", message);
            map.put("isHardConstraint", isHardConstraint);
            return map;
        }
    }

    public record VerificationResult(
            boolean feasible,
            List<PlanViolation> violations,
            Map<String, Object> metrics
    ) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("feasible", feasible);
            map.put("violations", violations.stream().map(PlanViolation::asMap).toList());
            map.put("metrics", metrics);
            return map;
        }
    }

    public VerificationResult verify(List<Map<String, Object>> days, TravelConstraints constraints) {
        List<PlanViolation> violations = new ArrayList<>();
        if (days == null || days.isEmpty()) {
            return new VerificationResult(true, List.of(), Map.of("totalStops", 0, "verified", true));
        }

        int totalStops = 0;
        int totalDays = days.size();

        for (int i = 0; i < days.size(); i++) {
            Map<String, Object> day = days.get(i);
            int dayNumber = i + 1;
            String dayDate = safeText(day.get("date"));
            boolean isMonday = dayDate.contains("一") || dayDate.contains("Monday") || isDateMonday(dayDate);

            Object stopsObj = day.get("stops");
            if (!(stopsObj instanceof List<?> stopList)) continue;

            List<Map<String, Object>> stops = new ArrayList<>();
            for (Object item : stopList) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> stopMap = new LinkedHashMap<>();
                    m.forEach((k, v) -> stopMap.put(String.valueOf(k), v));
                    stops.add(stopMap);
                }
            }
            totalStops += stops.size();

            // Check 1: Day 1 Arrival constraint (GS-04)
            if (dayNumber == 1) {
                String arrival = constraints == null ? "" : constraints.getArrivalAt();
                if (arrival.contains("下午") || arrival.contains("晚上") || arrival.contains("夜间")) {
                    for (Map<String, Object> stop : stops) {
                        String time = safeText(stop.get("time"));
                        if (isMorningSlot(time)) {
                            violations.add(new PlanViolation(
                                    "ARRIVAL_WINDOW_CONFLICT",
                                    dayNumber,
                                    safeText(stop.get("id")),
                                    safeText(stop.get("venueId")),
                                    "抵达时间为“" + arrival + "”，第一天不能生成上午时段行程（" + time + "）。",
                                    true
                            ));
                        }
                    }
                }
                if (arrival.contains("晚上") || arrival.contains("夜间")) {
                    for (Map<String, Object> stop : stops) {
                        String time = safeText(stop.get("time"));
                        if (isAfternoonSlot(time)) {
                            violations.add(new PlanViolation(
                                    "ARRIVAL_WINDOW_CONFLICT",
                                    dayNumber,
                                    safeText(stop.get("id")),
                                    safeText(stop.get("venueId")),
                                    "抵达时间为“" + arrival + "”，第一天不能生成下午时段行程（" + time + "）。",
                                    true
                            ));
                        }
                    }
                }
            }

            // Check 2: Final Day Departure deadline
            if (dayNumber == totalDays) {
                String departure = constraints == null ? "" : constraints.getDepartureAt();
                if (departure.contains("上午") || departure.contains("早晨")) {
                    for (Map<String, Object> stop : stops) {
                        String time = safeText(stop.get("time"));
                        if (isAfternoonSlot(time) || isEveningSlot(time)) {
                            violations.add(new PlanViolation(
                                    "DEPARTURE_DEADLINE_CONFLICT",
                                    dayNumber,
                                    safeText(stop.get("id")),
                                    safeText(stop.get("venueId")),
                                    "返程时间为“" + departure + "”，最后一天下午与夜间无法继续游览（" + time + "）。",
                                    true
                            ));
                        }
                    }
                } else if (departure.contains("下午") || departure.contains("中午")) {
                    for (Map<String, Object> stop : stops) {
                        String time = safeText(stop.get("time"));
                        if (isEveningSlot(time)) {
                            violations.add(new PlanViolation(
                                    "DEPARTURE_DEADLINE_CONFLICT",
                                    dayNumber,
                                    safeText(stop.get("id")),
                                    safeText(stop.get("venueId")),
                                    "返程时间为“" + departure + "”，最后一天晚间无法安排游览（" + time + "）。",
                                    true
                            ));
                        }
                    }
                }
            }

            // Check 3: Opening Hours & Closed Days (GS-03)
            for (Map<String, Object> stop : stops) {
                String bestTime = safeText(stop.get("bestTime"));
                String time = safeText(stop.get("time"));
                String venueId = safeText(stop.get("venueId"));
                String name = safeText(stop.get("name"));

                if (isMonday && (bestTime.contains("周一闭馆") || bestTime.contains("周一休息"))) {
                    violations.add(new PlanViolation(
                            "CLOSED_ON_MONDAY",
                            dayNumber,
                            safeText(stop.get("id")),
                            venueId,
                            "景点" + name + "已知周一闭馆，排入周一行程不可行。",
                            true
                    ));
                }

                if (bestTime.contains("17:00") && isEveningSlot(time)) {
                    violations.add(new PlanViolation(
                            "CLOSED_AT_SLOT_TIME",
                            dayNumber,
                            safeText(stop.get("id")),
                            venueId,
                            "景点" + name + "已知开放时间为" + bestTime + "，排入夜间时段（" + time + "）不可行。",
                            true
                    ));
                }
            }

            // Check 4: Cross-district extreme bounce in same half-day (GS-01)
            for (int j = 1; j < stops.size(); j++) {
                String prevDist = safeText(stops.get(j - 1).get("district"));
                String currDist = safeText(stops.get(j).get("district"));
                boolean isPrevSuburban = isDistantSuburban(prevDist);
                boolean isCurrSuburban = isDistantSuburban(currDist);

                if (isPrevSuburban != isCurrSuburban) {
                    violations.add(new PlanViolation(
                            "CROSS_DISTRICT_INVIABLE",
                            dayNumber,
                            safeText(stops.get(j).get("id")),
                            safeText(stops.get(j).get("venueId")),
                            "景点" + stops.get(j - 1).get("name") + "（" + prevDist + "）与" + stops.get(j).get("name") + "（" + currDist + "）相距远且交通耗时极长，在同一日紧凑切换不可行。",
                            true
                    ));
                }
            }

            // Check 5: Route timing & Day schedule budget check (Real/Estimated route feedback)
            int totalDayMinutes = 0;
            for (int j = 0; j < stops.size(); j++) {
                Map<String, Object> currStop = stops.get(j);
                int visitMinutes = parseDurationMinutes(currStop.get("duration"));
                totalDayMinutes += visitMinutes;

                if (j > 0) {
                    Map<String, Object> prevStop = stops.get(j - 1);
                    RouteCost cost = extractRouteCost(currStop);
                    int travelMinutes = cost != null ? (int) cost.duration().toMinutes() : 15;
                    totalDayMinutes += travelMinutes;

                    if (travelMinutes > 150) {
                        violations.add(new PlanViolation(
                                "TRAVEL_TIME_EXCESSIVE",
                                dayNumber,
                                safeText(currStop.get("id")),
                                safeText(currStop.get("venueId")),
                                "站点“" + prevStop.get("name") + "”至“" + currStop.get("name") + "”间交通耗时过长（" + travelMinutes + " 分钟），超出单次合理接驳上限。",
                                true
                        ));
                    }
                }
            }

            if (totalDayMinutes > 600 && !stops.isEmpty()) {
                Map<String, Object> lastStop = stops.get(stops.size() - 1);
                violations.add(new PlanViolation(
                        "DAY_SCHEDULE_OVERFLOW",
                        dayNumber,
                        safeText(lastStop.get("id")),
                        safeText(lastStop.get("venueId")),
                        "第 " + dayNumber + " 天总游览与交通耗时（" + totalDayMinutes + " 分钟）超出单日合理上限（600 分钟）。",
                        true
                ));
            }
        }

        boolean hardFeasible = violations.stream().noneMatch(PlanViolation::isHardConstraint);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("totalDays", totalDays);
        metrics.put("totalStops", totalStops);
        metrics.put("violationCount", violations.size());
        metrics.put("feasible", hardFeasible);

        return new VerificationResult(hardFeasible, violations, metrics);
    }

    private RouteCost extractRouteCost(Map<String, Object> stop) {
        if (stop == null) return null;
        if (stop.get("resolvedRouteCost") instanceof Map<?, ?> map) {
            return RouteCost.fromMap(map);
        }
        if (stop.get("routeFromPrevious") instanceof Map<?, ?> rfp) {
            if (rfp.get("selected") instanceof Map<?, ?> sel) {
                long durationSec = number(sel.get("durationSeconds"));
                if (durationSec <= 0) durationSec = number(sel.get("duration"));
                int dist = (int) number(sel.get("distanceMeters"));
                if (dist <= 0) dist = (int) number(sel.get("distance"));
                int walk = (int) number(sel.get("walkingDistanceMeters"));
                String mode = rfp.get("selectedMode") != null ? String.valueOf(rfp.get("selectedMode")) : "TRANSIT";
                String statusStr = rfp.get("routeDataStatus") != null ? String.valueOf(rfp.get("routeDataStatus")) : "ESTIMATED";
                RouteCost.RouteDataStatus status = "VERIFIED_AMAP".equals(statusStr) ? RouteCost.RouteDataStatus.VERIFIED_AMAP : RouteCost.RouteDataStatus.ESTIMATED;
                return new RouteCost(Duration.ofSeconds(durationSec), dist, walk, mode, status, "来自routeFromPrevious");
            }
        }
        if (stop.get("estimatedRouteCost") instanceof Map<?, ?> est) {
            long min = number(est.get("durationMinutes"));
            int dist = (int) number(est.get("distanceMeters"));
            String statusStr = est.get("status") != null ? String.valueOf(est.get("status")) : "ESTIMATED";
            RouteCost.RouteDataStatus status = "VERIFIED_AMAP".equals(statusStr) ? RouteCost.RouteDataStatus.VERIFIED_AMAP : RouteCost.RouteDataStatus.ESTIMATED;
            return new RouteCost(Duration.ofMinutes(min), dist, 0, "TRANSIT", status, String.valueOf(est.get("reason")));
        }
        return null;
    }

    private int parseDurationMinutes(Object durationObj) {
        if (durationObj == null) return 90;
        String text = String.valueOf(durationObj).trim();
        Matcher matcher = DURATION_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {}
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {}
        return 90;
    }

    private long number(Object value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String safeText(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    private boolean isDistantSuburban(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足");
    }

    private boolean isMorningSlot(String time) {
        return time.contains("09:") || time.contains("10:") || time.contains("11:") || time.contains("上午");
    }

    private boolean isAfternoonSlot(String time) {
        return time.contains("13:") || time.contains("14:") || time.contains("15:") || time.contains("16:") || time.contains("17:") || time.contains("下午");
    }

    private boolean isEveningSlot(String time) {
        return time.contains("18:") || time.contains("19:") || time.contains("20:") || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    private boolean isDateMonday(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return java.time.LocalDate.parse(date).getDayOfWeek() == java.time.DayOfWeek.MONDAY;
        } catch (Exception e) {
            return false;
        }
    }
}

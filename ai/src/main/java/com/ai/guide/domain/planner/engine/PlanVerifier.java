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
    private final ItineraryTimeEngine timeEngine = new ItineraryTimeEngine();

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
            boolean isMonday = dayDate.contains("星期一") || dayDate.contains("周一") || dayDate.contains("Monday") || isDateMonday(dayDate);

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

            // Compare actual clocks and occupied intervals, preserving (+Nd) and ISO dates.
            Integer arrivalBoundary = dayNumber == 1 && constraints != null
                    ? constraintMinute(constraints.getArrivalAt(), dayDate, true) : null;
            Integer departureBoundary = dayNumber == totalDays && constraints != null
                    ? constraintMinute(constraints.getDepartureAt(), dayDate, false) : null;
            for (Map<String, Object> stop : stops) {
                Integer start = timeEngine.parseExplicitTimeMinutes(stopTime(stop));
                if (start == null) continue;
                if (arrivalBoundary != null && start < arrivalBoundary) {
                    violations.add(new PlanViolation("ARRIVAL_WINDOW_CONFLICT", dayNumber,
                            safeText(stop.get("id")), safeText(stop.get("venueId")),
                            "站点开始时间早于已提供的抵达时间，不可安排。", true));
                }
                long end = (long) start + parseDurationMinutes(stop.get("duration"));
                // (+Nd) preserves arithmetic, but each day's occupied blocks must stay
                // within its date. Crossing the boundary requires a newly dated plan.
                if (start >= 1440 || end > 1440) {
                    violations.add(new PlanViolation("MIDNIGHT_OVERFLOW", dayNumber,
                            safeText(stop.get("id")), safeText(stop.get("venueId")),
                            "站点开始或完整停留时段跨越当前日期，请移至下一天或缩短当天安排。", true));
                }
                if (departureBoundary != null && end > departureBoundary) {
                    violations.add(new PlanViolation("DEPARTURE_DEADLINE_CONFLICT", dayNumber,
                            safeText(stop.get("id")), safeText(stop.get("venueId")),
                            "站点游览结束时间晚于已提供的返程时限，不可安排。", true));
                }
            }

            // Check 3: Opening Hours & Closed Days (GS-03)
            for (Map<String, Object> stop : stops) {
                String bestTime = OpeningHoursPolicy.evidence(safeText(stop.get("openingHours")), safeText(stop.get("bestTime")));
                String time = stopTime(stop);
                String venueId = safeText(stop.get("venueId"));
                String name = safeText(stop.get("name"));

                boolean closedOnArrival = OpeningHoursPolicy.isClosed(bestTime, dayDate, time);
                if (closedOnArrival) {
                    violations.add(new PlanViolation(
                            bestTime.contains("周一") ? "CLOSED_ON_MONDAY" : "CLOSED_ON_DATE",
                            dayNumber,
                            safeText(stop.get("id")),
                            venueId,
                            "景点" + name + "在到达日期已知休息，不可安排。",
                            true
                    ));
                }

                if (!closedOnArrival && !OpeningHoursPolicy.isVisitOpen(bestTime, dayDate, time, parseDurationMinutes(stop.get("duration")))) {
                    violations.add(new PlanViolation(
                            "CLOSED_AT_SLOT_TIME",
                            dayNumber,
                            safeText(stop.get("id")),
                            venueId,
                            "景点" + name + "的完整游览时段超过已知营业区间、休息日或中途休息时段，不可行。",
                            true
                    ));
                }
            }

            // Check 4: Cross-district extreme bounce in same half-day (GS-01, 包括餐饮站点)
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
                            "站点" + stops.get(j - 1).get("name") + "（" + prevDist + "）与" + stops.get(j).get("name") + "（" + currDist + "）相距远且交通耗时极长，在同一日紧凑切换不可行。",
                            true
                    ));
                }
            }

            // Check 5: Route timing & Day schedule budget check (Real/Estimated route feedback)
            long totalDayMinutes = 0;
            for (int j = 0; j < stops.size(); j++) {
                Map<String, Object> currStop = stops.get(j);
                int visitMinutes = parseDurationMinutes(currStop.get("duration"));
                totalDayMinutes += visitMinutes;

                if (j > 0) {
                    Map<String, Object> prevStop = stops.get(j - 1);
                    RouteCost cost = extractRouteCost(currStop);
                    int travelMinutes = timeEngine.transferMinutes(prevStop, currStop);
                    totalDayMinutes += travelMinutes;
                    Integer previousStart = timeEngine.parseExplicitTimeMinutes(stopTime(prevStop));
                    Integer currentStart = timeEngine.parseExplicitTimeMinutes(stopTime(currStop));
                    if (previousStart != null && currentStart != null
                            && currentStart < (long) previousStart + parseDurationMinutes(prevStop.get("duration")) + travelMinutes) {
                        violations.add(new PlanViolation("TIME_OVERLAP", dayNumber,
                                safeText(currStop.get("id")), safeText(currStop.get("venueId")),
                                "相邻站点未留足前站游览与接驳时间，时间安排存在重叠。", true));
                    }

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

            if (totalDayMinutes > 660 && !stops.isEmpty()) {
                Map<String, Object> lastStop = stops.get(stops.size() - 1);
                violations.add(new PlanViolation(
                        "DAY_SCHEDULE_OVERFLOW",
                        dayNumber,
                        safeText(lastStop.get("id")),
                        safeText(lastStop.get("venueId")),
                        "第 " + dayNumber + " 天总游览与交通耗时（" + totalDayMinutes + " 分钟）超出单日合理上限（660 分钟）。",
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
        return timeEngine.parseDurationMinutes(durationObj == null ? null : String.valueOf(durationObj));
    }

    private String stopTime(Map<String, Object> stop) {
        String value = safeText(stop.get("time"));
        return value.isBlank() ? safeText(stop.get("startTime")) : value;
    }

    private Integer constraintMinute(String value, String dayDate, boolean arrival) {
        if (value == null || value.isBlank()) return null;
        Integer clock = timeEngine.parseExplicitTimeMinutes(value);
        if (clock != null) {
            Matcher date = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(value);
            if (date.find()) {
                try {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(dayDate), java.time.LocalDate.parse(date.group()));
                    return Math.toIntExact(clock + days * 1440);
                } catch (RuntimeException unknownDate) { /* retain the explicit clock */ }
            }
            return clock;
        }
        if (arrival) {
            if (value.contains("晚上") || value.contains("夜间")) return 18 * 60;
            if (value.contains("下午") || value.contains("中午")) return 12 * 60;
        } else {
            if (value.contains("上午") || value.contains("早晨")) return 12 * 60;
            if (value.contains("下午") || value.contains("中午")) return 18 * 60;
        }
        return null;
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

    private boolean isDiningStop(Map<String, Object> stop) {
        if (stop == null) return false;
        String type = safeText(stop.get("type"));
        String category = safeText(stop.get("category"));
        String icon = safeText(stop.get("icon"));
        String name = safeText(stop.get("name"));
        String id = safeText(stop.get("id"));
        return "DINING".equalsIgnoreCase(type)
                || "美食".equals(category)
                || "餐".equals(icon)
                || id.contains("dining")
                || name.contains("餐推荐")
                || name.contains("【早餐】")
                || name.contains("【午餐】")
                || name.contains("【晚餐】");
    }

    private boolean isDistantSuburban(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足") || district.contains("江津");
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

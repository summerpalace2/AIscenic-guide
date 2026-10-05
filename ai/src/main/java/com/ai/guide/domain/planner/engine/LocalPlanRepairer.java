package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.planner.service.PoiCategoryPolicyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程局部智能修复器
 *
 * 所属领域：domain.planner.engine（规划引擎核心算法层）
 * 架构职责：在排程出现开闭园冲突、游玩超时或雨天室外冲突时，执行局部微调、候选景点就近替换与时间轴重算。
 */
@Service
public class LocalPlanRepairer {

    private final PoiCategoryPolicyRegistry categoryPolicyRegistry;

    @Autowired
    public LocalPlanRepairer(@Autowired(required = false) PoiCategoryPolicyRegistry categoryPolicyRegistry) {
        this.categoryPolicyRegistry = categoryPolicyRegistry != null ? categoryPolicyRegistry : new PoiCategoryPolicyRegistry();
    }

    public LocalPlanRepairer() {
        this(new PoiCategoryPolicyRegistry());
    }

    public List<Map<String, Object>> repair(List<Map<String, Object>> days,
                                           PlanVerifier.VerificationResult verification,
                                           TravelConstraints constraints,
                                           List<Attraction> catalog) {
        if (days == null || verification == null || verification.feasible()) {
            return days == null ? new ArrayList<>() : days;
        }

        List<Map<String, Object>> repairedDays = new ArrayList<>();
        for (Map<String, Object> day : days) {
            Map<String, Object> copy = new LinkedHashMap<>(day);
            Object stopsObj = day.get("stops");
            if (stopsObj instanceof List<?> list) {
                List<Map<String, Object>> stopsCopy = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        Map<String, Object> stopCopy = new LinkedHashMap<>();
                        m.forEach((k, v) -> stopCopy.put(String.valueOf(k), v));
                        stopsCopy.add(stopCopy);
                    }
                }
                copy.put("stops", stopsCopy);
            }
            repairedDays.add(copy);
        }

        List<PlanVerifier.PlanViolation> violations = verification.violations();

        for (PlanVerifier.PlanViolation v : violations) {
            if (!v.isHardConstraint()) continue;
            int dayIndex = v.dayNumber() - 1;
            if (dayIndex < 0 || dayIndex >= repairedDays.size()) continue;

            Map<String, Object> day = repairedDays.get(dayIndex);
            Object stopsObj = day.get("stops");
            if (!(stopsObj instanceof List<?> rawStops)) continue;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) rawStops;
            if (stops.isEmpty()) continue;

            // Strategy 1: Arrival window conflict on Day 1 -> remove infeasible morning/afternoon slot
            if ("ARRIVAL_WINDOW_CONFLICT".equals(v.type())) {
                stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                        && !isProtectedStop(s, constraints));
            }
            // Strategy 2: Departure deadline conflict on Final Day -> remove infeasible late slot
            else if ("DEPARTURE_DEADLINE_CONFLICT".equals(v.type())) {
                stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                        && !isProtectedStop(s, constraints));
            }
            // Strategy 3: Closed on Monday or Slot time conflict -> swap with available attraction or remove
            else if ("CLOSED_ON_MONDAY".equals(v.type()) || "CLOSED_AT_SLOT_TIME".equals(v.type())) {
                stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                        && !isProtectedStop(s, constraints));
            }
            // Strategy 4: Cross-district inviable bounce -> remove the distant stop or re-isolate
            else if ("CROSS_DISTRICT_INVIABLE".equals(v.type())) {
                stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                        && !isProtectedStop(s, constraints));
            }
            // Strategy 5: Day schedule overflow / excessive travel time -> compress duration or remove redundant stop
            else if ("DAY_SCHEDULE_OVERFLOW".equals(v.type()) || "TRAVEL_TIME_EXCESSIVE".equals(v.type())) {
                long scenicCount = stops.stream().filter(s -> !isDiningStop(s)).count();
                if (scenicCount <= 2) {
                    // Do not prune when only 1-2 scenic spots exist! Compress duration to keep day schedule within budget
                    compressStopDurations(stops);
                } else {
                    boolean removed = false;
                    if (v.stopId() != null && !v.stopId().isBlank()) {
                        removed = stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                                && !isProtectedStop(s, constraints));
                    }
                    if (!removed) {
                        for (int i = stops.size() - 1; i >= 0; i--) {
                            if (!isProtectedStop(stops.get(i), constraints)) {
                                stops.remove(i);
                                removed = true;
                                break;
                            }
                        }
                    }
                    if (!removed) {
                        // 全保护站点无法移除时压缩游玩时长（180->90->60->50）
                        compressStopDurations(stops);
                    }
                }
            }
        }

        return repairedDays;
    }

    private void compressStopDurations(List<Map<String, Object>> stops) {
        for (Map<String, Object> s : stops) {
            PoiDomainType domainType = categoryPolicyRegistry.resolveCategory(s);
            if (domainType == PoiDomainType.THEATER || domainType == PoiDomainType.HOTEL) continue;
            if (domainType == PoiDomainType.DINING) {
                // 餐饮节点正常不随意压缩，但超过餐饮合理上限 (120 分钟) 时防御性修正至标准时长
                int mins = parseDurationMinutes(text(s.get("duration")));
                if (mins > 120) {
                    s.put("duration", "约 90 分钟");
                }
                continue;
            }
            String dur = text(s.get("duration"));
            int mins = parseDurationMinutes(dur);
            if (mins > 90 || dur.contains("180") || dur.contains("3小时") || dur.contains("120") || dur.contains("2小时") || dur.contains("100")) {
                s.put("duration", "约 90 分钟");
            } else if (mins > 60 || dur.contains("90") || dur.contains("75")) {
                s.put("duration", "约 60 分钟");
            } else if (mins > 50 || dur.contains("60") || dur.contains("1小时")) {
                s.put("duration", "约 50 分钟");
            }
        }
    }

    private int parseDurationMinutes(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int hours = 0;
            int minutes = 0;
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?小时").matcher(raw);
            if (hm.find()) {
                double h = Double.parseDouble(hm.group(1));
                hours = (int) (h * 60);
            }
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(raw);
            if (mm.find()) {
                minutes = Integer.parseInt(mm.group(1));
            }
            if (hours > 0 || minutes > 0) {
                return hours + minutes;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
            if (m.find()) {
                double val = Double.parseDouble(m.group(1));
                if (raw.contains("小时")) return (int) (val * 60);
                return (int) val;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private boolean isDiningStop(Map<String, Object> stop) {
        return categoryPolicyRegistry.isDiningStop(stop);
    }

    private boolean isProtectedStop(Map<String, Object> stop, TravelConstraints constraints) {
        if (stop == null) return false;
        PoiDomainType domainType = categoryPolicyRegistry.resolveCategory(stop);
        if (domainType == PoiDomainType.DINING || domainType == PoiDomainType.THEATER || domainType == PoiDomainType.HOTEL) {
            return true;
        }
        if (Boolean.TRUE.equals(stop.get("pinned")) || Boolean.TRUE.equals(stop.get("isPinned"))
                || "true".equalsIgnoreCase(String.valueOf(stop.get("pinned")))) {
            return true;
        }
        String stopId = text(stop.get("id"));
        String stableStopId = text(stop.get("stableStopId"));
        String venueId = text(stop.get("venueId"));
        String name = text(stop.get("name"));
        return constraints != null && constraints.getMustVisit() != null
                && constraints.getMustVisit().stream().anyMatch(selector ->
                selector.equals(stopId) || selector.equals(stableStopId) || selector.equals(venueId)
                        || (!name.isBlank() && name.contains(selector)));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

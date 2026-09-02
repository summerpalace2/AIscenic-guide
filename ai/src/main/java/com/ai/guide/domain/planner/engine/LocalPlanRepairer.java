package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
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
            // Strategy 5: Day schedule overflow / excessive travel time -> remove the offending stop or the last/lowest utility stop
            else if ("DAY_SCHEDULE_OVERFLOW".equals(v.type()) || "TRAVEL_TIME_EXCESSIVE".equals(v.type())) {
                boolean removed = false;
                if (v.stopId() != null && !v.stopId().isBlank()) {
                    removed = stops.removeIf(s -> v.stopId().equals(String.valueOf(s.get("id")))
                            && !isProtectedStop(s, constraints));
                }
                if (!removed) {
                    for (int i = stops.size() - 1; i >= 0; i--) {
                        if (!isProtectedStop(stops.get(i), constraints)) {
                            stops.remove(i);
                            break;
                        }
                    }
                }
            }
        }

        return repairedDays;
    }

    private boolean isProtectedStop(Map<String, Object> stop, TravelConstraints constraints) {
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

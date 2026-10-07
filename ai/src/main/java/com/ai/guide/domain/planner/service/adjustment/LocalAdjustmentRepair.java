package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import java.util.*;
import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/** Reattaches repaired days without treating an interior day as the arrival/departure day. */
final class LocalAdjustmentRepair {
    private LocalAdjustmentRepair() {}
    static void repair(Map<String,Object> trip, Integer dayNumber, Map<String,Attraction> byId,
                       TravelConstraints constraints, PlanAdjustmentIntent intent, String requiredStopId,
                       RouteAwarePlanner planner) {
        Map<String,Object> affected = dayNumber == null ? null : findDay(trip, dayNumber);
        if (affected == null) return;
        List<Map<String,Object>> tripDays = new ArrayList<>(days(trip));
        int index = tripDays.indexOf(affected);
        TravelConstraints local = constraints == null ? new TravelConstraints() : constraints.copy();
        if (index != 0) local.setArrivalAt("未提供");
        if (index != tripDays.size()-1) local.setDepartureAt("未提供");
        Set<String> protectedIds = new LinkedHashSet<>(local.getMustVisit() == null ? List.of() : local.getMustVisit());
        if (requiredStopId != null && !requiredStopId.isBlank()) protectedIds.add(requiredStopId);
        if (intent != null && intent.getPinnedStopIds() != null) protectedIds.addAll(intent.getPinnedStopIds());
        var times = new ItineraryTimeEngine();
        for (var stop : dayStops(affected)) {
            Integer minute = times.parseExplicitTimeMinutes(firstNonBlank(safe(stop.get("time")), safe(stop.get("startTime"))));
            boolean outside = intent != null && intent.getTargetStartMinute() != null && intent.getTargetEndMinute() != null
                    && (minute == null || minute < intent.getTargetStartMinute() || minute >= intent.getTargetEndMinute());
            boolean preserved = intent != null && intent.getPreserveReferences() != null
                    && intent.getPreserveReferences().stream().anyMatch(ref -> !safe(stop.get("name")).isBlank() && ref.contains(safe(stop.get("name"))));
            if (outside || preserved) protectedIds.add(safe(stop.get("id")));
        }
        local.setMustVisit(new ArrayList<>(protectedIds));
        List<Map<String,Object>> resolved = planner.resolveAndVerifyDays(new ArrayList<>(List.of(affected)), byId, local);
        if (resolved != null && !resolved.isEmpty() && index >= 0) {
            tripDays.set(index, resolved.get(0));
            trip.put("days", tripDays);
        }
    }
}

package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TaxonomyPort.MealWindow;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Fits a complete meal plus adjacent travel into a free interval, preserving existing appointments. */
@Component
public class MealGapSolver {
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2})[:：](\\d{2})$");
    private static final Pattern DURATION_UNIT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|分钟|分|hours?|minutes?|min|h)", Pattern.CASE_INSENSITIVE);
    private final RouteCostProvider routeCosts;
    private final ItineraryTimeEngine times;

    public record Placement(int index, int startMinute, int inboundMinutes, int outboundMinutes,
                            RouteCost inboundCost, RouteCost outboundCost) {}
    private record Block(Map<String, Object> stop, int start, int end) {}

    public MealGapSolver(RouteCostProvider routeCosts, ItineraryTimeEngine times) {
        this.routeCosts = routeCosts;
        this.times = times;
    }

    public Optional<Placement> findGap(List<Map<String, Object>> stops, Attraction meal, int duration,
                                       MealWindow window, String preference, ItineraryBuilderPort builder) {
        if (stops == null || meal == null || duration <= 0 || window == null
                || window.startMinute() < 0 || window.endMinute() > 24 * 60 || window.startMinute() >= window.endMinute()
                || !PlanAdjustmentHelper.isValidCoordinate(meal.getLocation())) return Optional.empty();
        List<Block> blocks = new ArrayList<>();
        int lastEnd = -1;
        for (var stop : stops) {
            Integer start = parseStart(stop);
            if (start == null || start < lastEnd) return Optional.empty();
            int stopDuration = occupiedDuration(PlanAdjustmentHelper.safe(stop.get("duration")));
            if (stopDuration <= 0) return Optional.empty();
            int end = start + stopDuration;
            if (end > 24 * 60) return Optional.empty();
            blocks.add(new Block(stop, start, end));
            lastEnd = end;
        }
        Placement best = null;
        int bestTravel = Integer.MAX_VALUE;
        int bestIdeal = Integer.MAX_VALUE;
        for (int index = 0; index <= blocks.size(); index++) {
            Block previous = index == 0 ? null : blocks.get(index - 1);
            Block next = index == blocks.size() ? null : blocks.get(index);
            RouteCost inboundCost = previous == null ? null : travel(previous.stop(), meal, preference, builder, false);
            RouteCost outboundCost = next == null ? null : travel(next.stop(), meal, preference, builder, true);
            if ((previous != null && inboundCost == null) || (next != null && outboundCost == null)) continue;
            int inbound = inboundCost == null ? 0 : (int) Math.ceil(inboundCost.duration().toSeconds() / 60.0);
            int outbound = outboundCost == null ? 0 : (int) Math.ceil(outboundCost.duration().toSeconds() / 60.0);
            int earliest = Math.max(window.startMinute(), previous == null ? 0 : previous.end() + inbound);
            int latest = Math.min(window.endMinute() - duration, next == null ? 24 * 60 - duration : next.start() - outbound - duration);
            if (earliest > latest) continue;
            int start = Math.max(earliest, Math.min(latest, window.idealMinute()));
            int travel = inbound + outbound;
            int ideal = Math.abs(start - window.idealMinute());
            if (travel < bestTravel || (travel == bestTravel && ideal < bestIdeal)) {
                best = new Placement(index, start, inbound, outbound, inboundCost, outboundCost);
                bestTravel = travel;
                bestIdeal = ideal;
            }
        }
        return Optional.ofNullable(best);
    }

    private RouteCost travel(Map<String, Object> stop, Attraction meal, String preference, ItineraryBuilderPort builder, boolean outbound) {
        Attraction attraction = PlanAdjustmentHelper.resolveStopAnchor(stop, builder);
        if (attraction == null || !PlanAdjustmentHelper.isValidCoordinate(attraction.getLocation())) return null;
        RouteCost cost = outbound ? routeCosts.calculate(meal, attraction, preference) : routeCosts.calculate(attraction, meal, preference);
        if (cost == null || cost.duration() == null || cost.status() == RouteCost.RouteDataStatus.UNAVAILABLE) return null;
        return cost;
    }

    /** Update the two edges changed by insertion; keep display metadata consistent with scheduling costs. */
    public void applyRouteMetadata(List<Map<String, Object>> stops, Placement placement) {
        Map<String, Object> meal = stops.get(placement.index());
        if (placement.inboundCost() != null) {
            applyCost(meal, placement.inboundCost());
        } else {
            meal.remove("routeFromPrevious");
            meal.remove("resolvedRouteCost");
            meal.remove("estimatedRouteCost");
            meal.put("routeDataStatus", "UNAVAILABLE");
        }
        if (placement.outboundCost() != null && placement.index() + 1 < stops.size()) {
            applyCost(stops.get(placement.index() + 1), placement.outboundCost());
        }
    }

    private void applyCost(Map<String, Object> stop, RouteCost cost) {
        String summary = "交通约 " + (int) Math.ceil(cost.duration().toSeconds() / 60.0) + " 分钟（估算，待核验）";
        Map<String, Object> selected = new java.util.LinkedHashMap<>(cost.asMap());
        selected.put("mode", cost.transportMode());
        selected.put("duration", summary);
        selected.put("summary", summary);
        stop.put("routeFromPrevious", Map.of("selectedMode", cost.transportMode(), "summary", summary, "selected", selected));
        stop.put("estimatedRouteCost", cost.asMap());
        stop.put("resolvedRouteCost", cost.asMap());
        stop.put("routeDataStatus", cost.status().name());
        stop.put("walk", summary);
    }

    private Integer parseStart(Map<String, Object> stop) {
        String time = PlanAdjustmentHelper.firstNonBlank(PlanAdjustmentHelper.safe(stop.get("time")), PlanAdjustmentHelper.safe(stop.get("startTime")));
        if (time == null) return null;
        var matcher = TIME.matcher(time);
        if (!matcher.matches()) return null;
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        return hour < 24 && minute < 60 ? times.parseTimeMinutes(time) : null;
    }

    private int occupiedDuration(String text) {
        var matcher = DURATION_UNIT.matcher(text);
        double minutes = 0;
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            minutes += value * (unit.equals("小时") || unit.startsWith("h") ? 60 : 1);
        }
        if (minutes > 0 && minutes <= 24 * 60) return (int) Math.ceil(minutes);
        // Bare numeric duration is the established minute-valued JSON representation.
        if (text.matches("\\d+")) {
            try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }
}

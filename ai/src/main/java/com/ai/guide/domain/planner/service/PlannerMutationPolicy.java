package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.model.TravelConstraints;
import java.util.*;

/** Shared write rules for catalog edits and AI proposals; does not persist or repair drafts. */
final class PlannerMutationPolicy {
    private final ItineraryBuilderPort catalog;
    private final PlanVerifier verifier;
    private final ItineraryTimeEngine timeEngine = new ItineraryTimeEngine();
    private static final Set<String> NON_FORCEABLE = Set.of("CLOSED_ON_MONDAY", "CLOSED_ON_DATE",
            "CLOSED_AT_SLOT_TIME", "TIME_OVERLAP", "ARRIVAL_WINDOW_CONFLICT",
            "DEPARTURE_DEADLINE_CONFLICT", "MIDNIGHT_OVERFLOW", "DESTINATION_CITY_CONFLICT", "AVOIDED_PLACE", "TIMING_NOT_RESOLVED");

    PlannerMutationPolicy(ItineraryBuilderPort catalog, PlanVerifier verifier) {
        this.catalog = catalog;
        this.verifier = verifier;
    }

    PlanVerifier.VerificationResult verify(Map<String, Object> before, Map<String, Object> after,
                                            TravelConstraints constraints) {
        var plannedDays = days(after);
        var result = verifier.verify(plannedDays, constraints);
        var violations = new ArrayList<>(result.violations());
        Map<String, Map<String, Object>> originals = stopsById(before);
        List<String> cities = destinationCities(after, constraints);
        for (int index = 0; index < plannedDays.size(); index++) {
            var day = plannedDays.get(index);
            for (var stop : stops(day)) {
                var original = originals.get(text(stop.get("id")));
                boolean boundedDay = constraints != null && (index == 0 && provided(constraints.getArrivalAt())
                        || index == plannedDays.size() - 1 && provided(constraints.getDepartureAt()));
                String clock = timeEngine.explicitClock(stop);
                if (boundedDay && edited(original, stop) && timeEngine.parseExplicitTimeMinutes(clock) == null)
                    violations.add(new PlanVerifier.PlanViolation("TIMING_NOT_RESOLVED", dayNumber(day),
                            text(stop.get("id")), text(stop.get("venueId")), "新站点尚未排时，无法核验已明确的抵达或返程边界，请先生成调整预览。", true));
                if (original != null && Objects.equals(original.get("venueId"), stop.get("venueId"))) continue;
                Attraction attraction = catalog.attraction(text(stop.get("venueId")));
                // Dynamic dining/POI candidates are verified by their grounding strategies, not catalog identity.
                if (attraction == null) continue;
                String type = candidateRejection(attraction, cities, constraints);
                if (!type.isBlank()) violations.add(new PlanVerifier.PlanViolation(type, dayNumber(day),
                        text(stop.get("id")), attraction.getId(), "候选地点不符合当前目的地范围或明确避开条件。", true));
            }
        }
        boolean feasible = violations.stream().noneMatch(PlanVerifier.PlanViolation::isHardConstraint);
        var metrics = new LinkedHashMap<>(result.metrics());
        metrics.put("violationCount", violations.size());
        metrics.put("feasible", feasible);
        return new PlanVerifier.VerificationResult(feasible, List.copyOf(violations), metrics);
    }

    /** Existing warnings must not prevent a harmless deletion; newly introduced hard failures still reject the write. */
    List<PlanVerifier.PlanViolation> introducedHardViolations(Map<String, Object> before, Map<String, Object> after,
                                                             TravelConstraints constraints) {
        var prior = verifier.verify(days(before), constraints).violations().stream().map(this::identity).collect(java.util.stream.Collectors.toSet());
        var originalStops = stopsById(before);
        var revisedStops = stopsById(after);
        var originalEdges = incomingEdges(before);
        var revisedEdges = incomingEdges(after);
        return verify(before, after, constraints).violations().stream().filter(PlanVerifier.PlanViolation::isHardConstraint)
                .filter(v -> !prior.contains(identity(v)) || edited(originalStops.get(v.stopId()), revisedStops.get(v.stopId()))
                        || !Objects.equals(originalEdges.get(v.stopId()), revisedEdges.get(v.stopId()))).toList();
    }

    boolean candidateAllowed(Attraction attraction, Map<String, Object> trip, TravelConstraints constraints) {
        return candidateRejection(attraction, trip, constraints).isBlank();
    }

    String candidateRejection(Attraction attraction, Map<String, Object> trip, TravelConstraints constraints) {
        return candidateRejection(attraction, destinationCities(trip, constraints), constraints);
    }

    private String candidateRejection(Attraction attraction, List<String> cities, TravelConstraints constraints) {
        return !cityAllowed(attraction, cities) ? "DESTINATION_CITY_CONFLICT"
                : isAvoided(attraction, constraints) ? "AVOIDED_PLACE" : "";
    }

    static boolean nonForceable(PlanVerifier.PlanViolation violation) { return NON_FORCEABLE.contains(violation.type()); }

    private boolean cityAllowed(Attraction attraction, List<String> cities) {
        return cities.isEmpty() || cities.stream().anyMatch(city -> catalog.belongsToCity(attraction, city));
    }

    private List<String> destinationCities(Map<String, Object> trip, TravelConstraints constraints) {
        String destination = constraints == null ? "" : text(constraints.getDestination());
        if (destination.isBlank()) destination = text(trip.get("city"));
        // Only the explicit structured destination grants multi-city scope, never incidental existing stops or raw prompts.
        return Arrays.stream(destination.split("[、,，/→>]+"))
                .map(ItineraryBuilderPort::normalizeCity).filter(city -> !city.isBlank()).distinct().toList();
    }

    private boolean isAvoided(Attraction attraction, TravelConstraints constraints) {
        return constraints != null && constraints.getAvoid() != null && constraints.getAvoid().stream()
                .filter(value -> value != null && !value.isBlank()).anyMatch(value -> text(attraction.getId()).contains(value)
                        || text(attraction.getName()).contains(value) || value.equals(attraction.getCategory()));
    }

    private String identity(PlanVerifier.PlanViolation violation) {
        return violation.type() + "|" + violation.dayNumber() + "|" + violation.stopId() + "|" + violation.venueId();
    }

    private boolean edited(Map<String, Object> before, Map<String, Object> after) {
        return before == null || after == null || List.of("venueId", "time", "startTime", "duration")
                .stream().anyMatch(field -> !Objects.equals(before.get(field), after.get(field)));
    }

    private Map<String, String> incomingEdges(Map<String, Object> trip) {
        var result = new LinkedHashMap<String, String>();
        for (var day : days(trip)) {
            Map<String, Object> previous = null;
            for (var stop : stops(day)) {
                result.put(text(stop.get("id")), previous == null ? "" : text(previous.get("id")) + "|" + timeEngine.transferMinutes(previous, stop));
                previous = stop;
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> days(Map<String, Object> trip) {
        return trip.get("days") instanceof List<?> values ? (List<Map<String, Object>>) values : List.of();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stops(Map<String, Object> day) {
        return day.get("stops") instanceof List<?> values ? (List<Map<String, Object>>) values : List.of();
    }
    private static Map<String, Map<String, Object>> stopsById(Map<String, Object> trip) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var day : days(trip)) for (var stop : stops(day)) result.put(text(stop.get("id")), stop);
        return result;
    }
    private static int dayNumber(Map<String, Object> day) {
        try { return Integer.parseInt(text(day.get("day"))); } catch (NumberFormatException absent) { return 1; }
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean provided(String value) { return value != null && !value.isBlank() && !"未提供".equals(value); }
}

package com.ai.guide.domain.planner.model.itinerary;

import com.ai.guide.domain.planner.model.TravelConstraints;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 完整多日行程规划聚合根 (Trip Aggregate Root)
 */
public record ItineraryTrip(
        String tripId,
        String title,
        int version,
        String destinationCity,
        TravelConstraints constraints,
        List<ItineraryDay> days,
        TripQualityMetrics qualityMetrics,
        Map<String, Object> extensionAttributes,
        Instant createdAt,
        Instant updatedAt
) {
    public int totalStopsCount() {
        return days == null ? 0 : days.stream().mapToInt(d -> d.stops().size()).sum();
    }
}

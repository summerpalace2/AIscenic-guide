package com.ai.guide.domain.planner.model.itinerary;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 单日排程实体 (Day Schedule Entity)
 */
public record ItineraryDay(
        int dayNumber,
        LocalDate date,
        String dateLabel,
        String theme,
        String departureContext,
        String mealGuidance,
        List<ItineraryStop> stops,
        Map<String, Object> extraAttributes
) {
    public ItineraryDay(int dayNumber, LocalDate date, String dateLabel, String theme, String departureContext, String mealGuidance, List<ItineraryStop> stops) {
        this(dayNumber, date, dateLabel, theme, departureContext, mealGuidance, stops, Collections.emptyMap());
    }

    public boolean hasStop(String stopId) {
        return stops != null && stops.stream().anyMatch(s -> s.id().equals(stopId));
    }
}

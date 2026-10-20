package com.ai.guide.domain.dining.model;

/** Dining recommendation value shared with itinerary planning. */
public record DiningOption(
        String id,
        String name,
        String specialtyDish,
        String diningType,
        String averageCost,
        String duration,
        String distanceFromAttraction,
        String summary,
        String recommendationReason,
        String tone,
        String location
) {}

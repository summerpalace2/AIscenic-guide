package com.ai.guide.domain.geo.model;

import java.util.List;

/** Provider-neutral route metrics and geometry. */
public record GeoRouteSegment(String mode, Integer distanceMeters, Integer durationSeconds,
                              Integer walkingDistanceMeters, int stepCount, List<Integer> walkTypes,
                              String polyline, boolean metricsComplete) {
    public GeoRouteSegment {
        walkTypes = walkTypes == null ? List.of() : List.copyOf(walkTypes);
    }
}

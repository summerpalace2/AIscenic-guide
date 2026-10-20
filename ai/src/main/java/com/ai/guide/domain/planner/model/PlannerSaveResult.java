package com.ai.guide.domain.planner.model;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Result of saving a planner session as a formal trip.
 * serviceStatus preserves the established save endpoint status for its adapter
 * while keeping the use case independent from Spring MVC response types.
 */
public record PlannerSaveResult(int serviceStatus, Map<String, Object> body) {
    public PlannerSaveResult {
        body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }
}

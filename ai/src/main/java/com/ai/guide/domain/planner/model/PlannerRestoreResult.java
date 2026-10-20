package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP-independent result for restoring a local backup into a planner session. */
public record PlannerRestoreResult(int serviceStatus, Map<String, Object> body) {
    public PlannerRestoreResult {
        body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }
}

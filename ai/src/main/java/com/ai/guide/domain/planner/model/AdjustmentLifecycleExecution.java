package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Apply lifecycle result without transport status details. */
public record AdjustmentLifecycleExecution(Outcome outcome, Map<String, Object> body) {
    public enum Outcome { SUCCESS, BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, INTERNAL_ERROR }

    public AdjustmentLifecycleExecution {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body == null ? Map.of() : body));
    }
}

package com.ai.guide.application.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Application result for applying an adjustment; HTTP status mapping belongs to the adapter. */
public record ApplyAdjustmentResult(Outcome outcome, Map<String, Object> body) {
    public enum Outcome { SUCCESS, BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, INTERNAL_ERROR }

    public ApplyAdjustmentResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body == null ? Map.of() : body));
    }

    public static ApplyAdjustmentResult of(Outcome outcome, Map<String, Object> body) {
        return new ApplyAdjustmentResult(outcome, body);
    }
}

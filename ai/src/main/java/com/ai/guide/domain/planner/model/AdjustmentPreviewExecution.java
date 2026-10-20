package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Business result emitted by preview policy; transport status is intentionally excluded. */
public record AdjustmentPreviewExecution(Outcome outcome, Map<String, Object> body) {
    public enum Outcome {
        SUCCESS,
        CLARIFICATION,
        NO_CANDIDATE,
        PREVIEW_UNAPPLICABLE,
        BAD_REQUEST,
        UNAUTHORIZED,
        FORBIDDEN,
        NOT_FOUND,
        CONFLICT,
        UNPROCESSABLE,
        TOO_MANY_REQUESTS,
        SERVICE_UNAVAILABLE,
        INTERNAL_ERROR
    }

    public AdjustmentPreviewExecution {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body == null ? Map.of() : body));
    }
}

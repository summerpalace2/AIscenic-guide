package com.ai.guide.application.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Application outcome for an adjustment preview; intentionally carries no HTTP status. */
public record PreviewAdjustmentResult(Outcome outcome, Map<String, Object> body) {
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

    public PreviewAdjustmentResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body == null ? Map.of() : body));
    }

    public static PreviewAdjustmentResult of(Outcome outcome, Map<String, Object> body) {
        return new PreviewAdjustmentResult(outcome, body);
    }

    public static PreviewAdjustmentResult success(Map<String, Object> body) {
        return of(Outcome.SUCCESS, body);
    }

    public static PreviewAdjustmentResult failure(Outcome outcome, String message) {
        return of(outcome, Map.of("ok", false, "message", message));
    }
}

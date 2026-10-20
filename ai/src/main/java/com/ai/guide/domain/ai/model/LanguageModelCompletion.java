package com.ai.guide.domain.ai.model;

import java.util.Objects;

/** Elapsed time measures this local non-streaming call, not time to first token or monetary cost. */
public record LanguageModelCompletion(String text, LanguageModelUsage usage, String model,
                                      long elapsedMs, boolean measurementAvailable) {
    public LanguageModelCompletion {
        Objects.requireNonNull(usage, "usage");
        if (elapsedMs < 0) throw new IllegalArgumentException("Elapsed time cannot be negative");
        if (measurementAvailable != usage.available()) {
            throw new IllegalArgumentException("Measurement availability must match reported token counts");
        }
    }
}

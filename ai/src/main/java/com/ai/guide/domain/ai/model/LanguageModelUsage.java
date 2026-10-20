package com.ai.guide.domain.ai.model;

/** Token counts reported by the provider SDK; null means unavailable, never estimated. */
public record LanguageModelUsage(Long promptTokens, Long completionTokens, Long totalTokens) {
    public LanguageModelUsage {
        requireNonNegative(promptTokens);
        requireNonNegative(completionTokens);
        requireNonNegative(totalTokens);
    }

    public static LanguageModelUsage unknown() {
        return new LanguageModelUsage(null, null, null);
    }

    public boolean available() {
        return promptTokens != null || completionTokens != null || totalTokens != null;
    }

    private static void requireNonNegative(Long value) {
        if (value != null && value < 0) throw new IllegalArgumentException("Token counts cannot be negative");
    }
}

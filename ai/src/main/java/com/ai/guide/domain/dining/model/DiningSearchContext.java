package com.ai.guide.domain.dining.model;

/** Dining-owned subset of planning context required for candidate search. */
public record DiningSearchContext(
        String destination,
        String dietPreference,
        int timeBudgetMinutes,
        int durationDays,
        String walkingTolerance,
        String rawPrompt,
        String companions) {
}

package com.ai.guide.domain.planner.model;

/** Authenticated identity snapshot used by the formal-trip save use case. */
public record PlannerSaveActor(String userId, boolean anonymous) {
}

package com.ai.guide.application.planner;

/** Explicit identity supplied by the web adapter to planning use cases. */
public record PlanningActor(String userId, boolean anonymous) {
    public static PlanningActor anonymousActor() {
        return new PlanningActor(null, true);
    }
}

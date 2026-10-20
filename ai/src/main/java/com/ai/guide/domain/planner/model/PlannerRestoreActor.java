package com.ai.guide.domain.planner.model;

/** Explicit identity supplied by the adapter for restoring a planner draft. */
public record PlannerRestoreActor(String userId, boolean anonymous) { }

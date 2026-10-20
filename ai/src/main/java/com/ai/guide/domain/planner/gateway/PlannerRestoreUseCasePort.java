package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.PlannerRestoreActor;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;

/** Inward-facing boundary for the legacy planner facade to delegate draft restoration. */
public interface PlannerRestoreUseCasePort {
    PlannerRestoreResult restoreDraft(PlannerRestoreActor actor, String sessionId, RestoreDraftRequest request);
}

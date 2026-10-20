package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.PlannerSaveActor;
import com.ai.guide.domain.planner.model.PlannerSaveResult;

/** Inward-facing boundary through which the legacy planner facade delegates save orchestration. */
public interface PlannerSaveUseCasePort {
    PlannerSaveResult save(PlannerSaveActor actor, String sessionId, String accessToken);
}

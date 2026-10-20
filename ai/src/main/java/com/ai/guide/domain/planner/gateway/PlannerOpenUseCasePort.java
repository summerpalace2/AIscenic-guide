package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.PlannerOpenActor;
import com.ai.guide.domain.planner.model.PlannerOpenResult;

/** Inward-facing boundary for the legacy planner facade to delegate formal-trip opening. */
public interface PlannerOpenUseCasePort {
    default PlannerOpenResult openFormalTrip(PlannerOpenActor actor, String tripId) {
        return openFormalTrip(actor, tripId, java.util.UUID.randomUUID().toString());
    }

    PlannerOpenResult openFormalTrip(PlannerOpenActor actor, String tripId, String operationKey);

    PlannerOpenResult acknowledgeOpenSession(PlannerOpenActor actor, String tripId, String sessionId);

    PlannerOpenResult discardUncommittedOpen(PlannerOpenActor actor, String tripId, String sessionId);
}

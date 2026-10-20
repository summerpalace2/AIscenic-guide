package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.dining.model.DiningSearchContext;
import com.ai.guide.domain.planner.model.TravelConstraints;

/** Converts planner-owned constraints into the smaller dining search contract. */
public final class PlannerDiningContextMapper {
    private PlannerDiningContextMapper() {}

    public static DiningSearchContext from(TravelConstraints constraints) {
        if (constraints == null) return null;
        return new DiningSearchContext(constraints.getDestination(), constraints.getDietPreference(),
                constraints.getTimeBudgetMinutes(), constraints.getDurationDays(),
                constraints.getWalkingTolerance(), constraints.getRawPrompt(), constraints.getCompanions());
    }
}

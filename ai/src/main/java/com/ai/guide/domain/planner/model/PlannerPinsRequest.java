package com.ai.guide.domain.planner.model;

import java.util.List;

/** Explicit delta; an empty or omitted list never means clear all. */
public record PlannerPinsRequest(Integer expectedVersion, List<String> addStopIds,
                                 List<String> removeStopIds, String sessionAccessToken) { }

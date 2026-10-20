package com.ai.guide.domain.planner.model;

import java.util.Map;

/** A browser backup is a proposed draft, never an authoritative session. */
public record RestoreDraftRequest(Map<String, Object> trip, Integer expectedVersion,
                                  String sessionAccessToken) { }

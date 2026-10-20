package com.ai.guide.domain.trip.gateway;

import java.util.List;
import java.util.Map;

/** Persistence capability for opaque saved trip plans and their user-scoped projections. */
public interface TripPlanStorePort {
    List<Map<String, Object>> list(String userId);
    Map<String, Object> get(String userId, String tripId);
    Map<String, Object> save(String userId, Map<String, Object> input);
    boolean delete(String userId, String tripId);
    Map<String, Object> replan(String userId, String tripId, Map<String, Object> request);
    Map<String, Object> findAttraction(String userId, String entityId);
}

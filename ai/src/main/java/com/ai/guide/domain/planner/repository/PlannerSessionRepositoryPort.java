package com.ai.guide.domain.planner.repository;

import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerSession;
import java.util.List;
import java.util.Map;

/**
 * 规划会话仓储标准端口契约接口
 *
 * 所属领域：domain.planner.repository（规划会话仓储层）
 */
public interface PlannerSessionRepositoryPort {

    PlannerSessionRepository.CreateResult create(String ownerType, String ownerId, String prompt,
                                                Map<String, Object> constraints,
                                                AppliedPreferencesSnapshot appliedPreferences,
                                                Map<String, Object> trip, String idempotencyKey,
                                                String requestFingerprint);

    PlannerSessionRepository.StoredSession find(String sessionId, String ownerType, String ownerId, String accessToken);

    PlannerSessionRepository.StoredSession findAnonymousByAccessToken(String accessToken);

    PlannerSessionRepository.StoredSession findByIdempotency(String ownerType, String ownerId, String idempotencyKey);

    List<PlannerSessionRepository.StoredSession> list(String ownerType, String ownerId);

    PlannerSessionRepository.MutationResult update(PlannerSessionRepository.StoredSession current, Map<String, Object> trip,
                                                 int expectedVersion, String mutationType, String label,
                                                 String reason, List<String> changedSegments);

    boolean linkFormalTrip(PlannerSessionRepository.StoredSession current, Map<String, Object> trip);

    int revisionCount(String sessionId);

    PlannerSession toProjection(PlannerSessionRepository.StoredSession session, String rawToken);
}

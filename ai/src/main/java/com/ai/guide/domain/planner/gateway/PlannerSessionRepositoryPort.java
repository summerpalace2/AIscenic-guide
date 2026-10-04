package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerSession;
import java.util.List;
import java.util.Map;

/**
 * 规划会话仓储标准端口契约接口
 *
 * 架构定位：COLA 领域网关端口 (Domain Gateway Port)
 * 职责：定义规划会话草稿、版本演进以及访问令牌鉴权的持久化抽象，完全解耦领域层与具体持久化设施。
 */
public interface PlannerSessionRepositoryPort {

    record StoredSession(String sessionId, String ownerType, String ownerId, String accessTokenHash,
                         String requestFingerprint, String prompt, Map<String, Object> constraints,
                         AppliedPreferencesSnapshot appliedPreferences, Map<String, Object> trip,
                         int currentVersion, int syncRevision, String status,
                         long createdAt, long updatedAt) {
    }

    record CreateResult(String status, StoredSession session, String accessToken) {
        public static CreateResult created(StoredSession session, String token) {
            return new CreateResult("CREATED", session, token);
        }
        public static CreateResult existing(StoredSession session) {
            return new CreateResult("EXISTING", session, "");
        }
        public static CreateResult conflict(StoredSession session) {
            return new CreateResult("CONFLICT", session, "");
        }
    }

    record MutationResult(String status, StoredSession session) {
        public static MutationResult updated(StoredSession session) {
            return new MutationResult("UPDATED", session);
        }
        public static MutationResult conflict(StoredSession session) {
            return new MutationResult("CONFLICT", session);
        }
    }

    CreateResult create(String ownerType, String ownerId, String prompt,
                        Map<String, Object> constraints,
                        AppliedPreferencesSnapshot appliedPreferences,
                        Map<String, Object> trip, String idempotencyKey,
                        String requestFingerprint);

    StoredSession find(String sessionId, String ownerType, String ownerId, String accessToken);

    StoredSession findAnonymousByAccessToken(String accessToken);

    StoredSession findByIdempotency(String ownerType, String ownerId, String idempotencyKey);

    List<StoredSession> list(String ownerType, String ownerId);

    MutationResult update(StoredSession current, Map<String, Object> trip,
                          int expectedVersion, String mutationType, String label,
                          String reason, List<String> changedSegments);

    boolean linkFormalTrip(StoredSession current, Map<String, Object> trip);

    int revisionCount(String sessionId);

    PlannerSession toProjection(StoredSession session, String rawToken);
}

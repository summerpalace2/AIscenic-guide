package com.ai.guide.domain.planner.repository;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规划会话仓储实现类
 *
 * 所属领域：domain.planner.repository（规划会话仓储层）
 * 架构职责：基于内存与本地 SQLite 表提供会话草稿的高效读写、版本原子更新与访问令牌鉴权。
 */
@Repository
public class PlannerSessionRepository implements PlannerSessionRepositoryPort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PlannerSessionRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Compatibility overload for callers that have not adopted structured snapshots. */
    public CreateResult create(String ownerType, String ownerId, String prompt,
                               Map<String, Object> constraints, List<String> ignoredPreferences,
                               Map<String, Object> trip, String idempotencyKey,
                               String requestFingerprint) {
        return create(ownerType, ownerId, prompt, constraints, AppliedPreferencesSnapshot.empty(),
                trip, idempotencyKey, requestFingerprint);
    }

    public CreateResult create(String ownerType, String ownerId, String prompt,
                               Map<String, Object> constraints,
                               AppliedPreferencesSnapshot appliedPreferences,
                               Map<String, Object> trip, String idempotencyKey,
                               String requestFingerprint) {
        if ("ANONYMOUS".equals(ownerType) && (ownerId == null || ownerId.isBlank()
                || "anonymous".equalsIgnoreCase(ownerId))) {
            throw new IllegalArgumentException("guest planner owner 必须是本次会话生成的高熵标识。");
        }
        AppliedPreferencesSnapshot snapshot = appliedPreferences == null
                ? AppliedPreferencesSnapshot.empty() : appliedPreferences;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            StoredSession existing = findByIdempotency(ownerType, ownerId, idempotencyKey);
            if (existing != null) {
                if (!requestFingerprint.equals(existing.requestFingerprint())) {
                    return CreateResult.conflict(existing);
                }
                return CreateResult.existing(existing);
            }
        }

        String sessionId = "session-" + UUID.randomUUID();
        String rawToken = "";
        String tokenHash = "";
        if ("ANONYMOUS".equals(ownerType)) {
            rawToken = randomToken();
            tokenHash = sha256(rawToken);
        }
        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update("INSERT INTO planner_session (session_id, owner_type, owner_id, access_token_hash, " +
                            "idempotency_key, request_fingerprint, prompt, constraints_json, applied_preferences_json, " +
                            "current_trip_json, current_version, sync_revision, status, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionId, ownerType, ownerId, tokenHash,
                    blankToNull(idempotencyKey), requestFingerprint, prompt == null ? "" : prompt,
                    write(constraints), write(snapshot.asMap()), write(trip),
                    1, 1, "ACTIVE", now, now);
            jdbcTemplate.update("INSERT INTO planner_plan_revision (session_id, version, mutation_type, label, reason, " +
                            "changed_segments_json, trip_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    sessionId, 1, "CREATE", "初始规划", "", "[]", write(trip), now);
        } catch (DuplicateKeyException duplicate) {
            StoredSession winner = findByIdempotency(ownerType, ownerId, idempotencyKey);
            if (winner != null) {
                return requestFingerprint.equals(winner.requestFingerprint())
                        ? CreateResult.existing(winner) : CreateResult.conflict(winner);
            }
            throw duplicate;
        }
        StoredSession created = find(sessionId, ownerType, ownerId, rawToken);
        return CreateResult.created(created, rawToken);
    }

    public StoredSession find(String sessionId, String ownerType, String ownerId, String accessToken) {
        List<StoredSession> rows = jdbcTemplate.query(
                "SELECT session_id, owner_type, owner_id, access_token_hash, request_fingerprint, prompt, " +
                        "constraints_json, applied_preferences_json, current_trip_json, current_version, sync_revision, status, created_at, updated_at " +
                        "FROM planner_session WHERE session_id = ? AND owner_type = ? AND owner_id = ?",
                (rs, rowNum) -> mapRow(rs.getString("session_id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("access_token_hash"), rs.getString("request_fingerprint"), rs.getString("prompt"),
                        rs.getString("constraints_json"), rs.getString("applied_preferences_json"), rs.getString("current_trip_json"), rs.getInt("current_version"),
                        rs.getInt("sync_revision"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at")),
                sessionId, ownerType, ownerId);
        if (rows.isEmpty()) return null;
        StoredSession row = rows.get(0);
        if ("ANONYMOUS".equals(ownerType) && !matches(accessToken, row.accessTokenHash())) return null;
        return row;
    }

    /** Resolves a guest owner only from a stored capability digest. */
    public StoredSession findAnonymousByAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) return null;
        List<StoredSession> rows = jdbcTemplate.query(
                "SELECT session_id, owner_type, owner_id, access_token_hash, request_fingerprint, prompt, " +
                        "constraints_json, applied_preferences_json, current_trip_json, current_version, sync_revision, status, created_at, updated_at " +
                        "FROM planner_session WHERE owner_type = 'ANONYMOUS' AND access_token_hash = ?",
                (rs, rowNum) -> mapRow(rs.getString("session_id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("access_token_hash"), rs.getString("request_fingerprint"), rs.getString("prompt"),
                        rs.getString("constraints_json"), rs.getString("applied_preferences_json"), rs.getString("current_trip_json"), rs.getInt("current_version"),
                        rs.getInt("sync_revision"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at")),
                sha256(accessToken));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public StoredSession findByIdempotency(String ownerType, String ownerId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        List<StoredSession> rows = jdbcTemplate.query(
                "SELECT session_id, owner_type, owner_id, access_token_hash, request_fingerprint, prompt, " +
                        "constraints_json, applied_preferences_json, current_trip_json, current_version, sync_revision, status, created_at, updated_at " +
                        "FROM planner_session WHERE owner_type = ? AND owner_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> mapRow(rs.getString("session_id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("access_token_hash"), rs.getString("request_fingerprint"), rs.getString("prompt"),
                        rs.getString("constraints_json"), rs.getString("applied_preferences_json"), rs.getString("current_trip_json"), rs.getInt("current_version"),
                        rs.getInt("sync_revision"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at")),
                ownerType, ownerId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<StoredSession> list(String ownerType, String ownerId) {
        return jdbcTemplate.query(
                "SELECT session_id, owner_type, owner_id, access_token_hash, request_fingerprint, prompt, " +
                        "constraints_json, applied_preferences_json, current_trip_json, current_version, sync_revision, status, created_at, updated_at " +
                        "FROM planner_session WHERE owner_type = ? AND owner_id = ? ORDER BY updated_at DESC",
                (rs, rowNum) -> mapRow(rs.getString("session_id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("access_token_hash"), rs.getString("request_fingerprint"), rs.getString("prompt"),
                        rs.getString("constraints_json"), rs.getString("applied_preferences_json"), rs.getString("current_trip_json"), rs.getInt("current_version"),
                        rs.getInt("sync_revision"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at")),
                ownerType, ownerId);
    }

    @Transactional
    public MutationResult update(StoredSession current, Map<String, Object> trip, int expectedVersion,
                                 String mutationType, String label, String reason,
                                 List<String> changedSegments) {
        int nextVersion = current.currentVersion() + 1;
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update("UPDATE planner_session SET current_trip_json = ?, current_version = ?, " +
                        "sync_revision = sync_revision + 1, updated_at = ? WHERE session_id = ? AND current_version = ?",
                write(trip), nextVersion, now, current.sessionId(), expectedVersion);
        if (updated == 0) {
            StoredSession latest = findInternal(current.sessionId(), current.ownerType(), current.ownerId());
            return MutationResult.conflict(latest);
        }
        jdbcTemplate.update("INSERT INTO planner_plan_revision (session_id, version, mutation_type, label, reason, " +
                        "changed_segments_json, trip_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                current.sessionId(), nextVersion, mutationType, label, reason == null ? "" : reason,
                write(changedSegments == null ? List.of() : changedSegments), write(trip), now);
        StoredSession latest = findInternal(current.sessionId(), current.ownerType(), current.ownerId());
        return MutationResult.updated(latest);
    }

    private StoredSession findInternal(String sessionId, String ownerType, String ownerId) {
        List<StoredSession> rows = jdbcTemplate.query(
                "SELECT session_id, owner_type, owner_id, access_token_hash, request_fingerprint, prompt, " +
                        "constraints_json, applied_preferences_json, current_trip_json, current_version, sync_revision, status, created_at, updated_at " +
                        "FROM planner_session WHERE session_id = ? AND owner_type = ? AND owner_id = ?",
                (rs, rowNum) -> mapRow(rs.getString("session_id"), rs.getString("owner_type"), rs.getString("owner_id"),
                        rs.getString("access_token_hash"), rs.getString("request_fingerprint"), rs.getString("prompt"),
                        rs.getString("constraints_json"), rs.getString("applied_preferences_json"), rs.getString("current_trip_json"), rs.getInt("current_version"),
                        rs.getInt("sync_revision"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at")),
                sessionId, ownerType, ownerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Associates a planner workspace with its formal server-generated Trip id.
     * This is metadata linkage only: it does not create a Planner revision and
     * never changes the planner working version or applied preference snapshot.
     */
    public boolean linkFormalTrip(StoredSession current, Map<String, Object> trip) {
        long now = System.currentTimeMillis();
        int updated = jdbcTemplate.update(
                "UPDATE planner_session SET current_trip_json = ?, sync_revision = sync_revision + 1, " +
                        "updated_at = ? WHERE session_id = ? AND owner_type = ? AND owner_id = ? " +
                        "AND current_version = ?",
                write(trip), now, current.sessionId(), current.ownerType(), current.ownerId(),
                current.currentVersion());
        return updated > 0;
    }

    public int revisionCount(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM planner_plan_revision WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public PlannerSession toProjection(StoredSession session, String rawToken) {
        return new PlannerSession(session.sessionId(), session.ownerType(), session.ownerId(),
                session.currentVersion(), session.syncRevision(), session.trip(), session.constraints(),
                session.appliedPreferences(), rawToken);
    }

    private StoredSession mapRow(String sessionId, String ownerType, String ownerId, String accessTokenHash,
                                 String requestFingerprint, String prompt, String constraintsJson,
                                 String appliedPreferencesJson, String tripJson, int currentVersion,
                                 int syncRevision, String status, long createdAt, long updatedAt) {
        return new StoredSession(sessionId, ownerType, ownerId, accessTokenHash == null ? "" : accessTokenHash,
                requestFingerprint == null ? "" : requestFingerprint, prompt == null ? "" : prompt,
                readMap(constraintsJson), AppliedPreferencesSnapshot.fromJson(appliedPreferencesJson, objectMapper),
                readMap(tripJson), currentVersion, syncRevision,
                status == null ? "ACTIVE" : status, createdAt, updatedAt);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Planner JSON 数据无效", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean matches(String token, String expectedHash) {
        return token != null && !token.isBlank() && expectedHash != null && expectedHash.equals(sha256(token));
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public record StoredSession(String sessionId, String ownerType, String ownerId, String accessTokenHash,
                                String requestFingerprint, String prompt, Map<String, Object> constraints,
                                AppliedPreferencesSnapshot appliedPreferences, Map<String, Object> trip,
                                int currentVersion, int syncRevision, String status,
                                long createdAt, long updatedAt) {
    }

    public record CreateResult(String status, StoredSession session, String accessToken) {
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

    public record MutationResult(String status, StoredSession session) {
        public static MutationResult updated(StoredSession session) {
            return new MutationResult("UPDATED", session);
        }
        public static MutationResult conflict(StoredSession session) {
            return new MutationResult("CONFLICT", session);
        }
    }
}

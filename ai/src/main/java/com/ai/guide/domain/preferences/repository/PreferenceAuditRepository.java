package com.ai.guide.domain.preferences.repository;

import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/**
 * 用户偏好变更审计日志仓储
 *
 * 所属领域：domain.preferences.repository（用户偏好画像仓储层）
 */
@Repository
public class PreferenceAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PreferenceAuditRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(String userId, UserPreferences before, UserPreferences after, String operation) {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("INSERT INTO user_preference_audit (event_id, user_id, revision, operation, " +
                        "actor_type, actor_id, scope, source, device, session_id, planner_session_id, " +
                        "schema_version, normalization_version, decision, before_json, after_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), userId, after.revision(), operation,
                "USER", userId, PreferencesSchema.SCOPE, "API", "", "", "",
                after.schemaVersion(), PreferencesSchema.NORMALIZATION_VERSION, operation,
                snapshot(before), snapshot(after), now);
    }

    private String snapshot(UserPreferences preferences) {
        if (preferences == null) return "{}";
        try {
            return objectMapper.writeValueAsString(preferences);
        } catch (Exception error) {
            throw new IllegalStateException("无法记录用户偏好 provenance", error);
        }
    }
}

package com.ai.guide.infrastructure.preferences.repository;

import com.ai.guide.domain.preferences.gateway.PreferenceAuditRepositoryPort;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/**
 * 用户偏好变更审计日志仓储技术实现 (Preference Audit Repository)
 *
 * 架构定位：COLA 基础设施层 (Infrastructure Layer) - 数据持久化适配器。
 * 核心职责：实现领域端口 {@link PreferenceAuditRepositoryPort}，负责记录用户偏好历史变更与溯源 Provenance。
 */
@Repository
public class PreferenceAuditRepository implements PreferenceAuditRepositoryPort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PreferenceAuditRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
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

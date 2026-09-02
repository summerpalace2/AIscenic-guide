package com.ai.guide.domain.preferences.repository;

import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 用户偏好主数据仓储
 *
 * 所属领域：domain.preferences.repository（用户偏好画像仓储层）
 */
@Repository
public class PreferencesRepository {

    private static final TypeReference<List<String>> INTERESTS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() { };
    private static final String SELECT = "SELECT user_id, schema_version, revision, interests_json, " +
            "walking_tolerance, budget_json, companions, transport_preference, diet_preference, " +
            "stay_area, legacy_metadata_json, created_at, updated_at FROM user_preferences WHERE user_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PreferencesRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<UserPreferences> findByUserId(String userId) {
        List<UserPreferences> rows = jdbcTemplate.query(SELECT, (rs, rowNum) -> new UserPreferences(
                rs.getString("user_id"),
                rs.getInt("schema_version"),
                rs.getLong("revision"),
                readInterests(rs.getString("interests_json")),
                enumValue(UserPreferences.WalkingTolerance.class, rs.getString("walking_tolerance")),
                readBudget(rs.getString("budget_json")),
                enumValue(UserPreferences.CompanionPreference.class, rs.getString("companions")),
                enumValue(UserPreferences.TransportPreference.class, rs.getString("transport_preference")),
                enumValue(UserPreferences.DietPreference.class, rs.getString("diet_preference")),
                rs.getString("stay_area"),
                readMetadata(rs.getString("legacy_metadata_json")),
                rs.getLong("created_at"),
                rs.getLong("updated_at")), userId);
        return rows.stream().findFirst();
    }

    public void insert(UserPreferences preferences) {
        try {
            jdbcTemplate.update("INSERT INTO user_preferences (user_id, schema_version, revision, interests_json, " +
                            "walking_tolerance, budget_json, companions, transport_preference, diet_preference, " +
                            "stay_area, legacy_metadata_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    parameters(preferences));
        } catch (DuplicateKeyException duplicate) {
            throw duplicate;
        }
    }

    /** Updates only the row at expectedRevision, providing optimistic concurrency. */
    public boolean updateCas(UserPreferences next, long expectedRevision) {
        int updated = jdbcTemplate.update("UPDATE user_preferences SET schema_version = ?, revision = ?, " +
                        "interests_json = ?, walking_tolerance = ?, budget_json = ?, companions = ?, " +
                        "transport_preference = ?, diet_preference = ?, stay_area = ?, legacy_metadata_json = ?, " +
                        "updated_at = ? WHERE user_id = ? AND revision = ?",
                next.schemaVersion(), next.revision(), writeInterests(next.interests()),
                next.walkingTolerance().name(), writeBudget(next.budget()), next.companions().name(),
                next.transportPreference().name(), next.dietPreference().name(), next.stayArea(),
                writeMetadata(next.legacyMetadata()), next.updatedAt(), next.userId(), expectedRevision);
        return updated == 1;
    }

    private Object[] parameters(UserPreferences preferences) {
        return new Object[]{
                preferences.userId(),
                preferences.schemaVersion(),
                preferences.revision(),
                writeInterests(preferences.interests()),
                preferences.walkingTolerance().name(),
                writeBudget(preferences.budget()),
                preferences.companions().name(),
                preferences.transportPreference().name(),
                preferences.dietPreference().name(),
                preferences.stayArea(),
                writeMetadata(preferences.legacyMetadata()),
                preferences.createdAt(),
                preferences.updatedAt()
        };
    }

    private List<String> readInterests(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "[]" : json, INTERESTS_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("用户偏好 interests 数据损坏", error);
        }
    }

    private BudgetPreference readBudget(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, BudgetPreference.class);
        } catch (Exception error) {
            throw new IllegalStateException("用户偏好 budget 数据损坏", error);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, METADATA_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("用户偏好 legacy metadata 数据损坏", error);
        }
    }

    private String writeInterests(List<String> interests) {
        return write(interests);
    }

    private String writeBudget(BudgetPreference budget) {
        return write(budget);
    }

    private String writeMetadata(Map<String, Object> metadata) {
        return write(metadata);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("用户偏好 JSON 数据无效", error);
        }
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return Enum.valueOf(type, "UNSPECIFIED");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("用户偏好包含不支持的 canonical code", error);
        }
    }
}

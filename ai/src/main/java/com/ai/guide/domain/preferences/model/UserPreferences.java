package com.ai.guide.domain.preferences.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户偏好画像聚合根实体
 *
 * 所属领域：domain.preferences.model（用户偏好画像领域模型）
 * 包含：兴趣爱好列表、步行耐受度、预算偏好、同行人偏好、交通偏好、饮食口味偏好、住宿偏好区域及版本号 (revision)。
 */
public record UserPreferences(
        String userId,
        int schemaVersion,
        long revision,
        List<String> interests,
        WalkingTolerance walkingTolerance,
        BudgetPreference budget,
        CompanionPreference companions,
        TransportPreference transportPreference,
        DietPreference dietPreference,
        String stayArea,
        Map<String, Object> legacyMetadata,
        long createdAt,
        long updatedAt) {

    public enum WalkingTolerance {
        UNSPECIFIED,
        LOW,
        NORMAL,
        HIGH
    }

    public enum CompanionPreference {
        UNSPECIFIED,
        SOLO,
        COUPLE,
        FAMILY,
        FRIENDS,
        PARENTS,
        CHILDREN,
        GROUP
    }

    public enum TransportPreference {
        UNSPECIFIED,
        PUBLIC_TRANSIT,
        WALKING,
        DRIVING,
        TAXI,
        BICYCLE,
        MIXED
    }

    public enum DietPreference {
        UNSPECIFIED,
        NONE,
        VEGETARIAN,
        VEGAN,
        HALAL,
        KOSHER,
        ALLERGY
    }

    public UserPreferences {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("偏好 owner 不能为空");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("偏好 schemaVersion 无效");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("偏好 revision 不能为负数");
        }
        if (createdAt < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("偏好时间戳不能为负数");
        }
        interests = normalizeInterests(interests);
        walkingTolerance = walkingTolerance == null ? WalkingTolerance.UNSPECIFIED : walkingTolerance;
        budget = budget == null ? BudgetPreference.unspecified() : budget;
        companions = companions == null ? CompanionPreference.UNSPECIFIED : companions;
        transportPreference = transportPreference == null
                ? TransportPreference.UNSPECIFIED : transportPreference;
        dietPreference = dietPreference == null ? DietPreference.UNSPECIFIED : dietPreference;
        stayArea = normalizeText(stayArea, 255, "stayArea");
        legacyMetadata = normalizeMetadata(legacyMetadata);
    }

    public static UserPreferences empty(String userId, long revision, long timestamp) {
        return new UserPreferences(userId, PreferencesSchema.CURRENT_SCHEMA_VERSION, revision,
                List.of(), WalkingTolerance.UNSPECIFIED, BudgetPreference.unspecified(),
                CompanionPreference.UNSPECIFIED, TransportPreference.UNSPECIFIED,
                DietPreference.UNSPECIFIED, "", Map.of(), timestamp, timestamp);
    }

    private static List<String> normalizeInterests(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 32) throw new IllegalArgumentException("兴趣数量不能超过 32 个");
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null) continue;
            String normalized = value.trim();
            if (normalized.isEmpty()) continue;
            if (normalized.length() > 64) throw new IllegalArgumentException("单个兴趣过长");
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        return Collections.unmodifiableList(new ArrayList<>(unique.values()));
    }

    private static String normalizeText(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " 过长");
        return normalized;
    }

    private static Map<String, Object> normalizeMetadata(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > 64) throw new IllegalArgumentException("legacy metadata 字段过多");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty() || key.length() > 128) {
                throw new IllegalArgumentException("legacy metadata key 无效");
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}

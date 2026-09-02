package com.ai.guide.domain.preferences.api;

import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户偏好增量更新补丁模型
 *
 * 所属领域：domain.preferences.api（用户偏好画像 API 层）
 * 架构职责：承载客户端提交的偏好增量更新字段集合。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record UserPreferencesPatch(
        List<String> interests,
        UserPreferences.WalkingTolerance walkingTolerance,
        BudgetPreference budget,
        UserPreferences.CompanionPreference companions,
        UserPreferences.TransportPreference transportPreference,
        UserPreferences.DietPreference dietPreference,
        String stayArea,
        Map<String, Object> legacyMetadata,
        Long expectedRevision) {

    public UserPreferencesPatch {
        if (expectedRevision != null && expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负数");
        }
        interests = interests == null
                ? null : Collections.unmodifiableList(new java.util.ArrayList<>(interests));
        legacyMetadata = legacyMetadata == null
                ? null : Collections.unmodifiableMap(new LinkedHashMap<>(legacyMetadata));
    }

    public static UserPreferencesPatch empty() {
        return new UserPreferencesPatch(null, null, null, null, null, null, null, null, null);
    }

    public UserPreferences mergeOnto(UserPreferences current) {
        return new UserPreferences(
                current.userId(),
                PreferencesSchema.CURRENT_SCHEMA_VERSION,
                current.revision(),
                interests == null ? current.interests() : interests,
                walkingTolerance == null ? current.walkingTolerance() : walkingTolerance,
                budget == null ? current.budget() : budget,
                companions == null ? current.companions() : companions,
                transportPreference == null ? current.transportPreference() : transportPreference,
                dietPreference == null ? current.dietPreference() : dietPreference,
                stayArea == null ? current.stayArea() : stayArea,
                legacyMetadata == null ? current.legacyMetadata() : legacyMetadata,
                current.createdAt(),
                current.updatedAt());
    }

    public UserPreferences replaceOnto(String userId, long revision, long createdAt, long updatedAt) {
        return new UserPreferences(
                userId,
                PreferencesSchema.CURRENT_SCHEMA_VERSION,
                revision,
                interests,
                walkingTolerance,
                budget,
                companions,
                transportPreference,
                dietPreference,
                stayArea,
                legacyMetadata,
                createdAt,
                updatedAt);
    }
}

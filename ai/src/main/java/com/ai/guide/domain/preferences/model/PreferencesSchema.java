package com.ai.guide.domain.preferences.model;

import java.util.List;

/** Canonical schema and normalization identifiers for formal user preferences. */
public /**
 * 用户偏好模式定义与默认值常量
 *
 * 所属领域：domain.preferences.model（用户偏好画像领域模型）
 */
final class PreferencesSchema {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String NORMALIZATION_VERSION = "phase5c-normalization-v1";
    public static final String SCOPE = "FORMAL_USER_PREFERENCES";
    public static final List<String> CANONICAL_FIELDS = List.of(
            "interests",
            "walkingTolerance",
            "budget",
            "companions",
            "transportPreference",
            "dietPreference",
            "stayArea"
    );

    private PreferencesSchema() {
    }
}

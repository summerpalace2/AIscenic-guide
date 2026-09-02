package com.ai.guide.domain.planner.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 初始多天行程智能规划请求 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：封装用户初始发起的规划请求（包含自然语言 Prompt、天数、同行人、偏好标签、出发地与预算等级）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanRequest {
    @Builder.Default
    private String prompt = "";
    @Builder.Default
    private String freeText = "";
    @Builder.Default
    private String arrivalAt = "";
    @Builder.Default
    private String departureAt = "";
    private StructuredPreferences structuredPreferences;
    @Builder.Default
    private Map<String, Object> constraints = new LinkedHashMap<>();
    @Builder.Default
    private Boolean usePreferences = false;
    @Builder.Default
    private String preferenceDecision = "";
    @Builder.Default
    private String idempotencyKey = "";
    @Builder.Default
    private String sessionAccessToken = "";

    public String effectivePrompt() {
        if (freeText != null && !freeText.isBlank()) return freeText.trim();
        return prompt == null ? "" : prompt.trim();
    }

    public Map<String, Object> effectiveConstraints() {
        Map<String, Object> map = new LinkedHashMap<>(constraints == null ? Map.of() : constraints);
        if (structuredPreferences != null) {
            structuredPreferences.asMap().forEach(map::putIfAbsent);
        }
        if (arrivalAt != null && !arrivalAt.isBlank()) map.put("arrivalAt", arrivalAt.trim());
        if (departureAt != null && !departureAt.isBlank()) map.put("departureAt", departureAt.trim());
        return map;
    }
}

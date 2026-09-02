package com.ai.guide.domain.planner.api;

import com.ai.guide.domain.trip.model.Trip;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子测试多天规划请求 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：用于服务端影子模式并发验证 Java Planner 与 Node Planner 一致性的无副作用入参。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowPlanRequest {
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

    public String effectivePrompt() {
        if (freeText != null && !freeText.isBlank()) return freeText.trim();
        return prompt == null ? "" : prompt.trim();
    }

    public Map<String, Object> effectiveConstraints() {
        Map<String, Object> map = new LinkedHashMap<>(constraints == null ? Map.of() : constraints);
        if (structuredPreferences != null) {
            map.putAll(structuredPreferences.asMap());
        }
        if (arrivalAt != null && !arrivalAt.isBlank()) map.put("arrivalAt", arrivalAt.trim());
        if (departureAt != null && !departureAt.isBlank()) map.put("departureAt", departureAt.trim());
        return map;
    }
}

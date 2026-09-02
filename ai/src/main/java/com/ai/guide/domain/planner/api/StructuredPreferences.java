package com.ai.guide.domain.planner.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划引擎结构化偏好输入模型
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：承载规划时解析出的强类型偏好标签（步行耐受度、交通方式偏好、饮食口味偏好及住宿区域）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredPreferences {
    private Integer durationDays;
    private String companion;
    private String walkingPreference;
    private String transportPreference;
    private String dietPreference;
    private List<String> interests;
    private String stayArea;
    private String budget;
    private List<String> mustVisit;
    private List<String> avoid;
    @Builder.Default
    private Map<String, Object> extra = new LinkedHashMap<>();

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (durationDays != null) map.put("durationDays", durationDays);
        if (companion != null && !companion.isBlank()) map.put("companions", companion);
        if (walkingPreference != null && !walkingPreference.isBlank()) map.put("walkingTolerance", walkingPreference);
        if (transportPreference != null && !transportPreference.isBlank()) map.put("transportPreference", transportPreference);
        if (dietPreference != null && !dietPreference.isBlank()) map.put("dietPreference", dietPreference);
        if (interests != null && !interests.isEmpty()) map.put("interests", interests);
        if (stayArea != null && !stayArea.isBlank()) map.put("stayArea", stayArea);
        if (budget != null && !budget.isBlank()) map.put("budget", budget);
        if (mustVisit != null && !mustVisit.isEmpty()) map.put("mustVisit", mustVisit);
        if (avoid != null && !avoid.isEmpty()) map.put("avoid", avoid);
        if (extra != null && !extra.isEmpty()) map.putAll(extra);
        return map;
    }
}

package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划版本与算法策略元数据
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlannerVersionMetadata {

    public static final String CURRENT_PLANNER_VERSION = "1.0.0-v1";
    public static final String CURRENT_POLICY_VERSION = "2026.08-v1";
    public static final String EXPLANATION_SOURCE_TEMPLATE = "TEMPLATE";
    public static final String EXPLANATION_SOURCE_LLM_GROUNDED = "LLM_GROUNDED";

    @Builder.Default
    private String plannerVersion = CURRENT_PLANNER_VERSION;
    @Builder.Default
    private String policyVersion = CURRENT_POLICY_VERSION;
    @Builder.Default
    private String routeDataStatus = "ESTIMATED";
    @Builder.Default
    private String explanationSource = EXPLANATION_SOURCE_TEMPLATE;
    @Builder.Default
    private boolean degraded = false;
    @Builder.Default
    private List<String> degradationReasons = new ArrayList<>();

    public static PlannerVersionMetadata defaultV1(String routeDataStatus, boolean degraded, List<String> degradationReasons) {
        return PlannerVersionMetadata.builder()
                .plannerVersion(CURRENT_PLANNER_VERSION)
                .policyVersion(CURRENT_POLICY_VERSION)
                .routeDataStatus(routeDataStatus == null ? "ESTIMATED" : routeDataStatus)
                .explanationSource(EXPLANATION_SOURCE_TEMPLATE)
                .degraded(degraded)
                .degradationReasons(degradationReasons == null ? List.of() : new ArrayList<>(degradationReasons))
                .build();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("plannerVersion", plannerVersion);
        map.put("policyVersion", policyVersion);
        map.put("routeDataStatus", routeDataStatus);
        map.put("explanationSource", explanationSource);
        map.put("degraded", degraded);
        map.put("degradationReasons", degradationReasons == null ? List.of() : new ArrayList<>(degradationReasons));
        return map;
    }
}

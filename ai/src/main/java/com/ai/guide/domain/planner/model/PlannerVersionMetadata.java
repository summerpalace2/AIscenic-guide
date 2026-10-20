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

    /** Response metadata follows the authoritative snapshot, including incomplete generation. */
    public static PlannerVersionMetadata fromTrip(Map<String, Object> trip, boolean v1Enabled) {
        boolean degraded = !v1Enabled || trip == null || Boolean.TRUE.equals(trip.get("degraded"))
                || !"高德实时接口".equals(trip.get("sourceMode"));
        List<String> reasons = new ArrayList<>();
        if (!v1Enabled) reasons.add("LEGACY_PLANNER_ACTIVE");
        else {
            if (trip != null && trip.get("degradationReasons") instanceof List<?> values)
                for (Object value : values) if (value != null && !String.valueOf(value).isBlank()) reasons.add(String.valueOf(value));
            if (degraded && reasons.isEmpty()) reasons.add("DEMO_OR_ESTIMATED_ROUTE_FALLBACK");
        }
        return PlannerVersionMetadata.builder()
                .plannerVersion(v1Enabled ? CURRENT_PLANNER_VERSION : "legacy-v0")
                .policyVersion(v1Enabled ? CURRENT_POLICY_VERSION : "legacy-2026.07")
                .routeDataStatus(trip == null ? "ESTIMATED" : String.valueOf(trip.getOrDefault("routeDataStatus", "ESTIMATED")))
                .explanationSource(!v1Enabled ? "LEGACY_STATIC" : trip != null && trip.get("explanationSource") != null
                        ? String.valueOf(trip.get("explanationSource")) : EXPLANATION_SOURCE_TEMPLATE)
                .degraded(degraded).degradationReasons(reasons).build();
    }

    public static boolean planningIncomplete(Map<String, Object> trip) {
        return trip != null && (trip.get("spatialPlan") instanceof Map<?, ?> spatial
                && "PARTIAL_TIMEOUT".equals(spatial.get("status"))
                || trip.get("degradationReasons") instanceof List<?> reasons
                    && (reasons.contains("PLANNING_DEADLINE_EXCEEDED")
                        || reasons.contains("CATALOG_CANDIDATES_UNRESOLVED")));
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

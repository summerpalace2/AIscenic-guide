package com.ai.guide.domain.planner.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案调整多选项预览响应 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：承载规划引擎生成的 1~3 个调整选项、路线变更对比（changedSegments）、可行性校验结果及推荐理由。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanAdjustmentPreviewDto {
    @Builder.Default
    private boolean ok = true;
    private String proposalId;
    private String sessionId;
    private int baseRevision;
    @Builder.Default
    private Map<String, Object> intent = new LinkedHashMap<>();
    @Builder.Default
    private List<Map<String, Object>> candidateReplacements = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> proposedTrip = new LinkedHashMap<>();
    @Builder.Default
    private List<String> changedSegments = new ArrayList<>();
    @Builder.Default
    private List<String> unchangedStops = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> verification = new LinkedHashMap<>();
    @Builder.Default
    private boolean feasible = true;
    @Builder.Default
    private List<String> reasonCodes = new ArrayList<>();
    @Builder.Default
    private List<String> alternatives = new ArrayList<>();
    private long expiresAt;
    @Builder.Default
    private String message = "";
}

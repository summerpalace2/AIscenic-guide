package com.ai.guide.domain.planner.model;

import com.ai.guide.domain.planner.service.ProposalStore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划调整提案聚合模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：保存服务端为用户生成的调整方案提案实体（包含 1~N 个独立排程选项 OptionPlans、过期时间及变更片段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanAdjustmentProposal {
    private String proposalId;
    private String sessionId;
    private int baseRevision;
    private PlanAdjustmentIntent intent;
    @Builder.Default
    private Map<String, Object> proposedTrip = new LinkedHashMap<>();
    @Builder.Default
    private Map<String, Map<String, Object>> optionPlans = new LinkedHashMap<>();
    @Builder.Default
    private List<Map<String, Object>> candidateReplacements = new ArrayList<>();
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
    private long createdAt;
    private long expiresAt;

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("proposalId", proposalId);
        map.put("sessionId", sessionId);
        map.put("baseRevision", baseRevision);
        map.put("intent", intent == null ? Map.of() : intent.asMap());
        map.put("proposedTrip", proposedTrip);
        map.put("optionPlans", optionPlans);
        map.put("candidateReplacements", candidateReplacements);
        map.put("changedSegments", changedSegments);
        map.put("unchangedStops", unchangedStops);
        map.put("verification", verification);
        map.put("feasible", feasible);
        map.put("reasonCodes", reasonCodes);
        map.put("alternatives", alternatives);
        map.put("createdAt", createdAt);
        map.put("expiresAt", expiresAt);
        return map;
    }
}

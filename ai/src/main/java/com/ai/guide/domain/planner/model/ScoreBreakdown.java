package com.ai.guide.domain.planner.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程方案综合评分细分明细
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 包含：时间合理性、偏好契合度、交通紧凑度、整体顺畅度及扣分原因。
 */
public record ScoreBreakdown(
        int interestScore,
        int companionScore,
        int walkingScore,
        int diningScore,
        int mustVisitScore,
        int avoidPenalty,
        int explicitBonus,
        int totalScore,
        List<String> reasonCodes
) {
    public static ScoreBreakdown zero() {
        return new ScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("interest", interestScore);
        map.put("companion", companionScore);
        map.put("walking", walkingScore);
        map.put("dining", diningScore);
        map.put("mustVisit", mustVisitScore);
        map.put("avoid", avoidPenalty);
        map.put("explicit", explicitBonus);
        map.put("total", totalScore);
        map.put("reasonCodes", new ArrayList<>(reasonCodes == null ? List.of() : reasonCodes));
        return map;
    }
}

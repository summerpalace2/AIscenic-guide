package com.ai.guide.domain.planner.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程约束冲突诊断模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：记录排程过程中发现的具体约束冲突详情（冲突字段、用户偏好来源、冲突原因与建议修复方案）。
 */
public record ConstraintConflict(
        String field,
        String conflictType,
        String message,
        Object primaryValue,
        ConstraintOrigin primaryOrigin,
        Object secondaryValue,
        ConstraintOrigin secondaryOrigin
) {
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field == null ? "" : field);
        map.put("conflictType", conflictType == null ? "" : conflictType);
        map.put("message", message == null ? "" : message);
        map.put("primaryValue", primaryValue == null ? "" : String.valueOf(primaryValue));
        map.put("primaryOrigin", primaryOrigin == null ? "" : primaryOrigin.name().toLowerCase());
        map.put("secondaryValue", secondaryValue == null ? "" : String.valueOf(secondaryValue));
        map.put("secondaryOrigin", secondaryOrigin == null ? "" : secondaryOrigin.name().toLowerCase());
        return map;
    }
}

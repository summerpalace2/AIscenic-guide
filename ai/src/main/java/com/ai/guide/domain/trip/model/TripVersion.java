package com.ai.guide.domain.trip.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 正式行程历史版本快照实体
 *
 * 所属领域：domain.trip.model（正式行程领域模型）
 * 架构职责：保存行程在每次修改时的不可变全量 JSON 快照及变更原因说明。
 */
public record TripVersion(
        String id,
        String tripId,
        int versionNumber,
        Map<String, Object> snapshot,
        String changeReason,
        long createdAt,
        String createdBy
) {
    public TripVersion {
        snapshot = snapshot == null ? Map.of() : new LinkedHashMap<>(snapshot);
        changeReason = changeReason == null ? "" : changeReason;
        createdBy = createdBy == null ? "" : createdBy;
    }
}

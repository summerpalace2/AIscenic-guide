package com.ai.guide.domain.trip.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 正式行程聚合根实体
 *
 * 所属领域：domain.trip.model（正式行程领域模型）
 * 包含：行程 ID、拥有者 ID、标题、当前版本号、创建/更新时间戳及包含每日站点的完整排程数据。
 */
public record Trip(
        String id,
        String ownerId,
        String title,
        String status,
        int currentVersion,
        long createdAt,
        long updatedAt,
        Map<String, Object> plan
) {
    public Trip {
        plan = plan == null ? Map.of() : new LinkedHashMap<>(plan);
    }
}

package com.ai.guide.domain.geo.model;

import java.time.Instant;

/**
 * 单模式路线计算结果领域模型
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：封装单出行方式（如步行、公交）路线结果、执行状态及端点信息。
 *
 * @param status    调用状态
 * @param segment   归一化路线数据
 * @param reason    原因说明
 * @param endpoint  请求端点
 * @param fetchedAt 获取时间戳
 */
public record GeoRouteOutcome(
        GeoOutcomeStatus status,
        GeoRouteSegment segment,
        String reason,
        String endpoint,
        Instant fetchedAt
) {
}

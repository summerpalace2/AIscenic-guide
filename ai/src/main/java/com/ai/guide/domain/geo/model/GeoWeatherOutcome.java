package com.ai.guide.domain.geo.model;

import java.time.Instant;

/**
 * 天气查询结果领域模型
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：封装城市或区域的天气实况/预报查询结果。
 *
 * @param status    调用状态
 * @param weather   天气信息数据
 * @param reason    失败原因说明
 * @param fetchedAt 获取时间戳
 */
public record GeoWeatherOutcome(
        GeoOutcomeStatus status,
        GeoWeatherForecast weather,
        String reason,
        Instant fetchedAt
) {
}

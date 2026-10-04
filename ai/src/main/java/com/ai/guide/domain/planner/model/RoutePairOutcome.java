package com.ai.guide.domain.planner.model;

/**
 * 点对点多模式综合路线结果领域模型
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：封装起终点之间步行、公交及最优选定模式的路线决策结果。
 *
 * @param walking         步行路线结果
 * @param transit         公交地铁路线结果
 * @param selected        基于偏好选定的推荐路线结果
 * @param preference      出行偏好
 * @param taxiUnsupported 是否因距离或策略不支持打车模式
 */
public record RoutePairOutcome(
        RouteOutcome walking,
        RouteOutcome transit,
        RouteOutcome selected,
        String preference,
        boolean taxiUnsupported
) {
}

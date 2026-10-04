package com.ai.guide.domain.planner.model;

/**
 * 规划路线与动态数据水化结果
 *
 * 所属领域：domain.planner.model（规划引擎领域模型）
 * 架构职责：记录高德地图等外部服务在针对行程做 POI、路线、天气水化（Hydration）时的统计与降级状态。
 */
public record HydrationResult(String mode, int poiSuccess, int routeSuccess,
                              int weatherSuccess, boolean fallback) {
}

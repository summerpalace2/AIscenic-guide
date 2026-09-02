package com.ai.guide.domain.planner.service;

import java.util.Map;

/**
 * 路线计算网关标准端口接口
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
public interface RouteGatewayPort {

    AmapPlannerGateway.HydrationResult hydrate(Map<String, Object> trip);
}

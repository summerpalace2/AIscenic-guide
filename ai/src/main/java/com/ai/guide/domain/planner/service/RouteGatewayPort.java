package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.TravelConstraints;
import java.util.Map;

/**
 * 路线计算网关标准端口接口
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
public interface RouteGatewayPort {

    AmapPlannerGateway.HydrationResult hydrate(Map<String, Object> trip);

    /**
     * Builds a runtime nearby plan when the user supplied an explicit start
     * place and a short time budget. Static test doubles may keep the default.
     */
    default Map<String, Object> planFromLocation(TravelConstraints constraints, int version) {
        return null;
    }

    /** Applies a local mutation against a runtime AMap plan when supported. */
    default Map<String, Object> runtimeAdjustment(Map<String, Object> trip,
                                                  TravelConstraints constraints,
                                                  String targetStopId,
                                                  String candidateVenueId,
                                                  String reason) {
        return null;
    }
}

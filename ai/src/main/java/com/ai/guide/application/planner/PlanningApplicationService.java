package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;

/**
 * 智能规划应用层门面接口
 *
 * 架构定位：COLA 应用层 (Application Layer) - 用例接口。
 * 核心职责：定义多天行程初始排程、会话生命周期、多天局部重排、站点增删替换及对话式调整等核心用例契约。
 */
public interface PlanningApplicationService {

    PlannerService.ServiceResult create(PlanRequest request);

    PlannerService.ServiceResult shadow(ShadowPlanRequest request);

    PlannerService.ServiceResult shadowReplan(ShadowMutationRequest request);

    PlannerService.ServiceResult shadowMutateStops(ShadowMutationRequest request);

    PlannerService.ServiceResult get(String sessionId, String accessToken);

    PlannerService.ServiceResult refreshDynamicData(String sessionId, String accessToken);

    PlannerService.ServiceResult list();

    PlannerService.ServiceResult replan(String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult mutateStops(String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult save(String sessionId, String accessToken);

    PlannerService.ServiceResult openFormalTrip(String tripId);

    PlannerService.ServiceResult converse(String sessionId, PlanConversationRequestDto request);

    PlannerService.ServiceResult previewAdjustment(String sessionId, PlanConversationRequestDto request);

    PlannerService.ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request);
}

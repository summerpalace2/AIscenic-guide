package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.api.PlannerMutationRequest;
import com.ai.guide.domain.planner.api.ShadowMutationRequest;
import com.ai.guide.domain.planner.api.ShadowPlanRequest;

/**
 * 智能规划应用层门面接口
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
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

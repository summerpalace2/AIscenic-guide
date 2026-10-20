package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
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

    PlannerService.ServiceResult updatePins(PlanningActor actor, String sessionId,
            com.ai.guide.domain.planner.model.PlannerPinsRequest request);

    PlannerService.ServiceResult replan(String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult replan(PlanningActor actor, String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult mutateStops(String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult mutateStops(PlanningActor actor, String sessionId, PlannerMutationRequest request);

    PlannerService.ServiceResult save(String sessionId, String accessToken);

    PlannerSaveResult save(PlanningActor actor, String sessionId, String accessToken);

    PlannerService.ServiceResult openFormalTrip(String tripId);
    PlannerOpenResult openFormalTrip(PlanningActor actor, String tripId);
    PlannerOpenResult openFormalTrip(PlanningActor actor, String tripId, String operationKey);
    PlannerOpenResult acknowledgeOpenSession(PlanningActor actor, String tripId, String sessionId);
    PlannerOpenResult discardUncommittedOpen(PlanningActor actor, String tripId, String sessionId);
    PlannerService.ServiceResult restoreDraft(String sessionId, RestoreDraftRequest request);
    PlannerRestoreResult restoreDraft(PlanningActor actor, String sessionId, RestoreDraftRequest request);

    PreviewAdjustmentResult converse(PlanningActor actor, String sessionId, PlanConversationRequestDto request);

    PreviewAdjustmentResult previewAdjustment(PlanningActor actor, String sessionId, PlanConversationRequestDto request);

    ApplyAdjustmentResult applyAdjustment(PlanningActor actor, String sessionId, ApplyAdjustmentRequestDto request);

    /** Compatibility facade for legacy callers; HTTP adapters use the explicit-actor use case above. */
    PlannerService.ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request);
}

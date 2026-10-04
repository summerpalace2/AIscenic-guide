package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import org.springframework.stereotype.Service;

/**
 * 智能排程规划应用服务 (Planner Application Service)
 *
 * 架构定位：COLA 应用层 (Application Layer) - 用例编排中心。
 * 核心职责：作为文旅规划决策的核心应用编排层，实现 {@link PlanningApplicationService} 契约，
 * 统筹多天行程排程计算、草稿会话管理、多模式路线水化、局部去重重排、站点原子编辑以及对话式调整提案确认，
 * 隔离适配层 HTTP 协议与内部排程领域业务规则。
 */
@Service
public class PlannerApplicationService implements PlanningApplicationService {

    private final PlannerService plannerService;

    public PlannerApplicationService(PlannerService plannerService) {
        this.plannerService = plannerService;
    }

    @Override
    public PlannerService.ServiceResult create(PlanRequest request) {
        return plannerService.create(request);
    }

    @Override
    public PlannerService.ServiceResult shadow(ShadowPlanRequest request) {
        return plannerService.shadow(request);
    }

    @Override
    public PlannerService.ServiceResult shadowReplan(ShadowMutationRequest request) {
        return plannerService.shadowReplan(request);
    }

    @Override
    public PlannerService.ServiceResult shadowMutateStops(ShadowMutationRequest request) {
        return plannerService.shadowMutateStops(request);
    }

    @Override
    public PlannerService.ServiceResult get(String sessionId, String accessToken) {
        return plannerService.get(sessionId, accessToken);
    }

    @Override
    public PlannerService.ServiceResult refreshDynamicData(String sessionId, String accessToken) {
        return plannerService.refreshDynamicData(sessionId, accessToken);
    }

    @Override
    public PlannerService.ServiceResult list() {
        return plannerService.list();
    }

    @Override
    public PlannerService.ServiceResult replan(String sessionId, PlannerMutationRequest request) {
        return plannerService.replan(sessionId, request);
    }

    @Override
    public PlannerService.ServiceResult mutateStops(String sessionId, PlannerMutationRequest request) {
        return plannerService.mutateStops(sessionId, request);
    }

    @Override
    public PlannerService.ServiceResult save(String sessionId, String accessToken) {
        return plannerService.save(sessionId, accessToken);
    }

    @Override
    public PlannerService.ServiceResult openFormalTrip(String tripId) {
        return plannerService.openFormalTrip(tripId);
    }

    @Override
    public PlannerService.ServiceResult converse(String sessionId, PlanConversationRequestDto request) {
        return plannerService.converse(sessionId, request);
    }

    @Override
    public PlannerService.ServiceResult previewAdjustment(String sessionId, PlanConversationRequestDto request) {
        return plannerService.previewAdjustment(sessionId, request);
    }

    @Override
    public PlannerService.ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request) {
        return plannerService.applyAdjustment(sessionId, request);
    }
}

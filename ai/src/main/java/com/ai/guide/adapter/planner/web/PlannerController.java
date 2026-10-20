package com.ai.guide.adapter.planner.web;

import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.planner.ApplyAdjustmentResult;
import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PreviewAdjustmentResult;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能排程规划引擎与会话调整适配器控制器 (Planner Controller)
 *
 * 架构定位：COLA 适配层 (Adapter Layer) - Web 协议入口。
 * 核心职责：作为文旅规划决策的外部 HTTP 访问入口，负责入参协议解析、X-Plan-Session-Token 请求头提取、
 * 委派应用服务编排 {@link PlanningApplicationService}，并精确输出排程草稿状态、多天局部重排结果及对话式方案提案。
 *
 * 核心对外接口：
 * 1. {@link #plan}: 执行多天行程初始排程计算，创建规划会话并生成初始行程草稿。
 * 2. {@link #shadow}: 无状态静态候选规划（专供 Web Shadow 流程，不创建会话与持久化）。
 * 3. {@link #shadowReplan}: 无状态局部去重重排。
 * 4. {@link #shadowStops}: 无状态站点直接原子编辑。
 * 5. {@link #openFormalTrip}: 将已有正式行程 (Trip) 反向解构并在规划器中加载草稿。
 * 6. {@link #list}: 查询当前登录用户的全部活跃规划会话。
 * 7. {@link #get}: 查询进行中的规划会话上下文与当前草稿状态。
 * 8. {@link #refreshDynamicData}: 强制触发高德路线、天气及 POI 动态水化刷新。
 * 9. {@link #replan}: 对指定某天的某个景点发起局部重排（局部修复与候选去重替换）。
 * 10. {@link #stops}: 针对站点的直接原子操作（添加景点、删除站点、调整顺序）。
 * 11. {@link #save}: 将当前满意的规划会话转正持久化为正式行程 (Trip)。
 * 12. {@link #converse}: 基于自然语言对话提取用户的修改诉求或景点百科问答。
 * 13. {@link #previewAdjustment}: 生成调整方案预览（包含 1~3 个候选方案及路线差异比对）。
 * 14. {@link #applyAdjustment}: 确认并应用选定的方案选项，完成草稿原子升版。
 */
@RestController
@RequestMapping("/ai/planner/v1")
public class PlannerController {

    private final PlanningApplicationService plannerService;

    public PlannerController(PlanningApplicationService plannerService) {
        this.plannerService = plannerService;
    }

    @PostMapping("/plan")
    public ResponseEntity<?> plan(
            @RequestBody(required = false) PlanRequest request,
            @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanRequest effective = request == null ? new PlanRequest() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        return response(plannerService.create(effective));
    }

    /**
     * Stateless Java candidate used only by the Web Shadow path. It never
     * creates a planner session, formal Trip, idempotency record, or capability.
     */
    @PostMapping("/shadow")
    public ResponseEntity<?> shadow(@RequestBody(required = false) ShadowPlanRequest request) {
        return response(plannerService.shadow(request));
    }

    @PostMapping("/shadow/replan")
    public ResponseEntity<?> shadowReplan(@RequestBody(required = false) ShadowMutationRequest request) {
        return response(plannerService.shadowReplan(request));
    }

    @PostMapping("/shadow/stops")
    public ResponseEntity<?> shadowStops(@RequestBody(required = false) ShadowMutationRequest request) {
        return response(plannerService.shadowMutateStops(request));
    }

    @PostMapping("/trips/{tripId}/open")
    public ResponseEntity<?> openFormalTrip(@PathVariable String tripId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String operationKey) {
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PlannerOpenResult result = plannerService.openFormalTrip(actor, tripId, operationKey);
        return ResponseEntity.status(result.serviceStatus()).body(result.body());
    }

    @PostMapping("/trips/{tripId}/open-sessions/{sessionId}/ack")
    public ResponseEntity<?> acknowledgeOpenSession(@PathVariable String tripId, @PathVariable String sessionId) {
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PlannerOpenResult result = plannerService.acknowledgeOpenSession(actor, tripId, sessionId);
        return ResponseEntity.status(result.serviceStatus()).body(result.body());
    }

    @DeleteMapping("/trips/{tripId}/open-sessions/{sessionId}")
    public ResponseEntity<?> discardUncommittedOpen(@PathVariable String tripId, @PathVariable String sessionId) {
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PlannerOpenResult result = plannerService.discardUncommittedOpen(actor, tripId, sessionId);
        return ResponseEntity.status(result.serviceStatus()).body(result.body());
    }

    @PostMapping("/sessions/{sessionId}/restore-draft")
    public ResponseEntity<?> restoreDraft(@PathVariable String sessionId,
                                          @RequestBody RestoreDraftRequest request,
                                          @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        RestoreDraftRequest effective = request == null ? null : new RestoreDraftRequest(
                request.trip(), request.expectedVersion(),
                request.sessionAccessToken() == null || request.sessionAccessToken().isBlank()
                        ? token : request.sessionAccessToken());
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PlannerRestoreResult result = plannerService.restoreDraft(actor, sessionId, effective);
        return ResponseEntity.status(result.serviceStatus()).body(result.body());
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> list() {
        return response(plannerService.list());
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<?> get(@PathVariable String sessionId,
                                 @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        return response(plannerService.get(sessionId, token));
    }

    @PostMapping("/sessions/{sessionId}/dynamic-refresh")
    public ResponseEntity<?> refreshDynamicData(@PathVariable String sessionId,
                                                @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        return response(plannerService.refreshDynamicData(sessionId, token));
    }

    @PostMapping("/sessions/{sessionId}/replan")
    public ResponseEntity<?> replan(@PathVariable String sessionId,
                                    @RequestBody(required = false) PlannerMutationRequest request,
                                    @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlannerMutationRequest effective = request == null ? new PlannerMutationRequest() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) effective.setSessionAccessToken(token == null ? "" : token);
        return response(plannerService.replan(new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous()), sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/stops")
    public ResponseEntity<?> stops(@PathVariable String sessionId,
                                   @RequestBody(required = false) PlannerMutationRequest request,
                                   @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlannerMutationRequest effective = request == null ? new PlannerMutationRequest() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) effective.setSessionAccessToken(token == null ? "" : token);
        return response(plannerService.mutateStops(new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous()), sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/pins")
    public ResponseEntity<?> updatePins(@PathVariable String sessionId,
            @RequestBody(required = false) com.ai.guide.domain.planner.model.PlannerPinsRequest request,
            @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        var effective = request == null ? null : new com.ai.guide.domain.planner.model.PlannerPinsRequest(
                request.expectedVersion(), request.addStopIds(), request.removeStopIds(),
                request.sessionAccessToken() == null || request.sessionAccessToken().isBlank()
                        ? token : request.sessionAccessToken());
        return response(plannerService.updatePins(new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous()),
                sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/save")
    public ResponseEntity<?> save(@PathVariable String sessionId,
                                  @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PlannerSaveResult result = plannerService.save(actor, sessionId, token);
        return ResponseEntity.status(result.serviceStatus()).body(result.body());
    }

    @PostMapping("/sessions/{sessionId}/conversation")
    public ResponseEntity<?> converse(@PathVariable String sessionId,
                                      @RequestBody(required = false) PlanConversationRequestDto request,
                                      @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanConversationRequestDto effective = request == null ? new PlanConversationRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PreviewAdjustmentResult result = plannerService.converse(actor, sessionId, effective);
        return ResponseEntity.status(httpStatus(result.outcome())).body(result.body());
    }

    @PostMapping("/sessions/{sessionId}/adjust/preview")
    public ResponseEntity<?> previewAdjustment(@PathVariable String sessionId,
                                               @RequestBody(required = false) PlanConversationRequestDto request,
                                               @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanConversationRequestDto effective = request == null ? new PlanConversationRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        PreviewAdjustmentResult result = plannerService.previewAdjustment(actor, sessionId, effective);
        return ResponseEntity.status(httpStatus(result.outcome())).body(result.body());
    }

    @PostMapping("/sessions/{sessionId}/adjust/apply")
    public ResponseEntity<?> applyAdjustment(@PathVariable String sessionId,
                                             @RequestBody(required = false) ApplyAdjustmentRequestDto request,
                                             @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        ApplyAdjustmentRequestDto effective = request == null ? new ApplyAdjustmentRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        PlanningActor actor = new PlanningActor(UserContext.getUserId(), UserContext.isAnonymous());
        ApplyAdjustmentResult result = plannerService.applyAdjustment(actor, sessionId, effective);
        return ResponseEntity.status(httpStatus(result.outcome())).body(result.body());
    }

    private ResponseEntity<?> response(PlannerService.ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }

    private int httpStatus(PreviewAdjustmentResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS, CLARIFICATION, NO_CANDIDATE, PREVIEW_UNAPPLICABLE -> 200;
            case BAD_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case UNPROCESSABLE -> 422;
            case TOO_MANY_REQUESTS -> 429;
            case SERVICE_UNAVAILABLE -> 503;
            case INTERNAL_ERROR -> 500;
        };
    }

    private int httpStatus(ApplyAdjustmentResult.Outcome outcome) {
        return switch (outcome) {
            case SUCCESS -> 200;
            case BAD_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case INTERNAL_ERROR -> 500;
        };
    }
}

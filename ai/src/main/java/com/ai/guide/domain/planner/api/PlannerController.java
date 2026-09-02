package com.ai.guide.domain.planner.api;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.planner.service.PlanningApplicationService;
import com.ai.guide.domain.planner.service.PlannerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能排程规划引擎与会话调整控制器 (Planner Controller)
 *
 * 所属领域：domain.planner (智能排程与规划引擎域)
 * 架构职责：作为文旅规划决策的核心中枢，负责意图槽位解析、24景候选召回、高德路线耗时测算、多天动态规划 (RouteAwarePlanner)、局部快速重规划以及对话式方案提案与确认。
 *
 * 核心对外接口与关键方法：
 * 1. {@link #plan}: 执行多天行程初始排程计算，创建规划会话并生成初始行程草稿。
 *    - 关键参数：request (包含用户自由 prompt、天数、同行人、偏好标签及可选硬约束)。
 * 2. {@link #get}: 查询进行中的规划会话上下文与当前草稿状态。
 * 3. {@link #replan}: 对指定某天的某个景点发起局部重排（局部修复与候选去重替换）。
 * 4. {@link #stops}: 针对站点的直接原子操作（添加景点、删除站点、调整顺序）。
 * 5. {@link #conversation}: 基于自然语言对话提取用户的修改诉求或景点百科问答。
 * 6. {@link #previewAdjustment}: 生成调整方案预览（包含 1~3 个候选方案及路线差异比对）。
 * 7. {@link #applyAdjustment}: 确认并应用选定的方案选项，完成草稿原子升版。
 * 8. {@link #save}: 将当前满意的规划会话转正持久化为正式行程 (Trip)。
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
    public ResponseEntity<?> openFormalTrip(@PathVariable String tripId) {
        return response(plannerService.openFormalTrip(tripId));
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
        return response(plannerService.replan(sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/stops")
    public ResponseEntity<?> stops(@PathVariable String sessionId,
                                   @RequestBody(required = false) PlannerMutationRequest request,
                                   @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlannerMutationRequest effective = request == null ? new PlannerMutationRequest() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) effective.setSessionAccessToken(token == null ? "" : token);
        return response(plannerService.mutateStops(sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/save")
    public ResponseEntity<?> save(@PathVariable String sessionId,
                                  @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        return response(plannerService.save(sessionId, token));
    }

    @PostMapping("/sessions/{sessionId}/conversation")
    public ResponseEntity<?> converse(@PathVariable String sessionId,
                                      @RequestBody(required = false) PlanConversationRequestDto request,
                                      @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanConversationRequestDto effective = request == null ? new PlanConversationRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        return response(plannerService.converse(sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/adjust/preview")
    public ResponseEntity<?> previewAdjustment(@PathVariable String sessionId,
                                               @RequestBody(required = false) PlanConversationRequestDto request,
                                               @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        PlanConversationRequestDto effective = request == null ? new PlanConversationRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        return response(plannerService.previewAdjustment(sessionId, effective));
    }

    @PostMapping("/sessions/{sessionId}/adjust/apply")
    public ResponseEntity<?> applyAdjustment(@PathVariable String sessionId,
                                             @RequestBody(required = false) ApplyAdjustmentRequestDto request,
                                             @RequestHeader(value = "X-Plan-Session-Token", required = false) String token) {
        ApplyAdjustmentRequestDto effective = request == null ? new ApplyAdjustmentRequestDto() : request;
        if (effective.getSessionAccessToken() == null || effective.getSessionAccessToken().isBlank()) {
            effective.setSessionAccessToken(token == null ? "" : token);
        }
        return response(plannerService.applyAdjustment(sessionId, effective));
    }

    private ResponseEntity<?> response(PlannerService.ServiceResult result) {
        return ResponseEntity.status(result.status()).body(result.body());
    }
}

package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.planner.service.PlanAdjustmentService;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSaveUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerOpenUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerRestoreUseCasePort;
import com.ai.guide.domain.planner.model.AdjustmentPreviewExecution;
import com.ai.guide.domain.planner.model.AdjustmentLifecycleExecution;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.PlannerSaveActor;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.model.PlannerOpenActor;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerRestoreActor;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

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
    private final PlannerSaveUseCasePort plannerSaveUseCase;
    private final PlannerOpenUseCasePort plannerOpenUseCase;
    private final PlannerRestoreUseCasePort plannerRestoreUseCase;
    private final PlannerSessionRepositoryPort sessionRepository;
    private final PlanAdjustmentService planAdjustmentService;
    private final ProposalRepositoryPort proposalRepository;

    public PlannerApplicationService(PlannerService plannerService,
                                     PlannerSaveUseCasePort plannerSaveUseCase,
                                     PlannerSessionRepositoryPort sessionRepository,
                                     PlanAdjustmentService planAdjustmentService,
                                     ProposalRepositoryPort proposalRepository,
                                     PlannerOpenUseCasePort plannerOpenUseCase,
                                     PlannerRestoreUseCasePort plannerRestoreUseCase) {
        this.plannerService = plannerService;
        this.plannerSaveUseCase = plannerSaveUseCase;
        this.sessionRepository = sessionRepository;
        this.planAdjustmentService = planAdjustmentService;
        this.proposalRepository = proposalRepository;
        this.plannerOpenUseCase = plannerOpenUseCase;
        this.plannerRestoreUseCase = plannerRestoreUseCase;
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
    public PlannerService.ServiceResult replan(PlanningActor actor, String sessionId, PlannerMutationRequest request) {
        StoredSession session = findAuthorized(actor, sessionId, request == null ? "" : safe(request.getSessionAccessToken()));
        return commitStopMutation(plannerService.prepareReplanAuthorized(session, request));
    }

    @Override
    public PlannerService.ServiceResult mutateStops(PlanningActor actor, String sessionId, PlannerMutationRequest request) {
        StoredSession session = findAuthorized(actor, sessionId, request == null ? "" : safe(request.getSessionAccessToken()));
        return commitStopMutation(plannerService.prepareStopsAuthorized(session, request));
    }

    private PlannerService.ServiceResult commitStopMutation(PlannerService.StopMutationPreparation preparation) {
        if (preparation.rejected()) return preparation.rejection();
        var committed = sessionRepository.update(preparation.session(), preparation.trip(), preparation.expectedVersion(),
                preparation.mutationType(), preparation.label(), preparation.reason(), preparation.changedSegments());
        return plannerService.completeStopMutation(preparation, committed);
    }

    @Override
    public PlannerService.ServiceResult save(String sessionId, String accessToken) {
        return plannerService.save(sessionId, accessToken);
    }

    @Override
    public PlannerSaveResult save(PlanningActor actor, String sessionId, String accessToken) {
        PlannerSaveActor saveActor = actor == null ? null : new PlannerSaveActor(actor.userId(), actor.anonymous());
        return plannerSaveUseCase.save(saveActor, sessionId, accessToken);
    }

    @Override
    public PlannerService.ServiceResult openFormalTrip(String tripId) {
        return plannerService.openFormalTrip(tripId);
    }

    @Override
    public PlannerOpenResult openFormalTrip(PlanningActor actor, String tripId) {
        return openFormalTrip(actor, tripId, java.util.UUID.randomUUID().toString());
    }

    @Override
    public PlannerOpenResult openFormalTrip(PlanningActor actor, String tripId, String operationKey) {
        PlannerOpenActor openActor = actor == null ? null : new PlannerOpenActor(actor.userId(), actor.anonymous());
        return plannerOpenUseCase.openFormalTrip(openActor, tripId, operationKey);
    }

    @Override
    public PlannerOpenResult acknowledgeOpenSession(PlanningActor actor, String tripId, String sessionId) {
        PlannerOpenActor openActor = actor == null ? null : new PlannerOpenActor(actor.userId(), actor.anonymous());
        return plannerOpenUseCase.acknowledgeOpenSession(openActor, tripId, sessionId);
    }

    @Override
    public PlannerOpenResult discardUncommittedOpen(PlanningActor actor, String tripId, String sessionId) {
        PlannerOpenActor openActor = actor == null ? null : new PlannerOpenActor(actor.userId(), actor.anonymous());
        return plannerOpenUseCase.discardUncommittedOpen(openActor, tripId, sessionId);
    }

    @Override
    public PlannerService.ServiceResult restoreDraft(String sessionId, RestoreDraftRequest request) {
        return plannerService.restoreDraft(sessionId, request);
    }

    @Override
    public PlannerRestoreResult restoreDraft(PlanningActor actor, String sessionId, RestoreDraftRequest request) {
        PlannerRestoreActor restoreActor = actor == null ? null : new PlannerRestoreActor(actor.userId(), actor.anonymous());
        return plannerRestoreUseCase.restoreDraft(restoreActor, sessionId, request);
    }

    @Override
    public PreviewAdjustmentResult converse(PlanningActor actor, String sessionId, PlanConversationRequestDto request) {
        return previewAdjustment(actor, sessionId, request);
    }

    @Override
    public PreviewAdjustmentResult previewAdjustment(PlanningActor actor, String sessionId, PlanConversationRequestDto request) {
        if (!plannerService.isPlannerV1Enabled()) {
            return PreviewAdjustmentResult.failure(PreviewAdjustmentResult.Outcome.BAD_REQUEST,
                    "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。");
        }

        String token = request == null ? "" : safe(request.getSessionAccessToken());
        boolean reset = request != null && request.getContext() != null && request.getContext().isResetConversation();
        boolean cancelled = !reset && PlanAdjustmentService.isCancellationRequest(request == null ? "" : request.getMessage());
        if (reset || cancelled) {
            AdjustmentLifecycleExecution execution = planAdjustmentService.withSessionApplyLock(sessionId, () -> {
                StoredSession latest = findAuthorized(actor, sessionId, token);
                if (latest == null) return new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.NOT_FOUND,
                        Map.of("ok", false, "message", "规划会话不存在或无权访问。"));
                if (request != null && request.getBaseRevision() != latest.currentVersion())
                    return new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.CONFLICT,
                            planAdjustmentService.cancelRevisionConflictBody(latest));
                planAdjustmentService.resetAuthorizedContinuation(latest);
                return new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.SUCCESS,
                        Map.of("ok", true, "reset", true, "cancelled", cancelled, "modified", false,
                                "message", cancelled ? "已取消本轮调整，当前行程未改变。" : "已结束上一轮对话上下文。"));
            });
            return PreviewAdjustmentResult.of(PreviewAdjustmentResult.Outcome.valueOf(execution.outcome().name()), execution.body());
        }

        PreviewAdmission admission = planAdjustmentService.withSessionApplyLock(sessionId, () -> {
            StoredSession authorized = findAuthorized(actor, sessionId, token);
            if (authorized == null) return new PreviewAdmission(null, null,
                    PreviewAdjustmentResult.failure(PreviewAdjustmentResult.Outcome.NOT_FOUND, "规划会话不存在或无权访问。"));
            if (request != null && request.getBaseRevision() != authorized.currentVersion()) {
                return new PreviewAdmission(null, null, PreviewAdjustmentResult.of(PreviewAdjustmentResult.Outcome.CONFLICT,
                        planAdjustmentService.cancelRevisionConflictBody(authorized)));
            }
            return new PreviewAdmission(authorized, planAdjustmentService.beginPreviewForApplication(authorized), null);
        });
        if (admission.failure() != null) return admission.failure();

        AdjustmentPreviewExecution result = planAdjustmentService.previewForApplication(admission.session(), request,
                proposalRepository::put, admission.ticket());
        return PreviewAdjustmentResult.of(PreviewAdjustmentResult.Outcome.valueOf(result.outcome().name()), result.body());
    }

    @Override
    public PlannerService.ServiceResult updatePins(PlanningActor actor, String sessionId,
            com.ai.guide.domain.planner.model.PlannerPinsRequest request) {
        String token = request == null ? "" : safe(request.sessionAccessToken());
        return planAdjustmentService.withSessionApplyLock(sessionId, () -> {
            StoredSession session = findAuthorized(actor, sessionId, token);
            if (session == null) return new PlannerService.ServiceResult(404,
                    Map.of("ok", false, "message", "规划会话不存在或无权访问。"));
            if (request == null || request.expectedVersion() == null)
                return new PlannerService.ServiceResult(400,
                        Map.of("ok", false, "message", "固定操作必须提供当前行程版本。"));
            if (request.expectedVersion() != session.currentVersion())
                return new PlannerService.ServiceResult(409, planAdjustmentService.cancelRevisionConflictBody(session));
            java.util.List<String> pins;
            try {
                pins = com.ai.guide.domain.planner.service.PlannerPins.delta(session.trip(),
                        request.addStopIds(), request.removeStopIds());
            } catch (IllegalArgumentException invalid) {
                return new PlannerService.ServiceResult(400, Map.of("ok", false, "message", invalid.getMessage()));
            }
            if (pins.equals(com.ai.guide.domain.planner.service.PlannerPins.read(session.trip())))
                return pinsReceipt(session);
            var trip = new LinkedHashMap<>(session.trip());
            trip.put(com.ai.guide.domain.planner.service.PlannerPins.KEY, pins);
            trip.put("version", session.currentVersion() + 1);
            var result = sessionRepository.update(session, trip, request.expectedVersion(),
                    "PINS_UPDATED", "更新固定站点", "用户显式调整站点保护", java.util.List.of());
            if ("CONFLICT".equals(result.status()))
                return new PlannerService.ServiceResult(409,
                        planAdjustmentService.cancelRevisionConflictBody(result.session()));
            planAdjustmentService.resetAuthorizedContinuation(result.session());
            return pinsReceipt(result.session());
        });
    }

    private PlannerService.ServiceResult pinsReceipt(StoredSession session) {
        return new PlannerService.ServiceResult(200, Map.of("ok", true, "sessionId", session.sessionId(),
                "trip", session.trip(), "currentVersion", session.currentVersion(),
                "pinnedStopIds", com.ai.guide.domain.planner.service.PlannerPins.read(session.trip())));
    }

    private record PreviewAdmission(StoredSession session, PlanAdjustmentService.PreviewGenerationTicket ticket,
                                   PreviewAdjustmentResult failure) {}

    private StoredSession findAuthorized(PlanningActor actor, String sessionId, String token) {
        if (actor == null || sessionId == null || sessionId.isBlank()) return null;
        if (actor.anonymous()) {
            StoredSession capabilitySession = sessionRepository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return sessionRepository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        if (actor.userId() == null || actor.userId().isBlank()) return null;
        StoredSession owned = sessionRepository.find(sessionId, "USER", actor.userId(), token);
        return owned != null ? owned : sessionRepository.claimAnonymous(sessionId, token, actor.userId());
    }

    private String safe(String value) { return value == null ? "" : value; }

    @Override
    public ApplyAdjustmentResult applyAdjustment(PlanningActor actor, String sessionId, ApplyAdjustmentRequestDto request) {
        if (!plannerService.isPlannerV1Enabled()) {
            return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.BAD_REQUEST,
                    Map.of("ok", false, "message", "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。"));
        }
        String token = request == null ? "" : safe(request.getSessionAccessToken());
        StoredSession session = findAuthorized(actor, sessionId, token);
        if (session == null) {
            return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.NOT_FOUND,
                    Map.of("ok", false, "message", "规划会话不存在或无权访问。"));
        }
        return planAdjustmentService.withSessionApplyLock(session.sessionId(), () -> {
            StoredSession current = planAdjustmentService.refreshAuthorizedSessionForLifecycle(session, token);
            if (current == null) return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.NOT_FOUND,
                    Map.of("ok", false, "message", "规划会话不存在或无权访问。"));

            PlanAdjustmentService.ApplyReplayDecision replay = planAdjustmentService.lookupApplyReplay(current, request);
            if (replay.replayed() || replay.identityConflict())
                return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.valueOf(replay.result().outcome().name()), replay.result().body());

            PlanAdjustmentService.ApplyPreparation preparation = planAdjustmentService.prepareApply(current, request, replay);
            if (preparation.rejected())
                return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.valueOf(preparation.rejection().name()), preparation.rejectionBody());

            PlanAdjustmentService.ApplyCommit commit = planAdjustmentService.commitPreparedApply(preparation);
            if (!commit.committed())
                return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.valueOf(commit.outcome().name()), commit.rejectionBody());

            PlanAdjustmentService.ApplyCompletion completion = planAdjustmentService.completeCommittedApply(commit);
            Map<String, Object> body = assembleApplyReceipt(current, preparation, completion);
            ApplyAdjustmentResult result = ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.SUCCESS, body);
            planAdjustmentService.recordApplyReplay(current, request, preparation.replayKey(), preparation.requestFingerprint(),
                    new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.SUCCESS, result.body()));
            return result;
        });
    }

    private Map<String, Object> assembleApplyReceipt(StoredSession current,
                                                     PlanAdjustmentService.ApplyPreparation preparation,
                                                     PlanAdjustmentService.ApplyCompletion completion) {
        var proposal = preparation.proposal();
        var trip = preparation.proposedTrip();
        boolean forced = preparation.forceApply() && !proposal.isFeasible();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("applied", true);
        body.put("sessionId", current.sessionId());
        body.put("currentVersion", completion.currentVersion());
        body.put("trip", trip);
        body.put("changedSegments", proposal.getChangedSegments());
        body.put("unchangedStops", proposal.getUnchangedStops());
        body.put("appliedPreferences", completion.appliedPreferences());
        PlannerVersionMetadata metadata = PlannerVersionMetadata.fromTrip(trip, true);
        body.put("plannerVersion", metadata.getPlannerVersion());
        body.put("policyVersion", metadata.getPolicyVersion());
        body.put("explanationSource", metadata.getExplanationSource());
        body.put("routeDataStatus", metadata.getRouteDataStatus());
        body.put("degraded", metadata.isDegraded());
        body.put("degradationReasons", metadata.getDegradationReasons());
        body.put("forced", forced);
        if (!completion.pendingIntent().isEmpty()) {
            body.put("pendingIntent", completion.pendingIntent());
            body.put("nextActionPrompt", completion.nextActionPrompt());
        }
        body.put("message", forced
                ? "已按你的选择应用这份调整；它可能让当天安排不够顺路，请结合实际情况安排行程。"
                : "行程调整已成功应用并持久化。");
        return body;
    }

    @Override
    public PlannerService.ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request) {
        return plannerService.applyAdjustment(sessionId, request);
    }
}

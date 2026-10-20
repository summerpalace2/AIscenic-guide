package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.OperationDeadline;

import com.ai.guide.domain.planner.model.ConstraintConflict;
import com.ai.guide.domain.planner.engine.PlannerScoringPolicy;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSaveUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerOpenUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerRestoreUseCasePort;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.HydrationResult;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.PlannerSaveActor;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.model.PlannerOpenActor;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerRestoreActor;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.narrative.GroundedNarrativeService;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 智能文旅排程与规划会话编排服务
 *
 * 所属领域：domain.planner（智能排程与规划引擎域）
 * 架构职责：统筹自然语言意图解析、用户偏好注入、24景候选召回、高德路线计算、多天启发式排程、局部站点编辑以及草稿持久化全流程。
 *
 * 核心方法与职责：
 * 1. create：接收用户请求并生成多天行程草稿，初始化规划会话 (PlannerSession)
 *    - 参数：request（包含天数、自然语言 Prompt、同行人、偏好标签）
 *    - 返回值：包含会话 ID、访问令牌、完整多天排程与路线状态的 ServiceResult
 * 2. get：查询进行中的会话最新草稿数据
 * 3. mutateStops：对行程站点执行原子的增/删/改/替换操作并自动更新关联路线
 * 4. replan：对指定站点触发局部去重重排
 * 5. save：将当前的临时规划会话固化保存为正式行程 (Trip)
 */
@Service
public class PlannerService {
    @org.springframework.beans.factory.annotation.Value("${planner.request-timeout-ms:45000}")
    private long requestTimeoutMs = 45000L;

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);
    private static final Object[] CREATE_LOCKS = new Object[128];
    static { java.util.Arrays.setAll(CREATE_LOCKS, i -> new Object()); }

    private final TravelConstraintParserPort constraintParser;
    private final ItineraryBuilderPort itineraryBuilder;
    private final PlannerSessionRepositoryPort repository;
    private final TripService tripService;
    private final ObjectMapper objectMapper;
    private final RouteGatewayPort amapPlannerGateway;
    private final PlannerPreferencesResolver preferencesResolver;
    private final PlanAdjustmentService planAdjustmentService;
    private final PlannerMetricsLogger metricsLogger;
    private final GroundedNarrativeService groundedNarrativeService;
    private final boolean plannerV1Enabled;
    private final PlannerSaveUseCasePort plannerSaveUseCase;
    private final PlannerOpenUseCasePort plannerOpenUseCase;
    private final PlannerRestoreUseCasePort plannerRestoreUseCase;
    private final PlannerTaxonomyPort taxonomy;
    private final PlannerMutationPolicy mutationPolicy;
    private final com.ai.guide.domain.planner.engine.ItineraryTimeEngine mutationTimeEngine = new com.ai.guide.domain.planner.engine.ItineraryTimeEngine();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureMutationTiming(com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort policy) {
        mutationTimeEngine.configureUnknownTransferEstimate(policy);
    }

    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          GroundedNarrativeService groundedNarrativeService,
                          @org.springframework.beans.factory.annotation.Value("${planner.v1.enabled:true}") boolean plannerV1Enabled) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper, amapPlannerGateway,
                preferencesResolver, planAdjustmentService, metricsLogger, groundedNarrativeService,
                plannerV1Enabled, null, null, null);
    }

    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) GroundedNarrativeService groundedNarrativeService,
                          @org.springframework.beans.factory.annotation.Value("${planner.v1.enabled:true}") boolean plannerV1Enabled,
                          PlannerSaveUseCasePort plannerSaveUseCase,
                          PlannerOpenUseCasePort plannerOpenUseCase,
                          PlannerRestoreUseCasePort plannerRestoreUseCase) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper, amapPlannerGateway,
                preferencesResolver, planAdjustmentService, metricsLogger, groundedNarrativeService,
                plannerV1Enabled, plannerSaveUseCase, plannerOpenUseCase, plannerRestoreUseCase, TaxonomyPort.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          @org.springframework.beans.factory.annotation.Autowired(required = false) GroundedNarrativeService groundedNarrativeService,
                          @org.springframework.beans.factory.annotation.Value("${planner.v1.enabled:true}") boolean plannerV1Enabled,
                          PlannerSaveUseCasePort plannerSaveUseCase,
                          PlannerOpenUseCasePort plannerOpenUseCase,
                          PlannerRestoreUseCasePort plannerRestoreUseCase,
                          PlannerTaxonomyPort taxonomy) {
        this.constraintParser = constraintParser;
        this.itineraryBuilder = itineraryBuilder;
        this.repository = repository;
        this.tripService = tripService;
        this.objectMapper = objectMapper;
        this.amapPlannerGateway = amapPlannerGateway;
        this.preferencesResolver = preferencesResolver;
        this.planAdjustmentService = planAdjustmentService;
        this.metricsLogger = metricsLogger;
        this.groundedNarrativeService = groundedNarrativeService;
        this.plannerV1Enabled = plannerV1Enabled;
        this.plannerSaveUseCase = plannerSaveUseCase;
        this.plannerOpenUseCase = plannerOpenUseCase;
        this.plannerRestoreUseCase = plannerRestoreUseCase;
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
        this.mutationPolicy = planAdjustmentService == null
                ? new PlannerMutationPolicy(itineraryBuilder, new com.ai.guide.domain.planner.engine.PlanVerifier(this.taxonomy))
                : planAdjustmentService.mutationPolicy();
    }

    /** Compatibility constructor for CP4.2's explicit save/open gateway dependencies. */
    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          GroundedNarrativeService groundedNarrativeService,
                          boolean plannerV1Enabled,
                          PlannerSaveUseCasePort plannerSaveUseCase,
                          PlannerOpenUseCasePort plannerOpenUseCase) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper, amapPlannerGateway,
                preferencesResolver, planAdjustmentService, metricsLogger, groundedNarrativeService,
                plannerV1Enabled, plannerSaveUseCase, plannerOpenUseCase, null);
    }

    /** Compatibility constructor for existing direct domain-service fixtures. */
    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          GroundedNarrativeService groundedNarrativeService,
                          boolean plannerV1Enabled,
                          PlannerSaveUseCasePort plannerSaveUseCase) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper, amapPlannerGateway,
                preferencesResolver, planAdjustmentService, metricsLogger, groundedNarrativeService,
                plannerV1Enabled, plannerSaveUseCase, null, null);
    }

    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService,
                          PlannerMetricsLogger metricsLogger,
                          boolean plannerV1Enabled) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper,
                amapPlannerGateway, preferencesResolver, planAdjustmentService,
                metricsLogger, null, plannerV1Enabled);
    }

    public PlannerService(TravelConstraintParserPort constraintParser,
                          ItineraryBuilderPort itineraryBuilder,
                          PlannerSessionRepositoryPort repository,
                          TripService tripService,
                          ObjectMapper objectMapper,
                          RouteGatewayPort amapPlannerGateway,
                          PlannerPreferencesResolver preferencesResolver,
                          PlanAdjustmentService planAdjustmentService) {
        this(constraintParser, itineraryBuilder, repository, tripService, objectMapper,
                amapPlannerGateway, preferencesResolver, planAdjustmentService,
                new PlannerMetricsLogger(), null, true);
    }

    public boolean isPlannerV1Enabled() {
        return plannerV1Enabled;
    }

    public ServiceResult create(PlanRequest request) {
        String key = request == null ? "" : safe(request.getIdempotencyKey());
        if (key.isBlank() || (UserContext.isAnonymous() && safe(request.getSessionAccessToken()).isBlank())) return createInternal(request);
        String namespace = UserContext.isAnonymous() ? safe(request.getSessionAccessToken()) : UserContext.getUserId();
        synchronized (CREATE_LOCKS[((namespace + ":" + key).hashCode() & Integer.MAX_VALUE) % CREATE_LOCKS.length]) {
            return createInternal(request);
        }
    }

    private ServiceResult createInternal(PlanRequest request) {
        try (var ignored = OperationDeadline.open(requestTimeoutMs)) {
            return createWithinDeadline(request);
        }
    }

    private ServiceResult createWithinDeadline(PlanRequest request) {
        long startNanos = System.nanoTime();
        String prompt = request == null ? "" : safe(request.effectivePrompt());
        Map<String, Object> overrides = request == null ? Map.of() : request.effectiveConstraints();
        boolean usePreferences = request != null && Boolean.TRUE.equals(request.getUsePreferences());
        String ownerType = UserContext.isAnonymous() ? "ANONYMOUS" : "USER";
        String idempotencyKey = request == null ? "" : safe(request.getIdempotencyKey());
        String suppliedCapability = request == null ? "" : safe(request.getSessionAccessToken());
        String ownerId;
        if ("ANONYMOUS".equals(ownerType)) {
            if (!suppliedCapability.isBlank()) {
                StoredSession capabilitySession =
                        repository.findAnonymousByAccessToken(suppliedCapability);
                StoredSession keyedSession = capabilitySession == null
                        ? null
                        : repository.findByIdempotency("ANONYMOUS", capabilitySession.ownerId(), idempotencyKey);
                if (capabilitySession == null || idempotencyKey.isBlank() || keyedSession == null
                        || !capabilitySession.sessionId().equals(keyedSession.sessionId())) {
                    return error(401, "规划会话凭据无效，不能进行幂等重放。", null);
                }
                ownerId = capabilitySession.ownerId();
            } else {
                // Each guest plan gets a distinct durable owner namespace.
                ownerId = "guest-" + UUID.randomUUID();
            }
        } else {
            ownerId = UserContext.getUserId();
        }
        RequestPreparation prepared = prepare(prompt, overrides, usePreferences);
        TravelConstraints constraints = prepared.constraints();
        AppliedPreferencesSnapshot appliedPreferences = prepared.snapshot();
        String fingerprint = fingerprint(prompt, constraints.asMap(), usePreferences, appliedPreferences,
                request == null ? "" : request.getPreferenceDecision());
        StoredSession replay = repository.findByIdempotency(ownerType, ownerId, idempotencyKey);
        if (replay != null) {
            if (!fingerprint.equals(replay.requestFingerprint())) return error(409, "同一幂等键对应了不同的规划请求。", replay);
            var response = planResponse(replay, "");
            response.put("phase", "已生成结构化行程");
            response.put("source", sourceStatus(replay.trip()));
            response.put("retrieval", replay.trip().get("retrieval"));
            response.put("appliedPreferences", replay.appliedPreferences().asMap());
            response.put("preferenceProposal", null);
            response.putAll(constraintDiagnostics(constraints));
            return new ServiceResult(200, response);
        }
        if (constraints.getDestination() == null || constraints.getDestination().isBlank()) {
            return error(400, "请先提供旅行目的地，再生成行程。", null);
        }
        if (constraints.getDurationDays() <= 0) {
            return error(400, "请提供行程天数，或先配置有效的默认规划天数，再生成行程。", null);
        }
        PlanningComputation computation = compute(prepared);
        Map<String, Object> trip = computation.trip();
        if (OperationDeadline.expired() && isTripEmpty(trip)) {
            return error(504, "规划请求超时，尚未取得可用行程，请稍后重试。", null);
        }
        if (OperationDeadline.expired()) {
            trip.put("degraded", true);
            Set<String> reasons = new java.util.LinkedHashSet<>();
            if (trip.get("degradationReasons") instanceof List<?> existing)
                existing.forEach(reason -> reasons.add(String.valueOf(reason)));
            reasons.add("PLANNING_DEADLINE_EXCEEDED");
            trip.put("degradationReasons", new ArrayList<>(reasons));
            trip.put("summary", "本次规划达到时间预算，已保留已完成的行程；部分安排或动态信息尚未完成，可继续局部调整。");
            Map<String, Object> source = new LinkedHashMap<>();
            if (trip.get("sourceStatus") instanceof Map<?, ?> currentSource)
                currentSource.forEach((key, value) -> source.put(String.valueOf(key), value));
            source.put("planning", "部分完成：请求超时");
            source.put("fallback", true);
            trip.put("sourceStatus", source);
            if (trip.get("spatialPlan") instanceof Map<?, ?> spatial) {
                Map<String, Object> partialSpatial = new LinkedHashMap<>();
                spatial.forEach((key, value) -> partialSpatial.put(String.valueOf(key), value));
                partialSpatial.put("status", "PARTIAL_TIMEOUT");
                trip.put("spatialPlan", partialSpatial);
            }
        }
        CreateResult created = repository.create(ownerType, ownerId, prompt,
                constraints.asMap(), appliedPreferences, trip, idempotencyKey, fingerprint);
        if ("CONFLICT".equals(created.status())) {
            return error(409, "同一幂等键对应了不同的规划请求。", created.session());
        }

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (metricsLogger != null) {
            metricsLogger.logPlanCreated(created.session().sessionId(), 1, latencyMs,
                    String.valueOf(trip.getOrDefault("sourceMode", "ESTIMATED")),
                    !"高德实时接口".equals(trip.get("sourceMode")));
        }

        Map<String, Object> response = planResponse(created.session(), created.accessToken());
        response.put("phase", "已生成结构化行程");
        response.put("source", sourceStatus(trip));
        response.put("retrieval", trip.get("retrieval"));
        response.put("appliedPreferences", appliedPreferences.asMap());
        response.put("preferenceProposal", null);
        response.putAll(constraintDiagnostics(constraints));
        return new ServiceResult(200, response);
    }

    /**
     * Runs the Java planner as a stateless Web Shadow candidate. This endpoint
     * deliberately stops before every repository, capability, idempotency, and
     * formal Trip action in the durable create path.
     */
    public ServiceResult shadow(ShadowPlanRequest request) {
        ShadowPlanRequest effective = request == null ? new ShadowPlanRequest() : request;
        RequestPreparation shadowPreparation = prepare(safe(effective.effectivePrompt()),
                effective.effectiveConstraints(), Boolean.TRUE.equals(effective.getUsePreferences()));
        TravelConstraints requested = shadowPreparation.constraints();
        if (requested.getDestination() == null || requested.getDestination().isBlank())
            return shadowError("请先提供旅行目的地，再进行规划对照。");
        if (requested.getDurationDays() <= 0)
            return shadowError("请提供行程天数后再进行规划对照。");
        PlanningComputation computation = compute(shadowPreparation);
        Map<String, Object> body = shadowTripResponse(computation.trip());
        body.put("constraints", computation.constraints().asMap());
        body.put("appliedPreferences", computation.appliedPreferences().asMap());
        body.putAll(constraintDiagnostics(computation.constraints()));
        return new ServiceResult(200, body);
    }

    /** Applies Java's replacement ranking to a copied, non-durable draft. */
    public ServiceResult shadowReplan(ShadowMutationRequest request) {
        ShadowMutationRequest effective = request == null ? new ShadowMutationRequest() : request;
        Map<String, Object> trip = shadowTrip(effective.getTrip());
        if (trip.isEmpty()) return shadowError("缺少用于 Shadow 对照的行程草稿。");
        String targetId = safe(effective.getTargetStopId());
        String reason = safe(effective.getReason());
        StopLocation target = locateStop(trip, targetId);
        if (target == null) return shadowError("未在当前行程中找到要替换的站点。");
        Attraction oldAttraction = itineraryBuilder.attraction(String.valueOf(target.stop().get("venueId")));
        if (oldAttraction == null) return shadowError("当前站点缺少有效景点实体。");
        Set<String> used = usedVenues(trip);
        used.remove(oldAttraction.getId());
        String requestedCandidate = safe(effective.getCandidateVenueId());
        Attraction replacement;
        if (!requestedCandidate.isBlank()) {
            replacement = itineraryBuilder.attraction(requestedCandidate);
            if (replacement == null) return shadowError("候选景点不存在。");
            if (used.contains(replacement.getId())) return shadowError("候选景点已存在于当前行程，不能造成重复。");
        } else {
            replacement = rankReplacement(reason, oldAttraction, used, trip, extractMutationConstraints(trip));
            if (replacement == null) return shadowError("当前没有可用的替换景点。");
        }
        Map<String, Object> newStop = itineraryBuilder.createStop(replacement, targetId,
                safeValue(mutationTimeEngine.explicitClock(target.stop()), "待安排"),
                safeValue(target.stop().get("duration"), replacement.getDuration()),
                "因为“" + reason + "”，换成更合适的" + replacement.getName() + "（" + replacement.getSummary() + "）。",
                "这是局部替换，仅改变目标站点；" + replacement.getIntro());
        copyConstraintAnnotations(target.stop(), newStop);
        target.stops().set(target.index(), newStop);
        finalizeTrip(trip, currentVersion(trip) + 1, "局部重规划", reason, List.of(targetId));
        amapPlannerGateway.hydrate(trip);
        Map<String, Object> body = shadowTripResponse(trip);
        body.put("changedSegments", List.of(targetId));
        body.put("unchangedStops", unchangedStopIds(trip, targetId));
        body.put("replacementReason", reason);
        body.put("replacementVenueId", replacement.getId());
        if ("少走路".equals(reason)) {
            body.put("memoryProposal", Map.of("type", "preference", "value", "少走路", "copy", "要记住“少走路”偏好吗？"));
        }
        return new ServiceResult(200, body);
    }

    /** Applies an add, replace, or delete operation to a copied Shadow draft. */
    public ServiceResult shadowMutateStops(ShadowMutationRequest request) {
        ShadowMutationRequest effective = request == null ? new ShadowMutationRequest() : request;
        Map<String, Object> trip = shadowTrip(effective.getTrip());
        if (trip.isEmpty()) return shadowError("缺少用于 Shadow 对照的行程草稿。");
        String operation = safe(effective.getOperation());
        if (operation.isBlank()) operation = "add";
        if (!Set.of("add", "replace", "delete", "remove").contains(operation)) return shadowError("不支持的站点操作。");
        String changedId;
        String label;
        if ("delete".equals(operation) || "remove".equals(operation)) {
            String stopId = safe(effective.getStopId());
            if (stopId.isBlank()) stopId = safe(effective.getTargetStopId());
            StopLocation target = locateStop(trip, stopId);
            if (target == null) return shadowError("未在当前行程中找到要移除的站点。");
            target.stops().remove(target.index());
            changedId = stopId;
            label = "移除站点";
        } else {
            String attractionId = safe(effective.getAttractionId());
            Attraction attraction = itineraryBuilder.attraction(attractionId);
            if (attraction == null) return shadowError("景点不存在。");
            int dayNumber = effective.getDay() == null ? 1 : effective.getDay();
            Map<String, Object> targetDay = day(trip, dayNumber);
            if (targetDay == null) return shadowError("目标日期不存在。");
            if ("replace".equals(operation)) {
                String targetId = safe(effective.getTargetStopId());
                StopLocation target = locateStop(trip, targetId);
                if (target == null) return shadowError("未找到要替换的站点。");
                Set<String> used = usedVenues(trip);
                used.remove(safe(target.stop().get("venueId")));
                if (used.contains(attractionId)) return shadowError("候选景点已存在于当前行程，不能造成重复。");
                Map<String, Object> stop = itineraryBuilder.createStop(attraction, targetId,
                        safeValue(mutationTimeEngine.explicitClock(target.stop()), "待安排"),
                        safeValue(target.stop().get("duration"), attraction.getDuration()),
                        "根据偏好替换为" + attraction.getName() + "，保留原时间段与其余站点。",
                        attraction.getIntro());
                copyConstraintAnnotations(target.stop(), stop);
                target.stops().set(target.index(), stop);
                changedId = targetId;
                label = "替换站点";
            } else {
                if (usedVenues(trip).contains(attractionId) && !effective.isAllowRepeat()) return shadowError("该景点已在当前行程中。");
                changedId = "day" + dayNumber + "-added-" + attractionId + "-shadow";
                dayStops(targetDay).add(itineraryBuilder.createStop(attraction, changedId, "待安排",
                        attraction.getDuration(), "将" + attraction.getName() + "加入当前行程，具体停留与开放状态需出发前核验。",
                        attraction.getIntro()));
                label = "添加站点";
            }
        }
        finalizeTrip(trip, currentVersion(trip) + 1, label, safe(effective.getReason()), List.of(changedId));
        amapPlannerGateway.hydrate(trip);
        Map<String, Object> body = shadowTripResponse(trip);
        body.put("operation", operation);
        body.put("changedSegments", List.of(changedId));
        body.put("message", "Shadow 站点候选已生成，未持久化。");
        return new ServiceResult(200, body);
    }

    public ServiceResult get(String sessionId, String accessToken) {
        StoredSession session = findAuthorized(sessionId, accessToken);
        return session == null ? error(404, "规划会话不存在或无权访问。", null) : new ServiceResult(200, planResponse(session, ""));
    }

    /** Restore an offline edit into the currently authorized planner session. */
    public ServiceResult restoreDraft(String sessionId, RestoreDraftRequest request) {
        if (plannerRestoreUseCase == null) {
            throw new IllegalStateException("PlannerRestoreUseCasePort 未配置；请通过应用层草稿恢复用例执行。");
        }
        PlannerRestoreResult result = plannerRestoreUseCase.restoreDraft(
                new PlannerRestoreActor(UserContext.getUserId(), UserContext.isAnonymous()), sessionId, request);
        return new ServiceResult(result.serviceStatus(), result.body());
    }

    /**
     * Refreshes provider-backed facts without creating a planner revision. Dynamic
     * weather, POI and route data are intentionally not treated as a user edit.
     */
    public ServiceResult refreshDynamicData(String sessionId, String accessToken) {
        StoredSession session = findAuthorized(sessionId, accessToken);
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        Map<String, Object> trip = mutableMap(session.trip());
        HydrationResult hydration = amapPlannerGateway.hydrate(trip);
        Map<String, Object> response = planResponse(session, "");
        response.put("trip", trip);
        response.put("source", sourceStatus(trip));
        response.put("dynamicRefresh", Map.of(
                "mode", hydration.mode(),
                "poiResolved", hydration.poiSuccess(),
                "routeResolved", hydration.routeSuccess(),
                "weatherDaysResolved", hydration.weatherSuccess(),
                "fallback", hydration.fallback()));
        return new ServiceResult(200, response);
    }

    public ServiceResult list() {
        if (UserContext.isAnonymous()) return error(401, "登录后才能查看规划历史。", null);
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (StoredSession session : repository.list("USER", UserContext.getUserId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.sessionId());
            item.put("sessionId", session.sessionId());
            item.put("createdAt", session.createdAt());
            item.put("updatedAt", session.updatedAt());
            item.put("version", session.currentVersion());
            item.put("prompt", session.prompt());
            item.put("constraints", session.constraints());
            item.put("appliedPreferences", session.appliedPreferences().asMap());
            item.put("title", session.trip().get("title"));
            item.put("trip", session.trip());
            sessions.add(item);
        }
        return new ServiceResult(200, Map.of("ok", true, "sessions", sessions));
    }

    /** Compatibility entry; the HTTP application use case owns authorization and persistence. */
    public ServiceResult replan(String sessionId, PlannerMutationRequest request) {
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        return commitLegacyStopMutation(prepareReplanAuthorized(session, request));
    }

    /** Computes and verifies a candidate against an application-authorized snapshot, without session writes. */
    public StopMutationPreparation prepareReplanAuthorized(StoredSession session, PlannerMutationRequest request) {
        if (session == null) return StopMutationPreparation.rejected(error(404, "规划会话不存在或无权访问。", null));
        if (expectedVersion(request, session.currentVersion()) != session.currentVersion()) return StopMutationPreparation.rejected(conflict(session));
        Map<String, Object> trip = mutableMap(session.trip());
        String targetId = request == null ? "" : safe(request.getTargetStopId());
        String reason = request == null ? "" : safe(request.getReason());
        StopLocation target = locateStop(trip, targetId);
        if (target == null) return StopMutationPreparation.rejected(error(400, "未在当前行程中找到要替换的站点。", session));
        Attraction oldAttraction = itineraryBuilder.attraction(String.valueOf(target.stop().get("venueId")));
        if (oldAttraction == null) return StopMutationPreparation.rejected(error(400, "当前站点缺少有效景点实体。", session));
        Set<String> used = usedVenues(trip);
        used.remove(oldAttraction.getId());
        String requestedCandidate = request == null ? "" : safe(request.getCandidateVenueId());
        Attraction replacement;
        if (!requestedCandidate.isBlank()) {
            replacement = itineraryBuilder.attraction(requestedCandidate);
            if (replacement == null) return StopMutationPreparation.rejected(error(400, "候选景点不存在。", session));
            if (used.contains(replacement.getId())) return StopMutationPreparation.rejected(error(400, "候选景点已存在于当前行程，不能造成重复。", session));
        } else {
            replacement = rankReplacement(reason, oldAttraction, used, trip, extractMutationConstraints(session.constraints()));
            if (replacement == null) return StopMutationPreparation.rejected(error(400, "当前没有可用的替换景点。", session));
        }
        ServiceResult candidateRejected = rejectCandidate(session, trip, replacement);
        if (candidateRejected != null) return StopMutationPreparation.rejected(candidateRejected);
        Map<String, Object> newStop = itineraryBuilder.createStop(replacement, targetId,
                safeValue(mutationTimeEngine.explicitClock(target.stop()), "待安排"), safeValue(target.stop().get("duration"), replacement.getDuration()),
                "因为“" + reason + "”，换成更合适的" + replacement.getName() + "（" + replacement.getSummary() + "）。",
                "这是局部替换，保留其他站点，仅依据新接驳耗时推迟冲突时段；" + replacement.getIntro());
        copyConstraintAnnotations(target.stop(), newStop);
        invalidateEditedEdges(target);
        target.stops().set(target.index(), newStop);
        amapPlannerGateway.hydrate(trip);
        List<String> changed = delayAffectedStopTimes(trip, targetId);
        ServiceResult rejected = rejectInvalidMutation(session, trip);
        if (rejected != null) return StopMutationPreparation.rejected(rejected);
        finalizeTrip(trip, session.currentVersion() + 1, "局部重规划", reason, changed);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("unchangedStops", allStopIds(trip).stream().filter(id -> !changed.contains(id)).toList());
        receipt.put("replacementReason", reason);
        receipt.put("replacementVenueId", replacement.getId());
        if ("少走路".equals(reason)) receipt.put("memoryProposal", Map.of("type", "preference", "value", "少走路", "copy", "要记住“少走路”偏好吗？"));
        return new StopMutationPreparation(null, session, trip, expectedVersion(request, session.currentVersion()),
                "REPLAN", "局部重规划", reason, changed, receipt);
    }

    /** Compatibility entry; production HTTP mutations use the explicit-actor application use case. */
    public ServiceResult mutateStops(String sessionId, PlannerMutationRequest request) {
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        return commitLegacyStopMutation(prepareStopsAuthorized(session, request));
    }

    public StopMutationPreparation prepareStopsAuthorized(StoredSession session, PlannerMutationRequest request) {
        if (session == null) return StopMutationPreparation.rejected(error(404, "规划会话不存在或无权访问。", null));
        if (expectedVersion(request, session.currentVersion()) != session.currentVersion()) return StopMutationPreparation.rejected(conflict(session));
        String operation = request == null ? "add" : safe(request.getOperation());
        if (operation.isBlank()) operation = "add";
        if (!Set.of("add", "replace", "delete", "remove").contains(operation)) return StopMutationPreparation.rejected(error(400, "不支持的站点操作。", session));
        Map<String, Object> trip = mutableMap(session.trip());
        String changedId;
        String label;
        StopLocation removedLocation = null;
        if ("delete".equals(operation) || "remove".equals(operation)) {
            String stopId = request == null ? "" : safe(request.getStopId());
            if (stopId.isBlank()) stopId = request == null ? "" : safe(request.getTargetStopId());
            StopLocation target = locateStop(trip, stopId);
            if (target == null) return StopMutationPreparation.rejected(error(400, "未在当前行程中找到要移除的站点。", session));
            invalidateEditedEdges(target);
            target.stops().remove(target.index());
            removedLocation = target;
            changedId = stopId;
            label = "移除站点";
        } else {
            String attractionId = request == null ? "" : safe(request.getAttractionId());
            Attraction attraction = itineraryBuilder.attraction(attractionId);
            if (attraction == null) return StopMutationPreparation.rejected(error(400, "景点不存在。", session));
            ServiceResult candidateRejected = rejectCandidate(session, trip, attraction);
            if (candidateRejected != null) return StopMutationPreparation.rejected(candidateRejected);
            int dayNumber = request == null || request.getDay() == null ? 1 : request.getDay();
            Map<String, Object> day = day(trip, dayNumber);
            if (day == null) return StopMutationPreparation.rejected(error(400, "目标日期不存在。", session));
            if (usedVenues(trip).contains(attractionId) && !"replace".equals(operation)
                    && (request == null || !request.isAllowRepeat())) return StopMutationPreparation.rejected(error(400, "该景点已在当前行程中；如需再次访问，请明确允许重复访问。", session));
            if ("replace".equals(operation)) {
                String targetId = request == null ? "" : safe(request.getTargetStopId());
                StopLocation target = locateStop(trip, targetId);
                if (target == null) return StopMutationPreparation.rejected(error(400, "未找到要替换的站点。", session));
                Set<String> used = usedVenues(trip);
                used.remove(safe(target.stop().get("venueId")));
                if (used.contains(attractionId)) return StopMutationPreparation.rejected(error(400, "候选景点已存在于当前行程，不能造成重复。", session));
                Map<String, Object> stop = itineraryBuilder.createStop(attraction, targetId,
                        safeValue(mutationTimeEngine.explicitClock(target.stop()), "待安排"), safeValue(target.stop().get("duration"), attraction.getDuration()),
                        "根据偏好替换为" + attraction.getName() + "，保留其余站点，仅依据新接驳耗时推迟冲突时段。", attraction.getIntro());
                copyConstraintAnnotations(target.stop(), stop);
                invalidateEditedEdges(target);
                target.stops().set(target.index(), stop);
                changedId = targetId;
                label = "替换站点";
            } else {
                changedId = "day" + dayNumber + "-added-" + attractionId + "-" + UUID.randomUUID();
                Map<String, Object> stop = itineraryBuilder.createStop(attraction, changedId, "待安排", attraction.getDuration(),
                        "将" + attraction.getName() + "加入当前行程，具体停留与开放状态需出发前核验。", attraction.getIntro());
                dayStops(day).add(stop);
                label = "添加站点";
            }
        }
        amapPlannerGateway.hydrate(trip);
        if ("add".equals(operation)) {
            for (var item : days(trip)) {
                var stops = dayStops(item);
                if (!stops.isEmpty() && changedId.equals(stops.get(stops.size() - 1).get("id"))) {
                    mutationTimeEngine.materializeConfiguredTransferEstimates(stops, stops.size() - 1);
                    mutationTimeEngine.rippleRecalculateStopTimesFromExplicitFacts(stops, stops.size() - 1);
                }
            }
        }
        List<String> changed = "replace".equals(operation) ? delayAffectedStopTimes(trip, changedId)
                : removedLocation != null ? delayDeletedStopSuccessor(removedLocation, changedId) : List.of(changedId);
        ServiceResult rejected = rejectInvalidMutation(session, trip);
        if (rejected != null) return StopMutationPreparation.rejected(rejected);
        finalizeTrip(trip, session.currentVersion() + 1, label, safe(request == null ? null : request.getReason()), changed);
        return new StopMutationPreparation(null, session, trip, expectedVersion(request, session.currentVersion()),
                "STOP_MUTATION", label, safe(request == null ? null : request.getReason()), changed,
                Map.of("operation", operation, "message", "站点已更新，路线与版本信息已刷新。"));
    }

    private ServiceResult commitLegacyStopMutation(StopMutationPreparation preparation) {
        if (preparation.rejected()) return preparation.rejection();
        MutationResult result = repository.update(preparation.session(), preparation.trip(), preparation.expectedVersion(),
                preparation.mutationType(), preparation.label(), preparation.reason(), preparation.changedSegments());
        return completeStopMutation(preparation, result);
    }

    /** Assembles the same receipt for the application use case and compatibility callers; never writes sessions. */
    public ServiceResult completeStopMutation(StopMutationPreparation preparation, MutationResult result) {
        if ("CONFLICT".equals(result.status())) return conflict(result.session());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("trip", preparation.trip());
        body.put("changedSegments", preparation.changedSegments());
        body.put("appliedPreferences", result.session().appliedPreferences().asMap());
        body.putAll(preparation.receiptFields());
        return new ServiceResult(200, body);
    }

    public record StopMutationPreparation(ServiceResult rejection, StoredSession session, Map<String, Object> trip,
                                          int expectedVersion, String mutationType, String label, String reason,
                                          List<String> changedSegments, Map<String, Object> receiptFields) {
        static StopMutationPreparation rejected(ServiceResult result) {
            return new StopMutationPreparation(result, null, Map.of(), 0, "", "", "", List.of(), Map.of());
        }
        public boolean rejected() { return rejection != null; }
    }

    public ServiceResult save(String sessionId, String accessToken) {
        if (plannerSaveUseCase == null) {
            throw new IllegalStateException("PlannerSaveUseCasePort 未配置；请通过应用层保存用例执行 V1 保存。");
        }
        PlannerSaveResult result = plannerSaveUseCase.save(
                new PlannerSaveActor(UserContext.getUserId(), UserContext.isAnonymous()), sessionId, accessToken);
        return new ServiceResult(result.serviceStatus(), result.body());
    }

    public ServiceResult openFormalTrip(String tripId) {
        if (plannerOpenUseCase == null) {
            throw new IllegalStateException("PlannerOpenUseCasePort 未配置；请通过应用层正式行程打开用例执行 V1 打开。");
        }
        PlannerOpenResult result = plannerOpenUseCase.openFormalTrip(
                new PlannerOpenActor(UserContext.getUserId(), UserContext.isAnonymous()), tripId);
        return new ServiceResult(result.serviceStatus(), result.body());
    }

    public PlannerOpenResult discardUncommittedOpen(PlannerOpenActor actor, String tripId, String sessionId) {
        if (plannerOpenUseCase == null) {
            throw new IllegalStateException("PlannerOpenUseCasePort 未配置；无法清理未完成的正式行程打开会话。");
        }
        return plannerOpenUseCase.discardUncommittedOpen(actor, tripId, sessionId);
    }

    public ServiceResult converse(String sessionId, PlanConversationRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.preview(sessionId, request);
    }

    public ServiceResult previewAdjustment(String sessionId, PlanConversationRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.preview(sessionId, request);
    }

    public ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.apply(sessionId, request);
    }

    private record RequestPreparation(TravelConstraints constraints, AppliedPreferencesSnapshot snapshot) {}
    private RequestPreparation prepare(String prompt, Map<String, Object> overrides, boolean usePreferences) {
        TravelConstraints parsed = constraintParser.parse(safe(prompt));
        TravelConstraints requested = constraintParser.applyOverrides(parsed,
                overrides == null ? Map.of() : overrides);
        PlannerPreferencesResolver.Resolution resolved = preferencesResolver.resolve(requested, usePreferences);
        TravelConstraints constraints = constraintParser.applyDerivedTransportFallback(resolved.constraints());
        if (parsed.originOf("durationDays") == com.ai.guide.domain.planner.model.ConstraintOrigin.PROMPT && parsed.getDurationDays() > 0) {
            constraints.setDurationDays(parsed.getDurationDays());
        }
        return new RequestPreparation(constraints, resolved.snapshot());
    }

    private PlanningComputation compute(String prompt, Map<String, Object> overrides, boolean usePreferences) {
        return compute(prepare(prompt, overrides, usePreferences));
    }

    private PlanningComputation compute(RequestPreparation prepared) {
        long phaseStarted = System.nanoTime();
        TravelConstraints constraints = prepared.constraints();
        Map<String, Object> trip = null;
        if (plannerV1Enabled && constraints.hasExplicitSpatialRequest()) {
            trip = amapPlannerGateway.planFromLocation(constraints, 1);
            if (trip != null && isTripEmpty(trip) && !OperationDeadline.expired()) {
                log.warn("基于起点的周边动态规划未生成有效站点 (startPlace={})，自动降级为全城路线规划以确保行程可用",
                        constraints.getStartPlace());
                trip = null;
            }
        }
        if (trip == null) {
            trip = plannerV1Enabled
                    ? itineraryBuilder.build(1, constraints)
                    : itineraryBuilder.buildLegacy(1, constraints);
            log.info("[PLANNER_PHASE] phase=build latencyMs={}", (System.nanoTime() - phaseStarted) / 1_000_000L);
            phaseStarted = System.nanoTime();
            // Provider calls mutate only this in-memory result; they never write a
            // planner session or formal Trip.
            amapPlannerGateway.hydrate(trip);
        }
        log.info("[PLANNER_PHASE] phase=dynamic latencyMs={}", (System.nanoTime() - phaseStarted) / 1_000_000L);
        phaseStarted = System.nanoTime();

        if (plannerV1Enabled && groundedNarrativeService != null
                && !constraints.hasExplicitSpatialRequest()) {
            groundedNarrativeService.enhanceTrip(trip, constraints, prepared.snapshot());
        }
        log.info("[PLANNER_PHASE] phase=narrative latencyMs={}", (System.nanoTime() - phaseStarted) / 1_000_000L);

        return new PlanningComputation(constraints, prepared.snapshot(), trip);
    }

    private boolean isTripEmpty(Map<String, Object> trip) {
        if (trip == null || trip.isEmpty()) return true;
        Object daysObj = trip.get("days");
        if (!(daysObj instanceof List<?> days) || days.isEmpty()) return true;
        for (Object dayObj : days) {
            if (dayObj instanceof Map<?, ?> day) {
                Object stopsObj = day.get("stops");
                if (stopsObj instanceof List<?> stops && !stops.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<String, Object> shadowTripResponse(Map<String, Object> trip) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("persisted", false);
        body.put("shadow", true);
        body.put("trip", trip);
        body.put("phase", "已生成结构化行程");
        body.put("source", sourceStatus(trip));
        body.put("retrieval", trip.get("retrieval"));

        body.putAll(PlannerVersionMetadata.fromTrip(trip, plannerV1Enabled).asMap());
        return body;
    }

    private ServiceResult shadowError(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("persisted", false);
        body.put("shadow", true);
        body.put("message", message);
        return new ServiceResult(400, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> shadowTrip(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(source), LinkedHashMap.class);
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private int currentVersion(Map<String, Object> trip) {
        Integer version = number(trip == null ? null : trip.get("version"));
        return version == null || version < 1 ? 1 : version;
    }

    private StoredSession findAuthorized(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (UserContext.isAnonymous()) {
            // Resolve the owner from the capability digest before any session
            // lookup; a session idempotency key is never a credential.
            StoredSession capabilitySession =
                    repository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return repository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        StoredSession owned = repository.find(sessionId, "USER", UserContext.getUserId(), token);
        return owned != null ? owned : repository.claimAnonymous(sessionId, token, UserContext.getUserId());
    }

    private TravelConstraints extractMutationConstraints(Map<String, Object> source) {
        Object value = source.get("constraints");
        if (value == null && source.containsKey("days"))
            return TravelConstraints.builder().destination(safe(source.get("city"))).build();
        @SuppressWarnings("unchecked") var snapshot = value instanceof Map<?, ?> ? (Map<String, Object>) value : source;
        return TravelConstraints.fromSnapshot(objectMapper, snapshot);
    }

    private void invalidateEditedEdges(StopLocation target) {
        // The successor's incoming edge also changed; its previous route belongs to the removed venue.
        if (target.index() + 1 < target.stops().size())
            com.ai.guide.domain.planner.engine.RouteAwarePlanner.invalidateIncomingRoute(target.stops().get(target.index() + 1));
    }

    private List<String> delayDeletedStopSuccessor(StopLocation removed, String removedId) {
        var changed = new java.util.LinkedHashSet<String>();
        changed.add(removedId);
        int index = removed.index();
        // A new first stop has no incoming edge. Do not reinterpret unrelated unassigned blocks as scheduled visits.
        if (index <= 0 || index >= removed.stops().size()) return List.copyOf(changed);
        mutationTimeEngine.materializeConfiguredTransferEstimates(removed.stops(), index);
        if (mutationTimeEngine.parseExplicitTimeMinutes(mutationTimeEngine.explicitClock(removed.stops().get(index))) != null)
            changed.addAll(mutationTimeEngine.delayConflictingStopTimesFromExplicitFacts(removed.stops(), index));
        return List.copyOf(changed);
    }

    private List<String> delayAffectedStopTimes(Map<String, Object> trip, String targetId) {
        var changed = new java.util.LinkedHashSet<String>();
        changed.add(targetId);
        for (var day : days(trip)) {
            var stops = dayStops(day);
            for (int i = 0; i < stops.size(); i++) if (targetId.equals(stops.get(i).get("id"))) {
                mutationTimeEngine.materializeConfiguredTransferEstimates(stops, Math.max(1, i));
                changed.addAll(mutationTimeEngine.delayConflictingStopTimesFromExplicitFacts(stops, Math.max(1, i)));
                return List.copyOf(changed);
            }
        }
        return List.copyOf(changed);
    }

    private List<String> allStopIds(Map<String, Object> trip) {
        return days(trip).stream().flatMap(day -> dayStops(day).stream()).map(stop -> safe(stop.get("id"))).toList();
    }

    private ServiceResult rejectCandidate(StoredSession session, Map<String, Object> trip, Attraction candidate) {
        String reason = mutationPolicy.candidateRejection(candidate, trip, extractMutationConstraints(session.constraints()));
        if (reason.isBlank()) return null;
        var body = new LinkedHashMap<>(error(400, "候选地点不符合当前目的地范围或明确避开条件。", session).body());
        body.put("reasonCodes", List.of(reason));
        return new ServiceResult(400, body);
    }

    private ServiceResult rejectInvalidMutation(StoredSession session, Map<String, Object> trip) {
        if (!PlannerPins.retained(session.trip(), trip))
            return error(400, "该站点已固定，请先明确取消固定后再移除或替换。", session);
        PlannerPins.preserve(session.trip(), trip);
        var violations = mutationPolicy.introducedHardViolations(session.trip(), trip, extractMutationConstraints(session.constraints()));
        if (violations.isEmpty()) return null;
        var response = new LinkedHashMap<>(error(400, "这次修改违反目的地范围或行程约束，原行程未改变。", session).body());
        response.put("reasonCodes", violations.stream().map(com.ai.guide.domain.planner.engine.PlanVerifier.PlanViolation::type).distinct().toList());
        response.put("violations", violations.stream().map(com.ai.guide.domain.planner.engine.PlanVerifier.PlanViolation::asMap).toList());
        return new ServiceResult(400, response);
    }

    private Attraction rankReplacement(String reason, Attraction old, Set<String> used, Map<String, Object> trip, TravelConstraints constraints) {
        return itineraryBuilder.attractionServiceList().stream().filter(item -> mutationPolicy.candidateAllowed(item, trip, constraints))
                .filter(item -> !used.contains(item.getId()) && !item.getId().equals(old.getId()))
                .map(item -> new Ranked(item, replacementScore(item, old, reason)))
                .max(Comparator.comparingInt(Ranked::score)
                        .thenComparingInt(ranked -> -itineraryBuilder.catalogRank(ranked.attraction().getId())))
                .map(Ranked::attraction).orElse(null);
    }

    private int replacementScore(Attraction candidate, Attraction old, String reason) {
        int score = 0;
        List<String> tags = candidate.getTags() == null ? List.of() : candidate.getTags();
        if (matchesAnyHint(reason, "replanIndoorAliases")) {
            if (Boolean.TRUE.equals(candidate.getIndoor())) score += PlannerScoringPolicy.weight(taxonomy, "replacementIndoor");
            if (matchesAnyHint(tags, "replacementIndoorFeatures")) score += PlannerScoringPolicy.weight(taxonomy, "replacementIndoorFeature");
        }
        if (matchesAnyHint(reason, "replanLowWalkingAliases")) {
            if ("低".equals(candidate.getWalkDifficulty())) score += PlannerScoringPolicy.weight(taxonomy, "replacementLowWalking");
            if (matchesAnyHint(safe(candidate.getWalk()), "candidateLowWalkingFeatures")) score += PlannerScoringPolicy.weight(taxonomy, "replacementLowWalkingEvidence");
            if (matchesAnyHint(tags, "candidateLowWalkingFeatures")) score += PlannerScoringPolicy.weight(taxonomy, "replacementLowWalkingTag");
        }
        if (matchesAnyHint(reason, "replanUrgencyAliases")) {
            if (matchesAnyHint(safe(candidate.getDuration()), "shortVisitDurationTokens")) score += PlannerScoringPolicy.weight(taxonomy, "replacementUrgencyDuration");
            if (matchesAnyHint(tags, "shortVisitTags")) score += PlannerScoringPolicy.weight(taxonomy, "replacementShortVisitTag");
        }
        if (matchesAnyHint(reason, "replanNightAliases")
                && (taxonomy.getHints("plannerNightCategories").contains(candidate.getCategory()) || matchesAnyHint(tags, "plannerNightTags"))) {
            score += PlannerScoringPolicy.weight(taxonomy, "replacementNight");
        }
        if (matchesAnyHint(reason, "foodTags") || matchesAnyHint(reason, "dining")) {
            if (taxonomy.getHints("plannerFoodCategories").contains(candidate.getCategory()) || matchesAnyHint(tags, "foodTags")) {
                score += PlannerScoringPolicy.weight(taxonomy, "replacementFood");
            }
        }
        if (matchesAnyHint(reason, "replanCultureAliases")
                && (taxonomy.getHints("plannerCreativeCategories").contains(candidate.getCategory()) || matchesAnyHint(tags, "plannerCreativeTags"))) {
            score += PlannerScoringPolicy.weight(taxonomy, "replacementCulture");
        }
        if (matchesAnyHint(reason, "replanLowWalkingAliases")
                && (taxonomy.getHints("plannerNightCategories").contains(old.getCategory()) || old.matchesAnyTagOrFeature(taxonomy.getHints("plannerNightTags").toArray(String[]::new)))
                && (taxonomy.getHints("plannerNightCategories").contains(candidate.getCategory()) || candidate.matchesAnyTagOrFeature(taxonomy.getHints("plannerNightTags").toArray(String[]::new)))
                && ("低".equals(candidate.getWalkDifficulty()) || "SUPPORTED".equals(candidate.getEffectiveAccessibility()))
                && (matchesAnyHint(safe(candidate.getWalk()), "candidateLowWalkingFeatures") || candidate.matchesAnyTagOrFeature(taxonomy.getHints("plannerNightLowWalkingFeatures").toArray(String[]::new)))) {
            score += PlannerScoringPolicy.weight(taxonomy, "replacementNightLowWalking");
        }
        if (safe(candidate.getDistrict()).equals(old.getDistrict())) score += PlannerScoringPolicy.weight(taxonomy, "replacementSameDistrict");
        return score;
    }

    private boolean matchesAnyHint(String value, String key) {
        return value != null && taxonomy.getHints(key).stream().anyMatch(value::contains);
    }

    private boolean matchesAnyHint(List<String> values, String key) {
        return values != null && values.stream().anyMatch(value -> matchesAnyHint(value, key));
    }

    private Map<String, Object> planResponse(StoredSession session, String token) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("sessionId", session.sessionId());
        response.put("trip", session.trip());
        response.put("appliedPreferences", session.appliedPreferences().asMap());
        if (token != null && !token.isBlank()) response.put("sessionAccessToken", token);
        response.put("syncRevision", session.syncRevision());

        response.putAll(PlannerVersionMetadata.fromTrip(session.trip(), plannerV1Enabled).asMap());
        return response;
    }

    private Map<String, Object> constraintDiagnostics(TravelConstraints constraints) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("needsClarification", constraints != null && constraints.isNeedsClarification());
        diagnostics.put("missingCriticalFields", constraints == null || constraints.getCriticalMissingFields() == null
                ? List.of() : List.copyOf(constraints.getCriticalMissingFields()));
        diagnostics.put("constraintProvenance", constraints == null ? Map.of() : constraints.asMap().get("constraintProvenance"));
        if (constraints != null && constraints.getConflicts() != null && !constraints.getConflicts().isEmpty()) {
            diagnostics.put("conflicts", constraints.getConflicts().stream().map(com.ai.guide.domain.planner.model.ConstraintConflict::asMap).toList());
            diagnostics.put("constraintWarning", "检测到输入偏好存在冲突：" + constraints.getConflicts().get(0).message());
        } else if (constraints != null && constraints.isNeedsClarification()) {
            boolean durationMissing = constraints.getCriticalMissingFields() != null
                    && constraints.getCriticalMissingFields().contains("durationDays");
            String warning = durationMissing
                    ? "行程天数尚未明确，当前按规划策略暂定为" + constraints.getDurationDays() + "日可调整草案；确认前请补充行程天数与时间。"
                    : "部分关键出行时间未提供，当前为可调整草案；确认前请补充行程时间。";
            diagnostics.put("constraintWarning", warning);
        }
        if (constraints != null && constraints.getShadowSemanticPreferences() != null && !constraints.getShadowSemanticPreferences().isEmpty()) {
            diagnostics.put("shadowSemanticPreferences", constraints.getShadowSemanticPreferences());
        }
        return diagnostics;
    }

    private Map<String, Object> sourceStatus(Map<String, Object> trip) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("provider", "高德 Web Service API");
        source.put("mode", trip == null ? "演示回退模式" : trip.getOrDefault("sourceMode", "演示回退模式"));
        source.put("keyConfigured", trip != null && !"演示回退模式".equals(trip.get("sourceMode")));
        source.put("fallback", trip == null || !Boolean.FALSE.equals(
                ((trip.get("sourceStatus") instanceof Map<?, ?> status) ? status.get("fallback") : null)));
        if (trip != null && trip.get("sourceStatus") instanceof Map<?, ?> status) {
            status.forEach((key, value) -> source.put(String.valueOf(key), value));
        }
        return source;
    }
    private ServiceResult error(int status, String message, StoredSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false); body.put("message", message);
        if (session != null) {
            body.put("sessionId", session.sessionId());
            body.put("trip", session.trip());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
        }
        return new ServiceResult(status, body);
    }
    private ServiceResult conflict(StoredSession session) {
        if (metricsLogger != null && session != null) {
            metricsLogger.logRevisionConflict(session.sessionId(), session.currentVersion(), session.currentVersion());
        }
        return error(409, "规划版本已变化，请刷新后重试。", session);
    }
    private int expectedVersion(PlannerMutationRequest request, int current) { return request != null && request.getExpectedVersion() != null ? request.getExpectedVersion() : current; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Map<String, Object> value) {
        // A rejected edit must not mutate nested days/stops held by the repository's authoritative snapshot.
        return objectMapper.convertValue(value == null ? Map.of() : value,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dayStops(Map<String, Object> day) { return (List<Map<String, Object>>) day.computeIfAbsent("stops", ignored -> new ArrayList<>()); }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> days(Map<String, Object> trip) { return (List<Map<String, Object>>) trip.computeIfAbsent("days", ignored -> new ArrayList<>()); }
    private Map<String, Object> day(Map<String, Object> trip, int dayNumber) { return days(trip).stream().filter(item -> Integer.valueOf(dayNumber).equals(number(item.get("day")))).findFirst().orElse(null); }
    private Integer number(Object value) { try { return value == null ? null : Integer.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { return null; } }

    @SuppressWarnings("unchecked")
    private StopLocation locateStop(Map<String, Object> trip, String stopId) {
        for (Map<String, Object> day : days(trip)) {
            List<Map<String, Object>> stops = dayStops(day);
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> stop = stops.get(i);
                if (stopId.equals(stop.get("id")) || stopId.equals(stop.get("stableStopId"))) return new StopLocation(stops, i, stop);
            }
        }
        return null;
    }
    private Set<String> usedVenues(Map<String, Object> trip) { Set<String> result = new HashSet<>(); for (Map<String, Object> day : days(trip)) for (Map<String, Object> stop : dayStops(day)) if (stop.get("venueId") != null) result.add(String.valueOf(stop.get("venueId"))); return result; }
    private List<String> unchangedStopIds(Map<String, Object> trip, String changed) { List<String> result = new ArrayList<>(); for (Map<String, Object> day : days(trip)) for (Map<String, Object> stop : dayStops(day)) { String id = safe(stop.get("id")); if (!id.equals(changed)) result.add(id); } return result; }
    private void copyConstraintAnnotations(Map<String, Object> old, Map<String, Object> next) { next.put("matchedConstraints", old.getOrDefault("matchedConstraints", List.of())); next.put("routePreference", old.getOrDefault("routePreference", "walking")); }
    private void finalizeTrip(Map<String, Object> trip, int version, String label, String reason, List<String> changed) { trip.put("version", version); trip.put("id", "draft-plan-" + version); trip.put("subtitle", label + " · 路线已重新计算"); List<Map<String, Object>> history = new ArrayList<>(); Object old = trip.get("versionHistory"); if (old instanceof List<?> values) for (Object value : values) if (value instanceof Map<?, ?> map) { Map<String, Object> copy = new LinkedHashMap<>(); map.forEach((k, v) -> copy.put(String.valueOf(k), v)); history.add(copy); } history.add(Map.of("version", version, "label", label, "changedSegments", changed, "reason", safe(reason), "createdAt", Instant.now().toString())); trip.put("versionHistory", history); }
    private String safe(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String safeValue(Object value, String fallback) { String text = safe(value); return text.isBlank() ? fallback : text; }
    private String fingerprint(String prompt, Map<String, Object> constraints, boolean usePreferences,
                               AppliedPreferencesSnapshot appliedPreferences, String decision) {
        try {
            Map<String, Object> semantic = new LinkedHashMap<>();
            semantic.put("prompt", prompt);
            semantic.put("constraints", constraints);
            semantic.put("usePreferences", usePreferences);
            semantic.put("appliedPreferencesFingerprint", appliedPreferences == null ? ""
                    : appliedPreferences.fingerprint());
            // The decision is retained for request identity only; it is never an
            // authority and never mutates formal Java Preferences.
            semantic.put("preferenceDecision", decision == null ? "" : decision);
            String source = objectMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(semantic);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成规划请求指纹", e);
        }
    }

    public record ServiceResult(int status, Map<String, Object> body) { }
    private record PlanningComputation(TravelConstraints constraints,
                                       AppliedPreferencesSnapshot appliedPreferences,
                                       Map<String, Object> trip) { }
    private record StopLocation(List<Map<String, Object>> stops, int index, Map<String, Object> stop) { }
    private record Ranked(Attraction attraction, int score) { }

    public static String stripTrailingPunctuation(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        while (trimmed.endsWith("。") || trimmed.endsWith(".") || trimmed.endsWith("；") || trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}


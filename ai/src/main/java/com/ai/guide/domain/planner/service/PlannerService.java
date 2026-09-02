package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintConflict;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.api.PlannerMutationRequest;
import com.ai.guide.domain.planner.api.ShadowMutationRequest;
import com.ai.guide.domain.planner.api.ShadowPlanRequest;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.narrative.GroundedNarrativeService;
import com.ai.guide.domain.planner.repository.PlannerSessionRepository;
import com.ai.guide.domain.planner.repository.PlannerSessionRepositoryPort;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
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
public class PlannerService implements PlanningApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

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
                          @org.springframework.beans.factory.annotation.Value("${planner.v1.enabled:true}") boolean plannerV1Enabled) {
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
        long startNanos = System.nanoTime();
        String prompt = request == null ? "" : safe(request.effectivePrompt());
        Map<String, Object> overrides = request == null ? Map.of() : request.effectiveConstraints();
        boolean usePreferences = request != null && Boolean.TRUE.equals(request.getUsePreferences());
        PlanningComputation computation = compute(prompt, overrides, usePreferences);
        TravelConstraints constraints = computation.constraints();
        AppliedPreferencesSnapshot appliedPreferences = computation.appliedPreferences();
        Map<String, Object> trip = computation.trip();
        String ownerType = UserContext.isAnonymous() ? "ANONYMOUS" : "USER";
        String idempotencyKey = request == null ? "" : safe(request.getIdempotencyKey());
        String suppliedCapability = request == null ? "" : safe(request.getSessionAccessToken());
        String ownerId;
        if ("ANONYMOUS".equals(ownerType)) {
            if (!suppliedCapability.isBlank()) {
                PlannerSessionRepository.StoredSession capabilitySession =
                        repository.findAnonymousByAccessToken(suppliedCapability);
                PlannerSessionRepository.StoredSession keyedSession = capabilitySession == null
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
        String fingerprint = fingerprint(prompt, constraints.asMap(), usePreferences, appliedPreferences,
                request == null ? "" : request.getPreferenceDecision());
        PlannerSessionRepository.CreateResult created = repository.create(ownerType, ownerId, prompt,
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
        PlanningComputation computation = compute(safe(effective.effectivePrompt()),
                effective.effectiveConstraints(), Boolean.TRUE.equals(effective.getUsePreferences()));
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
            replacement = rankReplacement(reason, oldAttraction, used);
            if (replacement == null) return shadowError("当前没有可用的替换景点。");
        }
        Map<String, Object> newStop = itineraryBuilder.createStop(replacement, targetId,
                safeValue(target.stop().get("time"), "待安排"),
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
                        safeValue(target.stop().get("time"), "待安排"),
                        safeValue(target.stop().get("duration"), attraction.getDuration()),
                        "根据偏好替换为" + attraction.getName() + "，保留原时间段与其余站点。",
                        attraction.getIntro());
                copyConstraintAnnotations(target.stop(), stop);
                target.stops().set(target.index(), stop);
                changedId = targetId;
                label = "替换站点";
            } else {
                if (usedVenues(trip).contains(attractionId)) return shadowError("该景点已在当前行程中。");
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
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, accessToken);
        return session == null ? error(404, "规划会话不存在或无权访问。", null) : new ServiceResult(200, planResponse(session, ""));
    }

    /**
     * Refreshes provider-backed facts without creating a planner revision. Dynamic
     * weather, POI and route data are intentionally not treated as a user edit.
     */
    public ServiceResult refreshDynamicData(String sessionId, String accessToken) {
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, accessToken);
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        Map<String, Object> trip = mutableMap(session.trip());
        AmapPlannerGateway.HydrationResult hydration = amapPlannerGateway.hydrate(trip);
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
        for (PlannerSessionRepository.StoredSession session : repository.list("USER", UserContext.getUserId())) {
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

    public ServiceResult replan(String sessionId, PlannerMutationRequest request) {
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        Map<String, Object> trip = mutableMap(session.trip());
        String targetId = request == null ? "" : safe(request.getTargetStopId());
        String reason = request == null ? "" : safe(request.getReason());
        StopLocation target = locateStop(trip, targetId);
        if (target == null) return error(400, "未在当前行程中找到要替换的站点。", session);
        Attraction oldAttraction = itineraryBuilder.attraction(String.valueOf(target.stop().get("venueId")));
        if (oldAttraction == null) return error(400, "当前站点缺少有效景点实体。", session);
        Set<String> used = usedVenues(trip);
        used.remove(oldAttraction.getId());
        String requestedCandidate = request == null ? "" : safe(request.getCandidateVenueId());
        Attraction replacement;
        if (!requestedCandidate.isBlank()) {
            replacement = itineraryBuilder.attraction(requestedCandidate);
            if (replacement == null) return error(400, "候选景点不存在。", session);
            if (used.contains(replacement.getId())) return error(400, "候选景点已存在于当前行程，不能造成重复。", session);
        } else {
            replacement = rankReplacement(reason, oldAttraction, used);
            if (replacement == null) return error(400, "当前没有可用的替换景点。", session);
        }
        Map<String, Object> newStop = itineraryBuilder.createStop(replacement, targetId,
                safeValue(target.stop().get("time"), "待安排"), safeValue(target.stop().get("duration"), replacement.getDuration()),
                "因为“" + reason + "”，换成更合适的" + replacement.getName() + "（" + replacement.getSummary() + "）。",
                "这是局部替换，仅改变目标站点；" + replacement.getIntro());
        copyConstraintAnnotations(target.stop(), newStop);
        target.stops().set(target.index(), newStop);
        finalizeTrip(trip, session.currentVersion() + 1, "局部重规划", reason, List.of(targetId));
        amapPlannerGateway.hydrate(trip);
        int expected = expectedVersion(request, session.currentVersion());
        PlannerSessionRepository.MutationResult result = repository.update(session, trip, expected,
                "REPLAN", "局部重规划", reason, List.of(targetId));
        if ("CONFLICT".equals(result.status())) return conflict(result.session());
        List<String> unchanged = unchangedStopIds(trip, targetId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("trip", trip);
        body.put("changedSegments", List.of(targetId));
        body.put("unchangedStops", unchanged);
        body.put("replacementReason", reason);
        body.put("replacementVenueId", replacement.getId());
        body.put("appliedPreferences", result.session().appliedPreferences().asMap());
        if ("少走路".equals(reason)) body.put("memoryProposal", Map.of("type", "preference", "value", "少走路", "copy", "要记住“少走路”偏好吗？"));
        return new ServiceResult(200, body);
    }

    public ServiceResult mutateStops(String sessionId, PlannerMutationRequest request) {
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        String operation = request == null ? "add" : safe(request.getOperation());
        if (operation.isBlank()) operation = "add";
        Map<String, Object> trip = mutableMap(session.trip());
        String changedId;
        String label;
        if ("delete".equals(operation) || "remove".equals(operation)) {
            String stopId = request == null ? "" : safe(request.getStopId());
            if (stopId.isBlank()) stopId = request == null ? "" : safe(request.getTargetStopId());
            StopLocation target = locateStop(trip, stopId);
            if (target == null) return error(400, "未在当前行程中找到要移除的站点。", session);
            target.stops().remove(target.index());
            changedId = stopId;
            label = "移除站点";
        } else {
            String attractionId = request == null ? "" : safe(request.getAttractionId());
            Attraction attraction = itineraryBuilder.attraction(attractionId);
            if (attraction == null) return error(400, "景点不存在。", session);
            int dayNumber = request == null || request.getDay() == null ? 1 : request.getDay();
            Map<String, Object> day = day(trip, dayNumber);
            if (day == null) return error(400, "目标日期不存在。", session);
            if (usedVenues(trip).contains(attractionId) && !"replace".equals(operation)) return error(400, "该景点已在当前行程中。", session);
            if ("replace".equals(operation)) {
                String targetId = request == null ? "" : safe(request.getTargetStopId());
                StopLocation target = locateStop(trip, targetId);
                if (target == null) return error(400, "未找到要替换的站点。", session);
                Map<String, Object> stop = itineraryBuilder.createStop(attraction, targetId,
                        safeValue(target.stop().get("time"), "待安排"), safeValue(target.stop().get("duration"), attraction.getDuration()),
                        "根据偏好替换为" + attraction.getName() + "，保留原时间段与其余站点。", attraction.getIntro());
                copyConstraintAnnotations(target.stop(), stop);
                target.stops().set(target.index(), stop);
                changedId = targetId;
                label = "替换站点";
            } else {
                changedId = "day" + dayNumber + "-added-" + attractionId + "-" + UUID.randomUUID().toString().substring(0, 4);
                Map<String, Object> stop = itineraryBuilder.createStop(attraction, changedId, "待安排", attraction.getDuration(),
                        "将" + attraction.getName() + "加入当前行程，具体停留与开放状态需出发前核验。", attraction.getIntro());
                dayStops(day).add(stop);
                label = "添加站点";
            }
        }
        finalizeTrip(trip, session.currentVersion() + 1, label, safe(request == null ? null : request.getReason()), List.of(changedId));
        amapPlannerGateway.hydrate(trip);
        int expected = expectedVersion(request, session.currentVersion());
        PlannerSessionRepository.MutationResult result = repository.update(session, trip, expected,
                "STOP_MUTATION", label, safe(request == null ? null : request.getReason()), List.of(changedId));
        if ("CONFLICT".equals(result.status())) return conflict(result.session());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("operation", operation);
        body.put("trip", trip);
        body.put("changedSegments", List.of(changedId));
        body.put("appliedPreferences", result.session().appliedPreferences().asMap());
        body.put("message", "站点已更新，路线与版本信息已刷新。");
        return new ServiceResult(200, body);
    }

    public ServiceResult save(String sessionId, String accessToken) {
        try {
            return saveOnce(sessionId, accessToken);
        } catch (DataAccessResourceFailureException exception) {
            // 保存键按“会话 + 版本”幂等。首次请求若恰好拿到被云端回收的连接，
            // Hikari 会剔除坏连接，随后完整重试一次不会写出重复行程。
            log.warn("[Planner] 保存行程遇到瞬时数据库断连，正在使用新连接重试一次: {}", exception.getMostSpecificCause().getMessage());
            return saveOnce(sessionId, accessToken);
        }
    }

    private ServiceResult saveOnce(String sessionId, String accessToken) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后保存行程。", null);
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, accessToken);
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        Map<String, Object> plan = mutableMap(session.trip());
        plan.put("plannerSessionId", session.sessionId());
        plan.put("plannerVersion", session.currentVersion());
        plan.put("appliedPreferences", session.appliedPreferences().asMap());
        String saveKey = "planner-save-" + session.sessionId() + "-" + session.currentVersion();
        TripService.OperationResult saved = tripService.savePlannerSnapshot(
                UserContext.getUserId(), plan, saveKey);
        if (saved.status() >= 400 || saved.trip() == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", saved.message());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
            if (saved.tripId() != null) body.put("tripId", saved.tripId());
            if (saved.expectedVersion() != null) body.put("expectedVersion", saved.expectedVersion());
            if (saved.currentVersion() != null) body.put("currentVersion", saved.currentVersion());
            return new ServiceResult(saved.status(), body);
        }
        plan.put("formalTripId", saved.trip().id());
        plan.put("sourceTripId", saved.trip().id());
        plan.put("sourceTripVersion", saved.trip().currentVersion());
        repository.linkFormalTrip(session, plan);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("trip", saved.trip());
        body.put("tripId", saved.trip().id());
        body.put("currentVersion", saved.trip().currentVersion());
        body.put("appliedPreferences", session.appliedPreferences().asMap());
        return new ServiceResult(saved.status(), body);
    }

    @Override
    public ServiceResult openFormalTrip(String tripId) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后打开正式行程。", null);
        String formalTripId = safe(tripId);
        if (formalTripId.isBlank()) return error(400, "缺少正式行程 ID。", null);

        Trip formal = tripService.get(UserContext.getUserId(), formalTripId);
        if (formal == null) return error(404, "正式行程不存在或无权访问。", null);

        Map<String, Object> restoredPlan = copyTripSnapshot(formal.plan());
        if (restoredPlan.isEmpty()) return error(400, "正式行程缺少可恢复的规划快照。", null);
        restoredPlan.put("formalTripId", formal.id());
        restoredPlan.put("sourceTripId", formal.id());
        restoredPlan.put("sourceTripVersion", formal.currentVersion());
        restoredPlan.put("sourcePlannerRevision", restoredPlan.getOrDefault("version", 1));
        restoredPlan.put("plannerSessionRestoreSource", "RESTORED_FROM_TRIP");
        restoredPlan.put("version", 1);

        TravelConstraints constraints = restoreConstraints(restoredPlan);
        AppliedPreferencesSnapshot appliedPreferences = restoreAppliedPreferences(restoredPlan);
        if (appliedPreferences.isEmpty()) {
            restoredPlan.put("plannerSessionRestoreDegradation", "APPLIED_PREFERENCES_UNAVAILABLE");
        }
        String prompt = safe(restoredPlan.get("prompt"));
        if (prompt.isBlank()) prompt = safe(restoredPlan.get("input"));
        String restoreKey = "trip-open-" + formal.id() + "-" + formal.currentVersion() + "-" + UUID.randomUUID();
        String restoreFingerprint = fingerprint(prompt, constraints.asMap(), false, appliedPreferences,
                "RESTORED_FROM_TRIP:" + formal.id() + ":" + formal.currentVersion());
        PlannerSessionRepository.CreateResult created = repository.create("USER", UserContext.getUserId(), prompt,
                constraints.asMap(), appliedPreferences, restoredPlan, restoreKey, restoreFingerprint);
        Map<String, Object> response = planResponse(created.session(), "");
        response.put("formalTripId", formal.id());
        response.put("formalTripVersion", formal.currentVersion());
        response.put("restoredFromTrip", true);
        response.put("adjustmentCapability", "V1_PROPOSAL");
        response.put("legacyMode", false);
        return new ServiceResult(200, response);
    }

    @Override
    public ServiceResult converse(String sessionId, PlanConversationRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.preview(sessionId, request);
    }

    @Override
    public ServiceResult previewAdjustment(String sessionId, PlanConversationRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.preview(sessionId, request);
    }

    @Override
    public ServiceResult applyAdjustment(String sessionId, ApplyAdjustmentRequestDto request) {
        if (!plannerV1Enabled) {
            return error(400, "LEGACY_PLANNER_UNAVAILABLE: 详情页 AI 调整能力仅在 V1 规划器启用时可用。", null);
        }
        return planAdjustmentService.apply(sessionId, request);
    }

    private PlanningComputation compute(String prompt, Map<String, Object> overrides, boolean usePreferences) {
        TravelConstraints parsed = constraintParser.parse(safe(prompt));
        TravelConstraints requested = constraintParser.applyOverrides(parsed,
                overrides == null ? Map.of() : overrides);
        PlannerPreferencesResolver.Resolution resolved = preferencesResolver.resolve(requested, usePreferences);
        TravelConstraints constraints = constraintParser.applyDerivedTransportFallback(resolved.constraints());
        if (parsed.originOf("durationDays") == com.ai.guide.domain.planner.model.ConstraintOrigin.PROMPT && parsed.getDurationDays() > 0) {
            constraints.setDurationDays(parsed.getDurationDays());
        }
        Map<String, Object> trip = plannerV1Enabled
                ? itineraryBuilder.build(1, constraints)
                : itineraryBuilder.buildLegacy(1, constraints);
        // Provider calls mutate only this in-memory result; they never write a
        // planner session or formal Trip.
        amapPlannerGateway.hydrate(trip);

        if (plannerV1Enabled && groundedNarrativeService != null) {
            groundedNarrativeService.enhanceTrip(trip, constraints, resolved.snapshot());
        }

        return new PlanningComputation(constraints, resolved.snapshot(), trip);
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

        // V1 Metadata
        String routeStatus = String.valueOf(trip.getOrDefault("routeDataStatus", "ESTIMATED"));
        boolean isDegraded = !plannerV1Enabled || !"高德实时接口".equals(trip.get("sourceMode"));
        String plannerVer = !plannerV1Enabled ? "legacy-v0" : PlannerVersionMetadata.CURRENT_PLANNER_VERSION;
        String policyVer = !plannerV1Enabled ? "legacy-2026.07" : PlannerVersionMetadata.CURRENT_POLICY_VERSION;
        String expSource = !plannerV1Enabled ? "LEGACY_STATIC"
                : (trip != null && trip.get("explanationSource") != null
                    ? String.valueOf(trip.get("explanationSource"))
                    : PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
        List<String> degradationReasons = !plannerV1Enabled
                ? List.of("LEGACY_PLANNER_ACTIVE")
                : (isDegraded ? List.of("DEMO_OR_ESTIMATED_ROUTE_FALLBACK") : List.of());

        body.put("plannerVersion", plannerVer);
        body.put("policyVersion", policyVer);
        body.put("explanationSource", expSource);
        body.put("routeDataStatus", routeStatus);
        body.put("degraded", isDegraded);
        body.put("degradationReasons", degradationReasons);
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

    private PlannerSessionRepository.StoredSession findAuthorized(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (UserContext.isAnonymous()) {
            // Resolve the owner from the capability digest before any session
            // lookup; a session idempotency key is never a credential.
            PlannerSessionRepository.StoredSession capabilitySession =
                    repository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return repository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        return repository.find(sessionId, "USER", UserContext.getUserId(), token);
    }

    private Attraction rankReplacement(String reason, Attraction old, Set<String> used) {
        return itineraryBuilder.attractionServiceList().stream().filter(item -> !used.contains(item.getId()) && !item.getId().equals(old.getId()))
                .map(item -> new Ranked(item, replacementScore(item, old, reason)))
                .max(Comparator.comparingInt(Ranked::score)
                        .thenComparingInt(ranked -> -itineraryBuilder.catalogRank(ranked.attraction().getId())))
                .map(Ranked::attraction).orElse(null);
    }

    private int replacementScore(Attraction candidate, Attraction old, String reason) {
        int score = 0;
        List<String> tags = candidate.getTags() == null ? List.of() : candidate.getTags();
        if (reason.matches(".*(室内|雨|避暑).*")) { if (Boolean.TRUE.equals(candidate.getIndoor())) score += 20; if (tags.contains("室内")) score += 10; }
        if (reason.matches(".*(少走路|不想走|轻松|老人|长辈).*")) { if ("低".equals(candidate.getWalkDifficulty())) score += 20; if (safe(candidate.getWalk()).matches(".*(少走路|直达|扶梯).*")) score += 10; if (tags.contains("少走路") || tags.contains("长辈友好")) score += 10; }
        if (reason.matches(".*(时间变少|赶时间|快|短停留).*")) { if (safe(candidate.getDuration()).matches(".*(45|60).*")) score += 20; if (tags.contains("短停留")) score += 10; }
        if (reason.matches(".*(夜景|晚上|灯光).*")) if ("夜景".equals(candidate.getCategory()) || tags.contains("夜景")) score += 25;
        if (reason.matches(".*(美食|吃|火锅|小吃).*")) if ("美食".equals(candidate.getCategory()) || tags.contains("美食")) score += 25;
        if (reason.matches(".*(文创|艺术|拍照).*")) if ("文创".equals(candidate.getCategory()) || tags.contains("文创") || tags.contains("拍照")) score += 20;
        if (reason.matches(".*(少走路|不想走|轻松|老人|长辈).*")
                && ("夜景".equals(old.getCategory()) || old.matchesAnyTagOrFeature("夜景"))
                && ("夜景".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("夜景"))
                && ("低".equals(candidate.getWalkDifficulty()) || "SUPPORTED".equals(candidate.getEffectiveAccessibility()))
                && (safe(candidate.getWalk()).matches(".*(少走路|直达|短步行|平街|扶梯).*") || candidate.matchesAnyTagOrFeature("少走路", "视野开阔"))) {
            score += 30;
        }
        if (safe(candidate.getDistrict()).equals(old.getDistrict())) score += 5;
        return score;
    }

    private Map<String, Object> planResponse(PlannerSessionRepository.StoredSession session, String token) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("sessionId", session.sessionId());
        response.put("trip", session.trip());
        response.put("appliedPreferences", session.appliedPreferences().asMap());
        if (token != null && !token.isBlank()) response.put("sessionAccessToken", token);
        response.put("syncRevision", session.syncRevision());

        // V1 Metadata
        String routeStatus = session.trip() == null ? "ESTIMATED" : String.valueOf(session.trip().getOrDefault("routeDataStatus", "ESTIMATED"));
        boolean isDegraded = !plannerV1Enabled || session.trip() == null || !"高德实时接口".equals(session.trip().get("sourceMode"));
        String plannerVer = !plannerV1Enabled ? "legacy-v0" : PlannerVersionMetadata.CURRENT_PLANNER_VERSION;
        String policyVer = !plannerV1Enabled ? "legacy-2026.07" : PlannerVersionMetadata.CURRENT_POLICY_VERSION;
        String expSource = !plannerV1Enabled ? "LEGACY_STATIC"
                : (session.trip() != null && session.trip().containsKey("explanationSource")
                    ? String.valueOf(session.trip().get("explanationSource"))
                    : PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
        List<String> degradationReasons = !plannerV1Enabled
                ? List.of("LEGACY_PLANNER_ACTIVE")
                : (isDegraded ? List.of("DEMO_OR_ESTIMATED_ROUTE_FALLBACK") : List.of());

        response.put("plannerVersion", plannerVer);
        response.put("policyVersion", policyVer);
        response.put("explanationSource", expSource);
        response.put("routeDataStatus", routeStatus);
        response.put("degraded", isDegraded);
        response.put("degradationReasons", degradationReasons);
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
            diagnostics.put("constraintWarning", "部分关键出行时间未提供，当前为可调整草案；确认前请补充行程时间。");
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
    private ServiceResult error(int status, String message, PlannerSessionRepository.StoredSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false); body.put("message", message);
        if (session != null) {
            body.put("sessionId", session.sessionId());
            body.put("trip", session.trip());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
        }
        return new ServiceResult(status, body);
    }
    private ServiceResult conflict(PlannerSessionRepository.StoredSession session) {
        if (metricsLogger != null && session != null) {
            metricsLogger.logRevisionConflict(session.sessionId(), session.currentVersion(), session.currentVersion());
        }
        return error(409, "规划版本已变化，请刷新后重试。", session);
    }
    private int expectedVersion(PlannerMutationRequest request, int current) { return request != null && request.getExpectedVersion() != null ? request.getExpectedVersion() : current; }

    private TravelConstraints restoreConstraints(Map<String, Object> restoredPlan) {
        Object rawConstraints = restoredPlan.get("constraints");
        if (rawConstraints instanceof Map<?, ?> values) {
            try {
                return objectMapper.convertValue(values, TravelConstraints.class);
            } catch (IllegalArgumentException ignored) {
                restoredPlan.put("plannerSessionRestoreDegradation", "CONSTRAINTS_UNAVAILABLE");
            }
        }
        return new TravelConstraints();
    }

    private AppliedPreferencesSnapshot restoreAppliedPreferences(Map<String, Object> restoredPlan) {
        Object rawSnapshot = restoredPlan.get("appliedPreferences");
        if (!(rawSnapshot instanceof Map<?, ?> values)) return AppliedPreferencesSnapshot.empty();
        try {
            return AppliedPreferencesSnapshot.fromJson(objectMapper.writeValueAsString(values), objectMapper);
        } catch (Exception ignored) {
            return AppliedPreferencesSnapshot.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyTripSnapshot(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(value), LinkedHashMap.class);
        } catch (Exception ignored) {
            return new LinkedHashMap<>(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Map<String, Object> value) { return new LinkedHashMap<>(value == null ? Map.of() : value); }
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
    private void finalizeTrip(Map<String, Object> trip, int version, String label, String reason, List<String> changed) { trip.put("version", version); trip.put("id", "draft-cq-" + version); trip.put("subtitle", label + " · 路线已重新计算"); List<Map<String, Object>> history = new ArrayList<>(); Object old = trip.get("versionHistory"); if (old instanceof List<?> values) for (Object value : values) if (value instanceof Map<?, ?> map) { Map<String, Object> copy = new LinkedHashMap<>(); map.forEach((k, v) -> copy.put(String.valueOf(k), v)); history.add(copy); } history.add(Map.of("version", version, "label", label, "changedSegments", changed, "reason", safe(reason), "createdAt", Instant.now().toString())); trip.put("versionHistory", history); }
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
            String source = objectMapper.writeValueAsString(semantic);
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
}

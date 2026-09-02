package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanAdjustmentPreviewDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.repository.PlannerSessionRepository;
import com.ai.guide.domain.planner.repository.PlannerSessionRepositoryPort;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话式行程方案调整、预览与原子应用编排服务
 *
 * 所属领域：domain.planner.service（智能排程与规划引擎服务层）
 * 架构职责：编排详情页对话式行程调整全流程，包括自然语言诉求提取、冲突检测、1~3 个候选替换方案生成、路线重算差异对比与两阶段原子确认。
 *
 * 核心方法与职责：
 * 1. previewAdjustment：生成方案调整预览（返回包含 candidateReplacements 与可行性评估的 DTO）
 *    - 参数：sessionId（会话 ID）、request（包含对话内容与上下文状态）、session（持久化会话记录）
 * 2. applyAdjustment：确认并应用指定的调整方案选项（校验版本号一致性后原子升级版本）
 *    - 参数：sessionId、request（包含 proposalId 与选中的 optionId）
 */
@Service
public class PlanAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAdjustmentService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final PlannerSessionRepositoryPort repository;
    private final ItineraryBuilderPort itineraryBuilder;
    private final AttractionService attractionService;
    private final RouteCostProvider routeCostProvider;
    private final PlanVerifier planVerifier;
    private final RouteAwarePlanner routeAwarePlanner;
    private final ProposalStore proposalStore;
    private final LlmIntentExtractor llmIntentExtractor;
    private final ObjectMapper objectMapper;
    private final PlannerMetricsLogger metricsLogger;
    private final Map<String, ApplyReplay> applyReplays = new ConcurrentHashMap<>();

    @Autowired
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalStore proposalStore,
                                 LlmIntentExtractor llmIntentExtractor,
                                 ObjectMapper objectMapper,
                                 PlannerMetricsLogger metricsLogger) {
        this.repository = repository;
        this.itineraryBuilder = itineraryBuilder;
        this.attractionService = attractionService;
        this.routeCostProvider = routeCostProvider;
        this.planVerifier = planVerifier;
        this.routeAwarePlanner = routeAwarePlanner;
        this.proposalStore = proposalStore;
        this.llmIntentExtractor = llmIntentExtractor;
        this.objectMapper = objectMapper;
        this.metricsLogger = metricsLogger;
    }

    /** Backward-compatible constructor for deterministic unit-test callers. */
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalStore proposalStore,
                                 ConversationIntentClassifier intentClassifier,
                                 ObjectMapper objectMapper) {
        this(repository, itineraryBuilder, attractionService, routeCostProvider,
                planVerifier, routeAwarePlanner, proposalStore,
                disabledExtractor(intentClassifier, objectMapper), objectMapper,
                new PlannerMetricsLogger());
    }

    /** Backward-compatible constructor retaining the metrics injection seam. */
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalStore proposalStore,
                                 ConversationIntentClassifier intentClassifier,
                                 ObjectMapper objectMapper,
                                 PlannerMetricsLogger metricsLogger) {
        this(repository, itineraryBuilder, attractionService, routeCostProvider,
                planVerifier, routeAwarePlanner, proposalStore,
                disabledExtractor(intentClassifier, objectMapper), objectMapper,
                metricsLogger);
    }

    private static LlmIntentExtractor disabledExtractor(ConversationIntentClassifier classifier,
                                                        ObjectMapper objectMapper) {
        return new LlmIntentExtractor((systemPrompt, userPrompt) -> "", objectMapper,
                classifier, false, false, 0.75, 3500);
    }

    public PlannerService.ServiceResult preview(String sessionId, PlanConversationRequestDto request) {
        long startNanos = System.nanoTime();
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);

        PlanPageContext context = request == null || request.getContext() == null ? new PlanPageContext() : request.getContext();
        String messageText = request == null ? "" : request.getMessage();
        if (isAlternativeCandidateRequest(messageText)
                && context.getActiveProposalId() != null && !context.getActiveProposalId().isBlank()
                && (context.getSelectedStopId() == null || context.getSelectedStopId().isBlank())) {
            PlanAdjustmentProposal previous = proposalStore.get(context.getActiveProposalId());
            String previousTarget = previous == null || previous.getIntent() == null
                    ? null : previous.getIntent().getTargetStopId();
            if (previousTarget != null && !previousTarget.isBlank()) context.setSelectedStopId(previousTarget);
        }
        LlmIntentExtractor.LlmIntentExtractionResult extraction = llmIntentExtractor.extract(
                messageText, context, session.trip());
        PlanAdjustmentIntent intent = extraction.intent();

        // Server-side context resolution & sufficiency validation
        resolveAndValidate(intent, context, session.trip());

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        String decision = (intent.isRequiresClarification() || intent.getType() == ConversationIntentType.CLARIFICATION || intent.getType() == ConversationIntentType.UNKNOWN)
                ? "CLARIFICATION"
                : (intent.getType() == ConversationIntentType.PLACE_QUESTION ? "ANSWER"
                : (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT ? "OPTION_SELECT" : "PROPOSAL"));
        String toolName = (intent.getType() == ConversationIntentType.PLACE_QUESTION) ? "answerPlaceQuestion"
                : (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT ? "selectProposalOption"
                : (decision.equals("PROPOSAL") ? "generateProposalPreview" : "NONE"));

        log.info("[PLANNER_INTENT_DECISION] extractionSource={} operation={} scope={} targetDay={} targetStopId={} missingFields={} decision={} toolName={} latencyMs={}",
                extraction.intentExtractionSource(),
                intent.getOperation(),
                intent.getScope() != null ? intent.getScope() : "STOP",
                intent.getDayNumber(),
                intent.getTargetStopId(),
                intent.getMissingFields(),
                decision,
                toolName,
                latencyMs);

        if (intent.isRequiresClarification() || intent.getType() == ConversationIntentType.CLARIFICATION || intent.getType() == ConversationIntentType.UNKNOWN) {
            if (metricsLogger != null) {
                metricsLogger.logProposalPreviewed(session.sessionId(), "clarification", intent.getType().name(), false, latencyMs);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("type", intent.getType().name());
            body.put("operation", intent.getOperation());
            body.put("requiresClarification", true);
            body.put("clarificationQuestion", intent.getClarificationQuestion());
            body.put("message", intent.getClarificationQuestion());
            body.put("modified", false);
            return withIntentMetadata(new PlannerService.ServiceResult(200, body), extraction);
        }

        if (intent.getType() == ConversationIntentType.PLACE_QUESTION) {
            if (metricsLogger != null) {
                metricsLogger.logProposalPreviewed(session.sessionId(), "place-question", "PLACE_QUESTION", true, latencyMs);
            }
            String answer = answerPlaceQuestion(intent, session.trip());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("type", "PLACE_QUESTION");
            body.put("operation", "QA");
            body.put("answer", answer);
            body.put("message", answer);
            body.put("modified", false);
            return withIntentMetadata(new PlannerService.ServiceResult(200, body), extraction);
        }

        if (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT && intent.getProposalId() != null && !intent.getProposalId().isBlank()) {
            int optIndex = intent.getCandidateIndex() != null ? intent.getCandidateIndex() : 1;
            String optId = intent.getOptionId() != null ? intent.getOptionId() : "option-" + optIndex;
            PlanAdjustmentProposal activeProposal = proposalStore.get(intent.getProposalId());
            if (activeProposal == null || !session.sessionId().equals(activeProposal.getSessionId())
                    || activeProposal.getBaseRevision() != session.currentVersion()
                    || activeProposal.getOptionPlans() == null || !activeProposal.getOptionPlans().containsKey(optId)) {
                return error(409, "调整提案已失效、无权访问或不属于当前版本，请重新生成预览。", session);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("type", "PROPOSAL_OPTION_SELECTED");
            body.put("operation", "OPTION_SELECT");
            body.put("proposalId", intent.getProposalId());
            body.put("selectedOptionId", optId);
            body.put("candidateIndex", optIndex);
            body.put("baseRevision", session.currentVersion());
            body.put("message", "已为您选中心仪的方案 " + optIndex + "。请在下方方案卡片中核对，并点击【确认应用并生成新版本 (Revision +1)】生效。");
            body.put("modified", false);
            return withIntentMetadata(new PlannerService.ServiceResult(200, body), extraction);
        }

        // Generate proposal preview for adjustment intents
        return withIntentMetadata(generateProposalPreview(session, intent, context, startNanos), extraction);
    }

    private void resolveAndValidate(PlanAdjustmentIntent intent, PlanPageContext context, Map<String, Object> trip) {
        if (intent == null || trip == null) return;

        int totalDays = days(trip).size();
        Integer activeDay = context.getActiveDay() != null ? context.getActiveDay() : 1;

        // 1. Day resolution
        if (intent.getDayNumber() == null) {
            intent.setDayNumber(activeDay);
        }
        if (intent.getDayNumber() < 1 || intent.getDayNumber() > totalDays) {
            intent.setType(ConversationIntentType.CLARIFICATION);
            intent.setRequiresClarification(true);
            intent.setClarificationQuestion("当前行程共 " + totalDays + " 天，无法调整第 " + intent.getDayNumber() + " 天。");
            return;
        }

        // 2. Stop resolution
        String rawText = intent.getRawMessage() != null ? intent.getRawMessage() : "";
        String selectedStop = safeText(context.getSelectedStopId());

        // LLM 输出允许返回用户可读的景点名称；在进入排程前统一解析为
        // Java 景点目录中的稳定 ID，避免名称与内部 venueId 混用。
        if (intent.getType() == ConversationIntentType.ADD_STOP
                && safeText(intent.getTargetAttractionId()) != null) {
            String resolved = resolveAttractionId(intent.getTargetAttractionId());
            if (resolved != null) intent.setTargetAttractionId(resolved);
        }
        if (intent.getType() == ConversationIntentType.SUGGEST_REPLACEMENTS) {
            String replacementReference = firstNonBlank(
                    intent.getReplacementPlaceId(),
                    intent.getReplacementPlaceName(),
                    intent.getTargetAttractionId());
            String resolved = resolveAttractionId(replacementReference);
            if (resolved != null) {
                intent.setReplacementPlaceId(resolved);
                if (safeText(intent.getTargetAttractionId()) != null) intent.setTargetAttractionId(resolved);
            }
        }

        if (intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) {
            for (Map<?, ?> stop : allStops(trip)) {
                String name = String.valueOf(stop.get("name"));
                String id = String.valueOf(stop.get("id"));
                String stopReference = safeText(intent.getTargetStopReference());
                boolean matchesStopName = rawText.contains(name);
                boolean matchesStopReference = stopReference != null
                        && (name.contains(stopReference) || stopReference.contains(name));
                if (matchesStopName || matchesStopReference) {
                    intent.setTargetStopId(id);
                    if (stop.get("day") instanceof Number n) intent.setDayNumber(n.intValue());
                    break;
                }
            }
        }

        // “最后一个晚间行程/收尾站点”属于页面上下文中的相对目标，不能依赖前端先选中卡片。
        if ((intent.getTargetStopId() == null || intent.getTargetStopId().isBlank())
                && rawText.matches(".*(最后一个|最后一站|末尾|收尾|晚间最后|晚上最后).*")) {
            Map<String, Object> day = findDay(trip, intent.getDayNumber() != null ? intent.getDayNumber() : activeDay);
            if (day != null) {
                List<Map<String, Object>> stops = dayStops(day);
                boolean eveningReference = rawText.matches(".*(晚间|晚上|夜景|江景).*");
                for (int index = stops.size() - 1; index >= 0; index--) {
                    Map<String, Object> stop = stops.get(index);
                    if (!eveningReference || isEveningSlot(String.valueOf(stop.getOrDefault("time", "")))) {
                        intent.setTargetStopId(safe(stop.get("id")));
                        break;
                    }
                }
            }
        }

        if ((intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) && selectedStop != null) {
            intent.setTargetStopId(selectedStop);
        }

        // 3. Sufficiency validation
        switch (intent.getType()) {
            case SUGGEST_REPLACEMENTS -> {
                if (intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) {
                    int day = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                    List<String> stopNames = getStopNamesForDay(trip, day);
                    String question = stopNames.isEmpty()
                            ? "请问你想替换哪一个景点？您可以点击卡片选中，或直接输入景点名称。"
                            : "你想替换第 " + day + " 天的哪个景点？（当天有：" + String.join("、", stopNames) + "，可直接点击卡片或输入名称）";
                    intent.setType(ConversationIntentType.CLARIFICATION);
                    intent.setRequiresClarification(true);
                    intent.setClarificationQuestion(question);
                }
            }
            case REMOVE_STOP -> {
                if (intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) {
                    int day = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                    List<String> stopNames = getStopNamesForDay(trip, day);
                    String question = stopNames.isEmpty()
                            ? "请问你想移除哪一个景点？请点击卡片选中或输入景点名称。"
                            : "你想移除第 " + day + " 天的哪个景点？（当天有：" + String.join("、", stopNames) + "）";
                    intent.setType(ConversationIntentType.CLARIFICATION);
                    intent.setRequiresClarification(true);
                    intent.setClarificationQuestion(question);
                }
            }
            case ADD_STOP -> {
                int day = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                boolean hasVenue = intent.getTargetAttractionId() != null && !intent.getTargetAttractionId().isBlank();
                boolean hasPrefs = intent.getPreferences() != null && !intent.getPreferences().isEmpty();
                if (!hasVenue && !hasPrefs && (intent.getRequestedVenueName() == null || intent.getRequestedVenueName().isBlank())) {
                    intent.setType(ConversationIntentType.CLARIFICATION);
                    intent.setRequiresClarification(true);
                    intent.setClarificationQuestion("你想在第 " + day + " 天增加什么类型的景点？（例如：室内场馆、自然风光、地道美食，或直接输入具体景点名称如“北仓”）");
                }
            }
            case REPLAN_DAY -> {
                int day = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                boolean hasCondition = intent.getCondition() != null && !intent.getCondition().isBlank();
                boolean hasPrefs = intent.getPreferences() != null && !intent.getPreferences().isEmpty();
                if (!hasCondition && !hasPrefs) {
                    intent.setType(ConversationIntentType.CLARIFICATION);
                    intent.setRequiresClarification(true);
                    intent.setClarificationQuestion("你想怎样调整第 " + day + " 天的行程？\n\n1. 重新规划第 " + day + " 天全部景点（如偏好少走路/室内避雨）\n2. 替换第 " + day + " 天的某一个具体景点\n3. 减少第 " + day + " 天的景点数量（行程更轻松）\n4. 添加新的景点");
                }
            }
            case REDUCE_DAY_DENSITY -> {
                if (intent.getReduceCount() == null || intent.getReduceCount() < 1) {
                    intent.setReduceCount(1);
                }
            }
            default -> {}
        }
    }

    private List<String> getStopNamesForDay(Map<String, Object> trip, int dayNumber) {
        List<String> names = new ArrayList<>();
        Map<String, Object> dayMap = findDay(trip, dayNumber);
        if (dayMap != null) {
            for (Map<String, Object> stop : dayStops(dayMap)) {
                if (stop.get("name") != null) names.add(String.valueOf(stop.get("name")));
            }
        }
        return names;
    }

    private PlannerService.ServiceResult withIntentMetadata(
            PlannerService.ServiceResult result,
            LlmIntentExtractor.LlmIntentExtractionResult extraction) {
        if (result == null || extraction == null) return result;
        Map<String, Object> body = new LinkedHashMap<>(result.body());
        body.putAll(extraction.metadata());
        return new PlannerService.ServiceResult(result.status(), body);
    }

    public synchronized PlannerService.ServiceResult apply(String sessionId, ApplyAdjustmentRequestDto request) {
        long startNanos = System.nanoTime();
        PlannerSessionRepository.StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);

        String proposalId = request == null ? "" : safe(request.getProposalId());
        String optionId = request == null ? "option-1" : safe(request.getOptionId());
        if (optionId.isBlank()) optionId = "option-1";
        int requestedRevision = request == null ? session.currentVersion() : request.getBaseRevision();
        boolean forceApply = request != null && request.isForceApply();
        String idempotencyKey = request == null ? "" : safe(request.getIdempotencyKey());
        String replayKey = session.sessionId() + "|" + idempotencyKey;
        String requestFingerprint = proposalId + "|" + optionId + "|" + requestedRevision;
        if (!idempotencyKey.isBlank()) {
            ApplyReplay replay = applyReplays.get(replayKey);
            if (replay != null) {
                if (!replay.requestFingerprint().equals(requestFingerprint)) {
                    return error(409, "同一幂等键不能用于不同的调整确认请求。", session);
                }
                return new PlannerService.ServiceResult(replay.result().status(), new LinkedHashMap<>(replay.result().body()));
            }
        }

        PlanAdjustmentProposal proposal = proposalStore.get(proposalId);

        if (proposal == null) {
            return error(400, "调整提案不存在或已过期，请重新发起预览。", session);
        }
        if (!session.sessionId().equals(proposal.getSessionId())) {
            return error(400, "调整提案不属于当前规划会话。", session);
        }
        if (proposal.isExpired()) {
            proposalStore.remove(proposalId);
            return error(400, "调整提案已过期，请重新发起预览。", session);
        }
        boolean forceApplicable = proposal.getIntent() != null
                && proposal.getIntent().getTargetAttractionId() != null
                && !proposal.getIntent().getTargetAttractionId().isBlank()
                && proposal.getOptionPlans() != null
                && !proposal.getOptionPlans().isEmpty();
        if (!proposal.isFeasible() && !(forceApply && forceApplicable)) {
            return error(400, "这份调整当前不太建议直接采用（不可行约束冲突），请换一个方案或重新确认目标地点。", session);
        }

        int expectedRevision = requestedRevision;
        if (proposal.getBaseRevision() != expectedRevision || proposal.getBaseRevision() != session.currentVersion()) {
            if (metricsLogger != null) {
                metricsLogger.logRevisionConflict(sessionId, proposal.getBaseRevision(), session.currentVersion());
            }
            return conflict(session);
        }

        Map<String, Object> proposedTrip;
        if (proposal.getOptionPlans() != null && !proposal.getOptionPlans().isEmpty()) {
            if (!proposal.getOptionPlans().containsKey(optionId)) {
                return error(400, "指定的选项序号（" + optionId + "）不存在，请选择有效的候选方案选项。", session);
            }
            proposedTrip = deepCopy(proposal.getOptionPlans().get(optionId));
        } else {
            proposedTrip = deepCopy(proposal.getProposedTrip());
        }

        int nextVersion = session.currentVersion() + 1;
        finalizeTrip(proposedTrip, nextVersion, "AI 局部调整应用",
                proposal.getIntent() == null ? "用户确认应用" : proposal.getIntent().getRawMessage(),
                proposal.getChangedSegments());

        String mutationLabel = proposal.getIntent() != null ? proposal.getIntent().getType().name() : "ADJUSTMENT_APPLIED";
        PlannerSessionRepository.MutationResult result = repository.update(
                session, proposedTrip, expectedRevision,
                mutationLabel, "AI 局部调整应用",
                proposal.getIntent() == null ? "" : proposal.getIntent().getRawMessage(),
                proposal.getChangedSegments()
        );

        if ("CONFLICT".equals(result.status())) {
            if (metricsLogger != null) {
                metricsLogger.logRevisionConflict(sessionId, expectedRevision,
                        result.session() == null ? session.currentVersion() : result.session().currentVersion());
            }
            return conflict(result.session());
        }

        proposalStore.remove(proposalId);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (metricsLogger != null) {
            metricsLogger.logProposalApplied(sessionId, proposalId, nextVersion, latencyMs);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("applied", true);
        body.put("sessionId", sessionId);
        body.put("currentVersion", nextVersion);
        body.put("trip", proposedTrip);
        body.put("changedSegments", proposal.getChangedSegments());
        body.put("unchangedStops", proposal.getUnchangedStops());
        body.put("appliedPreferences", result.session().appliedPreferences().asMap());

        // V1 Metadata
        String routeStatus = proposedTrip == null ? "ESTIMATED" : String.valueOf(proposedTrip.getOrDefault("routeDataStatus", "ESTIMATED"));
        boolean isDegraded = proposedTrip == null || !"高德实时接口".equals(proposedTrip.get("sourceMode"));
        PlannerVersionMetadata meta = PlannerVersionMetadata.defaultV1(
                routeStatus, isDegraded, isDegraded ? List.of("DEMO_OR_ESTIMATED_ROUTE_FALLBACK") : List.of()
        );
        body.put("plannerVersion", meta.getPlannerVersion());
        body.put("policyVersion", meta.getPolicyVersion());
        body.put("explanationSource", meta.getExplanationSource());
        body.put("routeDataStatus", meta.getRouteDataStatus());
        body.put("degraded", meta.isDegraded());
        body.put("degradationReasons", meta.getDegradationReasons());

        body.put("forced", forceApply && !proposal.isFeasible());
        body.put("message", forceApply && !proposal.isFeasible()
                ? "已按你的选择应用这份调整；它可能让当天安排不够顺路，请结合实际情况安排行程。"
                : "行程调整已成功应用并持久化。");
        PlannerService.ServiceResult success = new PlannerService.ServiceResult(200, body);
        if (!idempotencyKey.isBlank()) {
            applyReplays.put(replayKey, new ApplyReplay(requestFingerprint, success));
        }
        return success;
    }

    private PlannerService.ServiceResult generateProposalPreview(PlannerSessionRepository.StoredSession session,
                                                                PlanAdjustmentIntent intent,
                                                                PlanPageContext context,
                                                                long startNanos) {
        Map<String, Object> sourceTrip = session.trip();
        Map<String, Object> draftTrip = deepCopy(sourceTrip);
        TravelConstraints constraints = extractConstraints(session);
        List<Map<String, Object>> candidateReplacements = new ArrayList<>();
        Map<String, Map<String, Object>> optionPlans = new LinkedHashMap<>();
        List<String> changedSegments = new ArrayList<>();
        List<String> unchangedStops = new ArrayList<>();
        List<String> reasonCodes = new ArrayList<>();
        List<String> alternatives = new ArrayList<>();
        Map<String, Object> adjustmentDiagnostics = new LinkedHashMap<>();
        List<Map<String, Object>> rejectedOptionDiagnostics = new ArrayList<>();
        boolean feasible = true;

        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction a : attractionService.list(null, null)) byId.put(a.getId(), a);

        switch (intent.getType()) {
            case SUGGEST_REPLACEMENTS -> {
                String targetId = intent.getTargetStopId();
                StopLocation target = locateStop(draftTrip, targetId);
                if (target == null && targetId != null && !targetId.isBlank()) {
                    target = locateStopByVenue(draftTrip, targetId);
                }
                if (target == null) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("requiresClarification", true);
                    body.put("clarificationQuestion", "未在当前行程中找到目标景点，请重新选择卡片。");
                    return new PlannerService.ServiceResult(200, body);
                }
                String effectiveStopId = String.valueOf(target.stop().get("id"));
                Attraction oldAttr = attractionService.get(String.valueOf(target.stop().get("venueId")));
                Set<String> used = usedVenues(draftTrip);
                if (oldAttr != null) used.remove(oldAttr.getId());

                String requestedReplacementId = firstNonBlank(
                        intent.getReplacementPlaceId(),
                        intent.getTargetAttractionId(),
                        resolveAttractionId(intent.getReplacementPlaceName())
                );
                boolean directReplacement = requestedReplacementId != null;
                List<Attraction> candidates;
                if (directReplacement) {
                    Attraction requested = attractionService.get(requestedReplacementId);
                    String slotTime = String.valueOf(target.stop().getOrDefault("time", ""));
                    if (requested == null) {
                        feasible = false;
                        reasonCodes.add("TARGET_VENUE_NOT_FOUND");
                        alternatives.addAll(List.of("从当前重庆核心景点中选择目标地点", "保持原行程"));
                        candidates = List.of();
                    } else if (oldAttr != null && requested.getId().equals(oldAttr.getId())) {
                        feasible = false;
                        reasonCodes.add("SAME_VENUE");
                        alternatives.addAll(List.of("选择其他未安排的景点", "保持原行程"));
                        // 用户明确指定目标地点时仍保留该目标，前端可让用户确认后强制采用。
                        candidates = List.of(requested);
                    } else if (used.contains(requested.getId())) {
                        feasible = false;
                        reasonCodes.add("DUPLICATE_VENUE");
                        alternatives.addAll(List.of("该景点已在行程中，无需重复添加", "选择其他未去过的景点", "保持原行程"));
                        candidates = List.of(requested);
                    } else if (!isOpeningCompatible(requested, slotTime)) {
                        feasible = false;
                        reasonCodes.add("CLOSED_AT_SLOT_TIME");
                        alternatives.addAll(List.of("调整该站点到营业时间内", "选择其他晚间景点", "保持原行程"));
                        candidates = List.of(requested);
                    } else {
                        candidates = List.of(requested);
                    }
                } else {
                    candidates = findCandidates(oldAttr, used, constraints, intent);
                }
                if (candidates.isEmpty()) {
                    if (!directReplacement) return error(400, "当前没有可用的替换候选景点。", session);
                }

                if (directReplacement) {
                    adjustmentDiagnostics.put("replacementMode", "DIRECT");
                    adjustmentDiagnostics.put("sourceStopName", String.valueOf(target.stop().getOrDefault("name", "当前站点")));
                    adjustmentDiagnostics.put("requestedReplacementName", firstNonBlank(intent.getReplacementPlaceName(), requestedReplacementId));
                    adjustmentDiagnostics.put("forceApplicable", !candidates.isEmpty());
                }

                for (int i = 0; i < candidates.size(); i++) {
                    Attraction c = candidates.get(i);
                    Map<String, Object> optionDraft = deepCopy(sourceTrip);
                    StopLocation optTarget = locateStop(optionDraft, effectiveStopId);
                    if (optTarget == null) continue;
                    Map<String, Object> newStop = itineraryBuilder.createStop(
                            c, effectiveStopId,
                            String.valueOf(optTarget.stop().getOrDefault("time", "待安排")),
                            c.getDuration(),
                            "根据调整需求替换为" + c.getName() + "（" + c.getSummary() + "）。",
                            c.getIntro()
                    );
                    optTarget.stops().set(optTarget.index(), newStop);
                    resolveAffectedDay(optionDraft, number(optTarget.day().get("day")), byId, constraints);
                    PlanVerifier.VerificationResult optionVerification = planVerifier.verify(days(optionDraft), constraints);
                    if (!optionVerification.feasible() && !directReplacement) {
                        rejectedOptionDiagnostics.add(Map.of(
                                "venueId", c.getId(),
                                "reasonCodes", optionVerification.violations().stream()
                                        .map(PlanVerifier.PlanViolation::type).distinct().toList()
                        ));
                        continue;
                    }
                    String optId = "option-" + (optionPlans.size() + 1);
                    candidateReplacements.add(Map.of(
                            "optionId", optId,
                            "venueId", c.getId(),
                            "name", c.getName(),
                            "requestedName", firstNonBlank(intent.getReplacementPlaceName(), c.getName()),
                            "directReplacement", directReplacement,
                            "summary", c.getSummary(),
                            "duration", c.getDuration(),
                            "district", c.getDistrict(),
                            "walkDifficulty", c.getWalkDifficulty(),
                            "fit", c.getFit()
                    ));
                    optionPlans.put(optId, optionDraft);
                }

                if (optionPlans.isEmpty()) {
                    feasible = false;
                    reasonCodes.add("NO_FEASIBLE_REPLACEMENT");
                    alternatives.addAll(List.of("换同片区其他景点", "减少当天一个景点", "调整至其他日期", "保持原行程"));
                } else {
                    draftTrip = optionPlans.get("option-1");
                    changedSegments.add(effectiveStopId);
                }
            }
            case ADD_STOP -> {
                String targetVenueId = intent.getTargetAttractionId();
                if (targetVenueId == null && intent.getTargetStopId() != null) {
                    StopLocation s = locateStop(draftTrip, intent.getTargetStopId());
                    if (s != null) targetVenueId = String.valueOf(s.stop().get("venueId"));
                }
                if (targetVenueId == null && intent.getRequestedVenueName() != null) {
                    for (Attraction a : attractionService.list(null, null)) {
                        if (intent.getRequestedVenueName().contains(a.getName()) || a.getName().contains(intent.getRequestedVenueName())) {
                            targetVenueId = a.getId();
                            break;
                        }
                    }
                }
                if (targetVenueId == null && intent.getPreferences() != null && !intent.getPreferences().isEmpty()) {
                    Set<String> used = usedVenues(draftTrip);
                    List<Attraction> candidates = findCandidates(null, used, constraints, intent);
                    if (!candidates.isEmpty()) {
                        targetVenueId = candidates.get(0).getId();
                    }
                }
                Attraction attraction = targetVenueId != null ? attractionService.get(targetVenueId) : null;
                if (attraction == null) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("requiresClarification", true);
                    body.put("clarificationQuestion", "当前 V1 版本支持重庆 24 大核心景点，库外地点添加属于加强版能力；请尝试添加核心景点，例如：三峡博物馆、解放碑、洪崖洞等。");
                    return new PlannerService.ServiceResult(200, body);
                }

                // Check duplicate
                if (usedVenues(draftTrip).contains(targetVenueId)) {
                    feasible = false;
                    reasonCodes.add("DUPLICATE_VENUE");
                    alternatives.addAll(List.of("该景点已在行程中，无需重复添加", "选择其他未去过的景点", "取消添加"));
                }

                int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
                Map<String, Object> day = findDay(draftTrip, dayNumber);
                if (day == null) return error(400, "目标日期不存在。", session);

                List<Map<String, Object>> stops = dayStops(day);
                if (stops.size() >= 4) {
                    feasible = false;
                    reasonCodes.add("DAY_CAPACITY_EXCEEDED");
                    alternatives.addAll(List.of("调整至其他游览天数", "替换当天已有景点", "取消添加"));
                }

                if (feasible) {
                    String stopId = "day" + dayNumber + "-added-" + attraction.getId().replaceFirst("^cq-", "");
                    Map<String, Object> addedStop = itineraryBuilder.createStop(
                            attraction, stopId, "待安排", attraction.getDuration(),
                            "将" + attraction.getName() + "加入当前行程。", attraction.getIntro()
                    );
                    stops.add(addedStop);
                    changedSegments.add(stopId);
                    resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
                    optionPlans.put("option-1", draftTrip);
                }
            }
            case REMOVE_STOP -> {
                String targetId = intent.getTargetStopId();
                StopLocation target = locateStop(draftTrip, targetId);
                if (target == null) return error(400, "未在行程中找到要移除的站点。", session);
                String stopId = String.valueOf(target.stop().get("id"));
                target.stops().remove(target.index());
                changedSegments.add(stopId);
                resolveAffectedDay(draftTrip, number(target.day().get("day")), byId, constraints);
                optionPlans.put("option-1", draftTrip);
            }
            case REPLAN_DAY_FOR_CONDITION -> {
                int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
                Map<String, Object> day = findDay(draftTrip, dayNumber);
                if (day == null) return error(400, "目标日期不存在。", session);

                List<Map<String, Object>> stops = new ArrayList<>(dayStops(day));
                Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
                Set<String> mustVisit = new HashSet<>(constraints.getMustVisit() == null ? List.of() : constraints.getMustVisit());
                Set<String> tripUsed = usedVenues(draftTrip);
                for (Map<String, Object> s : stops) tripUsed.remove(String.valueOf(s.get("venueId")));

                adjustmentDiagnostics.put("type", "RAIN_REPLAN");
                adjustmentDiagnostics.put("day", dayNumber);
                adjustmentDiagnostics.put("originalStops", describeStops(stops, byId));
                adjustmentDiagnostics.put("pinnedStopIds", new ArrayList<>(pinned));
                adjustmentDiagnostics.put("mustVisit", new ArrayList<>(mustVisit));

                List<Map<String, Object>> newStops = new ArrayList<>();
                for (int i = 0; i < stops.size(); i++) {
                    Map<String, Object> originalStop = stops.get(i);
                    String stopId = String.valueOf(originalStop.get("id"));

                    if (isProtectedStop(originalStop, pinned, constraints)) {
                        tripUsed.add(String.valueOf(originalStop.get("venueId")));
                        newStops.add(originalStop);
                        continue;
                    }

                    Attraction rainCandidate = findRainCandidate(stops, i, tripUsed, constraints, byId);
                    if (rainCandidate != null) {
                        tripUsed.add(rainCandidate.getId());
                        Map<String, Object> replaced = itineraryBuilder.createStop(
                                rainCandidate, stopId,
                                String.valueOf(originalStop.getOrDefault("time", "待安排")),
                                rainCandidate.getDuration(),
                                "因雨天/同区室内优先调整为" + rainCandidate.getName(),
                                rainCandidate.getIntro()
                        );
                        newStops.add(replaced);
                        changedSegments.add(stopId);
                    } else {
                        newStops.add(originalStop);
                    }
                }
                day.put("stops", newStops);
                List<Map<String, Object>> resolvedDays = resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
                restoreProtectedRainStops(resolvedDays, dayNumber, newStops, pinned, constraints);
                routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId, constraints.getTransportPreference());
                adjustmentDiagnostics.put("replacedStops", describeStops(newStops, byId));
                adjustmentDiagnostics.put("routeEdges", describeRouteEdges(findDay(draftTrip, dayNumber), byId));
                optionPlans.put("option-1", draftTrip);
            }
            case REDUCE_DAY_DENSITY -> {
                int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
                Map<String, Object> day = findDay(draftTrip, dayNumber);
                if (day == null) return error(400, "目标日期不存在。", session);

                List<Map<String, Object>> stops = dayStops(day);
                int count = intent.getReduceCount() == null ? 1 : intent.getReduceCount();
                Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
                if (constraints.getMustVisit() != null) pinned.addAll(constraints.getMustVisit());

                int removed = 0;
                for (int i = stops.size() - 1; i >= 0 && removed < count && stops.size() > 1; i--) {
                    Map<String, Object> stop = stops.get(i);
                    String venueId = String.valueOf(stop.get("venueId"));
                    String stopId = String.valueOf(stop.get("id"));
                    if (!pinned.contains(venueId) && !pinned.contains(stopId)) {
                        stops.remove(i);
                        changedSegments.add(stopId);
                        removed++;
                    }
                }
                resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
                optionPlans.put("option-1", draftTrip);
            }
            case REPLAN_DAY -> {
                Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
                if ("TRIP".equalsIgnoreCase(intent.getScope())) {
                    for (Map<String, Object> day : days(draftTrip)) {
                        Integer dayNumber = number(day.get("day"));
                        if (dayNumber != null) replanDay(draftTrip, dayNumber, byId, constraints, intent, pinned, changedSegments);
                    }
                } else {
                    int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
                    if (findDay(draftTrip, dayNumber) == null) return error(400, "目标日期不存在。", session);
                    replanDay(draftTrip, dayNumber, byId, constraints, intent, pinned, changedSegments);
                }
                optionPlans.put("option-1", draftTrip);
            }
            default -> {
                return error(400, "暂不支持该类型的调整意图。", session);
            }
        }

        // Final verification check
        PlanVerifier.VerificationResult verification = planVerifier.verify(days(draftTrip), constraints);
        if (!verification.feasible()) {
            feasible = false;
            reasonCodes.addAll(verification.violations().stream().map(PlanVerifier.PlanViolation::type).distinct().toList());
            if (alternatives.isEmpty()) {
                alternatives.addAll(List.of("调整至其他可行日期", "替换为其他无冲突景点", "取消调整"));
            }
        }

        if (!rejectedOptionDiagnostics.isEmpty()) {
            adjustmentDiagnostics.put("rejectedOptions", rejectedOptionDiagnostics);
        }
        boolean forceApplicable = Boolean.TRUE.equals(adjustmentDiagnostics.get("forceApplicable"))
                && !optionPlans.isEmpty();
        if (!feasible && !forceApplicable) {
            candidateReplacements.clear();
            optionPlans.clear();
            changedSegments.clear();
            unchangedStops = collectUnchangedStops(sourceTrip, List.of());
        } else {
            unchangedStops = collectUnchangedStops(sourceTrip, changedSegments);
        }

        String proposalId = "prop-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expiresAt = now + ProposalStore.DEFAULT_TTL_MS;

        PlanAdjustmentProposal proposal = PlanAdjustmentProposal.builder()
                .proposalId(proposalId)
                .sessionId(session.sessionId())
                .baseRevision(session.currentVersion())
                .intent(intent)
                .proposedTrip(draftTrip)
                .optionPlans(optionPlans)
                .candidateReplacements(candidateReplacements)
                .changedSegments(changedSegments)
                .unchangedStops(unchangedStops)
                .verification(verification.asMap())
                .feasible(feasible)
                .reasonCodes(reasonCodes)
                .alternatives(alternatives)
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        proposalStore.put(proposal);

        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (metricsLogger != null) {
            metricsLogger.logProposalPreviewed(session.sessionId(), proposalId,
                    intent == null || intent.getType() == null ? "UNKNOWN" : intent.getType().name(),
                    feasible, latencyMs);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("feasible", feasible);
        body.put("proposalId", proposalId);
        body.put("sessionId", session.sessionId());
        body.put("baseRevision", session.currentVersion());
        body.put("intent", intent.asMap());
        body.put("candidateReplacements", candidateReplacements);
        body.put("optionPlans", optionPlans.keySet().stream().toList());
        body.put("proposedTrip", draftTrip);
        body.put("changedSegments", changedSegments);
        body.put("unchangedStops", unchangedStops);
        body.put("verification", verification.asMap());
        body.put("reasonCodes", reasonCodes);
        body.put("alternatives", alternatives);
        body.put("replacementMode", adjustmentDiagnostics.getOrDefault("replacementMode", "CANDIDATE"));
        if (adjustmentDiagnostics.containsKey("sourceStopName")) {
            body.put("sourceStopName", adjustmentDiagnostics.get("sourceStopName"));
            body.put("requestedReplacementName", adjustmentDiagnostics.get("requestedReplacementName"));
        }
        if (!adjustmentDiagnostics.isEmpty()) body.put("diagnostics", adjustmentDiagnostics);
        body.put("expiresAt", expiresAt);

        // V1 Metadata
        String routeStatus = draftTrip == null ? "ESTIMATED" : String.valueOf(draftTrip.getOrDefault("routeDataStatus", "ESTIMATED"));
        boolean isDegraded = draftTrip == null || !"高德实时接口".equals(draftTrip.get("sourceMode"));
        PlannerVersionMetadata meta = PlannerVersionMetadata.defaultV1(
                routeStatus, isDegraded, isDegraded ? List.of("DEMO_OR_ESTIMATED_ROUTE_FALLBACK") : List.of()
        );
        body.put("plannerVersion", meta.getPlannerVersion());
        body.put("policyVersion", meta.getPolicyVersion());
        body.put("explanationSource", meta.getExplanationSource());
        body.put("routeDataStatus", meta.getRouteDataStatus());
        body.put("degraded", meta.isDegraded());
        body.put("degradationReasons", meta.getDegradationReasons());

        if (!feasible) {
            body.put("message", "当前调整会让当天安排不太合理，已提供可行的替代处理建议。");
        } else if ("DIRECT".equals(adjustmentDiagnostics.get("replacementMode"))) {
            body.put("message", "已将“" + adjustmentDiagnostics.get("sourceStopName") + "”替换为“"
                    + adjustmentDiagnostics.get("requestedReplacementName") + "”，生成一对一替换预览，请确认后应用。");
        } else {
            body.put("message", "调整方案已生成预览，请确认后应用。");
        }

        return new PlannerService.ServiceResult(200, body);
    }

    private void replanDay(Map<String, Object> draftTrip,
                           int dayNumber,
                           Map<String, Attraction> byId,
                           TravelConstraints constraints,
                           PlanAdjustmentIntent intent,
                           Set<String> pinned,
                           List<String> changedSegments) {
        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) return;

        List<Map<String, Object>> stops = new ArrayList<>(dayStops(day));
        Set<String> tripUsed = usedVenues(draftTrip);
        for (Map<String, Object> stop : stops) tripUsed.remove(String.valueOf(stop.get("venueId")));

        List<Map<String, Object>> newStops = new ArrayList<>();
        for (Map<String, Object> originalStop : stops) {
            String stopId = String.valueOf(originalStop.get("id"));
            if (isProtectedStop(originalStop, pinned, constraints)) {
                tripUsed.add(String.valueOf(originalStop.get("venueId")));
                newStops.add(originalStop);
                continue;
            }

            String slotTime = String.valueOf(originalStop.getOrDefault("time", ""));
            List<Attraction> candidates = findCandidates(
                    byId.get(String.valueOf(originalStop.get("venueId"))), tripUsed, constraints, intent, slotTime);
            if (candidates.isEmpty()) {
                newStops.add(originalStop);
                continue;
            }

            Attraction replacement = candidates.get(0);
            tripUsed.add(replacement.getId());
            newStops.add(itineraryBuilder.createStop(
                    replacement,
                    stopId,
                    String.valueOf(originalStop.getOrDefault("time", "待安排")),
                    replacement.getDuration(),
                    "根据偏好调整为" + replacement.getName() + "（" + replacement.getSummary() + "）。",
                    replacement.getIntro()
            ));
            changedSegments.add(stopId);
        }

        day.put("stops", newStops);
        List<Map<String, Object>> resolvedDays = resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId, constraints.getTransportPreference());
    }

    private List<Attraction> findCandidates(Attraction oldAttr, Set<String> used, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        return findCandidates(oldAttr, used, constraints, intent, null);
    }

    private List<Attraction> findCandidates(Attraction oldAttr,
                                             Set<String> used,
                                             TravelConstraints constraints,
                                             PlanAdjustmentIntent intent,
                                             String slotTime) {
        List<Attraction> catalog = attractionService.list(null, null);
        return catalog.stream()
                .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                .filter(a -> isOpeningCompatible(a, slotTime))
                .map(a -> new ScoredCandidate(a, scoreCandidate(a, oldAttr, constraints, intent)))
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                .map(ScoredCandidate::attraction)
                .limit(3)
                .toList();
    }

    private boolean isOpeningCompatible(Attraction attraction, String slotTime) {
        if (attraction == null || slotTime == null || slotTime.isBlank()) return true;
        String bestTime = safe(attraction.getBestTime());
        return !(bestTime.contains("17:00") && isEveningSlot(slotTime));
    }

    private boolean isEveningSlot(String time) {
        return time.contains("18:") || time.contains("19:") || time.contains("20:")
                || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String resolveAttractionId(String name) {
        String reference = safe(name);
        if (reference.isBlank()) return null;
        List<Attraction> catalog = attractionService.list(null, null);
        for (Attraction attraction : catalog) {
            if (reference.equals(safe(attraction.getId()))) return attraction.getId();
            if (matchesAttractionReference(reference, attraction)) return attraction.getId();
        }
        return null;
    }

    private boolean matchesAttractionReference(String reference, Attraction attraction) {
        if (attraction == null) return false;
        for (String candidate : List.of(attraction.getName(), attraction.getDisplayName())) {
            if (matchesText(reference, candidate)) return true;
        }
        Map<String, Object> amapQuery = attraction.getAmapQuery();
        if (amapQuery == null || amapQuery.isEmpty()) return false;
        Object keywords = amapQuery.get("keywords");
        if (keywords != null && matchesText(reference, String.valueOf(keywords))) return true;
        Object matches = amapQuery.get("matches");
        if (matches instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && matchesText(reference, String.valueOf(value))) return true;
            }
        }
        return false;
    }

    private boolean matchesText(String reference, String candidate) {
        String left = normalizeReference(reference);
        String right = normalizeReference(candidate);
        return !left.isBlank() && !right.isBlank()
                && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private String normalizeReference(String value) {
        return safe(value).replaceAll("[\\p{Punct}\\p{Z}\\p{IsPunctuation}]+", "");
    }

    private boolean isAlternativeCandidateRequest(String text) {
        return text != null && (text.matches(".*(?:推荐|提供|找|看看|换|替换).*(?:同片区|附近|其他|别的|更多|不同).*(?:景点|地点|室内|场馆|候选|备选|方案).*")
                || text.matches(".*(?:同片区|附近|其他|别的|更多|不同).*(?:景点|地点|室内|场馆).*(?:推荐|换|找|提供).*")
                || text.matches(".*(?:其他|别的|更多|换一批).*(?:替换|候选|备选|方案).*"));
    }

    private int scoreCandidate(Attraction candidate, Attraction old, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        int score = 0;
        if (old != null && safe(candidate.getDistrict()).equals(old.getDistrict())) score += 20;
        if ("低".equals(constraints.getWalkingTolerance()) && "低".equals(candidate.getWalkDifficulty())) score += 25;
        if (old != null && safe(candidate.getCategory()).equals(old.getCategory())) score += 15;
        if ("带父母".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "少走路")) score += 20;
        if ("带孩子".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("亲子", "科普")) score += 20;

        if (intent != null && intent.getPreferences() != null) {
            for (String p : intent.getPreferences()) {
                if ("INDOOR".equalsIgnoreCase(p) && Boolean.TRUE.equals(candidate.getIndoor())) score += 35;
                if ("LOW_WALKING".equalsIgnoreCase(p) && "低".equals(candidate.getWalkDifficulty())) score += 30;
                if ("HISTORY".equalsIgnoreCase(p) && ("人文".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("历史", "古建", "文物", "古镇"))) score += 30;
                if ("NIGHT_VIEW".equalsIgnoreCase(p) && ("夜景".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("夜景", "临江"))) score += 30;
                if ("FOOD".equalsIgnoreCase(p) && ("美食".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("美食", "老字号"))) score += 30;
                if ("NATURE".equalsIgnoreCase(p) && ("自然".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("自然", "峡谷", "喀斯特"))) score += 30;
            }
        }
        return score;
    }

    private Attraction findRainCandidate(List<Map<String, Object>> originalStops,
                                         int index,
                                         Set<String> used,
                                         TravelConstraints constraints,
                                         Map<String, Attraction> byId) {
        Map<String, Object> originalStop = originalStops.get(index);
        Attraction original = byId.get(safe(originalStop.get("venueId")));
        Attraction previous = index > 0 ? byId.get(safe(originalStops.get(index - 1).get("venueId"))) : null;
        Attraction next = index + 1 < originalStops.size()
                ? byId.get(safe(originalStops.get(index + 1).get("venueId"))) : null;

        List<Attraction> candidates = attractionService.list(null, null).stream()
                .filter(a -> !used.contains(a.getId()))
                .filter(a -> original == null || !a.getId().equals(original.getId()))
                .filter(a -> "INDOOR".equals(a.getEffectiveEnvironment()) || "MIXED".equals(a.getEffectiveEnvironment()))
                .filter(a -> !isAvoided(a, constraints))
                .filter(a -> !isCrossDistrictInviable(previous, a) && !isCrossDistrictInviable(a, next))
                .toList();

        if (original != null) {
            List<Attraction> sameDistrict = candidates.stream()
                    .filter(a -> safe(a.getDistrict()).equals(safe(original.getDistrict())))
                    .toList();
            if (!sameDistrict.isEmpty()) candidates = sameDistrict;
        }

        String preference = constraints == null ? "walking" : constraints.getTransportPreference();
        return candidates.stream()
                .map(candidate -> new RainCandidateScore(candidate, scoreRainCandidate(candidate, original, previous, next, preference)))
                .filter(scored -> scored.score() > -1000)
                .max(Comparator.comparingDouble(RainCandidateScore::score)
                        .thenComparingInt(scored -> -itineraryBuilder.catalogRank(scored.attraction().getId())))
                .map(RainCandidateScore::attraction)
                .orElse(null);
    }

    private double scoreRainCandidate(Attraction candidate,
                                      Attraction original,
                                      Attraction previous,
                                      Attraction next,
                                      String preference) {
        double score = "INDOOR".equals(candidate.getEffectiveEnvironment()) ? 70 : 42;
        if (original != null && safe(candidate.getDistrict()).equals(safe(original.getDistrict()))) score += 80;
        if (previous != null && safe(candidate.getDistrict()).equals(safe(previous.getDistrict()))) score += 18;
        if (next != null && safe(candidate.getDistrict()).equals(safe(next.getDistrict()))) score += 18;
        if ("低".equals(candidate.getWalkDifficulty())) score += 15;

        List<Attraction> neighbors = new ArrayList<>();
        if (previous != null) neighbors.add(previous);
        if (next != null) neighbors.add(next);
        for (Attraction neighbor : neighbors) {
            RouteCost cost;
            try {
                cost = neighbor == previous
                        ? routeCostProvider.calculate(neighbor, candidate, preference)
                        : routeCostProvider.calculate(candidate, neighbor, preference);
            } catch (RuntimeException e) {
                score -= 40;
                continue;
            }
            if (cost == null || cost.status() == RouteCost.RouteDataStatus.UNAVAILABLE) {
                score -= 20;
            } else {
                long minutes = cost.duration() == null ? 0 : cost.duration().toMinutes();
                if (minutes > 150) return -10000;
                score -= Math.min(180, minutes) * 0.45;
            }
        }
        return score;
    }

    private boolean isAvoided(Attraction attraction, TravelConstraints constraints) {
        if (constraints == null || constraints.getAvoid() == null) return false;
        return constraints.getAvoid().stream().anyMatch(avoid ->
                attraction.getName().contains(avoid)
                        || attraction.getId().contains(avoid)
                        || avoid.equals(attraction.getCategory()));
    }

    private boolean isCrossDistrictInviable(Attraction left, Attraction right) {
        if (left == null || right == null) return false;
        boolean leftDistant = isDistantDistrict(left.getDistrict());
        boolean rightDistant = isDistantDistrict(right.getDistrict());
        return leftDistant != rightDistant;
    }

    private boolean isDistantDistrict(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足");
    }

    private String answerPlaceQuestion(PlanAdjustmentIntent intent, Map<String, Object> trip) {
        String stopId = intent.getTargetStopId();
        String venueId = intent.getTargetAttractionId();
        String rawText = intent.getRawMessage() != null ? intent.getRawMessage() : "";

        if (stopId != null && !stopId.isBlank()) {
            StopLocation location = locateStop(trip, stopId);
            if (location != null) {
                String name = String.valueOf(location.stop().get("name"));
                String reason = String.valueOf(location.stop().getOrDefault("recommendationReason", ""));
                String summary = String.valueOf(location.stop().getOrDefault("summary", ""));
                String time = String.valueOf(location.stop().getOrDefault("time", ""));
                return "【" + name + "】安排在 " + time + "。" + (reason.isBlank() ? summary : reason);
            }
        }

        for (Map<?, ?> stop : allStops(trip)) {
            String name = String.valueOf(stop.get("name"));
            String venue = String.valueOf(stop.get("venueId"));
            if (rawText.contains(name) || (venueId != null && venueId.equals(venue))) {
                String reason = String.valueOf(stop.get("recommendationReason") == null ? "" : stop.get("recommendationReason"));
                String summary = String.valueOf(stop.get("summary") == null ? "" : stop.get("summary"));
                String time = String.valueOf(stop.get("time") == null ? "" : stop.get("time"));
                return "【" + name + "】安排在 " + time + "。" + (reason.isBlank() ? summary : reason);
            }
        }

        for (Attraction a : attractionService.list(null, null)) {
            if (rawText.contains(a.getName()) || (venueId != null && venueId.equals(a.getId()))) {
                return "【" + a.getName() + "】" + a.getSummary() + "（门票：" + a.getTicket() + "，建议游玩：" + a.getDuration() + "）。" + a.getIntro();
            }
        }

        return "关于重庆旅游景点，您可以随时在行程中点击卡片提问，或告诉我具体景点名称。";
    }

    private void restoreProtectedRainStops(List<Map<String, Object>> resolvedDays,
                                           int dayNumber,
                                           List<Map<String, Object>> proposedStops,
                                           Set<String> pinned,
                                           TravelConstraints constraints) {
        Map<String, Object> targetDay = null;
        for (Map<String, Object> candidate : resolvedDays) {
            if (Integer.valueOf(dayNumber).equals(number(candidate.get("day")))) {
                targetDay = candidate;
                break;
            }
        }
        if (targetDay == null) return;
        List<Map<String, Object>> repairedStops = dayStops(targetDay);
        for (int index = 0; index < proposedStops.size(); index++) {
            Map<String, Object> protectedStop = proposedStops.get(index);
            if (!isProtectedStop(protectedStop, pinned, constraints)) continue;
            String stopId = safe(protectedStop.get("id"));
            boolean present = repairedStops.stream().anyMatch(existing ->
                    stopId.equals(safe(existing.get("id")))
                            || safe(protectedStop.get("venueId")).equals(safe(existing.get("venueId"))));
            if (!present) repairedStops.add(Math.min(index, repairedStops.size()), protectedStop);
        }
    }

    private boolean isProtectedStop(Map<String, Object> stop,
                                     Set<String> pinned,
                                     TravelConstraints constraints) {
        String stopId = safe(stop.get("id"));
        String stableStopId = safe(stop.get("stableStopId"));
        String venueId = safe(stop.get("venueId"));
        String name = safe(stop.get("name"));
        if (pinned != null && pinned.stream().anyMatch(selector ->
                selector.equals(stopId) || selector.equals(stableStopId) || selector.equals(venueId)
                        || (!name.isBlank() && name.contains(selector)))) {
            return true;
        }
        if (constraints == null || constraints.getMustVisit() == null) return false;
        return constraints.getMustVisit().stream().anyMatch(selector ->
                selector.equals(stopId) || selector.equals(stableStopId) || selector.equals(venueId)
                        || (!name.isBlank() && name.contains(selector)));
    }

    private List<Map<String, Object>> describeStops(List<Map<String, Object>> stops,
                                                     Map<String, Attraction> byId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stop : stops) {
            Attraction attraction = byId.get(safe(stop.get("venueId")));
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("id", safe(stop.get("id")));
            description.put("venueId", safe(stop.get("venueId")));
            description.put("name", safe(stop.get("name")));
            description.put("district", attraction == null ? "" : safe(attraction.getDistrict()));
            description.put("environment", attraction == null ? "" : attraction.getEffectiveEnvironment());
            description.put("routeDataStatus", safe(stop.get("routeDataStatus")));
            result.add(description);
        }
        return result;
    }

    private List<Map<String, Object>> describeRouteEdges(Map<String, Object> day,
                                                         Map<String, Attraction> byId) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (day == null) return edges;
        List<Map<String, Object>> stops = dayStops(day);
        for (int i = 1; i < stops.size(); i++) {
            Map<String, Object> previous = stops.get(i - 1);
            Map<String, Object> current = stops.get(i);
            RouteCost cost = RouteCost.fromMap(current.get("resolvedRouteCost"));
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("fromStopId", safe(previous.get("id")));
            edge.put("toStopId", safe(current.get("id")));
            edge.put("fromVenueId", safe(previous.get("venueId")));
            edge.put("toVenueId", safe(current.get("venueId")));
            edge.put("fromDistrict", safe(byId.containsKey(safe(previous.get("venueId")))
                    ? byId.get(safe(previous.get("venueId"))).getDistrict() : ""));
            edge.put("toDistrict", safe(byId.containsKey(safe(current.get("venueId")))
                    ? byId.get(safe(current.get("venueId"))).getDistrict() : ""));
            edge.put("durationMinutes", cost == null || cost.duration() == null ? null : cost.duration().toMinutes());
            edge.put("routeDataStatus", cost == null || cost.status() == null ? "UNAVAILABLE" : cost.status().name());
            edges.add(edge);
        }
        return edges;
    }

    private List<String> collectUnchangedStops(Map<String, Object> sourceTrip, List<String> changedSegments) {
        List<String> unchanged = new ArrayList<>();
        Set<String> changedSet = new HashSet<>(changedSegments);
        for (Map<String, Object> day : days(sourceTrip)) {
            for (Map<String, Object> stop : dayStops(day)) {
                String id = safe(stop.get("id"));
                if (!changedSet.contains(id)) unchanged.add(id);
            }
        }
        return unchanged;
    }

    private TravelConstraints extractConstraints(PlannerSessionRepository.StoredSession session) {
        if (session == null || session.constraints() == null) return new TravelConstraints();
        try {
            return objectMapper.convertValue(session.constraints(), TravelConstraints.class);
        } catch (Exception e) {
            return new TravelConstraints();
        }
    }

    private PlannerSessionRepository.StoredSession findAuthorized(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (UserContext.isAnonymous()) {
            PlannerSessionRepository.StoredSession capabilitySession = repository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return repository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        return repository.find(sessionId, "USER", UserContext.getUserId(), token);
    }

    private void finalizeTrip(Map<String, Object> trip, int version, String label, String reason, List<String> changed) {
        trip.put("version", version);
        trip.put("id", "draft-cq-" + version);
        trip.put("subtitle", label + " · 路线已更新");
        List<Map<String, Object>> history = new ArrayList<>();
        Object old = trip.get("versionHistory");
        if (old instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    history.add(copy);
                }
            }
        }
        history.add(Map.of("version", version, "label", label, "changedSegments", changed, "reason", safe(reason), "createdAt", Instant.now().toString()));
        trip.put("versionHistory", history);
    }

    private PlannerService.ServiceResult error(int status, String message, PlannerSessionRepository.StoredSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (session != null) {
            body.put("sessionId", session.sessionId());
            body.put("trip", session.trip());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
        }
        return new PlannerService.ServiceResult(status, body);
    }

    private PlannerService.ServiceResult conflict(PlannerSessionRepository.StoredSession session) {
        return error(409, "规划版本已变化，请基于最新行程重新操作。", session);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(source), LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>(source);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> days(Map<String, Object> trip) {
        return (List<Map<String, Object>>) trip.computeIfAbsent("days", k -> new ArrayList<>());
    }

    private List<Map<String, Object>> resolveAffectedDay(Map<String, Object> trip, Integer dayNumber,
                                                         Map<String, Attraction> byId, TravelConstraints constraints) {
        Map<String, Object> affected = dayNumber == null ? null : findDay(trip, dayNumber);
        if (affected == null) return List.of();
        return routeAwarePlanner.resolveAndVerifyDays(new ArrayList<>(List.of(affected)), byId, constraints);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dayStops(Map<String, Object> day) {
        return (List<Map<String, Object>>) day.computeIfAbsent("stops", k -> new ArrayList<>());
    }

    private Map<String, Object> findDay(Map<String, Object> trip, int dayNumber) {
        for (Map<String, Object> d : days(trip)) {
            if (Integer.valueOf(dayNumber).equals(number(d.get("day")))) return d;
        }
        return null;
    }

    private StopLocation locateStop(Map<String, Object> trip, String stopId) {
        if (stopId == null || stopId.isBlank()) return null;
        for (Map<String, Object> d : days(trip)) {
            List<Map<String, Object>> stops = dayStops(d);
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> s = stops.get(i);
                if (stopId.equals(s.get("id")) || stopId.equals(s.get("stableStopId"))) {
                    return new StopLocation(d, stops, i, s);
                }
            }
        }
        return null;
    }

    private StopLocation locateStopByVenue(Map<String, Object> trip, String venueIdOrName) {
        if (venueIdOrName == null || venueIdOrName.isBlank()) return null;
        for (Map<String, Object> d : days(trip)) {
            List<Map<String, Object>> stops = dayStops(d);
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> s = stops.get(i);
                if (venueIdOrName.equals(s.get("venueId")) || String.valueOf(s.get("name")).contains(venueIdOrName)) {
                    return new StopLocation(d, stops, i, s);
                }
            }
        }
        return null;
    }

    private Set<String> usedVenues(Map<String, Object> trip) {
        Set<String> set = new HashSet<>();
        for (Map<String, Object> d : days(trip)) {
            for (Map<String, Object> s : dayStops(d)) {
                if (s.get("venueId") != null) set.add(String.valueOf(s.get("venueId")));
            }
        }
        return set;
    }

    private Integer number(Object value) {
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    /**
     * Returns a read-only view of the stop maps contained in a generated trip.
     * The planner payload is intentionally kept as JSON-shaped data at this
     * boundary, so malformed days/stops are ignored instead of failing an
     * otherwise recoverable adjustment request.
     */
    private List<Map<?, ?>> allStops(Map<String, Object> trip) {
        if (trip == null) return List.of();
        Object daysObj = trip.get("days");
        if (!(daysObj instanceof List<?> daysList)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object dayObj : daysList) {
            if (dayObj instanceof Map<?, ?> dayMap) {
                Object stopsObj = dayMap.get("stops");
                if (stopsObj instanceof List<?> stopsList) {
                    for (Object stopObj : stopsList) {
                        if (stopObj instanceof Map<?, ?> stopMap) {
                            result.add(stopMap);
                        }
                    }
                }
            }
        }
        return result;
    }

    private record ApplyReplay(String requestFingerprint, PlannerService.ServiceResult result) {}
    private record StopLocation(Map<String, Object> day, List<Map<String, Object>> stops, int index, Map<String, Object> stop) {}
    private record ScoredCandidate(Attraction attraction, int score) {}
    private record RainCandidateScore(Attraction attraction, double score) {}
}

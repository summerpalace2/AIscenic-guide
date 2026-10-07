package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.engine.DynamicGeoCentroidResolver;
import com.ai.guide.domain.planner.engine.MealGapSolver;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.adjustment.AddStopAdjustmentStrategy;
import com.ai.guide.domain.planner.service.adjustment.AdjustmentContext;
import com.ai.guide.domain.planner.service.adjustment.AdjustmentStrategy;
import com.ai.guide.domain.planner.service.adjustment.PlaceQuestionAdjustmentStrategy;
import com.ai.guide.domain.planner.service.adjustment.RemoveStopAdjustmentStrategy;
import com.ai.guide.domain.planner.service.adjustment.ReplanDayAdjustmentStrategy;
import com.ai.guide.domain.planner.service.adjustment.ReplaceStopAdjustmentStrategy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/**
 * 对话式行程方案调整、预览与原子应用编排服务
 *
 * 所属领域：domain.planner.service（智能排程与规划引擎服务层）
 * 架构职责：编排详情页对话式行程调整全流程，包括自然语言诉求提取、冲突检测、
 * 策略模式（Strategy Pattern）分发调整意图、全量路线重算差异对比与两阶段原子确认。
 */
@Service
public class PlanAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAdjustmentService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final DiningCategoryGrounder STATIC_DINING_GROUNDER = new DiningCategoryGrounder();

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

    private final Map<String, ApplyReplay> applyReplays = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ApplyReplay> eldest) {
                    return size() > 256;
                }
            });
    private final Object[] sessionApplyLocks = java.util.stream.IntStream.range(0, 128).mapToObj(i -> new Object()).toArray();
    private final PendingIntentContinuations continuations = new PendingIntentContinuations();

    @Autowired(required = false)
    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

    private final ItineraryTimeEngine itineraryTimeEngine;
    private final CandidateRankingEngine candidateRankingEngine;
    private final DiningCategoryGrounder diningCategoryGrounder;
    private final List<AdjustmentStrategy> strategies;
    private TaxonomyPort taxonomy;
    private MealGapSolver mealGapSolver;
    private DynamicGeoCentroidResolver geoCentroidResolver;

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

        this.itineraryTimeEngine = new ItineraryTimeEngine();
        this.candidateRankingEngine = new CandidateRankingEngine(routeCostProvider);
        this.diningCategoryGrounder = new DiningCategoryGrounder();
        this.taxonomy = new TaxonomyProvider();
        this.mealGapSolver = new MealGapSolver(routeCostProvider, itineraryTimeEngine);
        this.geoCentroidResolver = new DynamicGeoCentroidResolver(null);
        this.strategies = initDefaultStrategies();
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

    private List<AdjustmentStrategy> initDefaultStrategies() {
        ReplaceStopAdjustmentStrategy replace = new ReplaceStopAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner, planVerifier,
                candidateRankingEngine, itineraryTimeEngine, diningCategoryGrounder);
        replace.setDiningVenueService(diningVenueService);
        replace.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);

        AddStopAdjustmentStrategy add = new AddStopAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner,
                candidateRankingEngine, itineraryTimeEngine, diningCategoryGrounder);
        add.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
        add.setDiningVenueService(diningVenueService);
        add.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);

        ReplanDayAdjustmentStrategy replan = new ReplanDayAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner,
                candidateRankingEngine, itineraryTimeEngine, routeCostProvider);

        RemoveStopAdjustmentStrategy remove = new RemoveStopAdjustmentStrategy(routeAwarePlanner);

        PlaceQuestionAdjustmentStrategy placeQ = new PlaceQuestionAdjustmentStrategy(attractionService);
        placeQ.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
        placeQ.setDiningVenueService(diningVenueService);
        placeQ.setTaxonomy(taxonomy);

        return List.of(replace, add, replan, remove, placeQ);
    }

    /**
     * 静态餐饮泛需求判定门禁（委托至 DiningCategoryGrounder 动态核验网关）
     */
    public static boolean isGenericDiningCraving(String name) {
        return STATIC_DINING_GROUNDER.isGenericDiningCraving(name);
    }

    public void setRuntimeAttractionDetailService(RuntimeAttractionDetailService runtimeAttractionDetailService) {
        this.runtimeAttractionDetailService = runtimeAttractionDetailService;
        syncStrategyDependencies();
    }

    public void setDiningVenueService(DiningVenueService diningVenueService) {
        this.diningVenueService = diningVenueService;
        syncStrategyDependencies();
    }

    @Autowired
    public void setDiningScheduling(TaxonomyPort taxonomy, MealGapSolver mealGapSolver,
                                    DynamicGeoCentroidResolver geoCentroidResolver) {
        this.taxonomy = taxonomy;
        this.mealGapSolver = mealGapSolver;
        this.geoCentroidResolver = geoCentroidResolver;
        this.diningCategoryGrounder.setTaxonomy(taxonomy);
        syncStrategyDependencies();
    }

    private void syncStrategyDependencies() {
        for (AdjustmentStrategy strategy : strategies) {
            if (strategy instanceof ReplaceStopAdjustmentStrategy rep) {
                rep.setDiningVenueService(diningVenueService);
                rep.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);
            } else if (strategy instanceof AddStopAdjustmentStrategy add) {
                add.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
                add.setDiningVenueService(diningVenueService);
                add.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);
            } else if (strategy instanceof PlaceQuestionAdjustmentStrategy pq) {
                pq.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
                pq.setDiningVenueService(diningVenueService);
                pq.setTaxonomy(taxonomy);
            }
        }
    }

    public PlannerService.ServiceResult preview(String sessionId, PlanConversationRequestDto request) {
        syncStrategyDependencies();
        long startNanos = System.nanoTime();
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);

        if (request != null && request.getBaseRevision() != session.currentVersion()) return error(409, "行程版本已更新，请刷新后基于最新行程调整。", session);
        PlanPageContext context = request == null || request.getContext() == null ? new PlanPageContext() : request.getContext();
        if (context.isResetConversation()) return resetAdjustmentRound(session, request, false);
        String messageText = request == null ? "" : request.getMessage();
        if (IntentLanguagePolicy.positiveText(messageText == null ? "" : messageText).trim()
                .matches("(?:请|帮我)?(?:取消|停止)(?:这次|本次|当前|这个|这轮|本轮)?(?:调整|修改|预览|方案|操作)(?:预览|方案)?[吧啊。！!\\s]*")) {
            return resetAdjustmentRound(session, request, true);
        }
        String generation = continuations.begin(session.sessionId());
        if (isAlternativeCandidateRequest(messageText)
                && context.getActiveProposalId() != null && !context.getActiveProposalId().isBlank()
                && (context.getSelectedStopId() == null || context.getSelectedStopId().isBlank())) {
            PlanAdjustmentProposal previous = proposalStore.get(context.getActiveProposalId());
            String previousTarget = previous == null || previous.getIntent() == null || !"STOP".equals(previous.getIntent().getScope())
                    ? null : previous.getIntent().getTargetStopId();
            if (previousTarget != null && !previousTarget.isBlank()) context.setSelectedStopId(previousTarget);
        }
        PlanAdjustmentProposal pending = context.getActiveProposalId() == null ? null : proposalStore.get(context.getActiveProposalId());
        if (pending != null && session.sessionId().equals(pending.getSessionId())
                && pending.getBaseRevision() == session.currentVersion() && pending.getExpiresAt() > System.currentTimeMillis()
                && continuations.validProposal(session.sessionId(),pending.getConversationEpoch(),pending.getCreatedAt())
                && pending.getIntent() != null && !hasMissingAdjustmentTarget(pending.getReasonCodes())
                && java.util.Objects.equals(pending.getIntent().getRawMessage(), messageText == null ? "" : messageText.trim())
                && java.util.Objects.equals(pending.getIntent().getDayNumber(), context.getActiveDay())
                && new HashSet<>(pending.getIntent().getPinnedStopIds()).equals(new HashSet<>(context.getPinnedStopIds() == null ? List.of() : context.getPinnedStopIds()))
                && (context.getSelectedStopId() == null || java.util.Objects.equals(context.getSelectedStopId(), pending.getIntent().getTargetStopId()))) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true); body.put("proposalId", pending.getProposalId()); body.put("baseRevision", pending.getBaseRevision());
            body.put("intent", pending.getIntent().asMap()); body.put("feasible", pending.isFeasible()); body.put("canApply", true);
            body.put("proposedTrip", pending.getProposedTrip()); body.put("candidateReplacements", pending.getCandidateReplacements());
            body.put("changedSegments", pending.getChangedSegments()); body.put("reasonCodes", pending.getReasonCodes());
            body.put("stopChanges", describeActualChanges(session.trip(), pending.getProposedTrip()));
            var optionChanges = new LinkedHashMap<String, Object>();
            pending.getOptionPlans().forEach((id, plan) -> optionChanges.put(id, describeActualChanges(session.trip(), plan)));
            body.put("optionStopChanges", optionChanges);
            body.put("optionStatus", pending.getOptionStatus());
            body.put("canApply", pending.getOptionStatus().isEmpty() ? !hasMissingAdjustmentTarget(pending.getReasonCodes()) : pending.getOptionStatus().values().stream().anyMatch(v -> Boolean.TRUE.equals(v.get("canApply"))));
            body.put("expiresAt", pending.getExpiresAt()); body.put("reused", true); body.put("modified", false);
            body.put("message", "这项调整已有待确认预览，请核对后点击确认应用；当前行程尚未修改。");
            return new PlannerService.ServiceResult(200, body);
        }
        PlanAdjustmentIntent resumed = continuations.resume(session.sessionId(), session.currentVersion(), context.getPendingIntentId(), messageText, context, generation);
        if (resumed == null) continuations.abandonCompoundQueue(session.sessionId());
        LlmIntentExtractor.LlmIntentExtractionResult extraction = resumed == null ? llmIntentExtractor.extract(messageText, context, session.trip())
                : new LlmIntentExtractor.LlmIntentExtractionResult(resumed, "PENDING_INTENT", null, 0, "HIGH");
        PlanAdjustmentIntent intent = extraction.intent();
        if (resumed == null) intent.setRawMessage(messageText);
        if (intent.getDayNumber() == null && !PendingIntentContinuations.requestsDay(intent) && (intent.isRequiresClarification() || intent.getType() != ConversationIntentType.ADD_STOP)) intent.setDayNumber(context.getActiveDay() == null ? 1 : context.getActiveDay());

        if (!continuations.current(session.sessionId(), generation)) return error(409, "这轮请求已被新对话取代，请使用最新结果。", session);
        AdjustmentTimeScopeResolver.resolve(intent, taxonomy);
        resolveAndValidate(intent, context, session.trip());
        var protectedIds = new ArrayList<>(intent.getPinnedStopIds() == null ? List.<String>of() : intent.getPinnedStopIds());
        for (var d : days(session.trip())) for (var stop : dayStops(d))
            if ((AdjustmentTimeScopeResolver.preserves(stop, intent, taxonomy) || intent.getPreserveReferences().stream().anyMatch(ref -> java.util.Objects.equals(ConversationIntentClassifier.extractExplicitDayNumber(ref), number(d.get("day"))))) && !protectedIds.contains(safe(stop.get("id")))) protectedIds.add(safe(stop.get("id")));
        intent.setPinnedStopIds(protectedIds);

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
            final PendingIntentContinuations.Pending[] slot = new PendingIntentContinuations.Pending[1];
            if (!continuations.commit(session.sessionId(), generation, () -> slot[0] = continuations.remember(session.sessionId(), session.currentVersion(), intent))) return error(409, "这轮请求已结束，请继续最新对话。", session);
            var continuation = slot[0];
            body.put("intent", intent.asMap()); body.put("pendingIntent", Map.of("intentId", continuation.intentId(), "baseRevision", continuation.baseRevision(), "expiresAt", continuation.expiresAt(), "intent", intent.asMap()));
            return withIntentMetadata(new PlannerService.ServiceResult(200, body), extraction);
        }

        if (!continuations.commit(session.sessionId(), generation, () -> continuations.clear(session.sessionId()))) return error(409, "这轮请求已被新对话取代。", session);
        if (intent.getType() == ConversationIntentType.PLACE_QUESTION) {
            if (metricsLogger != null) {
                metricsLogger.logProposalPreviewed(session.sessionId(), "place-question", "PLACE_QUESTION", true, latencyMs);
            }
            AdjustmentContext adjCtx = new AdjustmentContext(
                    session, intent, context, session.trip(), deepCopy(session.trip(), objectMapper),
                    extractConstraints(session), Map.of(), objectMapper);
            AdjustmentStrategy pqStrategy = findStrategy(ConversationIntentType.PLACE_QUESTION);
            if (pqStrategy != null) {
                pqStrategy.apply(adjCtx);
                if (adjCtx.hasFatalError()) {
                    return withIntentMetadata(adjCtx.getFatalError(), extraction);
                }
            }
        }

        if (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT && intent.getProposalId() != null && !intent.getProposalId().isBlank()) {
            int optIndex = intent.getCandidateIndex() != null ? intent.getCandidateIndex() : 1;
            String optId = intent.getOptionId() != null ? intent.getOptionId() : "option-" + optIndex;
            PlanAdjustmentProposal activeProposal = proposalStore.get(intent.getProposalId());
            if (activeProposal == null || !continuations.validProposal(session.sessionId(), activeProposal.getConversationEpoch(),activeProposal.getCreatedAt()) || !session.sessionId().equals(activeProposal.getSessionId())
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

        return withIntentMetadata(generateProposalPreview(session, intent, context, startNanos, generation), extraction);
    }

    private AdjustmentStrategy findStrategy(ConversationIntentType intentType) {
        for (AdjustmentStrategy strategy : strategies) {
            if (strategy.supports(intentType)) {
                return strategy;
            }
        }
        return null;
    }

    private PlannerService.ServiceResult generateProposalPreview(StoredSession session,
                                                                PlanAdjustmentIntent intent,
                                                                PlanPageContext context,
                                                                long startNanos, String generation) {
        Map<String, Object> sourceTrip = session.trip();
        Map<String, Object> draftTrip = deepCopy(sourceTrip, objectMapper);
        TravelConstraints constraints = extractConstraints(session);

        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction a : attractionService.list(null, null)) byId.put(a.getId(), a);

        AdjustmentContext adjContext = new AdjustmentContext(
                session, intent, context, sourceTrip, draftTrip, constraints, byId, objectMapper);

        AdjustmentStrategy strategy = findStrategy(intent.getType());
        if (strategy == null) {
            return error(400, "暂不支持该类型的调整意图。", session);
        }

        strategy.apply(adjContext);
        if (adjContext.hasFatalError()) {
            return adjContext.getFatalError();
        }

        draftTrip = adjContext.getDraftTrip();

        List<Map<String, Object>> candidateReplacements = adjContext.getCandidateReplacements();
        Map<String, Map<String, Object>> optionPlans = adjContext.getOptionPlans();
        List<String> changedSegments = adjContext.getChangedSegments();
        List<String> reasonCodes = adjContext.getReasonCodes();
        List<String> alternatives = adjContext.getAlternatives();
        Map<String, Object> adjustmentDiagnostics = adjContext.getAdjustmentDiagnostics();
        List<Map<String, Object>> rejectedOptionDiagnostics = adjContext.getRejectedOptionDiagnostics();
        boolean feasible = adjContext.isFeasible();

        List<Map<String, Object>> stopChanges = describeActualChanges(sourceTrip, draftTrip);
        if (stopChanges.isEmpty()) {
            feasible = false;
            reasonCodes.add("NO_ACTUAL_CHANGE");
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
        if (optionPlans.isEmpty() && draftTrip != null) {
            optionPlans.put("option-1", draftTrip);
        }
        var optionStatus = new LinkedHashMap<String, Map<String,Object>>();
        for (var entry : optionPlans.entrySet()) optionStatus.put(entry.getKey(), optionStatus(sourceTrip, entry.getValue(), intent, constraints, reasonCodes, entry.getKey(), adjContext.getRequiredStopIdsByOption().getOrDefault(entry.getKey(), List.of())));
        boolean forceApplicable = optionStatus.values().stream().anyMatch(v -> Boolean.TRUE.equals(v.get("canApply")));
        if (!forceApplicable) feasible = false;
        adjustmentDiagnostics.put("forceApplicable", forceApplicable);
        List<String> unchangedStops = collectUnchangedStops(sourceTrip, changedSegments);

        String proposalId = "prop-" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        long expiresAt = now + ProposalStore.DEFAULT_TTL_MS;

        PlanAdjustmentProposal proposal = PlanAdjustmentProposal.builder()
                .proposalId(proposalId)
                .sessionId(session.sessionId())
                .conversationEpoch(continuations.epoch(session.sessionId()))
                .baseRevision(session.currentVersion())
                .intent(intent)
                .proposedTrip(draftTrip)
                .optionPlans(optionPlans)
                .optionStatus(optionStatus)
                .requiredStopIdsByOption(adjContext.getRequiredStopIdsByOption())
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

        if (!continuations.commit(session.sessionId(), generation, () -> proposalStore.put(proposal))) return error(409, "旧请求的方案已作废，请查看最新对话。", session);

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
        body.put("stopChanges", stopChanges);
        var optionStopChanges = new LinkedHashMap<String, Object>();
        optionPlans.forEach((id, plan) -> optionStopChanges.put(id, describeActualChanges(sourceTrip, plan)));
        body.put("optionStopChanges", optionStopChanges);
        body.put("unchangedStops", unchangedStops);
        body.put("verification", verification.asMap());
        body.put("reasonCodes", reasonCodes);
        body.put("canApply", forceApplicable);
        body.put("optionStatus", optionStatus);
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
            if (reasonCodes.contains("NO_ACTUAL_CHANGE") && !hasMissingDiningTarget(reasonCodes)) {
                body.put("message", "本次没有产生实际改动：目标时段可能没有可调整站点，或没有符合条件的替代候选。请指定要替换的站点或另选时段。");
            } else if (reasonCodes.contains("DUPLICATE_VENUE")) {
                String stopName = adjustmentDiagnostics.get("addedStopName") != null ? String.valueOf(adjustmentDiagnostics.get("addedStopName")) : "该地点";
                body.put("message", "【" + stopName + "】已经在当前行程中安排过了，无需重复添加。您可以选择其他想去的地标或保持原安排。");
            } else if (reasonCodes.contains("DINING_CANDIDATES_UNAVAILABLE")) {
                body.put("message", "已理解您想吃【" + adjustmentDiagnostics.getOrDefault("requestedFlavor", "餐饮")
                        + "】，但当前搜索未找到可用候选。可以扩大搜索范围或指定一家餐厅。");
            } else if (reasonCodes.contains("DINING_POI_UNRESOLVED")) {
                body.put("message", "暂时未能核实您指定的餐厅，请补充完整店名或选择其他餐厅。");
            } else if (reasonCodes.contains("NO_FEASIBLE_MEAL_GAP")) {
                body.put("message", "已理解您的餐饮需求，但当天对应时段没有足够空闲时间安排用餐和往返路程。可以换一天，或替换已有的餐饮安排。");
            } else if (reasonCodes.contains("MEAL_WINDOW_UNAVAILABLE")) {
                body.put("message", "已理解您的餐饮需求，但当前尚未配置这个用餐时段，请选择其他时段。");
            } else {
                body.put("message", "当前调整会让当天安排不太合理，已提供可行的替代处理建议。");
            }
        } else if ("DIRECT".equals(adjustmentDiagnostics.get("replacementMode"))) {
            if (intent != null && intent.getType() == ConversationIntentType.ADD_STOP) {
                body.put("message", "已为您在行程中安排【" + adjustmentDiagnostics.get("requestedReplacementName")
                        + "】，生成调整预览，请确认后应用。");
            } else {
                body.put("message", "已将“" + adjustmentDiagnostics.get("sourceStopName") + "”替换为“"
                        + adjustmentDiagnostics.get("requestedReplacementName") + "”，生成一对一替换预览，请确认后应用。");
            }
        } else if ("TEA_BREAK".equals(adjustmentDiagnostics.get("replacementMode"))) {
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", "特色老茶馆与盖碗茶歇"));
            body.put("message", "已为您推荐【" + flavor + "】沿途候选，请选择心仪方案后确认应用。");
        } else if ("DINING_ADD".equals(adjustmentDiagnostics.get("replacementMode"))) {
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", "特色美食"));
            body.put("message", "已为您推荐【" + flavor + "】沿途候选餐厅，请选择心仪方案后确认应用。");
        } else if ("DINING".equals(adjustmentDiagnostics.get("replacementMode"))) {
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", "特色美食"));
            if (flavor == null || flavor.isBlank() || "未提供".equals(flavor) || "null".equals(flavor)) {
                flavor = "特色美食";
            }
            body.put("message", "已为您推荐【" + flavor + "】候选餐厅，请选择心仪方案后确认应用。");
        } else if (intent != null && intent.getType() == ConversationIntentType.ADD_STOP) {
            String stopName = adjustmentDiagnostics.get("addedStopName") != null ? String.valueOf(adjustmentDiagnostics.get("addedStopName")) : "新站点";
            Object dayN = adjustmentDiagnostics.get("addedDayNumber");
            if (Boolean.TRUE.equals(adjustmentDiagnostics.get("denseDay"))) {
                body.put("message", "已为您在第 " + (dayN != null ? dayN : 1) + " 天安排【" + stopName + "】。当天景点较多（共 " + adjustmentDiagnostics.get("totalDayScenicCount") + " 处），游玩节奏偏紧凑，已自动为您优化了站点时长与顺路动线，请确认后应用。");
            } else {
                body.put("message", "已为您在第 " + (dayN != null ? dayN : 1) + " 天安排【" + stopName + "】，并顺路优化了当天动线与时间排程，请确认后应用。");
            }
        } else if (intent != null && intent.getConditions() != null && intent.getConditions().contains("ACCESSIBILITY")) {
            body.put("message", "得知您身体在恢复期，健康与舒适最重要！悠悠已为您大幅降低步行强度：精简了当天行程密度，优先保留平缓舒适的核心打卡点，并建议点对点打车接驳。请确认减负方案：");
        } else if (intent != null && intent.getType() == ConversationIntentType.REDUCE_DAY_DENSITY) {
            body.put("message", "已为您精简当天行程密度，游玩节奏更轻松从容，请确认后应用。");
        } else {
            body.put("message", "调整方案已生成预览，请确认后应用。");
        }

        return new PlannerService.ServiceResult(200, body);
    }

    private Map<String,Object> optionStatus(Map<String,Object> before, Map<String,Object> after, PlanAdjustmentIntent intent, TravelConstraints constraints, List<String> globalReasons, String optionId, List<String> requiredStopIds) {
        var verification = planVerifier.verify(days(after), constraints);
        var reasons = new ArrayList<String>();
        boolean changed = !describeActualChanges(before, after).isEmpty();
        if (!changed) reasons.add("NO_ACTUAL_CHANGE");
        if (intent != null && violatesProtection(before, after, intent)) reasons.add("PRESERVED_SCOPE_VIOLATION");
        var retainedIds = allStops(after).stream().map(stop -> safe(stop.get("id"))).collect(java.util.stream.Collectors.toSet());
        if (requiredStopIds != null && !retainedIds.containsAll(requiredStopIds)) reasons.add("REQUESTED_STOP_NOT_RETAINED");
        // Strategy-level unresolved targets are hard failures; default-option quality warnings are recalculated per option.
        if (globalReasons != null) for (String reason : globalReasons) {
            // Multi-stop addition emits this failure for its complete route (option-1).
            // Its explicitly labelled single-stop alternatives have a different target set.
            if ("REQUESTED_STOP_NOT_RETAINED".equals(reason)
                    && (requiredStopIds != null && !requiredStopIds.isEmpty() || !"option-1".equals(optionId))) continue;
            if (!"NO_ACTUAL_CHANGE".equals(reason) && hasMissingAdjustmentTarget(List.of(reason))) reasons.add(reason);
        }
        boolean canApply = reasons.isEmpty() && verification.violations().stream()
                .noneMatch(v -> java.util.Set.of("CLOSED_ON_MONDAY", "CLOSED_ON_DATE", "CLOSED_AT_SLOT_TIME", "TIME_OVERLAP", "ARRIVAL_WINDOW_CONFLICT", "DEPARTURE_DEADLINE_CONFLICT", "MIDNIGHT_OVERFLOW").contains(v.type()));
        verification.violations().stream().map(PlanVerifier.PlanViolation::type).distinct().forEach(reasons::add);
        var result = new LinkedHashMap<String,Object>();
        var venueCounts=new LinkedHashMap<String,Integer>();
        for(var stop:allStops(after)) { String venue=safe(stop.get("venueId"));if(!venue.isBlank()) venueCounts.merge(venue,1,Integer::sum); }
        boolean strategyCaution = globalReasons != null && globalReasons.contains("DUPLICATE_VENUE") && venueCounts.values().stream().anyMatch(n -> n > 1);
        if (strategyCaution) reasons.add("DUPLICATE_VENUE");
        result.put("canApply", canApply); result.put("feasible", canApply && verification.feasible() && !strategyCaution);
        result.put("verification", verification.asMap()); result.put("reasonCodes", reasons); return result;
    }

    private boolean violatesProtection(Map<String,Object> before, Map<String,Object> after, PlanAdjustmentIntent intent) {
        Map<String,Map<String,Object>> next = new LinkedHashMap<>();
        for (var d : days(after)) for (var stop : dayStops(d)) next.put(safe(stop.get("id")), stop);
        for (var d : days(before)) for (var stop : dayStops(d)) {
            boolean pinned = intent.getPinnedStopIds() != null && (intent.getPinnedStopIds().contains(safe(stop.get("id"))) || intent.getPinnedStopIds().contains(safe(stop.get("venueId"))));
            boolean scoped = intent.getTargetStartMinute() != null && intent.getTargetEndMinute() != null;
            int minute = AdjustmentTimeScopeResolver.minute(stop);
            boolean outside = scoped && (minute < intent.getTargetStartMinute() || minute >= intent.getTargetEndMinute());
            boolean otherDay = intent.getDayNumber() != null && !"TRIP".equals(intent.getScope()) && !java.util.Objects.equals(number(d.get("day")), intent.getDayNumber());
            if (!pinned && !outside && !otherDay) continue;
            var revised = next.get(safe(stop.get("id")));
            if (revised == null || days(after).stream().filter(nd -> dayStops(nd).stream().anyMatch(ns -> java.util.Objects.equals(ns.get("id"), stop.get("id")))).noneMatch(nd -> java.util.Objects.equals(number(nd.get("day")), number(d.get("day"))))) return true;
            for (String field : List.of("venueId", "name", "time", "startTime", "duration"))
                if (!java.util.Objects.equals(stop.get(field), revised.get(field))) return true;
        }
        if (intent.getTargetStartMinute() != null && intent.getTargetEndMinute() != null)
            for (var d : days(after)) for (var stop : dayStops(d)) {
                var original = allStops(before).stream().filter(s -> java.util.Objects.equals(s.get("id"), stop.get("id")))
                        .findFirst().orElse(null);
                boolean edited = original == null || List.of("venueId", "name", "time", "startTime", "duration").stream()
                        .anyMatch(field -> !java.util.Objects.equals(original.get(field), stop.get(field)));
                if (!edited) continue;
                Integer minute = itineraryTimeEngine.parseExplicitTimeMinutes(firstNonBlank(safe(stop.get("time")), safe(stop.get("startTime"))));
                long end = minute == null ? Long.MAX_VALUE : (long) minute + itineraryTimeEngine.parseDurationMinutes(safe(stop.get("duration")));
                if (minute == null || minute < intent.getTargetStartMinute() || minute >= intent.getTargetEndMinute()
                        || end > intent.getTargetEndMinute()) return true;
            }
        return false;
    }

    private boolean hasMissingDiningTarget(List<String> reasons) {
        return reasons.stream().anyMatch(r -> r.startsWith("DINING_") || r.equals("MEAL_WINDOW_UNAVAILABLE") || r.equals("NO_FEASIBLE_MEAL_GAP"));
    }

    private List<Map<String, Object>> describeActualChanges(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Map<String, Object>> old = new LinkedHashMap<>(), current = new LinkedHashMap<>();
        for (var day : days(before)) for (var stop : dayStops(day)) old.put(safe(stop.get("id")), stop);
        for (var day : days(after)) for (var stop : dayStops(day)) current.put(safe(stop.get("id")), stop);
        Set<String> ids = new java.util.LinkedHashSet<>(old.keySet()); ids.addAll(current.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (String id : ids) {
            var a = old.get(id); var b = current.get(id);
            if (a != null && b != null && java.util.Objects.equals(a.get("venueId"), b.get("venueId"))
                    && java.util.Objects.equals(a.get("name"), b.get("name"))
                    && java.util.Objects.equals(a.get("time"), b.get("time"))
                    && java.util.Objects.equals(a.get("duration"), b.get("duration"))) continue;
            var change = new LinkedHashMap<String, Object>();
            change.put("stopId", id); change.put("before", a); change.put("after", b); result.add(change);
        }
        return result;
    }

    private boolean hasMissingAdjustmentTarget(List<String> reasons) {
        return reasons != null && reasons.stream().anyMatch(reason -> Set.of(
                "DINING_CANDIDATES_UNAVAILABLE", "DINING_POI_UNRESOLVED",
                "MEAL_WINDOW_UNAVAILABLE", "NO_FEASIBLE_MEAL_GAP", "NO_ACTUAL_CHANGE", "REQUESTED_STOP_NOT_RETAINED").contains(reason));
    }

    private PlannerService.ServiceResult resetAdjustmentRound(StoredSession session, PlanConversationRequestDto request, boolean cancelled) {
        Object lock = sessionApplyLocks[Math.floorMod(session.sessionId().hashCode(), sessionApplyLocks.length)];
        synchronized (lock) {
            StoredSession latest = findAuthorized(session.sessionId(), request == null ? "" : request.getSessionAccessToken());
            if (latest == null) return error(404, "规划会话不存在或无权访问。", null);
            if (request != null && request.getBaseRevision() != latest.currentVersion()) return conflict(latest);
            continuations.reset(latest.sessionId());
            return new PlannerService.ServiceResult(200, Map.of("ok", true, "reset", true, "cancelled", cancelled,
                    "modified", false, "message", cancelled ? "已取消本轮调整，当前行程未改变。" : "已结束上一轮对话上下文。"));
        }
    }

    public PlannerService.ServiceResult apply(String sessionId, ApplyAdjustmentRequestDto request) {
        String lockKey = sessionId == null ? "" : sessionId;
        Object sessionLock = sessionApplyLocks[Math.floorMod(lockKey.hashCode(), sessionApplyLocks.length)];
        synchronized (sessionLock) {
            return doApply(sessionId, request);
        }
    }

    private PlannerService.ServiceResult doApply(String sessionId, ApplyAdjustmentRequestDto request) {
        long startNanos = System.nanoTime();
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
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

        if (requestedRevision != session.currentVersion()) { if (metricsLogger != null) metricsLogger.logRevisionConflict(sessionId, requestedRevision, session.currentVersion()); return conflict(session); }
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
        int expectedRevision = requestedRevision;
        if (proposal.getBaseRevision() != expectedRevision || proposal.getBaseRevision() != session.currentVersion()) {
            if (metricsLogger != null) {
                metricsLogger.logRevisionConflict(sessionId, proposal.getBaseRevision(), session.currentVersion());
            }
            return conflict(session);
        }

        if (!continuations.validProposal(session.sessionId(), proposal.getConversationEpoch(), proposal.getCreatedAt())) return error(409, "这份提案属于已结束的对话，请重新预览。", session);
        Map<String, Object> proposedTrip;
        if (proposal.getOptionPlans() != null && !proposal.getOptionPlans().isEmpty()) {
            if (!proposal.getOptionPlans().containsKey(optionId)) {
                return error(400, "指定的选项序号（" + optionId + "）不存在，请选择有效的候选方案选项。", session);
            }
            proposedTrip = deepCopy(proposal.getOptionPlans().get(optionId), objectMapper);
        } else {
            proposedTrip = deepCopy(proposal.getProposedTrip(), objectMapper);
        }

        if (proposal.getIntent() != null && proposal.getIntent().getType() == ConversationIntentType.ADD_STOP
                && (proposal.getRequiredStopIdsByOption() == null
                    || proposal.getRequiredStopIdsByOption().getOrDefault(optionId, List.of()).isEmpty())) {
            return error(400, "这份加站预览缺少目标校验信息，请重新生成预览后应用。", session);
        }
        var selectedStatus = optionStatus(session.trip(), proposedTrip, proposal.getIntent(), extractConstraints(session), proposal.getReasonCodes(), optionId,
                proposal.getRequiredStopIdsByOption() == null ? List.of() : proposal.getRequiredStopIdsByOption().getOrDefault(optionId, List.of()));
        if (!Boolean.TRUE.equals(selectedStatus.get("canApply"))) return error(400, "这个候选没有有效改动，或违反必须保留的范围、开放条件，不能应用；请选择其他候选。", session);
        if (!Boolean.TRUE.equals(selectedStatus.get("feasible")) && !forceApply) return error(400, "这个候选有不可行约束冲突提示，请查看后决定是否继续。", session);
        int nextVersion = session.currentVersion() + 1;
        finalizeTrip(proposedTrip, nextVersion, "AI 局部调整应用",
                proposal.getIntent() == null ? "用户确认应用" : proposal.getIntent().getRawMessage(),
                proposal.getChangedSegments());

        String mutationLabel = proposal.getIntent() != null ? proposal.getIntent().getType().name() : "ADJUSTMENT_APPLIED";
        MutationResult result = repository.update(
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
        continuations.afterApply(session.sessionId(), result.session().currentVersion());

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
        var continuation = continuations.pending(session.sessionId());
        if (continuation != null) {
            body.put("pendingIntent", Map.of("intentId", continuation.intentId(), "baseRevision", continuation.baseRevision(), "expiresAt", continuation.expiresAt(), "intent", continuation.intent().asMap()));
            body.put("nextActionPrompt", continuation.intent().getClarificationQuestion());
        }
        body.put("message", forceApply && !proposal.isFeasible()
                ? "已按你的选择应用这份调整；它可能让当天安排不够顺路，请结合实际情况安排行程。"
                : "行程调整已成功应用并持久化。");
        PlannerService.ServiceResult success = new PlannerService.ServiceResult(200, body);
        if (!idempotencyKey.isBlank()) {
            applyReplays.put(replayKey, new ApplyReplay(requestFingerprint, success));
        }
        return success;
    }

    private void resolveAndValidate(PlanAdjustmentIntent intent, PlanPageContext context, Map<String, Object> trip) {
        if (intent == null || trip == null) return;

        int totalDays = days(trip).size();
        Integer activeDay = context.getActiveDay() != null ? context.getActiveDay() : 1;
        if (totalDays > 0) {
            activeDay = Math.min(totalDays, Math.max(1, activeDay));
        }

        // 1. Day resolution
        if (intent.getType() == ConversationIntentType.ADD_STOP) {
            if (intent.getDayNumber() != null) {
                if (intent.getDayNumber() == totalDays + 1) {
                    // 允许顺延扩日
                } else if (intent.getDayNumber() < 1 || intent.getDayNumber() > totalDays) {
                    intent.setType(ConversationIntentType.CLARIFICATION);
                    intent.setRequiresClarification(true);
                    intent.setClarificationQuestion("当前行程共 " + totalDays + " 天，无法调整第 " + intent.getDayNumber() + " 天。");
                    return;
                }
            }
        } else {
            if (intent.getDayNumber() == null) {
                intent.setDayNumber(activeDay);
            }
            if (intent.getDayNumber() < 1 || intent.getDayNumber() > totalDays) {
                intent.setType(ConversationIntentType.CLARIFICATION);
                intent.setRequiresClarification(true);
                intent.setClarificationQuestion("当前行程共 " + totalDays + " 天，无法调整第 " + intent.getDayNumber() + " 天。");
                return;
            }
        }

        // 2. Stop resolution
        String rawText = intent.getRawMessage() != null ? intent.getRawMessage() : "";
        String selectedStop = safeText(context.getSelectedStopId());
        if (Boolean.TRUE.equals(intent.getGenericDiningCraving()) && safeText(intent.getCuisineOrPlace()) != null) {
            // A generic cuisine cannot inherit a catalog venue guessed from the raw sentence.
            intent.setTargetAttractionId(null);
            intent.setReplacementPlaceId(null);
        }

        if (intent.getType() == ConversationIntentType.ADD_STOP
                && safeText(intent.getTargetAttractionId()) != null) {
            String resolved = resolveAttractionId(intent.getTargetAttractionId());
            if (resolved != null) intent.setTargetAttractionId(resolved);
        }
        if (intent.getType() == ConversationIntentType.SUGGEST_REPLACEMENTS
                && !Boolean.TRUE.equals(intent.getGenericDiningCraving())) {
            String replacementReference = firstNonBlank(
                    intent.getReplacementPlaceName(),
                    intent.getRequestedVenueName()
            );
            if (safeText(replacementReference) != null) {
                String resolved = resolveAttractionId(replacementReference);
                if (resolved != null) intent.setReplacementPlaceId(resolved);
            }
        }

        boolean isMealSlotReference = intent.getTargetStopReference() != null
                && intent.getTargetStopReference().matches(".*(午餐|晚餐|早餐|早点|午饭|晚饭|宵夜|夜宵).*");
        if ((intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) && !isMealSlotReference) {
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

        boolean isDiningIntent = isDiningIntent(intent);
        boolean targetStopMismatched = false;
        if (selectedStop != null) {
            StopLocation selectedLoc = locateStop(trip, selectedStop);
            if (selectedLoc != null && selectedLoc.stop() != null) {
                String selectedName = String.valueOf(selectedLoc.stop().getOrDefault("name", ""));
                String selectedTime = String.valueOf(selectedLoc.stop().getOrDefault("time", ""));
                boolean selectedIsDining = isDiningStop(selectedLoc.stop());
                if (selectedIsDining && !isDiningIntent) {
                    targetStopMismatched = true;
                } else if (!selectedIsDining && isDiningIntent && !selectedName.isBlank() && !rawText.contains(selectedName)) {
                    targetStopMismatched = true;
                } else if (selectedIsDining && isDiningIntent) {
                    String explicitSlot = safeText(intent.getTimeSlot());
                    boolean wantsBreakfast = explicitSlot != null ? "BREAKFAST".equals(explicitSlot) : rawText.matches(".*(早|早点|早餐|早饭).*")
                            || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(早|早点|早餐).*"));
                    boolean wantsDinner = explicitSlot != null ? "DINNER".equals(explicitSlot) || "NIGHT_SNACK".equals(explicitSlot) : rawText.matches(".*(晚|晚餐|晚上|夜间|夜景|收尾|夜宵).*")
                            || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(晚|晚餐).*"));
                    boolean wantsLunch = explicitSlot != null ? "LUNCH".equals(explicitSlot) : rawText.matches(".*(午|午餐|中午|午饭).*")
                            || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(午|午餐).*"));

                    if (wantsLunch && (itineraryTimeEngine.isEveningSlot(selectedTime) || itineraryTimeEngine.isMorningSlot(selectedTime) || selectedName.contains("晚餐") || selectedName.contains("早餐"))) {
                        targetStopMismatched = true;
                    } else if (wantsDinner && (itineraryTimeEngine.isMorningSlot(selectedTime) || (!itineraryTimeEngine.isEveningSlot(selectedTime) && selectedName.contains("午餐")) || selectedName.contains("早餐"))) {
                        targetStopMismatched = true;
                    } else if (wantsBreakfast && (itineraryTimeEngine.isEveningSlot(selectedTime) || selectedName.contains("晚餐") || selectedName.contains("午餐"))) {
                        targetStopMismatched = true;
                    }
                }
            }
        }

        if (targetStopMismatched && intent.getTargetStopId() != null && intent.getTargetStopId().equals(selectedStop)) {
            intent.setTargetStopId(null);
        }

        if (isDiningIntent) {
            String refPlace = firstNonBlank(intent.getReplacementPlaceName(), intent.getRequestedVenueName());
            boolean hasSpecificDiningName = !diningCategoryGrounder.isGenericDiningCraving(intent, refPlace);

            if (selectedStop != null && !targetStopMismatched) {
                StopLocation targetLoc = locateStop(trip, selectedStop);
                if (targetLoc != null && isDiningStop(targetLoc.stop())) {
                    intent.setTargetStopId(selectedStop);
                }
            }

            if (intent.getTargetStopId() == null) {
                int dayNum = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                Map<String, Object> dayMap = findDay(trip, dayNum);
                if (dayMap != null) {
                    List<Map<String, Object>> stops = dayStops(dayMap);
                    String slotType = diningCategoryGrounder.resolveSlotType(intent);
                    boolean isLunch = "LUNCH".equals(slotType);
                    boolean isDinner = "DINNER".equals(slotType);
                    boolean isBreakfast = "BREAKFAST".equals(slotType);

                    for (Map<String, Object> s : stops) {
                        if (isDiningStop(s)) {
                            String time = String.valueOf(s.getOrDefault("time", ""));
                            String name = String.valueOf(s.getOrDefault("name", ""));
                            if (isLunch && (itineraryTimeEngine.isEveningSlot(time) || itineraryTimeEngine.isMorningSlot(time) || name.contains("晚餐") || name.contains("早餐"))) {
                                continue;
                            }
                            if (isDinner && (itineraryTimeEngine.isMorningSlot(time) || (!itineraryTimeEngine.isEveningSlot(time) && name.contains("午餐")) || name.contains("早餐"))) {
                                continue;
                            }
                            if (isBreakfast && (itineraryTimeEngine.isEveningSlot(time) || name.contains("晚餐") || name.contains("午餐"))) {
                                continue;
                            }
                            intent.setTargetStopId(String.valueOf(s.get("id")));
                            break;
                        }
                    }
                    if (intent.getTargetStopId() == null && intent.getType() != ConversationIntentType.ADD_STOP) {
                        intent.setType(ConversationIntentType.ADD_STOP);
                    }
                }
            }
        }

        if ((intent.getTargetStopId() == null || intent.getTargetStopId().isBlank())
                && selectedStop != null
                && !targetStopMismatched
                && intent.getType() != ConversationIntentType.ADD_STOP) {
            intent.setTargetStopId(selectedStop);
        }

        // Semantic Compatibility Guard (DEF-PLAN-02)
        if (isDiningIntent && (intent.getType() == ConversationIntentType.SUGGEST_REPLACEMENTS)) {
            if (intent.getTargetStopId() != null && !intent.getTargetStopId().isBlank()) {
                StopLocation targetLoc = locateStop(trip, intent.getTargetStopId());
                if (targetLoc != null && !isDiningStop(targetLoc.stop())) {
                    String stopName = String.valueOf(targetLoc.stop().getOrDefault("name", ""));
                    if (!stopName.isBlank() && !rawText.contains(stopName)) {
                        log.info("[PlanAdjustment] 目标节点 {} 是景点而非餐饮，动态升维为 ADD_STOP 保护景点", stopName);
                        intent.setType(ConversationIntentType.ADD_STOP);
                    }
                }
            }
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
                    String ref = intent.getTargetStopReference() != null ? intent.getTargetStopReference() : rawText;
                    if (ref != null && (ref.contains("午餐后") || ref.contains("午餐前") || ref.contains("午饭后") || ref.contains("午饭前")
                            || ref.contains("后面的那个") || ref.contains("前面的那个") || ref.contains("后一个") || ref.contains("前一个")
                            || ref.contains("最后") || ref.contains("第一"))) {
                        int dayNum = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                        Map<String, Object> targetDayMap = findDay(trip, dayNum);
                        if (targetDayMap != null) {
                            List<Map<String, Object>> stops = dayStops(targetDayMap);
                            if (ref.contains("午餐后") || ref.contains("午饭后") || ref.contains("后面的那个") || ref.contains("后一个")) {
                                for (int i = 0; i < stops.size(); i++) {
                                    if (isDiningStop(stops.get(i)) || String.valueOf(stops.get(i).getOrDefault("name", "")).contains("午餐")) {
                                        if (i + 1 < stops.size()) {
                                            intent.setTargetStopId(String.valueOf(stops.get(i + 1).get("id")));
                                            break;
                                        }
                                    }
                                }
                            } else if (ref.contains("午餐前") || ref.contains("午饭前") || ref.contains("前面的那个") || ref.contains("前一个")) {
                                for (int i = 0; i < stops.size(); i++) {
                                    if (isDiningStop(stops.get(i)) || String.valueOf(stops.get(i).getOrDefault("name", "")).contains("午餐")) {
                                        if (i - 1 >= 0) {
                                            intent.setTargetStopId(String.valueOf(stops.get(i - 1).get("id")));
                                            break;
                                        }
                                    }
                                }
                            } else if (ref.contains("最后")) {
                                if (!stops.isEmpty()) {
                                    intent.setTargetStopId(String.valueOf(stops.get(stops.size() - 1).get("id")));
                                }
                            } else if (ref.contains("第一")) {
                                if (!stops.isEmpty()) {
                                    intent.setTargetStopId(String.valueOf(stops.get(0).get("id")));
                                }
                            }
                        }
                    }
                }
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

    private boolean isDiningIntent(PlanAdjustmentIntent intent) {
        if (intent == null) return false;
        if (safeText(intent.getCuisineOrPlace()) != null && intent.getGenericDiningCraving() != null) return true;
        if (intent.getPreferences() != null && intent.getPreferences().contains("FOOD")) {
            return true;
        }
        String refPlace = firstNonBlank(intent.getReplacementPlaceName(), intent.getRequestedVenueName());
        if (refPlace != null && diningCategoryGrounder.isGenericDiningCraving(refPlace)) {
            return true;
        }
        String raw = firstNonBlank(intent.getRawMessage(), "");
        if (diningCategoryGrounder.isNonDiningPlace(raw)) {
            return false;
        }
        return raw.matches(".*(餐饮|餐厅|美食|火锅|江湖菜|泉水鸡|正餐|晚餐|午餐|早餐|早点|早饭|小面|面馆|面条|抄手|吃辣|辣味|烧烤|烤肉|烤鱼|大排档|甜品|糖水|甜食|冰粉|凉糕|茶馆|茶室|茶楼|盖碗茶|下午茶|咖啡|点心|小吃).*")
                || (refPlace != null && refPlace.matches(".*(美食|火锅|江湖菜|泉水鸡|餐厅|小面|面条|早餐|早点|早饭|小吃|甜品|糖水|茶馆|下午茶).*"));
    }

    private String resolveAttractionId(String name) {
        String reference = safe(name);
        if (reference.isBlank()) return null;
        if (reference.startsWith("amap-")) return reference;
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

    private TravelConstraints extractConstraints(StoredSession session) {
        if (session == null || session.constraints() == null) return new TravelConstraints();
        try {
            return objectMapper.convertValue(session.constraints(), TravelConstraints.class);
        } catch (Exception e) {
            return new TravelConstraints();
        }
    }

    private StoredSession findAuthorized(String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (UserContext.isAnonymous()) {
            StoredSession capabilitySession = repository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return repository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        StoredSession owned = repository.find(sessionId, "USER", UserContext.getUserId(), token);
        return owned != null ? owned : repository.claimAnonymous(sessionId, token, UserContext.getUserId());
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

    private PlannerService.ServiceResult error(int status, String message, StoredSession session) {
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

    private PlannerService.ServiceResult conflict(StoredSession session) {
        return error(409, "规划版本已变化，请基于最新行程重新操作。", session);
    }

    private PlannerService.ServiceResult withIntentMetadata(
            PlannerService.ServiceResult result,
            LlmIntentExtractor.LlmIntentExtractionResult extraction) {
        if (result == null || extraction == null) return result;
        Map<String, Object> body = new LinkedHashMap<>(result.body());
        body.putAll(extraction.metadata());
        return new PlannerService.ServiceResult(result.status(), body);
    }

    private record ApplyReplay(String requestFingerprint, PlannerService.ServiceResult result) {}
}

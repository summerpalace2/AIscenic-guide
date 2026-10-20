package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.language.IntentLanguagePolicy;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
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
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;
import com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort;
import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.AdjustmentPreviewExecution;
import com.ai.guide.domain.planner.model.AdjustmentLifecycleExecution;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
    private final PlannerSessionRepositoryPort repository;
    private final ItineraryBuilderPort itineraryBuilder;
    private final AttractionService attractionService;
    private final RouteCostProvider routeCostProvider;
    private final PlanVerifier planVerifier;
    private final RouteAwarePlanner routeAwarePlanner;
    private final ProposalRepositoryPort proposalStore;
    private final LlmIntentExtractor llmIntentExtractor;
    private final ObjectMapper objectMapper;
    private final PlannerMetricsLogger metricsLogger;

    private com.ai.guide.domain.planner.gateway.PlannerConversationStatePort conversationState;
    private PendingIntentContinuations continuations;
    private final ConversationIntentClassifier continuationClassifier;

    @Autowired
    public void setConversationState(com.ai.guide.domain.planner.gateway.PlannerConversationStatePort store) {
        this.conversationState = store;
        this.continuations = new PendingIntentContinuations(continuationClassifier,System::currentTimeMillis,store);
    }

    private ApplyReplay loadReplay(String sessionId,String key) {
        var stored = conversationState.applyReceipt(sessionId,key);
        return stored==null?null:new ApplyReplay(stored.fingerprint(),new PlannerService.ServiceResult(stored.status(),stored.body()));
    }
    private void saveReplay(String sessionId,String key,ApplyReplay replay) {
        conversationState.saveApplyReceipt(sessionId,key,new com.ai.guide.domain.planner.gateway.PlannerConversationStatePort.ApplyReceipt(
                replay.requestFingerprint(),replay.result().status(),replay.result().body()));
    }

    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    private DiningVenueService diningVenueService;

    private final ItineraryTimeEngine itineraryTimeEngine;
    private final CandidateRankingEngine candidateRankingEngine;
    private final DiningCategoryGrounder diningCategoryGrounder;
    private volatile List<AdjustmentStrategy> strategies;
    private final PlannerMutationPolicy mutationPolicy;
    private PlannerTaxonomyPort taxonomy;
    private MealGapSolver mealGapSolver;
    private DynamicGeoCentroidResolver geoCentroidResolver;
    private AttractionAliasMatcher attractionAliasMatcher;

    @Autowired
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalRepositoryPort proposalStore,
                                 LlmIntentExtractor llmIntentExtractor,
                                 ConversationIntentClassifier intentClassifier,
                                 ObjectMapper objectMapper,
                                 PlannerMetricsLogger metricsLogger) {
        this.repository = repository;
        this.itineraryBuilder = itineraryBuilder;
        this.attractionService = attractionService;
        this.routeCostProvider = routeCostProvider;
        this.planVerifier = planVerifier;
        this.mutationPolicy = new PlannerMutationPolicy(itineraryBuilder, planVerifier);
        this.routeAwarePlanner = routeAwarePlanner;
        this.proposalStore = proposalStore;
        this.llmIntentExtractor = llmIntentExtractor;
        this.continuationClassifier = intentClassifier;
        this.conversationState = new InMemoryPlannerConversationState();
        this.continuations = new PendingIntentContinuations(intentClassifier,System::currentTimeMillis,conversationState);
        this.objectMapper = objectMapper;
        this.metricsLogger = metricsLogger;

        this.itineraryTimeEngine = new ItineraryTimeEngine();
        this.candidateRankingEngine = new CandidateRankingEngine(routeCostProvider);
        this.diningCategoryGrounder = intentClassifier != null && intentClassifier.diningCategoryGrounder() != null
                ? intentClassifier.diningCategoryGrounder()
                : new DiningCategoryGrounder();
        this.taxonomy = TaxonomyPort.empty();
        this.mealGapSolver = new MealGapSolver(routeCostProvider, itineraryTimeEngine);
        this.geoCentroidResolver = new DynamicGeoCentroidResolver(null);
        this.strategies = initDefaultStrategies();
    }

    /** Compatibility constructor for callers that do not provide an intent classifier. */
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalRepositoryPort proposalStore,
                                 LlmIntentExtractor llmIntentExtractor,
                                 ObjectMapper objectMapper,
                                 PlannerMetricsLogger metricsLogger) {
        this(repository, itineraryBuilder, attractionService, routeCostProvider, planVerifier,
                routeAwarePlanner, proposalStore, llmIntentExtractor,
                new ConversationIntentClassifier(attractionService), objectMapper, metricsLogger);
    }

    /** Backward-compatible constructor for deterministic unit-test callers. */
    public PlanAdjustmentService(PlannerSessionRepositoryPort repository,
                                 ItineraryBuilderPort itineraryBuilder,
                                 AttractionService attractionService,
                                 RouteCostProvider routeCostProvider,
                                 PlanVerifier planVerifier,
                                 RouteAwarePlanner routeAwarePlanner,
                                 ProposalRepositoryPort proposalStore,
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
                                 ProposalRepositoryPort proposalStore,
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

    /** Single composition point for stateless strategies, also used by direct-construction test fixtures. */
    private List<AdjustmentStrategy> initDefaultStrategies() {
        ReplaceStopAdjustmentStrategy replace = new ReplaceStopAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner, planVerifier,
                candidateRankingEngine, itineraryTimeEngine, diningCategoryGrounder);
        replace.setDiningVenueService(diningVenueService);
        replace.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);

        AddStopAdjustmentStrategy add = new AddStopAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner,
                candidateRankingEngine, itineraryTimeEngine, diningCategoryGrounder);
        add.setAliasMatcher(attractionAliasMatcher);
        add.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
        add.setDiningVenueService(diningVenueService);
        add.setDiningScheduling(taxonomy, mealGapSolver, geoCentroidResolver);
        add.setPlanVerifier(planVerifier);

        ReplanDayAdjustmentStrategy replan = new ReplanDayAdjustmentStrategy(
                attractionService, itineraryBuilder, routeAwarePlanner,
                candidateRankingEngine, itineraryTimeEngine, routeCostProvider);
        replan.setTaxonomy(taxonomy);

        RemoveStopAdjustmentStrategy remove = new RemoveStopAdjustmentStrategy(routeAwarePlanner);

        PlaceQuestionAdjustmentStrategy placeQ = new PlaceQuestionAdjustmentStrategy(attractionService);
        placeQ.setRuntimeAttractionDetailService(runtimeAttractionDetailService);
        placeQ.setDiningVenueService(diningVenueService);
        placeQ.setTaxonomy(taxonomy);

        return List.of(replace, add, replan, remove, placeQ);
    }

    @Autowired(required = false)
    public void setRuntimeAttractionDetailService(RuntimeAttractionDetailService runtimeAttractionDetailService) {
        this.runtimeAttractionDetailService = runtimeAttractionDetailService;
        this.strategies = initDefaultStrategies();
    }

    @Autowired(required = false)
    public void setDiningVenueService(DiningVenueService diningVenueService) {
        this.diningVenueService = diningVenueService;
        this.strategies = initDefaultStrategies();
    }

    @Autowired
    public void setDiningScheduling(PlannerTaxonomyPort taxonomy, MealGapSolver mealGapSolver,
                                    DynamicGeoCentroidResolver geoCentroidResolver) {
        this.taxonomy = taxonomy;
        this.candidateRankingEngine.setTaxonomy(taxonomy);
        this.mealGapSolver = mealGapSolver;
        this.geoCentroidResolver = geoCentroidResolver;
        this.diningCategoryGrounder.setTaxonomy(taxonomy);
        this.strategies = initDefaultStrategies();
    }

    @Autowired(required = false)
    public void setDynamicShortTripPolicy(DynamicShortTripPolicyPort policy) {
        this.itineraryTimeEngine.setUnknownTransferEstimateMinutes(
                policy == null ? -1 : policy.getPolicy().unknownTravelDurationMinutes());
    }

    @Autowired(required = false)
    public void setCandidateRankingPolicy(CandidateRankingPolicyPort policy) {
        this.candidateRankingEngine.setPolicyPort(policy);
    }

    @Autowired(required = false)
    public void setAttractionAliasMatcher(AttractionAliasMatcher matcher) {
        this.attractionAliasMatcher = matcher;
        this.strategies = initDefaultStrategies();
    }

    PlannerMutationPolicy mutationPolicy() { return mutationPolicy; }

    public PlannerService.ServiceResult preview(String sessionId, PlanConversationRequestDto request) {
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        return previewAuthorized(session, request);
    }

    /**
     * Executes preview policy against a session already resolved by the application use case.
     * The legacy session-id entry above remains available to existing domain callers.
     */
    public PlannerService.ServiceResult previewAuthorized(StoredSession session, PlanConversationRequestDto request) {
        return previewAuthorized(session, request, proposalStore::put);
    }

    /** Runs preview rules while allowing the application use case to own proposal persistence. */
    public PlannerService.ServiceResult previewAuthorized(StoredSession session,
                                                          PlanConversationRequestDto request,
                                                          Consumer<PlanAdjustmentProposal> proposalWriter) {
        return previewAuthorized(session, request, proposalWriter, null);
    }

    private PlannerService.ServiceResult previewAuthorized(StoredSession session,
                                                           PlanConversationRequestDto request,
                                                           Consumer<PlanAdjustmentProposal> proposalWriter,
                                                           PreviewGenerationTicket admittedTicket) {
        long startNanos = System.nanoTime();
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        PlanPageContext context = request == null || request.getContext() == null ? new PlanPageContext() : request.getContext();
        String originalMessage = request == null ? "" : request.getMessage();
        var cancellation = cancellationDirective(originalMessage);
        String messageText = cancellation.cancelled() && !cancellation.remaining().isBlank() ? cancellation.remaining() : originalMessage;
        if (request != null && request.getContext() != null && request.getContext().isResetConversation()) return resetAdjustmentRound(session, request, false);
        if (isCancellationRequest(originalMessage)) return resetAdjustmentRound(session, request, true);
        if (cancellation.cancelled()) {
            var cancelled = resetAdjustmentRound(session, request, true);
            if (cancelled.status() != 200) return cancelled;
            // A new complete clause starts in a fresh epoch, never in the cancelled task.
            admittedTicket = null;
        }
        if (admittedTicket == null) {
            StoredSession requestedSession = session;
            PreviewAdmission admission = withSessionApplyLock(requestedSession.sessionId(), () -> {
                StoredSession latest = refreshAuthorizedSession(requestedSession, request == null ? "" : request.getSessionAccessToken());
                if (latest == null) return new PreviewAdmission(null, null, error(404, "规划会话不存在或无权访问。", null));
                if (request != null && request.getBaseRevision() != latest.currentVersion())
                    return new PreviewAdmission(null, null, error(409, "行程版本已更新，请刷新后基于最新行程调整。", latest));
                return new PreviewAdmission(latest, beginPreviewForApplication(latest), null);
            });
            if (admission.failure() != null) return admission.failure();
            session = admission.session();
            admittedTicket = admission.ticket();
        } else if (!session.sessionId().equals(admittedTicket.sessionId())) {
            return error(409, "预览请求与授权会话不匹配，请重新发起。", session);
        }
        if (request != null && request.getBaseRevision() != session.currentVersion())
            return error(409, "行程版本已更新，请刷新后基于最新行程调整。", session);
        // Legacy request pins may add protection for this proposal, never remove stored pins.
        var effectivePins = new LinkedHashSet<>(PlannerPins.read(session.trip()));
        if (context.getPinnedStopIds() != null) effectivePins.addAll(context.getPinnedStopIds());
        context.setPinnedStopIds(new ArrayList<>(effectivePins));
        String generation = admittedTicket.generation();
        final StoredSession previewSession = session;
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
            if (!continuations.current(session.sessionId(), generation))
                return error(409, "这轮预览已被取消或替换，请查看最新对话。", session);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true); body.put("proposalId", pending.getProposalId()); body.put("baseRevision", pending.getBaseRevision());
            body.put("intent", pending.getIntent().asMap()); body.put("feasible", pending.isFeasible()); body.put("canApply", true);
            body.put("proposedTrip", pending.getProposedTrip()); body.put("candidateReplacements", pending.getCandidateReplacements());
            body.put("changedSegments", pending.getChangedSegments()); body.put("reasonCodes", pending.getReasonCodes());
            body.put("stopChanges", describeActualChanges(session.trip(), pending.getProposedTrip()));
            var optionChanges = new LinkedHashMap<String, Object>();
            pending.getOptionPlans().forEach((id, plan) -> optionChanges.put(id, describeActualChanges(previewSession.trip(), plan)));
            body.put("optionStopChanges", optionChanges);
            body.put("optionStatus", pending.getOptionStatus());
            body.put("canApply", pending.getOptionStatus().isEmpty() ? !hasMissingAdjustmentTarget(pending.getReasonCodes()) : pending.getOptionStatus().values().stream().anyMatch(v -> Boolean.TRUE.equals(v.get("canApply"))));
            body.put("expiresAt", pending.getExpiresAt()); body.put("reused", true); body.put("modified", false);
            body.put("message", "这项调整已有待确认预览，请核对后点击确认应用；当前行程尚未修改。");
            return new PlannerService.ServiceResult(200, body);
        }
        // An unrelated social aside must not be interpreted as the missing station name.
        if (isSocialAside(messageText, session.trip())) {
            var suspended = continuations.pending(session.sessionId());
            if (suspended != null && (suspended.baseRevision() != session.currentVersion()
                    || suspended.expiresAt() <= System.currentTimeMillis())) suspended = null;
            var dialogue = new LinkedHashMap<String,Object>();
            dialogue.put("ok", true); dialogue.put("type", "DIALOGUE"); dialogue.put("operation", "QA");
            dialogue.put("modified", false); dialogue.put("suspended", suspended != null);
            dialogue.put("message", "我在听，你可以先聊聊现在的感受。行程保持原样；想继续调整时再告诉我，或随时取消。");
            if (suspended != null) dialogue.put("pendingIntent", Map.of("intentId", suspended.intentId(),
                    "baseRevision", suspended.baseRevision(), "expiresAt", suspended.expiresAt(), "intent", suspended.intent().asMap()));
            if (!continuations.current(session.sessionId(), generation)) return error(409, "这轮对话已被取代。", session);
            return new PlannerService.ServiceResult(200, dialogue);
        }
        var calendarReference = PlannerCalendarDateResolver.resolve(messageText, session.trip());
        if (calendarReference.present()) calendarReference = new PlannerCalendarDateResolver.Reference(true,
                calendarReference.day(),calendarReference.literal(),
                !isModificationClause(messageText.replace(calendarReference.literal(),""),context) && !namesKnownTarget(messageText,session.trip()),calendarReference.error());

        final var resolvedCalendar = calendarReference;
        final var dateTrip = session.trip();
        boolean standaloneDateClause = resolvedCalendar.present() && com.ai.guide.domain.language.IntentLanguagePolicy.clauses(messageText).stream()
                .anyMatch(clause -> clause.contains(resolvedCalendar.literal())
                        && !isModificationClause(clause.replace(resolvedCalendar.literal(),""),context)
                        && !namesKnownTarget(clause,dateTrip));
        var pendingBeforeAnswer = continuations.pending(session.sessionId());
        var deferredActions = new ArrayList<String>();
        if (pendingBeforeAnswer != null && pendingBeforeAnswer.baseRevision()==session.currentVersion()
                && pendingBeforeAnswer.expiresAt()>System.currentTimeMillis()
                && (context.getPendingIntentId()==null || context.getPendingIntentId().isBlank()
                    || context.getPendingIntentId().equals(pendingBeforeAnswer.intentId()))
                && PendingIntentContinuations.requestsDay(pendingBeforeAnswer.intent())
                && standaloneDateClause && calendarReference.present() && calendarReference.day()!=null) {
            for (String clause:com.ai.guide.domain.language.IntentLanguagePolicy.clauses(messageText))
                if (!clause.replace(calendarReference.literal(), "").isBlank()
                        && isModificationClause(clause,context))
                    deferredActions.add(clause);
        }
        String continuationAnswer = deferredActions.isEmpty() ? messageText : calendarReference.literal();
        PlanAdjustmentIntent resumed = continuations.resume(session.sessionId(), session.currentVersion(), context.getPendingIntentId(), continuationAnswer, context, generation, calendarReference);
        if (resumed != null && !deferredActions.isEmpty()
                && !continuations.deferAfterApply(session.sessionId(),generation,resumed,deferredActions))
            return error(409,"这轮请求已被替换。",session);
        if (resumed == null) continuations.abandonCompoundQueue(session.sessionId());
        LlmIntentExtractor.LlmIntentExtractionResult extraction = resumed == null ? llmIntentExtractor.extract(messageText, context, session.trip())
                : new LlmIntentExtractor.LlmIntentExtractionResult(resumed, "PENDING_INTENT", null, 0, "HIGH");
        PlanAdjustmentIntent intent = extraction.intent();
        if (resumed == null) intent.setRawMessage(messageText);
        PlannerQuantityPolicy.normalize(intent);
        PlannerCalendarDateResolver.apply(intent, calendarReference);
        if (intent.getDayNumber() == null && !PendingIntentContinuations.requestsDay(intent) && (intent.isRequiresClarification() || intent.getType() != ConversationIntentType.ADD_STOP)) intent.setDayNumber(context.getActiveDay() == null ? 1 : context.getActiveDay());

        if (!continuations.current(session.sessionId(), generation)) return error(409, "这轮请求已被新对话取代，请使用最新结果。", session);
        AdjustmentTimeScopeResolver.resolve(intent, taxonomy);
        resolveAndValidate(intent, context, session.trip());
        var protectedIds = new ArrayList<>(intent.getPinnedStopIds() == null ? List.<String>of() : intent.getPinnedStopIds());
        for (String id : context.getPinnedStopIds()) if (!protectedIds.contains(id)) protectedIds.add(id);
        for (var d : days(session.trip())) for (var stop : dayStops(d))
            if ((AdjustmentTimeScopeResolver.preserves(stop, intent, taxonomy) || intent.getPreserveReferences().stream().anyMatch(ref -> java.util.Objects.equals(ConversationIntentClassifier.extractExplicitDayNumber(ref), number(d.get("day"))))) && !protectedIds.contains(safe(stop.get("id")))) protectedIds.add(safe(stop.get("id")));
        intent.setPinnedStopIds(protectedIds);
        if (intent.getType() == ConversationIntentType.REDUCE_DAY_DENSITY && intent.getReduceCount() != null) {
            var requestedDay = days(session.trip()).stream().filter(d -> java.util.Objects.equals(number(d.get("day")), intent.getDayNumber())).findFirst().orElse(null);
            if (requestedDay != null) {
                var stops = dayStops(requestedDay);
                int removable = (int)stops.stream().filter(stop -> !protectedIds.contains(safe(stop.get("id")))
                        && !protectedIds.contains(safe(stop.get("venueId")))).count();
                removable = Math.min(removable, Math.max(0, stops.size() - 1));
                if (intent.getReduceCount() > removable) {
                    intent.setType(ConversationIntentType.CLARIFICATION); intent.setRequiresClarification(true);
                    intent.setReduceCount(null); intent.setMissingFields(List.of("REDUCE_COUNT"));
                    intent.setClarificationQuestion("保留固定站点且每天至少保留一站后，这天最多可减少 " + removable + " 站。请重新指定数量，或取消本次调整。");
                }
            }
        }


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
            if (!continuations.commit(previewSession.sessionId(), generation, () -> slot[0] = continuations.remember(previewSession.sessionId(), previewSession.currentVersion(), intent))) return error(409, "这轮请求已结束，请继续最新对话。", session);
            var continuation = slot[0];
            body.put("intent", intent.asMap()); body.put("pendingIntent", Map.of("intentId", continuation.intentId(), "baseRevision", continuation.baseRevision(), "expiresAt", continuation.expiresAt(), "intent", intent.asMap()));
            return withIntentMetadata(new PlannerService.ServiceResult(200, body), extraction);
        }

        if (!continuations.commit(previewSession.sessionId(), generation, () -> continuations.clear(previewSession.sessionId()))) return error(409, "这轮请求已被新对话取代。", session);
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

        var previewResult = withIntentMetadata(generateProposalPreview(session, intent, context, startNanos, generation,
                proposalWriter == null ? proposalStore::put : proposalWriter), extraction);
        var receipt = new LinkedHashMap<String,Object>(previewResult.body());
        receipt.put("originalMessage", originalMessage);
        if (!deferredActions.isEmpty()) {
            receipt.put("unexecutedActions", deferredActions);
            receipt.put("message",String.valueOf(receipt.get("message"))+" 另一项要求尚未执行；请先确认本次预览，再继续处理："+String.join("；",deferredActions));
        }
        return new PlannerService.ServiceResult(previewResult.status(),receipt);
    }

    /** Typed policy boundary for application preview use cases; legacy HTTP-shaped result stays internal. */
    public AdjustmentPreviewExecution previewForApplication(StoredSession session,
                                                             PlanConversationRequestDto request,
                                                             Consumer<PlanAdjustmentProposal> proposalWriter) {
        return mapPreviewExecution(previewAuthorized(session, request, proposalWriter, null));
    }

    public AdjustmentPreviewExecution previewForApplication(StoredSession session,
                                                             PlanConversationRequestDto request,
                                                             Consumer<PlanAdjustmentProposal> proposalWriter,
                                                             PreviewGenerationTicket admittedTicket) {
        if (admittedTicket == null) return new AdjustmentPreviewExecution(AdjustmentPreviewExecution.Outcome.INTERNAL_ERROR,
                Map.of("ok", false, "message", "预览请求未登记，请重新发起。"));
        return mapPreviewExecution(previewAuthorized(session, request, proposalWriter, admittedTicket));
    }

    private AdjustmentPreviewExecution mapPreviewExecution(PlannerService.ServiceResult legacy) {
        AdjustmentPreviewExecution.Outcome outcome = switch (legacy.status()) {
            case 200 -> AdjustmentPreviewExecution.Outcome.SUCCESS;
            case 400 -> AdjustmentPreviewExecution.Outcome.BAD_REQUEST;
            case 401 -> AdjustmentPreviewExecution.Outcome.UNAUTHORIZED;
            case 403 -> AdjustmentPreviewExecution.Outcome.FORBIDDEN;
            case 404 -> AdjustmentPreviewExecution.Outcome.NOT_FOUND;
            case 409 -> AdjustmentPreviewExecution.Outcome.CONFLICT;
            case 422 -> AdjustmentPreviewExecution.Outcome.UNPROCESSABLE;
            case 429 -> AdjustmentPreviewExecution.Outcome.TOO_MANY_REQUESTS;
            case 503 -> AdjustmentPreviewExecution.Outcome.SERVICE_UNAVAILABLE;
            default -> AdjustmentPreviewExecution.Outcome.INTERNAL_ERROR;
        };
        if (outcome == AdjustmentPreviewExecution.Outcome.SUCCESS) {
            if (Boolean.TRUE.equals(legacy.body().get("requiresClarification"))) {
                outcome = AdjustmentPreviewExecution.Outcome.CLARIFICATION;
            } else if (legacy.body().containsKey("proposalId")
                    && Boolean.FALSE.equals(legacy.body().get("canApply"))
                    && legacy.body().get("candidateReplacements") instanceof List<?> candidates
                    && candidates.isEmpty()) {
                outcome = AdjustmentPreviewExecution.Outcome.NO_CANDIDATE;
            } else if (Boolean.FALSE.equals(legacy.body().get("canApply"))) {
                outcome = AdjustmentPreviewExecution.Outcome.PREVIEW_UNAPPLICABLE;
            }
        }
        return new AdjustmentPreviewExecution(outcome, legacy.body());
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
                                                                long startNanos, String generation,
                                                                Consumer<PlanAdjustmentProposal> proposalWriter) {
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
        PlanVerifier.VerificationResult verification = mutationPolicy.verify(sourceTrip, draftTrip, constraints);
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
        long expiresAt = now + PlanAdjustmentProposal.DEFAULT_TTL_MS;

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

        if (!continuations.commit(session.sessionId(), generation, () -> proposalWriter.accept(proposal))) return error(409, "旧请求的方案已作废，请查看最新对话。", session);

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
        PlannerVersionMetadata meta = PlannerVersionMetadata.fromTrip(draftTrip, true);
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
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", taxonomyHint("teaBreakCategoryLabel")));
            body.put("message", "已为您推荐【" + flavor + "】沿途候选，请选择心仪方案后确认应用。");
        } else if ("DINING_ADD".equals(adjustmentDiagnostics.get("replacementMode"))) {
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", taxonomyHint("defaultDiningDisplayLabel")));
            body.put("message", "已为您推荐【" + flavor + "】沿途候选餐厅，请选择心仪方案后确认应用。");
        } else if ("DINING".equals(adjustmentDiagnostics.get("replacementMode"))) {
            String flavor = String.valueOf(adjustmentDiagnostics.getOrDefault("requestedFlavor", taxonomyHint("defaultDiningDisplayLabel")));
            if (flavor == null || flavor.isBlank() || taxonomyHints("missingPreferenceValues").contains(flavor) || "null".equals(flavor)) {
                flavor = taxonomyHint("defaultDiningDisplayLabel");
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

    private List<String> taxonomyHints(String key) {
        return taxonomy == null ? List.of() : taxonomy.getHints(key);
    }

    private String taxonomyHint(String key) {
        return taxonomyHints(key).stream().findFirst().orElse("");
    }

    private boolean isModificationClause(String text, PlanPageContext context) {
        // This predicate only separates/records user clauses. It does not authorize
        // executing their operation; deferred work still passes normal parsing and validation.
        return com.ai.guide.domain.language.IntentLanguagePolicy.hasAdjustmentAction(text);
    }

    private boolean namesKnownTarget(String text, Map<String,Object> trip) {
        return allStops(trip).stream().anyMatch(stop -> !safe(stop.get("name")).isBlank() && text.contains(safe(stop.get("name"))))
                || itineraryBuilder.attractionServiceList().stream().anyMatch(attraction ->
                    attraction.getName()!=null && !attraction.getName().isBlank() && text.contains(attraction.getName())
                    || attraction.getDisplayName()!=null && !attraction.getDisplayName().isBlank() && text.contains(attraction.getDisplayName()));
    }

    private boolean isSocialAside(String message, Map<String,Object> trip) {
        if (message == null || message.isBlank()) return false;
        if (allStops(trip).stream().anyMatch(stop -> message.trim().equals(safe(stop.get("name"))))
                || itineraryBuilder.attractionServiceList().stream().anyMatch(attraction -> message.trim().equals(attraction.getName())
                    || message.trim().equals(attraction.getDisplayName()))) return false;
        if (message.matches("(?s).*(?:添加|加入|删除|移除|替换|换成|减少|少排|保留|重排|重新规划|怎么走|门票|几点).*")
                || message.endsWith("?") || message.endsWith("？")) return false;
        // This fallback recognizes explicit self-reported affect, not arbitrary unknown inputs.
        return message.matches("(?s)^(?:我(?:的|现在|今天|此刻)?|今天|现在|此刻|这会儿)(?:的)?(?:心情|情绪|感到|觉得自己|很|有点).*")
                && (message.matches("(?s).*(?:心情|情绪).*")
                    || new com.ai.guide.domain.analytics.service.SentimentService().analyze(message)
                        != com.ai.guide.domain.analytics.service.SentimentService.Sentiment.NEUTRAL);
    }

    private Map<String,Object> optionStatus(Map<String,Object> before, Map<String,Object> after, PlanAdjustmentIntent intent, TravelConstraints constraints, List<String> globalReasons, String optionId, List<String> requiredStopIds) {
        var verification = mutationPolicy.verify(before, after, constraints);
        var reasons = new ArrayList<String>();
        boolean changed = !describeActualChanges(before, after).isEmpty();
        if (!changed) reasons.add("NO_ACTUAL_CHANGE");
        if (!PlannerPins.retained(before, after)) reasons.add("PINNED_STOP_VIOLATION");
        PlannerPins.preserve(before, after);
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
                .noneMatch(PlannerMutationPolicy::nonForceable);
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
                int duration = itineraryTimeEngine.parseKnownDurationMinutes(safe(stop.get("duration")));
                long end = minute == null ? Long.MAX_VALUE : (long) minute + duration;
                if (minute == null || minute < intent.getTargetStartMinute() || minute >= intent.getTargetEndMinute()
                        || duration <= 0 || end > intent.getTargetEndMinute()) return true;
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
                "MEAL_WINDOW_UNAVAILABLE", "NO_FEASIBLE_MEAL_GAP", "NO_ACTUAL_CHANGE", "REQUESTED_STOP_NOT_RETAINED",
                "ROUTE_DURATION_UNKNOWN").contains(reason));
    }

    private record CancellationDirective(boolean cancelled, String remaining) { }
    private static CancellationDirective cancellationDirective(String message) {
        String raw = message == null ? "" : message.trim();
        // Zero plus an explicit refusal of reduction cancels; a bare zero is still ambiguous.
        if (raw.matches("(?s).*(?:0|零)\\s*(?:个|处|站).*不(?:想|要|用)?(?:再)?(?:少|减少|删减).*")
                || raw.matches("(?s).*不(?:想|要|用)?(?:再)?(?:少|减少|删减).*(?:0|零)\\s*(?:个|处|站).*"))
            return new CancellationDirective(true, "");
        boolean cancelled = false;
        var remaining = new ArrayList<String>();
        for (String clause : com.ai.guide.domain.language.IntentLanguagePolicy.clauses(raw)) {
            boolean cancel = clause.matches("(?:请|帮我)?(?:取消|停止).*")
                    && !clause.matches("(?s).*(?:固定|锁定).*")
                    && !clause.endsWith("?") && !clause.endsWith("？");
            if (cancel) cancelled = true;
            else if (!clause.matches("(?:算了|算啦|那算了)")) remaining.add(clause);
        }
        if (!cancelled) return new CancellationDirective(false, raw);
        String newCommand = String.join("，",remaining);
        boolean hasNewOperation = com.ai.guide.domain.language.IntentLanguagePolicy.hasAdjustmentAction(newCommand);
        return new CancellationDirective(true,hasNewOperation?newCommand:"");
    }
    public static boolean isCancellationRequest(String message) {
        var directive = cancellationDirective(message);
        return directive.cancelled() && directive.remaining().isBlank();
    }

    /** Execute lifecycle work under the single per-session apply/cancel lock also used by legacy entry points. */
    public <T> T withSessionApplyLock(String sessionId, Supplier<T> operation) {
        return conversationState.withSessionLock(sessionId==null?"":sessionId,operation);
    }

    public StoredSession refreshAuthorizedSessionForLifecycle(StoredSession authorizedSession, String token) {
        return authorizedSession == null ? null : refreshAuthorizedSession(authorizedSession, token);
    }

    public void resetAuthorizedContinuation(StoredSession currentSession) {
        if (currentSession != null) continuations.reset(currentSession.sessionId());
    }

    public record PreviewGenerationTicket(String sessionId, String generation) {}

    private record PreviewAdmission(StoredSession session, PreviewGenerationTicket ticket,
                                    PlannerService.ServiceResult failure) {}

    /** Must be called by the application while holding this service's shared session lifecycle lock. */
    public PreviewGenerationTicket beginPreviewForApplication(StoredSession authorizedSession) {
        if (authorizedSession == null) return null;
        return new PreviewGenerationTicket(authorizedSession.sessionId(), continuations.begin(authorizedSession.sessionId()));
    }

    public Map<String, Object> cancelRevisionConflictBody(StoredSession currentSession) {
        return conflict(currentSession).body();
    }

    public record ApplyReplayDecision(boolean replayed, boolean identityConflict,
                                      AdjustmentLifecycleExecution result,
                                      String replayKey, String requestFingerprint) {}

    public ApplyReplayDecision lookupApplyReplay(StoredSession session, ApplyAdjustmentRequestDto request) {
        String proposalId = request == null ? "" : safe(request.getProposalId());
        String optionId = normalizedOptionId(request);
        int revision = request == null ? session.currentVersion() : request.getBaseRevision();
        boolean force = request != null && request.isForceApply();
        String keyValue = request == null ? "" : safe(request.getIdempotencyKey());
        String replayKey = session.sessionId() + "|" + keyValue;
        String fingerprint = proposalId + "|" + optionId + "|" + revision + "|force=" + force;
        if (keyValue.isBlank()) return new ApplyReplayDecision(false, false, null, replayKey, fingerprint);
        ApplyReplay replay = loadReplay(session.sessionId(),replayKey);
        if (replay == null) return new ApplyReplayDecision(false, false, null, replayKey, fingerprint);
        if (!replay.requestFingerprint().equals(fingerprint)) {
            return new ApplyReplayDecision(false, true,
                    new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.CONFLICT,
                            error(409, "同一幂等键不能用于不同的调整确认请求。", session).body()), replayKey, fingerprint);
        }
        PlannerService.ServiceResult stored = replay.result();
        return new ApplyReplayDecision(true, false,
                new AdjustmentLifecycleExecution(toLifecycleOutcome(stored.status()), stored.body()), replayKey, fingerprint);
    }

    public void recordApplyReplay(StoredSession session, ApplyAdjustmentRequestDto request,
                                  String replayKey, String fingerprint,
                                  AdjustmentLifecycleExecution result) {
        if (session == null || request == null || safe(request.getIdempotencyKey()).isBlank()) return;
        saveReplay(session.sessionId(),replayKey, new ApplyReplay(fingerprint,
                new PlannerService.ServiceResult(toHttpStatus(result.outcome()), result.body())));
    }

    public record ApplyPreparation(AdjustmentLifecycleExecution.Outcome rejection,
                                   Map<String, Object> rejectionBody,
                                   StoredSession session,
                                   PlanAdjustmentProposal proposal,
                                   Map<String, Object> proposedTrip,
                                   String optionId,
                                   int expectedRevision,
                                   boolean forceApply,
                                   String replayKey,
                                   String requestFingerprint,
                                   long startedNanos) {
        public boolean rejected() { return rejection != null; }
    }

    public ApplyPreparation prepareApply(StoredSession session, ApplyAdjustmentRequestDto request,
                                         ApplyReplayDecision replay) {
        long startedNanos = System.nanoTime();
        String sessionId = session.sessionId();
        String proposalId = request == null ? "" : safe(request.getProposalId());
        String optionId = normalizedOptionId(request);
        int requestedRevision = request == null ? session.currentVersion() : request.getBaseRevision();
        boolean forceApply = request != null && request.isForceApply();
        if (replay != null && replay.identityConflict())
            return rejectedApply(replay.result(), session, optionId, requestedRevision, forceApply, replay);
        if (requestedRevision != session.currentVersion()) {
            if (metricsLogger != null) metricsLogger.logRevisionConflict(sessionId, requestedRevision, session.currentVersion());
            return rejectedApply(conflict(session), session, optionId, requestedRevision, forceApply, replay);
        }
        PlanAdjustmentProposal proposal = proposalStore.get(proposalId);
        if (proposal == null) return rejectedApply(error(400, "调整提案不存在或已过期，请重新发起预览。", session), session, optionId, requestedRevision, forceApply, replay);
        if (!session.sessionId().equals(proposal.getSessionId()))
            return rejectedApply(error(400, "调整提案不属于当前规划会话。", session), session, optionId, requestedRevision, forceApply, replay);
        if (proposal.isExpired()) {
            proposalStore.remove(proposalId);
            return rejectedApply(error(400, "调整提案已过期，请重新发起预览。", session), session, optionId, requestedRevision, forceApply, replay);
        }
        if (proposal.getBaseRevision() != requestedRevision || proposal.getBaseRevision() != session.currentVersion()) {
            if (metricsLogger != null) metricsLogger.logRevisionConflict(sessionId, proposal.getBaseRevision(), session.currentVersion());
            return rejectedApply(conflict(session), session, optionId, requestedRevision, forceApply, replay);
        }
        if (!continuations.validProposal(session.sessionId(), proposal.getConversationEpoch(), proposal.getCreatedAt()))
            return rejectedApply(error(409, "这份提案属于已结束的对话，请重新预览。", session), session, optionId, requestedRevision, forceApply, replay);
        Map<String, Object> proposedTrip;
        if (proposal.getOptionPlans() != null && !proposal.getOptionPlans().isEmpty()) {
            if (!proposal.getOptionPlans().containsKey(optionId))
                return rejectedApply(error(400, "指定的选项序号（" + optionId + "）不存在，请选择有效的候选方案选项。", session), session, optionId, requestedRevision, forceApply, replay);
            proposedTrip = deepCopy(proposal.getOptionPlans().get(optionId), objectMapper);
        } else {
            proposedTrip = deepCopy(proposal.getProposedTrip(), objectMapper);
        }
        if (proposal.getIntent() != null && proposal.getIntent().getType() == ConversationIntentType.ADD_STOP
                && (proposal.getRequiredStopIdsByOption() == null
                || proposal.getRequiredStopIdsByOption().getOrDefault(optionId, List.of()).isEmpty()))
            return rejectedApply(error(400, "这份加站预览缺少目标校验信息，请重新生成预览后应用。", session), session, optionId, requestedRevision, forceApply, replay);
        var selectedStatus = optionStatus(session.trip(), proposedTrip, proposal.getIntent(), extractConstraints(session), proposal.getReasonCodes(), optionId,
                proposal.getRequiredStopIdsByOption() == null ? List.of() : proposal.getRequiredStopIdsByOption().getOrDefault(optionId, List.of()));
        if (!Boolean.TRUE.equals(selectedStatus.get("canApply")))
            return rejectedApply(error(400, "这个候选没有有效改动，或违反必须保留的范围、开放条件，不能应用；请选择其他候选。", session), session, optionId, requestedRevision, forceApply, replay);
        if (!Boolean.TRUE.equals(selectedStatus.get("feasible")) && !forceApply)
            return rejectedApply(error(400, "这个候选有不可行约束冲突提示，请查看后决定是否继续。", session), session, optionId, requestedRevision, forceApply, replay);
        return new ApplyPreparation(null, Map.of(), session, proposal, proposedTrip, optionId, requestedRevision,
                forceApply, replay == null ? session.sessionId() + "|" + safe(request == null ? null : request.getIdempotencyKey()) : replay.replayKey(),
                replay == null ? fingerprint(request, session) : replay.requestFingerprint(), startedNanos);
    }

    private ApplyPreparation rejectedApply(PlannerService.ServiceResult result, StoredSession session,
                                          String optionId, int revision, boolean force,
                                          ApplyReplayDecision replay) {
        return rejectedApply(lifecycle(result), session, optionId, revision, force, replay);
    }

    private ApplyPreparation rejectedApply(AdjustmentLifecycleExecution result, StoredSession session,
                                          String optionId, int revision, boolean force,
                                          ApplyReplayDecision replay) {
        return new ApplyPreparation(result.outcome(), result.body(), session, null, null, optionId, revision, force,
                replay == null ? session.sessionId() + "|" : replay.replayKey(),
                replay == null ? "" : replay.requestFingerprint(), System.nanoTime());
    }

    public record ApplyCommit(AdjustmentLifecycleExecution.Outcome outcome, Map<String, Object> rejectionBody,
                              ApplyPreparation preparation, StoredSession committedSession, int nextVersion) {
        public boolean committed() { return outcome == AdjustmentLifecycleExecution.Outcome.SUCCESS && committedSession != null; }
    }

    public ApplyCommit commitPreparedApply(ApplyPreparation preparation) {
        if (preparation == null || preparation.rejected()) throw new IllegalArgumentException("valid apply preparation required");
        StoredSession session = preparation.session();
        PlanAdjustmentProposal proposal = preparation.proposal();
        int nextVersion = session.currentVersion() + 1;
        finalizeTrip(preparation.proposedTrip(), nextVersion, "AI 局部调整应用",
                proposal.getIntent() == null ? "用户确认应用" : proposal.getIntent().getRawMessage(), proposal.getChangedSegments());
        MutationResult result = repository.update(session, preparation.proposedTrip(), preparation.expectedRevision(),
                proposal.getIntent() != null ? proposal.getIntent().getType().name() : "ADJUSTMENT_APPLIED",
                "AI 局部调整应用", proposal.getIntent() == null ? "" : proposal.getIntent().getRawMessage(),
                proposal.getChangedSegments());
        if ("CONFLICT".equals(result.status())) {
            if (metricsLogger != null) metricsLogger.logRevisionConflict(session.sessionId(), preparation.expectedRevision(),
                    result.session() == null ? session.currentVersion() : result.session().currentVersion());
            return new ApplyCommit(AdjustmentLifecycleExecution.Outcome.CONFLICT, conflict(result.session()).body(), preparation, null, 0);
        }
        return new ApplyCommit(AdjustmentLifecycleExecution.Outcome.SUCCESS, Map.of(), preparation, result.session(), nextVersion);
    }

    public record ApplyCompletion(int currentVersion, Map<String, Object> appliedPreferences,
                                  Map<String, Object> pendingIntent, String nextActionPrompt) {}

    public ApplyCompletion completeCommittedApply(ApplyCommit commit) {
        if (commit == null || !commit.committed()) throw new IllegalArgumentException("committed apply required");
        ApplyPreparation preparation = commit.preparation();
        StoredSession session = preparation.session();
        PlanAdjustmentProposal proposal = preparation.proposal();
        removeProposalAfterCommit(session.sessionId(),proposal.getProposalId());
        continuations.afterApply(session.sessionId(), commit.committedSession().currentVersion());
        long latencyMs = (System.nanoTime() - preparation.startedNanos()) / 1_000_000L;
        if (metricsLogger != null) metricsLogger.logProposalApplied(session.sessionId(), proposal.getProposalId(), commit.nextVersion(), latencyMs);
        var continuation = continuations.pending(session.sessionId());
        Map<String, Object> pending = continuation == null ? Map.of() : Map.of("intentId", continuation.intentId(),
                "baseRevision", continuation.baseRevision(), "expiresAt", continuation.expiresAt(), "intent", continuation.intent().asMap());
        return new ApplyCompletion(commit.nextVersion(), commit.committedSession().appliedPreferences().asMap(), pending,
                continuation == null ? "" : continuation.intent().getClarificationQuestion());
    }

    private void removeProposalAfterCommit(String sessionId,String proposalId) {
        Runnable cleanup = () -> {
            try { proposalStore.remove(proposalId); }
            catch (RuntimeException failure) { log.warn("[PLANNER_PROPOSAL_CLEANUP] committed apply cleanup pending; sessionId={} proposalId={}",sessionId,proposalId,failure); }
        };
        conversationState.afterCommit(cleanup);
    }

    private String normalizedOptionId(ApplyAdjustmentRequestDto request) {
        String optionId = request == null ? "option-1" : safe(request.getOptionId());
        return optionId.isBlank() ? "option-1" : optionId;
    }

    private String fingerprint(ApplyAdjustmentRequestDto request, StoredSession session) {
        String proposalId = request == null ? "" : safe(request.getProposalId());
        int revision = request == null ? session.currentVersion() : request.getBaseRevision();
        return proposalId + "|" + normalizedOptionId(request) + "|" + revision + "|force=" + (request != null && request.isForceApply());
    }

    private static AdjustmentLifecycleExecution.Outcome toLifecycleOutcome(int status) {
        return switch (status) {
            case 200 -> AdjustmentLifecycleExecution.Outcome.SUCCESS;
            case 400 -> AdjustmentLifecycleExecution.Outcome.BAD_REQUEST;
            case 401 -> AdjustmentLifecycleExecution.Outcome.UNAUTHORIZED;
            case 403 -> AdjustmentLifecycleExecution.Outcome.FORBIDDEN;
            case 404 -> AdjustmentLifecycleExecution.Outcome.NOT_FOUND;
            case 409 -> AdjustmentLifecycleExecution.Outcome.CONFLICT;
            default -> AdjustmentLifecycleExecution.Outcome.INTERNAL_ERROR;
        };
    }

    private static int toHttpStatus(AdjustmentLifecycleExecution.Outcome outcome) {
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

    /** Shared lifecycle authority used by the application use case and legacy preview path. */
    public AdjustmentLifecycleExecution cancelOrResetForApplication(StoredSession authorizedSession,
                                                                    PlanConversationRequestDto request,
                                                                    boolean cancelled) {
        String sessionId = authorizedSession == null ? "" : authorizedSession.sessionId();
        return withSessionApplyLock(sessionId,() -> {
            StoredSession latest = authorizedSession == null ? null
                    : refreshAuthorizedSession(authorizedSession, request == null ? "" : request.getSessionAccessToken());
            if (latest == null) return lifecycle(error(404, "规划会话不存在或无权访问。", null));
            if (request != null && request.getBaseRevision() != latest.currentVersion()) return lifecycle(conflict(latest));
            continuations.reset(latest.sessionId());
            return new AdjustmentLifecycleExecution(AdjustmentLifecycleExecution.Outcome.SUCCESS,
                    Map.of("ok", true, "reset", true, "cancelled", cancelled,
                            "modified", false, "message", cancelled ? "已取消本轮调整，当前行程未改变。" : "已结束上一轮对话上下文。"));
        });
    }

    private PlannerService.ServiceResult resetAdjustmentRound(StoredSession session, PlanConversationRequestDto request, boolean cancelled) {
        AdjustmentLifecycleExecution execution = cancelOrResetForApplication(session, request, cancelled);
        int status = switch (execution.outcome()) {
            case SUCCESS -> 200;
            case BAD_REQUEST -> 400;
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case INTERNAL_ERROR -> 500;
        };
        return new PlannerService.ServiceResult(status, execution.body());
    }

    private AdjustmentLifecycleExecution lifecycle(PlannerService.ServiceResult result) {
        AdjustmentLifecycleExecution.Outcome outcome = switch (result.status()) {
            case 200 -> AdjustmentLifecycleExecution.Outcome.SUCCESS;
            case 400 -> AdjustmentLifecycleExecution.Outcome.BAD_REQUEST;
            case 401 -> AdjustmentLifecycleExecution.Outcome.UNAUTHORIZED;
            case 403 -> AdjustmentLifecycleExecution.Outcome.FORBIDDEN;
            case 404 -> AdjustmentLifecycleExecution.Outcome.NOT_FOUND;
            case 409 -> AdjustmentLifecycleExecution.Outcome.CONFLICT;
            default -> AdjustmentLifecycleExecution.Outcome.INTERNAL_ERROR;
        };
        return new AdjustmentLifecycleExecution(outcome, result.body());
    }

    public PlannerService.ServiceResult apply(String sessionId, ApplyAdjustmentRequestDto request) {
        return withSessionApplyLock(sessionId,() -> doApply(sessionId,request));
    }

    private PlannerService.ServiceResult doApply(String sessionId, ApplyAdjustmentRequestDto request) {
        long startNanos = System.nanoTime();
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);

        return doApply(session, request, startNanos);
    }

    private PlannerService.ServiceResult doApply(StoredSession session, ApplyAdjustmentRequestDto request) {
        return doApply(session, request, System.nanoTime());
    }

    private PlannerService.ServiceResult doApply(StoredSession session, ApplyAdjustmentRequestDto request, long startNanos) {
        String sessionId = session.sessionId();

        String proposalId = request == null ? "" : safe(request.getProposalId());
        String optionId = request == null ? "option-1" : safe(request.getOptionId());
        if (optionId.isBlank()) optionId = "option-1";
        int requestedRevision = request == null ? session.currentVersion() : request.getBaseRevision();
        boolean forceApply = request != null && request.isForceApply();
        String idempotencyKey = request == null ? "" : safe(request.getIdempotencyKey());
        String replayKey = session.sessionId() + "|" + idempotencyKey;
        String requestFingerprint = proposalId + "|" + optionId + "|" + requestedRevision + "|force=" + forceApply;
        if (!idempotencyKey.isBlank()) {
            ApplyReplay replay = loadReplay(session.sessionId(),replayKey);
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

        removeProposalAfterCommit(sessionId,proposalId);
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
        PlannerVersionMetadata meta = PlannerVersionMetadata.fromTrip(proposedTrip, true);
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
            saveReplay(session.sessionId(),replayKey, new ApplyReplay(requestFingerprint, success));
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

        if (PendingIntentContinuations.requestsDay(intent) && intent.getDayNumber() == null) return;

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
        if (intent.getDayNumber() != null) {
            StopLocation selectedLocation = selectedStop == null ? null : locateStop(trip, selectedStop);
            if (selectedLocation == null || !intent.getDayNumber().equals(number(selectedLocation.day().get("day"))))
                selectedStop = null;
            String targetId = safeText(intent.getTargetStopId());
            StopLocation targetLocation = targetId == null ? null : locateStop(trip, targetId);
            if (targetId != null && (targetLocation == null
                    || !intent.getDayNumber().equals(number(targetLocation.day().get("day")))))
                intent.setTargetStopId(null);
        }
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

        boolean isMealSlotReference = diningCategoryGrounder.hasMealSlotReference(intent.getTargetStopReference());
        if ((intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) && !isMealSlotReference) {
            List<Map<?, ?>> scopedStops = new ArrayList<>();
            if (intent.getDayNumber() == null) scopedStops.addAll(allStops(trip));
            else scopedStops.addAll(dayStops(findDay(trip, intent.getDayNumber())));
            List<Map<?, ?>> matchingStops = new ArrayList<>();
            for (Map<?, ?> stop : scopedStops) {
                Object stopName = stop.get("name");
                String name = stopName == null ? null : safeText(String.valueOf(stopName));
                if (name == null) continue;
                String stopReference = safeText(intent.getTargetStopReference());
                boolean matches = stopReference != null ? name.contains(stopReference) || stopReference.contains(name)
                        : rawText.contains(name);
                if (matches) matchingStops.add(stop);
            }
            String selectedCandidate = selectedStop;
            boolean requiresSingleStop = Set.of("REMOVE_STOP", "REPLACE_STOP", "SUGGEST_REPLACEMENTS")
                    .contains(intent.getOperation());
            if (requiresSingleStop && matchingStops.size() > 1 && selectedCandidate != null
                    && matchingStops.stream().anyMatch(stop -> selectedCandidate.equals(String.valueOf(stop.get("id"))))) {
                intent.setTargetStopId(selectedCandidate);
            } else if (requiresSingleStop && matchingStops.size() > 1) {
                String operation = intent.getOperation();
                intent.setType(ConversationIntentType.CLARIFICATION);
                intent.setOperation(operation);
                intent.setRequiresClarification(true);
                var missing = new ArrayList<>(intent.getMissingFields());
                if (!missing.contains("TARGET_STOP")) missing.add(0, "TARGET_STOP");
                intent.setMissingFields(missing);
                intent.setClarificationQuestion("当天有多个符合描述的站点，请点击选中具体卡片后继续。");
                return;
            }
            if (matchingStops.size() == 1) intent.setTargetStopId(String.valueOf(matchingStops.get(0).get("id")));
        }

        boolean isDiningIntent = isDiningIntent(intent);
        boolean targetStopMismatched = false;
        if (selectedStop != null) {
            StopLocation selectedLoc = locateStop(trip, selectedStop);
            if (selectedLoc != null && selectedLoc.stop() != null) {
                String selectedName = String.valueOf(selectedLoc.stop().getOrDefault("name", ""));
                String selectedTime = String.valueOf(selectedLoc.stop().getOrDefault("time", ""));
                boolean selectedIsDining = isDiningStop(selectedLoc.stop(), taxonomy);
                if (selectedIsDining && !isDiningIntent) {
                    targetStopMismatched = true;
                } else if (!selectedIsDining && isDiningIntent && !selectedName.isBlank() && !rawText.contains(selectedName)) {
                    targetStopMismatched = true;
                } else if (selectedIsDining && isDiningIntent) {
                    String explicitSlot = safeText(intent.getTimeSlot());
                    String requestedPlace = intent.getReplacementPlaceName();
                    boolean wantsBreakfast = explicitSlot != null ? "BREAKFAST".equals(explicitSlot)
                            : diningCategoryGrounder.matchesTaxonomyHint(rawText, "slotBreakfast")
                            || diningCategoryGrounder.matchesTaxonomyHint(requestedPlace, "slotBreakfast");
                    boolean wantsDinner = explicitSlot != null ? "DINNER".equals(explicitSlot) || "NIGHT_SNACK".equals(explicitSlot)
                            : diningCategoryGrounder.matchesTaxonomyHint(rawText, "slotDinner")
                            || diningCategoryGrounder.matchesTaxonomyHint(rawText, "slotNightSnack")
                            || diningCategoryGrounder.matchesTaxonomyHint(requestedPlace, "slotDinner");
                    boolean wantsLunch = explicitSlot != null ? "LUNCH".equals(explicitSlot)
                            : diningCategoryGrounder.matchesTaxonomyHint(rawText, "slotLunch")
                            || diningCategoryGrounder.matchesTaxonomyHint(requestedPlace, "slotLunch");

                    if (wantsLunch && (itineraryTimeEngine.isEveningSlot(selectedTime, taxonomy) || itineraryTimeEngine.isMorningSlot(selectedTime, taxonomy)
                            || diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotDinner") || diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotBreakfast"))) {
                        targetStopMismatched = true;
                    } else if (wantsDinner && (itineraryTimeEngine.isMorningSlot(selectedTime, taxonomy) || (!itineraryTimeEngine.isEveningSlot(selectedTime, taxonomy)
                            && diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotLunch")) || diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotBreakfast"))) {
                        targetStopMismatched = true;
                    } else if (wantsBreakfast && (itineraryTimeEngine.isEveningSlot(selectedTime, taxonomy)
                            || diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotDinner") || diningCategoryGrounder.matchesTaxonomyHint(selectedName, "slotLunch"))) {
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
                if (targetLoc != null && isDiningStop(targetLoc.stop(), taxonomy)) {
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
                        if (isDiningStop(s, taxonomy)) {
                            String time = String.valueOf(s.getOrDefault("time", ""));
                            String name = String.valueOf(s.getOrDefault("name", ""));
                            if (isLunch && (itineraryTimeEngine.isEveningSlot(time, taxonomy) || itineraryTimeEngine.isMorningSlot(time, taxonomy)
                                    || diningCategoryGrounder.matchesTaxonomyHint(name, "slotDinner") || diningCategoryGrounder.matchesTaxonomyHint(name, "slotBreakfast"))) {
                                continue;
                            }
                            if (isDinner && (itineraryTimeEngine.isMorningSlot(time, taxonomy) || (!itineraryTimeEngine.isEveningSlot(time, taxonomy)
                                    && diningCategoryGrounder.matchesTaxonomyHint(name, "slotLunch")) || diningCategoryGrounder.matchesTaxonomyHint(name, "slotBreakfast"))) {
                                continue;
                            }
                            if (isBreakfast && (itineraryTimeEngine.isEveningSlot(time, taxonomy)
                                    || diningCategoryGrounder.matchesTaxonomyHint(name, "slotDinner") || diningCategoryGrounder.matchesTaxonomyHint(name, "slotLunch"))) {
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
                if (targetLoc != null && !isDiningStop(targetLoc.stop(), taxonomy)) {
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
                    if (ref != null && (diningCategoryGrounder.matchesTaxonomyHint(ref, "mealRelativeAfterLunch")
                            || diningCategoryGrounder.matchesTaxonomyHint(ref, "mealRelativeBeforeLunch")
                            || ref.contains("后面的那个") || ref.contains("前面的那个") || ref.contains("后一个") || ref.contains("前一个")
                            || ref.contains("最后") || ref.contains("第一"))) {
                        int dayNum = intent.getDayNumber() != null ? intent.getDayNumber() : activeDay;
                        Map<String, Object> targetDayMap = findDay(trip, dayNum);
                        if (targetDayMap != null) {
                            List<Map<String, Object>> stops = dayStops(targetDayMap);
                            if (diningCategoryGrounder.matchesTaxonomyHint(ref, "mealRelativeAfterLunch")
                                    || ref.contains("后面的那个") || ref.contains("后一个")) {
                                for (int i = 0; i < stops.size(); i++) {
                                    if (isDiningStop(stops.get(i), taxonomy) || diningCategoryGrounder.matchesTaxonomyHint(
                                            String.valueOf(stops.get(i).getOrDefault("name", "")), "slotLunch")) {
                                        if (i + 1 < stops.size()) {
                                            intent.setTargetStopId(String.valueOf(stops.get(i + 1).get("id")));
                                            break;
                                        }
                                    }
                                }
                            } else if (diningCategoryGrounder.matchesTaxonomyHint(ref, "mealRelativeBeforeLunch")
                                    || ref.contains("前面的那个") || ref.contains("前一个")) {
                                for (int i = 0; i < stops.size(); i++) {
                                    if (isDiningStop(stops.get(i), taxonomy) || diningCategoryGrounder.matchesTaxonomyHint(
                                            String.valueOf(stops.get(i).getOrDefault("name", "")), "slotLunch")) {
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
        return diningCategoryGrounder.hasDiningKeywords(raw)
                || (refPlace != null && diningCategoryGrounder.hasDiningKeywords(refPlace));
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
        if (matchesText(reference, attraction.getName()) || matchesText(reference, attraction.getDisplayName())) return true;
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
        return TravelConstraints.fromSnapshot(objectMapper, session.constraints());
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

    private StoredSession refreshAuthorizedSession(StoredSession authorizedSession, String token) {
        if (authorizedSession == null) return null;
        return repository.find(authorizedSession.sessionId(), authorizedSession.ownerType(),
                authorizedSession.ownerId(), token == null ? "" : token);
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

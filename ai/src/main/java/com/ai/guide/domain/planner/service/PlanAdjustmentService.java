package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanAdjustmentPreviewDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final Map<String, ApplyReplay> applyReplays = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, ApplyReplay> eldest) {
                    return size() > 256;
                }
            });
    private final java.util.concurrent.ConcurrentHashMap<String, Object> sessionApplyLocks = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired(required = false)
    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

    private final AttractionAliasMatcher aliasMatcher;

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
        this.aliasMatcher = new AttractionAliasMatcher(attractionService);
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
        StoredSession session = findAuthorized(sessionId, request == null ? "" : request.getSessionAccessToken());
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
        if (totalDays > 0) {
            activeDay = Math.min(totalDays, Math.max(1, activeDay));
        }

        // 1. Day resolution
        if (intent.getType() == ConversationIntentType.ADD_STOP) {
            if (intent.getDayNumber() != null) {
                if (intent.getDayNumber() == totalDays + 1) {
                    // 允许顺延扩日：在单日或多日行程最后一天顺延追加新一天行程
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

        // 餐饮微调与推荐：若未选中特定卡片或选中的卡片餐次与意图不符，自动定位当天对应时段餐饮卡片；若没有早餐卡片，标记早点插入；若没有餐饮卡片，定位晚间收尾站点
        boolean isDiningIntent = (intent.getPreferences() != null && intent.getPreferences().contains("FOOD"))
                || rawText.matches(".*(餐饮|餐厅|美食|火锅|江湖菜|泉水鸡|正餐|晚餐|午餐|早餐|早点|早饭|小面|面馆|面条|抄手|吃辣|辣味).*")
                || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(美食|火锅|江湖菜|泉水鸡|餐厅|小面|面条|早餐|早点|早饭).*"));
        boolean targetStopMismatched = false;
        if (isDiningIntent) {
            Map<String, Object> day = findDay(trip, intent.getDayNumber() != null ? intent.getDayNumber() : activeDay);
            if (day != null) {
                List<Map<String, Object>> stops = dayStops(day);
                boolean wantsBreakfast = rawText.matches(".*(早|早点|早餐|早饭).*")
                        || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(早|早点|早餐).*"));
                boolean wantsDinner = rawText.matches(".*(晚|晚餐|晚上|夜间|夜景|收尾|夜宵).*")
                        || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(晚|晚餐).*"));
                boolean wantsLunch = rawText.matches(".*(午|午餐|中午|午饭).*")
                        || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(午|午餐).*"));

                if (intent.getTargetStopId() != null && !intent.getTargetStopId().isBlank()) {
                    StopLocation curLoc = locateStop(trip, intent.getTargetStopId());
                    if (curLoc != null) {
                        Map<String, Object> curStop = curLoc.stop();
                        String time = String.valueOf(curStop.getOrDefault("time", ""));
                        String name = String.valueOf(curStop.getOrDefault("name", ""));
                        if (wantsLunch && (isEveningSlot(time) || isMorningSlot(time) || name.contains("晚餐") || name.contains("早餐"))) {
                            targetStopMismatched = true;
                        } else if (wantsDinner && (isMorningSlot(time) || (!isEveningSlot(time) && name.contains("午餐")) || name.contains("早餐"))) {
                            targetStopMismatched = true;
                        } else if (wantsBreakfast && (isEveningSlot(time) || name.contains("晚餐") || name.contains("午餐"))) {
                            targetStopMismatched = true;
                        }
                    }
                }

                if (targetStopMismatched) {
                    intent.setTargetStopId(null);
                }

                if (intent.getTargetStopId() == null || intent.getTargetStopId().isBlank()) {
                    if (wantsBreakfast) {
                        for (Map<String, Object> stop : stops) {
                            if (isDiningStop(stop) && (isMorningSlot(String.valueOf(stop.getOrDefault("time", "")))
                                    || String.valueOf(stop.getOrDefault("name", "")).contains("早餐")
                                    || String.valueOf(stop.getOrDefault("name", "")).contains("早点"))) {
                                intent.setTargetStopId(safe(stop.get("id")));
                                break;
                            }
                        }
                        if (intent.getTargetStopId() == null) {
                            intent.setTargetStopId("NEW_BREAKFAST_STOP");
                        }
                    } else if (wantsDinner) {
                        for (int i = stops.size() - 1; i >= 0; i--) {
                            Map<String, Object> stop = stops.get(i);
                            if (isDiningStop(stop) && (isEveningSlot(String.valueOf(stop.getOrDefault("time", "")))
                                    || String.valueOf(stop.getOrDefault("name", "")).contains("晚餐"))) {
                                intent.setTargetStopId(safe(stop.get("id")));
                                break;
                            }
                        }
                        if (intent.getTargetStopId() == null) {
                            // 当天无晚餐槽位，动态升维为加站，杜绝串换为午餐
                            intent.setType(ConversationIntentType.ADD_STOP);
                        }
                    } else if (wantsLunch) {
                        for (Map<String, Object> stop : stops) {
                            if (isDiningStop(stop) && !isEveningSlot(String.valueOf(stop.getOrDefault("time", "")))
                                    && !isMorningSlot(String.valueOf(stop.getOrDefault("time", "")))) {
                                intent.setTargetStopId(safe(stop.get("id")));
                                break;
                            }
                        }
                        if (intent.getTargetStopId() == null) {
                            // 当天无午餐槽位，动态升维为加站，杜绝串换为晚餐
                            intent.setType(ConversationIntentType.ADD_STOP);
                        }
                    }

                    // If still not resolved and not a specific meal slot, fallback to first dining stop
                    if (intent.getTargetStopId() == null && intent.getType() != ConversationIntentType.ADD_STOP
                            && !wantsLunch && !wantsDinner && !wantsBreakfast) {
                        for (Map<String, Object> stop : stops) {
                            if (isDiningStop(stop)) {
                                intent.setTargetStopId(safe(stop.get("id")));
                                break;
                            }
                        }
                    }
                    if (intent.getTargetStopId() == null && intent.getType() != ConversationIntentType.ADD_STOP) {
                        // 当天无任何餐饮卡片，动态升维为加站，严禁偷换末尾景点
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

        // Semantic Compatibility Guard (DEF-PLAN-02): 若意图为替换/推荐替换餐饮，但目标节点是景点且用户未明确提及该景点名称，自动升维为 ADD_STOP
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

    private PlannerService.ServiceResult withIntentMetadata(
            PlannerService.ServiceResult result,
            LlmIntentExtractor.LlmIntentExtractionResult extraction) {
        if (result == null || extraction == null) return result;
        Map<String, Object> body = new LinkedHashMap<>(result.body());
        body.putAll(extraction.metadata());
        return new PlannerService.ServiceResult(result.status(), body);
    }

    public PlannerService.ServiceResult apply(String sessionId, ApplyAdjustmentRequestDto request) {
        String lockKey = sessionId == null ? "" : sessionId;
        Object sessionLock = sessionApplyLocks.computeIfAbsent(lockKey, k -> new Object());
        synchronized (sessionLock) {
            try {
                return doApply(sessionId, request);
            } finally {
                // 安全单点清理当前完成会话的锁映射，严禁全局全量 clear 防止并发互斥逃逸
                sessionApplyLocks.remove(lockKey, sessionLock);
            }
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
        if (!proposal.isFeasible() && !forceApply) {
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

    private PlannerService.ServiceResult generateProposalPreview(StoredSession session,
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
                boolean isBreakfastInsertion = "NEW_BREAKFAST_STOP".equals(targetId)
                        || ((targetId == null || targetId.isBlank()) && (
                                (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().contains("早"))
                                || (intent.getRawMessage() != null && intent.getRawMessage().matches(".*(早|早餐|早点).*"))
                        ));

                if (isBreakfastInsertion) {
                    int dayNum = intent.getDayNumber() != null ? intent.getDayNumber() : (context.getActiveDay() != null ? context.getActiveDay() : 1);
                    Map<String, Object> day = findDay(draftTrip, dayNum);
                    if (day == null) {
                        return error(400, "目标日期不存在。", session);
                    }
                    List<Map<String, Object>> stops = dayStops(day);
                    if (stops.isEmpty()) {
                        return error(400, "当前日期没有任何景点，无法添加早餐。", session);
                    }
                    Map<String, Object> firstStop = stops.get(0);
                    Attraction anchor = resolveStopAnchor(firstStop);
                    String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank())
                            ? anchor.getDistrict()
                            : extractDistrictFromStop(firstStop);
                    String effectiveStopId = "day" + dayNum + "-breakfast";
                    String slotTime = "08:00";
                    String requestedFlavor = "重庆小面";

                    List<AttractionDiningKnowledge.DiningOption> diningCandidates =
                            AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                                    district, "", requestedFlavor, "BREAKFAST", anchor, constraints);

                    adjustmentDiagnostics.put("replacementMode", "DINING");
                    adjustmentDiagnostics.put("sourceStopName", "晨间早餐");
                    adjustmentDiagnostics.put("requestedFlavor", requestedFlavor);
                    adjustmentDiagnostics.put("forceApplicable", true);

                    for (int i = 0; i < diningCandidates.size(); i++) {
                        AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
                        String optId = "option-" + (i + 1);
                        Map<String, Object> optionDraft = deepCopy(sourceTrip);
                        Map<String, Object> optDay = findDay(optionDraft, dayNum);
                        if (optDay == null) continue;
                        List<Map<String, Object>> optStops = dayStops(optDay);

                        Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(
                                dining, effectiveStopId, slotTime, district);
                        optStops.add(0, newDiningStop);
                        optionPlans.put(optId, optionDraft);

                        Map<String, Object> cand = new LinkedHashMap<>();
                        cand.put("optionId", optId);
                        cand.put("venueId", dining.id());
                        cand.put("name", dining.name());
                        cand.put("requestedName", dining.name());
                        cand.put("directReplacement", false);
                        cand.put("summary", dining.summary());
                        cand.put("specialtyDish", dining.specialtyDish());
                        cand.put("costSummary", dining.averageCost());
                        cand.put("duration", dining.duration());
                        cand.put("district", district);
                        cand.put("walkDifficulty", "低");
                        cand.put("walk", dining.distanceFromAttraction());
                        cand.put("type", "DINING");
                        cand.put("isDining", true);
                        cand.put("fit", dining.recommendationReason());
                        candidateReplacements.add(cand);
                    }

                    if (optionPlans.isEmpty()) {
                        feasible = false;
                        reasonCodes.add("NO_FEASIBLE_REPLACEMENT");
                        alternatives.addAll(List.of("换周边其他早点", "保持原行程安排"));
                    } else {
                        draftTrip = optionPlans.get("option-1");
                        changedSegments.add(effectiveStopId);
                    }
                    break;
                }

                StopLocation target = locateStop(draftTrip, targetId);
                if (target == null && targetId != null && !targetId.isBlank()) {
                    target = locateStopByVenue(draftTrip, targetId);
                }
                if (target == null && isDiningIntent(intent)) {
                    int dayNum = intent.getDayNumber() != null ? intent.getDayNumber() : (context.getActiveDay() != null ? context.getActiveDay() : 1);
                    Map<String, Object> day = findDay(draftTrip, dayNum);
                    if (day != null) {
                        List<Map<String, Object>> stops = dayStops(day);
                        String rawMsg = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), "");
                        boolean wantsLunch = rawMsg.matches(".*(午|午餐|中午|午饭).*");
                        boolean wantsDinner = rawMsg.matches(".*(晚|晚餐|晚上|晚饭).*");
                        boolean wantsBreakfast = rawMsg.matches(".*(早|早餐|早点|早饭).*");
                        for (int i = 0; i < stops.size(); i++) {
                            Map<String, Object> s = stops.get(i);
                            if (isDiningStop(s)) {
                                String time = String.valueOf(s.getOrDefault("time", ""));
                                String name = String.valueOf(s.getOrDefault("name", ""));
                                if (wantsLunch && (isEveningSlot(time) || isMorningSlot(time) || name.contains("晚餐") || name.contains("早餐"))) {
                                    continue;
                                }
                                if (wantsDinner && (isMorningSlot(time) || (!isEveningSlot(time) && name.contains("午餐")) || name.contains("早餐"))) {
                                    continue;
                                }
                                if (wantsBreakfast && (isEveningSlot(time) || name.contains("晚餐") || name.contains("午餐"))) {
                                    continue;
                                }
                                target = new StopLocation(day, stops, i, s);
                                break;
                            }
                        }
                    }
                }
                if (target == null) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("requiresClarification", true);
                    body.put("clarificationQuestion", "未在当前行程中找到目标景点，请重新选择卡片。");
                    return new PlannerService.ServiceResult(200, body);
                }
                String effectiveStopId = String.valueOf(target.stop().get("id"));
                if (isDiningStop(target.stop())) {
                    String rawMsg = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), "");
                    String requestedFlavor = "地道江湖菜";
                    if (rawMsg.matches(".*(火锅|九宫格|老火锅|串串).*")) {
                        requestedFlavor = "重庆火锅";
                    } else if (rawMsg.matches(".*(小面|面馆|面条|豌杂).*")) {
                        requestedFlavor = "重庆小面";
                    } else if (rawMsg.matches(".*(早|早餐|早点|早饭).*")) {
                        requestedFlavor = "重庆小面";
                    } else if (rawMsg.matches(".*(小吃|点心|抄手).*")) {
                        requestedFlavor = "街头小吃";
                    } else if (rawMsg.matches(".*(清淡|不辣|汤锅|炖鸡|老鸭汤).*")) {
                        requestedFlavor = "清淡不辣";
                    } else if (rawMsg.matches(".*(茶|咖啡|小憩|歇脚|观景茶|甜点|甜食|糖水|冰粉|凉糕|糍粑).*")) {
                        requestedFlavor = "休闲茶歇";
                    } else if (rawMsg.matches(".*(江湖菜|川菜|家常菜|中餐|大排档|辣子鸡|泉水鸡|毛血旺|酸菜鱼|烤鱼).*")) {
                        requestedFlavor = "地道江湖菜";
                    } else if (constraints.getDietPreference() != null
                            && !constraints.getDietPreference().isBlank()
                            && !"未提供".equals(constraints.getDietPreference().trim())) {
                        requestedFlavor = constraints.getDietPreference();
                    } else {
                        requestedFlavor = "特色美食";
                    }

                    Attraction anchor = null;
                    int targetIdx = target.index();
                    for (int offset = 1; offset < target.stops().size(); offset++) {
                        int beforeIdx = targetIdx - offset;
                        if (beforeIdx >= 0 && !isDiningStop(target.stops().get(beforeIdx))) {
                            anchor = resolveStopAnchor(target.stops().get(beforeIdx));
                            if (anchor != null) break;
                        }
                        int afterIdx = targetIdx + offset;
                        if (afterIdx < target.stops().size() && !isDiningStop(target.stops().get(afterIdx))) {
                            anchor = resolveStopAnchor(target.stops().get(afterIdx));
                            if (anchor != null) break;
                        }
                    }
                    if (anchor == null) {
                        anchor = resolveStopAnchor(target.stop());
                    }

                    String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank() && !"待确认".equals(anchor.getDistrict()))
                            ? anchor.getDistrict()
                            : extractDistrictFromStop(target.stop());
                    String slotTime = String.valueOf(target.stop().getOrDefault("time", "18:00"));
                    String slotType = "LUNCH";
                    if (rawMsg.matches(".*(午|午餐|中午|午饭).*") || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(午|午餐).*"))) {
                        slotType = "LUNCH";
                    } else if (rawMsg.matches(".*(晚|晚餐|晚上|晚饭).*") || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(晚|晚餐).*"))) {
                        slotType = "DINNER";
                    } else if (rawMsg.matches(".*(早|早餐|早点).*") || (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().matches(".*(早|早餐).*"))) {
                        slotType = "BREAKFAST";
                    } else if (rawMsg.matches(".*(宵夜|夜宵).*")) {
                        slotType = "NIGHT_SNACK";
                    } else if ("休闲茶歇".equals(requestedFlavor)) {
                        slotType = "TEA_BREAK";
                    } else if (isEveningSlot(slotTime) || String.valueOf(target.stop().get("name")).contains("晚餐")) {
                        slotType = "DINNER";
                    } else if (isMorningSlot(slotTime) || String.valueOf(target.stop().get("name")).contains("早餐")) {
                        slotType = "BREAKFAST";
                    }

                    String directDiningName = intent.getReplacementPlaceName();
                    if ((directDiningName == null || directDiningName.isBlank()) && rawMsg != null) {
                        Matcher bm = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(rawMsg);
                        if (bm.find()) {
                            directDiningName = bm.group(1).trim();
                        } else {
                            Matcher rm = Pattern.compile("(?:改成|换成|替换为|改为|换为|换到|改到)\\s*([^\\s，,。！!？?；;]+)").matcher(rawMsg);
                            if (rm.find()) {
                                String cand = rm.group(1).trim();
                                if (cand.matches(".*[（(].*[）)]?$") && !cand.matches(".*[（(][^）)]*店[）)]$")) {
                                    cand = cand.replaceFirst("[（(][^）)]*[）)]?$", "").trim();
                                }
                                if (!cand.isBlank()) {
                                    directDiningName = cand;
                                }
                            }
                        }
                    }
                    boolean isGenericDiningPhrase = isGenericDiningCraving(directDiningName);
                    boolean isDirectDining = !isGenericDiningPhrase;

                    List<AttractionDiningKnowledge.DiningOption> diningCandidates = new ArrayList<>();
                    if (isDirectDining) {
                        String slotPrefix = "BREAKFAST".equalsIgnoreCase(slotType) ? "【早餐推荐】"
                                : ("NIGHT_SNACK".equalsIgnoreCase(slotType) ? "【宵夜推荐】"
                                : ("TEA_BREAK".equalsIgnoreCase(slotType) ? "【特色茶歇】"
                                : ("DINNER".equalsIgnoreCase(slotType) ? "【晚餐推荐】" : "【午餐推荐】")));
                        String directFullName = directDiningName.startsWith("【") ? directDiningName : slotPrefix + directDiningName;
                        String directCoord = (anchor != null && anchor.getLocation() != null && !anchor.getLocation().isBlank())
                                ? anchor.getLocation() : "106.577000,29.556000";
                        String directVenueId = "direct-dining-" + Math.abs(directDiningName.hashCode());
                        if (diningVenueService != null) {
                            try {
                                var foundList = diningVenueService.searchLiveDiningOrTea(district != null ? district : "重庆市", directCoord, directDiningName, slotType, 1);
                                if (foundList != null && !foundList.isEmpty()) {
                                    var v0 = foundList.get(0);
                                    directVenueId = v0.getId();
                                    if (v0.getLocation() != null && !v0.getLocation().isBlank()) {
                                        directCoord = v0.getLocation();
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                        String mealNameZh = "LUNCH".equalsIgnoreCase(slotType) ? "午餐" : ("DINNER".equalsIgnoreCase(slotType) ? "晚餐" : "餐饮");
                        AttractionDiningKnowledge.DiningOption directOpt = new AttractionDiningKnowledge.DiningOption(
                                directVenueId,
                                directFullName,
                                "招牌特色、精选毛肚鸭肠与经典巴渝风味",
                                requestedFlavor,
                                "人均约 65-88 元",
                                "约 60 分钟",
                                "步行约 80 米",
                                "高德地图/真实认证餐厅【" + directDiningName + "】，已为您定向锁定并一对一替换至当前" + mealNameZh + "。",
                                "用户明确指定心仪餐厅，精准一对一替换当前" + mealNameZh + "。",
                                "gold",
                                directCoord
                        );
                        diningCandidates.add(directOpt);

                        String directNameForFilter = directDiningName;
                        List<AttractionDiningKnowledge.DiningOption> altCandidates =
                                AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                                        district, String.valueOf(target.stop().get("name")), requestedFlavor, slotType, anchor, constraints);
                        if (altCandidates != null) {
                            for (AttractionDiningKnowledge.DiningOption alt : altCandidates) {
                                if (diningCandidates.stream().noneMatch(c -> c.name().contains(alt.name()) || alt.name().contains(directNameForFilter))) {
                                    diningCandidates.add(alt);
                                    if (diningCandidates.size() >= 3) break;
                                }
                            }
                        }
                        adjustmentDiagnostics.put("replacementMode", "DIRECT");
                        adjustmentDiagnostics.put("directReplacement", true);
                        adjustmentDiagnostics.put("requestedReplacementName", directDiningName);
                    } else {
                        diningCandidates.addAll(AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                                district, String.valueOf(target.stop().get("name")), requestedFlavor, slotType, anchor, constraints));
                        adjustmentDiagnostics.put("replacementMode", "DINING");
                        adjustmentDiagnostics.put("requestedFlavor", requestedFlavor);
                    }

                    adjustmentDiagnostics.put("slotType", slotType);
                    adjustmentDiagnostics.put("sourceStopName", String.valueOf(target.stop().getOrDefault("name", "当前餐饮")));
                    adjustmentDiagnostics.put("forceApplicable", true);

                    for (int i = 0; i < diningCandidates.size(); i++) {
                        AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
                        String optId = "option-" + (i + 1);
                        Map<String, Object> optionDraft = deepCopy(sourceTrip);
                        StopLocation optTarget = locateStop(optionDraft, effectiveStopId);
                        if (optTarget == null) continue;

                        Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(
                                dining, effectiveStopId, slotTime, district);
                        optTarget.stops().set(optTarget.index(), newDiningStop);
                        optionPlans.put(optId, optionDraft);

                        boolean isThisDirect = isDirectDining && i == 0;
                        Map<String, Object> cand = new LinkedHashMap<>();
                        cand.put("optionId", optId);
                        cand.put("venueId", dining.id());
                        cand.put("name", dining.name());
                        cand.put("requestedName", isThisDirect ? directDiningName : dining.name());
                        cand.put("directReplacement", isThisDirect);
                        cand.put("summary", dining.summary());
                        cand.put("specialtyDish", dining.specialtyDish());
                        cand.put("costSummary", dining.averageCost());
                        cand.put("duration", dining.duration());
                        cand.put("district", district);
                        cand.put("walkDifficulty", "低");
                        cand.put("walk", dining.distanceFromAttraction());
                        cand.put("type", "DINING");
                        cand.put("isDining", true);
                        cand.put("fit", dining.recommendationReason());
                        candidateReplacements.add(cand);
                    }

                    if (optionPlans.isEmpty()) {
                        feasible = false;
                        reasonCodes.add("NO_FEASIBLE_REPLACEMENT");
                        alternatives.addAll(List.of("换周边其他风味美食", "保持原餐饮安排"));
                    } else {
                        draftTrip = optionPlans.get("option-1");
                        changedSegments.add(effectiveStopId);
                    }
                    break;
                }

                Attraction oldAttr = itineraryBuilder.attraction(String.valueOf(target.stop().get("venueId")));
                if (oldAttr == null) {
                    String district = extractDistrictFromStop(target.stop());
                    oldAttr = Attraction.builder()
                            .id(String.valueOf(target.stop().get("venueId")))
                            .name(String.valueOf(target.stop().get("name")))
                            .displayName(String.valueOf(target.stop().getOrDefault("displayName", target.stop().get("name"))))
                            .district(district)
                            .location(String.valueOf(target.stop().getOrDefault("location", "")))
                            .category(String.valueOf(target.stop().getOrDefault("category", "探索打卡")))
                            .summary(String.valueOf(target.stop().getOrDefault("summary", "")))
                            .build();
                }
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
                    Attraction requested = itineraryBuilder.attraction(requestedReplacementId);
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
                    Attraction prevAttr = target.index() > 0 ? resolveStopAttraction(target.stops().get(target.index() - 1)) : null;
                    Attraction nextAttr = target.index() + 1 < target.stops().size() ? resolveStopAttraction(target.stops().get(target.index() + 1)) : null;
                    String startCoord = extractStartCoordinate(draftTrip, constraints);
                    CandidateContext candContext = new CandidateContext(oldAttr, prevAttr, nextAttr, startCoord);
                    candidates = findCandidates(candContext, used, constraints, intent, String.valueOf(target.stop().getOrDefault("time", "")));
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
                            "根据调整需求替换为" + c.getName() + "（" + PlannerService.stripTrailingPunctuation(c.getSummary()) + "）。",
                            c.getIntro()
                    );
                    optTarget.stops().set(optTarget.index(), newStop);
                    resolveAffectedDay(optionDraft, number(optTarget.day().get("day")), byId, constraints);
                    PlanVerifier.VerificationResult optionVerification = planVerifier.verify(days(optionDraft), constraints);
                    int targetDayNum = optTarget.day().get("day") instanceof Number n ? n.intValue() : 1;
                    boolean affectedDayFeasible = optionVerification.violations().stream()
                            .noneMatch(v -> v.dayNumber() == targetDayNum && v.isHardConstraint());
                    if (!affectedDayFeasible && !directReplacement) {
                        rejectedOptionDiagnostics.add(Map.of(
                                "venueId", c.getId(),
                                "reasonCodes", optionVerification.violations().stream()
                                        .filter(v -> v.dayNumber() == targetDayNum)
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
                String rawMsg = intent.getRawMessage() != null ? intent.getRawMessage().replaceAll("[\\u200B-\\u200D\\uFEFF]", "") : "";
                List<String> explicitInOrder = aliasMatcher.findExplicitAttractionIdsInOrder(rawMsg, null);
                Set<String> used = usedVenues(draftTrip);

                // Multi-stop sequential route addition detection
                // 1. 甄别途经中继点 vs 最终返程目的地（例如“先打车回解放碑换件厚衣服吃个火锅”是中继点，而“随后打车返回解放碑”是返程终点）
                List<String> validCandidateVenues = new ArrayList<>();
                for (String vId : explicitInOrder) {
                    String canonical = aliasMatcher.getVenueIdToCanonicalMap().getOrDefault(vId, vId);
                    boolean isTransitWaypoint = rawMsg.matches(".*(?:先回|先到|先去|回\\s*" + Pattern.quote(canonical) + "[，, ]*?[^。！？!?;]{0,25}?(?:换|吃|拿|放|看|歇|再|然后|之后|接着)).*");
                    boolean isReturnTerminus = !isTransitWaypoint && rawMsg.matches(".*(?:最后|随后|晚间|晚上|游玩后|结束)?(?:打车|坐车|乘车|步行)?(?:返回|回|回住处|回酒店)\\s*" + Pattern.quote(canonical) + "(?:附近|商圈|酒店|住处)?(?:结束|休息|住宿|离开|。|！|!|$)");
                    if (isReturnTerminus) {
                        continue;
                    }
                    validCandidateVenues.add(vId);
                }

                // 2. 区分已在行程中的锚点 (anchor) 与待新增的站点 (newVenuesToAdd)
                List<String> newVenuesToAdd = new ArrayList<>();
                String anchorVenueId = null;
                boolean isBefore = false;

                for (String vId : validCandidateVenues) {
                    if (used.contains(vId)) {
                        if (anchorVenueId == null) {
                            anchorVenueId = vId; // 发现行程内既有锚点
                        }
                    } else {
                        if (!newVenuesToAdd.contains(vId)) {
                            newVenuesToAdd.add(vId);
                        }
                    }
                }

                // 3. 方位语法识别（在...之前 vs 在...之后）
                if (anchorVenueId != null) {
                    String anchorCanonical = aliasMatcher.getVenueIdToCanonicalMap().getOrDefault(anchorVenueId, anchorVenueId);
                    String anchorPattern = Pattern.quote(anchorCanonical);
                    if (rawMsg.matches(".*(?:在|去)?\\s*" + anchorPattern + "\\s*(?:之前|前|前先|之前先|前先去|前面).*")
                            || rawMsg.matches(".*(?:加在|放在|排在)\\s*" + anchorPattern + "\\s*(?:前面|之前).*")
                            || rawMsg.matches(".*(?:先去|先到|先逛|先玩)?.*再去\\s*" + anchorPattern + ".*")) {
                        isBefore = true;
                    }
                }

                if (newVenuesToAdd.size() > 1 || (newVenuesToAdd.size() == 1 && anchorVenueId != null && (rawMsg.contains("之后接") || rawMsg.contains("后面接") || rawMsg.contains("动线") || rawMsg.contains("接上") || rawMsg.contains("接入") || isBefore))) {
                    // Multi-stop route segment addition
                    int targetDayNumber;
                    int insertIndex = -1;
                    Map<String, Object> targetDayMap = null;

                    if (anchorVenueId != null) {
                        StopLocation anchorLoc = locateStopByVenue(draftTrip, anchorVenueId);
                        if (anchorLoc != null) {
                            targetDayMap = anchorLoc.day();
                            targetDayNumber = number(anchorLoc.day().get("day"));
                            insertIndex = isBefore ? anchorLoc.index() : anchorLoc.index() + 1;
                        } else {
                            targetDayNumber = intent.getDayNumber() != null ? intent.getDayNumber() : (context.getActiveDay() != null ? context.getActiveDay() : 1);
                            targetDayMap = findDay(draftTrip, targetDayNumber);
                        }
                    } else {
                        targetDayNumber = intent.getDayNumber() != null ? intent.getDayNumber() : (context.getActiveDay() != null ? context.getActiveDay() : 1);
                        targetDayMap = findDay(draftTrip, targetDayNumber);
                    }

                    if (targetDayMap == null && !days(draftTrip).isEmpty()) {
                        targetDayMap = days(draftTrip).get(0);
                        targetDayNumber = number(targetDayMap.get("day"));
                    }

                    if (targetDayMap != null) {
                        List<Map<String, Object>> targetDayStops = dayStops(targetDayMap);
                        if (insertIndex < 0 || insertIndex > targetDayStops.size()) {
                            insertIndex = targetDayStops.size();
                        }

                        // 计算起始时间与步长
                        int baseTimeMinute = 20 * 60; // 默认晚间 20:00
                        Integer explicitTime = extractExplicitTimeMinutes(rawMsg);
                        if (explicitTime != null) {
                            baseTimeMinute = explicitTime;
                        } else if (isBefore) {
                            if (insertIndex > 0 && insertIndex - 1 < targetDayStops.size()) {
                                Map<String, Object> prevStop = targetDayStops.get(insertIndex - 1);
                                int prevStart = parseTimeMinutes(String.valueOf(prevStop.getOrDefault("time", "")));
                                int prevDur = parseDurationMinutes(String.valueOf(prevStop.getOrDefault("duration", "")));
                                if (prevStart > 0) {
                                    baseTimeMinute = prevStart + (prevDur > 0 ? prevDur : 60) + 15;
                                }
                            } else if (insertIndex < targetDayStops.size()) {
                                Map<String, Object> anchorStop = targetDayStops.get(insertIndex);
                                int anchorStart = parseTimeMinutes(String.valueOf(anchorStop.getOrDefault("time", "")));
                                if (anchorStart > 0) {
                                    int totalNewDuration = 0;
                                    for (String vId : newVenuesToAdd) {
                                        Attraction a = byId.get(vId);
                                        if (a == null) a = attractionService.get(vId);
                                        int d = a != null ? parseDurationMinutes(a.getDuration()) : 45;
                                        totalNewDuration += (d > 0 ? d : 45) + 15;
                                    }
                                    baseTimeMinute = Math.max(9 * 60, anchorStart - totalNewDuration);
                                }
                            }
                        } else {
                            if (insertIndex > 0 && insertIndex - 1 < targetDayStops.size()) {
                                Map<String, Object> prevStop = targetDayStops.get(insertIndex - 1);
                                int prevStart = parseTimeMinutes(String.valueOf(prevStop.getOrDefault("time", "")));
                                int prevDur = parseDurationMinutes(String.valueOf(prevStop.getOrDefault("duration", "")));
                                if (prevStart > 0) {
                                    if (prevDur > 60 && newVenuesToAdd.size() > 1 && prevStart >= 18 * 60) {
                                        prevDur = 60; // 晚间连游时前序站点预留 60 分钟观景时间，避免起步过晚
                                    }
                                    baseTimeMinute = prevStart + (prevDur > 0 ? prevDur : 60) + 15;
                                }
                            }
                        }

                        // Option 1: Full sequential route addition (All new venues in order)
                        Map<String, Object> draftOpt1 = deepCopy(sourceTrip);
                        Map<String, Object> dayOpt1 = findDay(draftOpt1, targetDayNumber);
                        List<Map<String, Object>> stopsOpt1 = dayStops(dayOpt1);
                        int curInsert = insertIndex;
                        int curMinute = baseTimeMinute;
                        List<String> opt1AddedStopIds = new ArrayList<>();
                        List<String> addedNames = new ArrayList<>();
                        boolean option1Feasible = true;

                        for (String vId : newVenuesToAdd) {
                            Attraction attr = byId.get(vId);
                            if (attr == null) attr = attractionService.get(vId);
                            if (attr == null) continue;
                            String cleanId = attr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                            String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                            String slotTime = formatTime(curMinute);

                            // 开闭园与午夜门禁校验
                            if (curMinute >= 24 * 60 || !isOpeningCompatible(attr, slotTime)) {
                                option1Feasible = false;
                                reasonCodes.add(curMinute >= 24 * 60 ? "MIDNIGHT_OVERFLOW" : "CLOSED_AT_SLOT_TIME");
                                alternatives.add("当前排程时间较晚（" + attr.getName() + "在" + slotTime + "可能已闭园/停运或跨夜），建议提早出发或精简站点");
                            }

                            Map<String, Object> addedStop = itineraryBuilder.createStop(
                                    attr, stopId, slotTime, attr.getDuration(),
                                    "串联游览：" + attr.getName() + "（" + PlannerService.stripTrailingPunctuation(attr.getSummary()) + "）。",
                                    attr.getIntro()
                            );
                            addedStop.put("time", slotTime);
                            addedStop.put("startTime", slotTime);
                            stopsOpt1.add(Math.min(curInsert, stopsOpt1.size()), addedStop);
                            curInsert++;
                            int dur = parseDurationMinutes(attr.getDuration());
                            if (dur > 50 && newVenuesToAdd.size() > 1 && baseTimeMinute >= 18 * 60) {
                                dur = 45; // 晚间多站连游时单个站点适当紧凑游玩时间，避免拖入深夜
                            }
                            curMinute += (dur > 0 ? dur : 45) + 15;
                            opt1AddedStopIds.add(stopId);
                            addedNames.add(attr.getName());
                        }

                        // 级联推迟插入点之后的所有既有站点，彻底消除时空倒流
                        rippleRecalculateStopTimes(stopsOpt1, curInsert);

                        // 校验推迟后的后续既有站点是否跨午夜
                        for (int i = curInsert; i < stopsOpt1.size(); i++) {
                            Map<String, Object> s = stopsOpt1.get(i);
                            int sMin = parseTimeMinutes(String.valueOf(s.getOrDefault("time", "")));
                            if (sMin >= 24 * 60) {
                                option1Feasible = false;
                                reasonCodes.add("MIDNIGHT_OVERFLOW");
                                alternatives.add("插入新站点后导致后续行程（" + s.get("name") + "）推迟至深夜/次日凌晨，建议调整至其他日期");
                                break;
                            }
                        }

                        resolveAffectedDay(draftOpt1, targetDayNumber, byId, constraints);
                        optionPlans.put("option-1", draftOpt1);

                        // 动态生成 Option 1 卡片（通用语义，杜绝硬编码文案）
                        Map<String, Object> cand1 = new LinkedHashMap<>();
                        cand1.put("optionId", "option-1");
                        cand1.put("venueId", newVenuesToAdd.get(0));
                        cand1.put("name", String.join(" + ", addedNames) + "（完整动线）");
                        cand1.put("requestedName", String.join(" + ", addedNames));
                        cand1.put("summary", "依序串联" + String.join("、", addedNames) + "，顺路衔接且游玩动线连贯，适合完整打卡该片区核心地标。");
                        cand1.put("duration", "约 " + (addedNames.size() * 50) + " 分钟");
                        cand1.put("district", byId.get(newVenuesToAdd.get(0)) != null ? byId.get(newVenuesToAdd.get(0)).getDistrict() : "主城区");
                        cand1.put("walkDifficulty", "低");
                        cand1.put("fit", "适合少走路、行程充实、一次性体验连贯风貌路线的旅行者。");
                        candidateReplacements.add(cand1);

                        // Option 2: First stop only (Single core venue)
                        if (newVenuesToAdd.size() > 1) {
                            String firstVenueId = newVenuesToAdd.get(0);
                            Attraction firstAttr = byId.get(firstVenueId);
                            if (firstAttr == null) firstAttr = attractionService.get(firstVenueId);
                            if (firstAttr != null) {
                                Map<String, Object> draftOpt2 = deepCopy(sourceTrip);
                                Map<String, Object> dayOpt2 = findDay(draftOpt2, targetDayNumber);
                                List<Map<String, Object>> stopsOpt2 = dayStops(dayOpt2);
                                String cleanId = firstAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                                String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                                String slotTime = formatTime(baseTimeMinute);
                                Map<String, Object> addedStop = itineraryBuilder.createStop(
                                        firstAttr, stopId, slotTime, firstAttr.getDuration(),
                                        "单站体验：" + firstAttr.getName(), firstAttr.getIntro()
                                );
                                addedStop.put("time", slotTime);
                                addedStop.put("startTime", slotTime);
                                stopsOpt2.add(Math.min(insertIndex, stopsOpt2.size()), addedStop);
                                rippleRecalculateStopTimes(stopsOpt2, insertIndex + 1);
                                resolveAffectedDay(draftOpt2, targetDayNumber, byId, constraints);
                                optionPlans.put("option-2", draftOpt2);

                                Map<String, Object> cand2 = new LinkedHashMap<>();
                                cand2.put("optionId", "option-2");
                                cand2.put("venueId", firstAttr.getId());
                                String opt2Label = (firstAttr.getTags() != null && firstAttr.getTags().contains("索道")) || firstAttr.getName().contains("索道") ? "（单程过江体验）" : "（精选单站）";
                                cand2.put("name", firstAttr.getName() + opt2Label);
                                cand2.put("requestedName", firstAttr.getName());
                                cand2.put("summary", "仅保留" + firstAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(firstAttr.getSummary()) + "），游玩节奏更紧凑轻松。");
                                cand2.put("duration", firstAttr.getDuration());
                                cand2.put("district", firstAttr.getDistrict());
                                cand2.put("walkDifficulty", firstAttr.getWalkDifficulty() != null ? firstAttr.getWalkDifficulty() : "低");
                                cand2.put("fit", firstAttr.getFit() != null ? firstAttr.getFit() : "适合时间有限、想体验核心地标的旅行者。");
                                candidateReplacements.add(cand2);
                            }
                        }

                        // Option 3: Last stop only
                        if (newVenuesToAdd.size() > 2) {
                            String lastVenueId = newVenuesToAdd.get(newVenuesToAdd.size() - 1);
                            Attraction lastAttr = byId.get(lastVenueId);
                            if (lastAttr == null) lastAttr = attractionService.get(lastVenueId);
                            if (lastAttr != null) {
                                Map<String, Object> draftOpt3 = deepCopy(sourceTrip);
                                Map<String, Object> dayOpt3 = findDay(draftOpt3, targetDayNumber);
                                List<Map<String, Object>> stopsOpt3 = dayStops(dayOpt3);
                                String cleanId = lastAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                                String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                                String slotTime = formatTime(baseTimeMinute);
                                Map<String, Object> addedStop = itineraryBuilder.createStop(
                                        lastAttr, stopId, slotTime, lastAttr.getDuration(),
                                        "休闲体验：" + lastAttr.getName(), lastAttr.getIntro()
                                );
                                addedStop.put("time", slotTime);
                                addedStop.put("startTime", slotTime);
                                stopsOpt3.add(Math.min(insertIndex, stopsOpt3.size()), addedStop);
                                rippleRecalculateStopTimes(stopsOpt3, insertIndex + 1);
                                resolveAffectedDay(draftOpt3, targetDayNumber, byId, constraints);
                                optionPlans.put("option-3", draftOpt3);

                                Map<String, Object> cand3 = new LinkedHashMap<>();
                                cand3.put("optionId", "option-3");
                                cand3.put("venueId", lastAttr.getId());
                                String opt3Label = (lastAttr.getTags() != null && (lastAttr.getTags().contains("夜景") || lastAttr.getTags().contains("江景"))) || lastAttr.getName().contains("滨江") || lastAttr.getName().contains("老街") ? "（江岸夜景茶歇）" : "（休闲漫步体验）";
                                cand3.put("name", lastAttr.getName() + opt3Label);
                                cand3.put("requestedName", lastAttr.getName());
                                cand3.put("summary", "重点安排" + lastAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(lastAttr.getSummary()) + "），全程节奏舒适平缓。");
                                cand3.put("duration", lastAttr.getDuration());
                                cand3.put("district", lastAttr.getDistrict());
                                cand3.put("walkDifficulty", lastAttr.getWalkDifficulty() != null ? lastAttr.getWalkDifficulty() : "低");
                                cand3.put("fit", lastAttr.getFit() != null ? lastAttr.getFit() : "适合喜爱开阔视野、慢节奏观景的长辈友好路线。");
                                candidateReplacements.add(cand3);
                            }
                        }

                        draftTrip = optionPlans.get("option-1");
                        changedSegments.addAll(opt1AddedStopIds);
                        feasible = option1Feasible;
                        adjustmentDiagnostics.put("addedStopName", String.join("+", addedNames));
                        adjustmentDiagnostics.put("addedDayNumber", targetDayNumber);
                        break;
                    }
                }

                String targetVenueId = intent.getTargetAttractionId();
                if (targetVenueId == null && intent.getTargetStopId() != null) {
                    StopLocation s = locateStop(draftTrip, intent.getTargetStopId());
                    if (s != null) targetVenueId = String.valueOf(s.stop().get("venueId"));
                }
                if (targetVenueId == null && !isDiningIntent(intent) && intent.getRequestedVenueName() != null) {
                    for (Attraction a : attractionService.list(null, null)) {
                        if (intent.getRequestedVenueName().contains(a.getName()) || a.getName().contains(intent.getRequestedVenueName())) {
                            targetVenueId = a.getId();
                            break;
                        }
                    }
                }
                if (targetVenueId == null && !isDiningIntent(intent) && intent.getPreferences() != null && !intent.getPreferences().isEmpty()) {
                    used = usedVenues(draftTrip);
                    String startCoord = extractStartCoordinate(draftTrip, constraints);
                    CandidateContext candContext = new CandidateContext(null, null, null, startCoord);
                    List<Attraction> candidates = findCandidates(candContext, used, constraints, intent, null);
                    if (!candidates.isEmpty()) {
                        targetVenueId = candidates.get(0).getId();
                    }
                }
                Attraction attraction = targetVenueId != null ? attractionService.get(targetVenueId) : null;
                if (attraction == null && targetVenueId != null && targetVenueId.startsWith("amap-") && runtimeAttractionDetailService != null) {
                    attraction = runtimeAttractionDetailService.toAttraction(targetVenueId);
                }
                if (attraction == null && !isDiningIntent(intent) && runtimeAttractionDetailService != null
                        && intent.getRequestedVenueName() != null && !intent.getRequestedVenueName().isBlank()) {
                    String searchCity = resolveTripCity(draftTrip, session, intent);
                    Attraction dynamicPoi = runtimeAttractionDetailService.searchAndBuildAttraction(intent.getRequestedVenueName(), searchCity);
                    if (dynamicPoi != null) {
                        attraction = dynamicPoi;
                        targetVenueId = dynamicPoi.getId();
                        byId.put(targetVenueId, dynamicPoi);
                    }
                }
                if (attraction == null) {
                    if (isDiningIntent(intent)) {
                        int dayNumber = (intent.getDayNumber() != null && findDay(draftTrip, intent.getDayNumber()) != null)
                                ? intent.getDayNumber()
                                : (context.getActiveDay() != null && findDay(draftTrip, context.getActiveDay()) != null
                                        ? context.getActiveDay()
                                        : (days(draftTrip).isEmpty() ? 1 : number(days(draftTrip).get(0).get("day"))));
                        Map<String, Object> day = findDay(draftTrip, dayNumber);
                        if (day == null) return error(400, "目标日期不存在。", session);
                        List<Map<String, Object>> stops = dayStops(day);
                        if (stops.isEmpty()) return error(400, "当前日期没有任何景点，无法添加餐饮站点。", session);

                        String rawCombined = ((intent.getRequestedVenueName() != null ? intent.getRequestedVenueName() : "") + " "
                                + (intent.getReplacementPlaceName() != null ? intent.getReplacementPlaceName() : "") + " "
                                + (intent.getRawMessage() != null ? intent.getRawMessage() : "")).trim();
                        String requestedFlavor = "特色风味小吃";
                        String slotType = "LUNCH";
                        String slotTime = "12:00";

                        // 优先根据时段关键词确定 slotType 与默认 slotTime
                        if (rawCombined.matches(".*(早|早餐|早点|早饭).*")) {
                            slotType = "BREAKFAST";
                            slotTime = "08:30";
                        } else if (rawCombined.matches(".*(午|午餐|中午|午饭).*")) {
                            slotType = "LUNCH";
                            slotTime = "12:00";
                        } else if (rawCombined.matches(".*(晚|晚餐|晚上|晚饭).*")) {
                            slotType = "DINNER";
                            slotTime = "18:00";
                        } else if (rawCombined.matches(".*(夜市|烧烤|宵夜|夜宵).*")) {
                            slotType = "NIGHT_SNACK";
                            slotTime = "21:00";
                        } else if (rawCombined.matches(".*(茶|茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|老茶馆|咖啡|下午茶|茶歇|小憩|歇脚|甜|糖水|冰粉|凉糕|糍粑|麻花|点心|蛋糕|烘焙).*")) {
                            slotType = "TEA_BREAK";
                            slotTime = "15:30";
                        }

                        // 识别风味特色
                        if (rawCombined.matches(".*(火锅|九宫格|老火锅|串串).*")) {
                            requestedFlavor = "重庆火锅";
                        } else if (rawCombined.matches(".*(小面|面馆|面条|豌杂).*")) {
                            requestedFlavor = "重庆小面";
                        } else if (rawCombined.matches(".*(甜|糖水|冰粉|凉糕|糍粑|麻花|点心|蛋糕|烘焙).*")) {
                            requestedFlavor = "特色甜品糖水";
                        } else if (rawCombined.matches(".*(茶|茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|老茶馆|咖啡|下午茶|茶歇|小憩|歇脚).*")) {
                            requestedFlavor = "特色老茶馆与盖碗茶歇";
                        } else if (rawCombined.matches(".*(江湖菜|川菜|家常菜|中餐|大排档|辣子鸡|泉水鸡|毛血旺|酸菜鱼|烤鱼).*")) {
                            requestedFlavor = "地道江湖菜";
                        } else if (rawCombined.matches(".*(清淡|不辣|汤锅|炖鸡|老鸭汤).*")) {
                            requestedFlavor = "清淡不辣";
                        }

                        // 1. 确定期望时段 (slotTime) 与插入位置 (plannedInsertIdx)
                        int targetMinutes = parseTimeMinutes(slotTime);
                        int plannedInsertIdx = stops.size();
                        for (int si = 0; si < stops.size(); si++) {
                            String stTime = String.valueOf(stops.get(si).getOrDefault("time", ""));
                            int stMinutes = parseTimeMinutes(stTime);
                            if (stMinutes > targetMinutes) {
                                plannedInsertIdx = si;
                                break;
                            }
                        }

                        // 2. 寻找真实的就近地理锚点 (Anchor Stop)
                        // 优先检查用户是否在页面显式选中了某个卡片
                        Map<String, Object> anchorStop = null;
                        if (context != null && context.getSelectedStopId() != null && !context.getSelectedStopId().isBlank()) {
                            StopLocation selectedLoc = locateStop(draftTrip, context.getSelectedStopId());
                            if (selectedLoc != null) {
                                anchorStop = selectedLoc.stop();
                            }
                        }
                        // 若未显式选中卡片：锚点取新站点插入位置的前一站（即用户刚刚游览完/所在的地标）
                        // 杜绝死板取 stops.get(0) 导致跨越数公里甚至跨区跑到第一站附近的严重折返 bug！
                        if (anchorStop == null) {
                            if (plannedInsertIdx > 0 && plannedInsertIdx - 1 < stops.size()) {
                                anchorStop = stops.get(plannedInsertIdx - 1);
                            } else if (plannedInsertIdx < stops.size()) {
                                anchorStop = stops.get(plannedInsertIdx);
                            } else if (!stops.isEmpty()) {
                                anchorStop = stops.get(stops.size() - 1);
                            }
                        }

                        Attraction anchor = anchorStop != null ? resolveStopAnchor(anchorStop) : null;
                        String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank() && !"待确认".equals(anchor.getDistrict()))
                                ? anchor.getDistrict()
                                : (anchorStop != null ? extractDistrictFromStop(anchorStop) : "沙坪坝区");

                        String directDiningName = firstNonBlank(intent.getReplacementPlaceName(), intent.getRequestedVenueName());
                        if ((directDiningName == null || directDiningName.isBlank()) && intent.getRawMessage() != null) {
                            Matcher bm = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(intent.getRawMessage());
                            if (bm.find()) {
                                directDiningName = bm.group(1).trim();
                            } else {
                                Matcher rm = Pattern.compile("(?:改成|换成|替换为|改为|换为|换到|改到|加上|添加|加个|吃个|去吃)\\s*([^\\s，,。！!？?；;]+)").matcher(intent.getRawMessage());
                                if (rm.find()) {
                                    String cand = rm.group(1).trim();
                                    if (cand.matches(".*[（(].*[）)]?$") && !cand.matches(".*[（(][^）)]*店[）)]$")) {
                                        cand = cand.replaceFirst("[（(][^）)]*[）)]?$", "").trim();
                                    }
                                    if (!cand.isBlank()) {
                                        directDiningName = cand;
                                    }
                                }
                            }
                        }
                        boolean isGenericDining = isGenericDiningCraving(directDiningName);
                        boolean isDirectDining = !isGenericDining;

                        List<AttractionDiningKnowledge.DiningOption> diningCandidates = new ArrayList<>();
                        if (isDirectDining) {
                            String slotPrefix = "BREAKFAST".equalsIgnoreCase(slotType) ? "【早餐推荐】"
                                    : ("NIGHT_SNACK".equalsIgnoreCase(slotType) ? "【宵夜推荐】"
                                    : ("TEA_BREAK".equalsIgnoreCase(slotType) ? "【特色茶歇】"
                                    : ("DINNER".equalsIgnoreCase(slotType) ? "【晚餐推荐】" : "【午餐推荐】")));
                            String directFullName = directDiningName.startsWith("【") ? directDiningName : slotPrefix + directDiningName;
                            String directCoord = (anchor != null && anchor.getLocation() != null && !anchor.getLocation().isBlank())
                                    ? anchor.getLocation() : "106.577000,29.556000";
                            String directVenueId = "direct-dining-" + Math.abs(directDiningName.hashCode());
                            if (diningVenueService != null) {
                                try {
                                    var foundList = diningVenueService.searchLiveDiningOrTea(district != null ? district : "重庆市", directCoord, directDiningName, slotType, 1);
                                    if (foundList != null && !foundList.isEmpty()) {
                                        var v0 = foundList.get(0);
                                        directVenueId = v0.getId();
                                        if (v0.getLocation() != null && !v0.getLocation().isBlank()) {
                                            directCoord = v0.getLocation();
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                            diningCandidates.add(new AttractionDiningKnowledge.DiningOption(
                                    directVenueId,
                                    directFullName,
                                    "招牌特色风味菜品",
                                    requestedFlavor,
                                    "人均约 58-88 元",
                                    "约 60 分钟",
                                    "步行直达",
                                    "根据您的调整要求，直接安排指定餐厅：" + directDiningName,
                                    "用户指定餐厅，为您精准规划动线",
                                    "gold",
                                    directCoord
                            ));
                            var alts = AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                                    district, directFullName, requestedFlavor, slotType, anchor, constraints);
                            if (alts != null) {
                                for (var alt : alts) {
                                    if (!alt.name().contains(directDiningName)) {
                                        diningCandidates.add(alt);
                                        if (diningCandidates.size() >= 3) break;
                                    }
                                }
                            }
                            adjustmentDiagnostics.put("replacementMode", "DIRECT");
                            adjustmentDiagnostics.put("directReplacement", true);
                            adjustmentDiagnostics.put("requestedReplacementName", directDiningName);
                        } else {
                            diningCandidates.addAll(AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                                    district, "", requestedFlavor, slotType, anchor, constraints));
                            boolean isTea = "TEA_BREAK".equals(slotType);
                            adjustmentDiagnostics.put("replacementMode", isTea ? "TEA_BREAK" : "DINING_ADD");
                            adjustmentDiagnostics.put("requestedFlavor", isTea ? "特色老茶馆与盖碗茶歇" : requestedFlavor);
                        }

                        adjustmentDiagnostics.put("sourceStopName", anchorStop != null ? String.valueOf(anchorStop.getOrDefault("name", "沿途加站")) : "沿途加站");
                        adjustmentDiagnostics.put("forceApplicable", true);

                        String effectiveStopId = "day" + dayNumber + "-added-dining";

                        for (int i = 0; i < diningCandidates.size(); i++) {
                            AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
                            String optId = "option-" + (i + 1);
                            Map<String, Object> optionDraft = deepCopy(sourceTrip);
                            Map<String, Object> optDay = findDay(optionDraft, dayNumber);
                            if (optDay == null) continue;
                            List<Map<String, Object>> optStops = dayStops(optDay);

                            // 重新在当前 optionDraft 中确定插入下标
                            int insertIdx = optStops.size();
                            for (int si = 0; si < optStops.size(); si++) {
                                String stTime = String.valueOf(optStops.get(si).getOrDefault("time", ""));
                                int stMinutes = parseTimeMinutes(stTime);
                                if (stMinutes > targetMinutes) {
                                    insertIdx = si;
                                    break;
                                }
                            }

                            // 动态起步时间：杜绝与前一站游玩时间发生重叠撞车（若前一站游览结束时间晚于 targetMinutes，则顺延至游览结束）
                            int actualStartMinute = targetMinutes;
                            if (insertIdx > 0 && insertIdx - 1 < optStops.size()) {
                                Map<String, Object> prev = optStops.get(insertIdx - 1);
                                int prevStart = parseTimeMinutes(String.valueOf(prev.getOrDefault("time", "")));
                                int prevDuration = parseDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
                                int prevEnd = prevStart + prevDuration;
                                if (actualStartMinute < prevEnd) {
                                    actualStartMinute = prevEnd;
                                }
                            }
                            String effectiveSlotTime = formatTime(actualStartMinute);

                            Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(
                                    dining, effectiveStopId, effectiveSlotTime, district);
                            newDiningStop.put("time", effectiveSlotTime);
                            newDiningStop.put("startTime", effectiveSlotTime);

                            insertIdx = Math.max(0, Math.min(insertIdx, optStops.size()));
                            optStops.add(insertIdx, newDiningStop);

                            // 级联后移：如果新插入的餐饮站点推迟了后续行程，依次将后续站点顺延，防止时间轴倒挂
                            int transitBuffer = 15;
                            int diningDur = parseDurationMinutes(dining.duration());
                            int currentEndMinute = actualStartMinute + (diningDur > 0 ? diningDur : 50) + transitBuffer;
                            for (int si = insertIdx + 1; si < optStops.size(); si++) {
                                Map<String, Object> nextStop = optStops.get(si);
                                int nextStart = parseTimeMinutes(String.valueOf(nextStop.getOrDefault("time", "")));
                                int nextDuration = parseDurationMinutes(String.valueOf(nextStop.getOrDefault("duration", "")));
                                if (nextStart < currentEndMinute) {
                                    nextStart = currentEndMinute;
                                    String updatedTime = formatTime(nextStart);
                                    nextStop.put("time", updatedTime);
                                    nextStop.put("startTime", updatedTime);
                                    // 若正餐顺延到了 17:00 以后，修正不合时宜的“【午餐推荐】”前缀
                                    String curName = String.valueOf(nextStop.getOrDefault("name", ""));
                                    if (nextStart >= 17 * 60 && curName.startsWith("【午餐推荐】")) {
                                        nextStop.put("name", curName.replace("【午餐推荐】", "【晚餐推荐】"));
                                        if (nextStop.get("displayName") != null) {
                                            nextStop.put("displayName", String.valueOf(nextStop.get("displayName")).replace("【午餐推荐】", "【晚餐推荐】"));
                                        }
                                    }
                                }
                                currentEndMinute = nextStart + (nextDuration > 0 ? nextDuration : 40) + transitBuffer;
                            }

                            optionPlans.put(optId, optionDraft);

                            Map<String, Object> cand = new LinkedHashMap<>();
                            cand.put("optionId", optId);
                            cand.put("venueId", dining.id());
                            cand.put("name", dining.name());
                            cand.put("requestedName", (isDirectDining && i == 0) ? directDiningName : dining.name());
                            cand.put("directReplacement", isDirectDining && i == 0);
                            cand.put("summary", dining.summary());
                            cand.put("specialtyDish", dining.specialtyDish());
                            cand.put("costSummary", dining.averageCost());
                            cand.put("duration", dining.duration());
                            cand.put("district", district);
                            cand.put("walkDifficulty", "低");
                            cand.put("walk", dining.distanceFromAttraction());
                            cand.put("type", "DINING");
                            cand.put("isDining", true);
                            cand.put("fit", dining.recommendationReason());
                            candidateReplacements.add(cand);
                        }

                        if (optionPlans.isEmpty()) {
                            feasible = false;
                            reasonCodes.add("NO_FEASIBLE_REPLACEMENT");
                            alternatives.addAll(List.of("换周边其他风味美食", "保持原行程安排"));
                        } else {
                            draftTrip = optionPlans.get("option-1");
                            changedSegments.add(effectiveStopId);
                        }
                        break;
                    }
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("ok", true);
                    body.put("requiresClarification", true);
                    String venue = intent.getRequestedVenueName() == null ? "该地点" : ("“" + intent.getRequestedVenueName() + "”");
                    body.put("clarificationQuestion", "在高德地图上未能检索到" + venue + "。请尝试补充所属区县、详细路名，或直接告诉我特色打卡地标。");
                    return new PlannerService.ServiceResult(200, body);
                }

                // Check duplicate
                if (usedVenues(draftTrip).contains(targetVenueId)) {
                    feasible = false;
                    reasonCodes.add("DUPLICATE_VENUE");
                    alternatives.addAll(List.of("该景点已在行程中，无需重复添加", "选择其他未去过的景点", "取消添加"));
                }

                int totalTripDays = days(draftTrip).size();
                int dayNumber;
                if (intent.getDayNumber() != null && intent.getDayNumber() == totalTripDays + 1) {
                    Map<String, Object> newDay = new LinkedHashMap<>();
                    newDay.put("day", totalTripDays + 1);
                    newDay.put("title", "第" + (totalTripDays + 1) + "天");
                    newDay.put("stops", new ArrayList<>());
                    days(draftTrip).add(newDay);
                    dayNumber = totalTripDays + 1;
                } else if (intent.getDayNumber() != null && findDay(draftTrip, intent.getDayNumber()) != null) {
                    dayNumber = intent.getDayNumber();
                } else {
                    int matchedDay = findBestMatchingDayForDistrict(draftTrip, attraction.getDistrict());
                    if (matchedDay > 0 && findDay(draftTrip, matchedDay) != null) {
                        dayNumber = matchedDay;
                    } else if (context != null && context.getActiveDay() != null && findDay(draftTrip, context.getActiveDay()) != null) {
                        dayNumber = context.getActiveDay();
                    } else {
                        dayNumber = days(draftTrip).isEmpty() ? 1 : number(days(draftTrip).get(0).get("day"));
                    }
                }

                Map<String, Object> day = findDay(draftTrip, dayNumber);
                if (day == null) return error(400, "目标日期不存在。", session);

                List<Map<String, Object>> stops = dayStops(day);
                long scenicCount = stops.stream()
                        .filter(s -> !"DINING".equals(s.get("type")) && !"餐".equals(s.get("icon")))
                        .count();
                boolean denseDay = scenicCount >= 4;
                if (denseDay) {
                    adjustmentDiagnostics.put("denseDay", true);
                    adjustmentDiagnostics.put("totalDayScenicCount", scenicCount + 1);
                    alternatives.addAll(List.of("替换当天已有某一景点", "保持原行程"));
                }

                String cleanId = attraction.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                String stopId = "day" + dayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                Map<String, Object> addedStop = itineraryBuilder.createStop(
                        attraction, stopId, "待安排", attraction.getDuration(),
                        "将" + attraction.getName() + "加入当前行程。", attraction.getIntro()
                );
                stops.add(addedStop);
                changedSegments.add(stopId);
                resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
                optionPlans.put("option-1", draftTrip);

                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("optionId", "option-1");
                cand.put("venueId", attraction.getId());
                cand.put("name", attraction.getName());
                cand.put("requestedName", intent.getRequestedVenueName() != null ? intent.getRequestedVenueName() : attraction.getName());
                cand.put("district", attraction.getDistrict());
                cand.put("summary", attraction.getSummary());
                cand.put("duration", attraction.getDuration());
                cand.put("fit", attraction.getFit());
                candidateReplacements.add(cand);

                adjustmentDiagnostics.put("addedStopName", attraction.getName());
                adjustmentDiagnostics.put("addedDayNumber", dayNumber);
            }
            case REMOVE_STOP -> {
                String targetId = intent.getTargetStopId();
                StopLocation target = locateStop(draftTrip, targetId);
                if (target == null) return error(400, "未在行程中找到要移除的站点。", session);
                // 最小站点数不变量守卫（DEF-R3-05）：当天至少保留 1 个站点，防止空天数引发排程雪崩
                if (target.stops().size() <= 1) {
                    return error(400, "当天行程至少需要保留 1 个站点，不能直接删空。如需调整天数，请使用缩减天数或重新规划功能。", session);
                }
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

                    // 空间环境不变式守卫 (DEF-PLAN-10): 若原站点本身就是合规室内场馆，无需重复驱逐替换
                    Attraction originalAttr = byId.get(safe(originalStop.get("venueId")));
                    if (originalAttr != null && "INDOOR".equalsIgnoreCase(originalAttr.getEffectiveEnvironment())) {
                        tripUsed.add(originalAttr.getId());
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
            case PLAN -> {
                Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
                int dayNumber = intent.getDayNumber() != null ? intent.getDayNumber()
                        : (context.getActiveDay() != null ? context.getActiveDay() : 1);
                if (findDay(draftTrip, dayNumber) == null) {
                    dayNumber = 1;
                }
                if (findDay(draftTrip, dayNumber) != null) {
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
        if (optionPlans.isEmpty() && draftTrip != null) {
            optionPlans.put("option-1", draftTrip);
        }
        boolean forceApplicable = !optionPlans.isEmpty() || draftTrip != null;
        adjustmentDiagnostics.put("forceApplicable", forceApplicable);
        unchangedStops = collectUnchangedStops(sourceTrip, changedSegments);

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
            if (reasonCodes.contains("DUPLICATE_VENUE")) {
                String stopName = adjustmentDiagnostics.get("addedStopName") != null ? String.valueOf(adjustmentDiagnostics.get("addedStopName")) : "该地点";
                body.put("message", "【" + stopName + "】已经在当前行程中安排过了，无需重复添加。您可以选择其他想去的地标或保持原安排。");
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
            if (flavor.contains("甜") || flavor.contains("糖水") || flavor.contains("点心") || flavor.contains("冰粉")) {
                body.put("message", "已为您在沿途精心挑选【" + flavor + "】方案，请选择心仪甜品铺后确认应用。");
            } else {
                body.put("message", "已为您在沿途精心挑选【" + flavor + "】品茗小憩方案，请选择心仪茶社后确认应用。");
            }
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

        Attraction requestedTargetAttr = null;
        if (intent != null && intent.getTargetAttractionId() != null && !intent.getTargetAttractionId().isBlank()) {
            requestedTargetAttr = byId.get(intent.getTargetAttractionId());
        }
        boolean targetInjected = false;

        List<Map<String, Object>> newStops = new ArrayList<>();
        for (Map<String, Object> originalStop : stops) {
            String stopId = String.valueOf(originalStop.get("id"));
            if (isProtectedStop(originalStop, pinned, constraints)) {
                tripUsed.add(String.valueOf(originalStop.get("venueId")));
                newStops.add(originalStop);
                continue;
            }

            String slotTime = String.valueOf(originalStop.getOrDefault("time", ""));
            int stopIdx = stops.indexOf(originalStop);
            Attraction prevAttr = stopIdx > 0 ? resolveStopAttraction(stops.get(stopIdx - 1)) : null;
            Attraction nextAttr = stopIdx + 1 < stops.size() ? resolveStopAttraction(stops.get(stopIdx + 1)) : null;
            String startCoord = extractStartCoordinate(draftTrip, constraints);
            CandidateContext context = new CandidateContext(
                    byId.get(String.valueOf(originalStop.get("venueId"))),
                    prevAttr, nextAttr, startCoord);

            Attraction replacement = null;
            if (requestedTargetAttr != null && !targetInjected && !isDiningStop(originalStop)) {
                replacement = requestedTargetAttr;
                targetInjected = true;
            } else {
                List<Attraction> candidates = findCandidates(context, tripUsed, constraints, intent, slotTime);
                if (candidates.isEmpty()) {
                    newStops.add(originalStop);
                    continue;
                }
                replacement = candidates.get(0);
            }

            tripUsed.add(replacement.getId());
            newStops.add(itineraryBuilder.createStop(
                    replacement,
                    stopId,
                    String.valueOf(originalStop.getOrDefault("time", "待安排")),
                    replacement.getDuration(),
                    "根据偏好调整为" + replacement.getName() + "（" + PlannerService.stripTrailingPunctuation(replacement.getSummary()) + "）。",
                    replacement.getIntro()
            ));
            changedSegments.add(stopId);
        }

        if (requestedTargetAttr != null && !targetInjected) {
            String newStopId = "day" + dayNumber + "-added-" + requestedTargetAttr.getId();
            Map<String, Object> added = itineraryBuilder.createStop(
                    requestedTargetAttr,
                    newStopId,
                    "10:00",
                    requestedTargetAttr.getDuration(),
                    "根据偏好新增为" + requestedTargetAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(requestedTargetAttr.getSummary()) + "）。",
                    requestedTargetAttr.getIntro()
            );
            newStops.add(0, added);
            changedSegments.add(newStopId);
            tripUsed.add(requestedTargetAttr.getId());
        }

        // 自适应容量修剪：重排后若单日非餐饮景点超过 4 个，修剪冗余非固定景点，防止总耗时超过 660 分钟触发违规报错
        int nonDiningCount = 0;
        for (Map<String, Object> s : newStops) {
            if (!isDiningStop(s)) nonDiningCount++;
        }
        while (nonDiningCount > 4) {
            int removeIdx = -1;
            for (int i = newStops.size() - 1; i >= 0; i--) {
                Map<String, Object> s = newStops.get(i);
                if (!isDiningStop(s) && !isProtectedStop(s, pinned, constraints)) {
                    removeIdx = i;
                    break;
                }
            }
            if (removeIdx >= 0) {
                newStops.remove(removeIdx);
                nonDiningCount--;
            } else {
                break;
            }
        }

        day.put("stops", newStops);
        List<Map<String, Object>> resolvedDays = resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId, constraints.getTransportPreference());
    }

    private List<Attraction> findCandidates(Attraction oldAttr, Set<String> used, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        return findCandidates(CandidateContext.of(oldAttr), used, constraints, intent, null);
    }

    private List<Attraction> findCandidates(Attraction oldAttr,
                                             Set<String> used,
                                             TravelConstraints constraints,
                                             PlanAdjustmentIntent intent,
                                             String slotTime) {
        return findCandidates(CandidateContext.of(oldAttr), used, constraints, intent, slotTime);
    }

    private List<Attraction> findCandidates(CandidateContext context,
                                             Set<String> used,
                                             TravelConstraints constraints,
                                             PlanAdjustmentIntent intent,
                                             String slotTime) {
        List<Attraction> catalog = attractionService.list(null, null);
        boolean userRequestedSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && (intent.getRawMessage().contains("同片区") || intent.getRawMessage().contains("同区")));

        Attraction oldAttr = context != null ? context.oldAttr() : null;
        boolean oldDistant = oldAttr != null && isDistantSuburban(oldAttr.getDistrict());
        boolean allowDistant = oldDistant || (constraints != null && constraints.getStartPlace() != null && isDistantSuburban(constraints.getStartPlace()));
        String targetDistrict = oldAttr != null && oldAttr.getDistrict() != null && !oldAttr.getDistrict().isBlank()
                ? oldAttr.getDistrict() : "";

        // 当用户明确指定“同片区”替换时，硬性优先同行政区候选
        if (userRequestedSameDistrict && !targetDistrict.isBlank() && !"待确认".equals(targetDistrict)) {
            List<Attraction> sameDistrictCandidates = catalog.stream()
                    .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                    .filter(a -> safe(a.getDistrict()).equals(targetDistrict) || targetDistrict.contains(safe(a.getDistrict())) || safe(a.getDistrict()).contains(targetDistrict))
                    .filter(a -> isOpeningCompatible(a, slotTime))
                    .filter(a -> allowDistant || !isDistantSuburban(a.getDistrict()))
                    .map(a -> new ScoredCandidate(a, scoreCandidate(a, context, constraints, intent)))
                    .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                            .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                    .map(ScoredCandidate::attraction)
                    .limit(3)
                    .toList();
            if (!sameDistrictCandidates.isEmpty()) {
                return sameDistrictCandidates;
            }
        }

        return catalog.stream()
                .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                .filter(a -> isOpeningCompatible(a, slotTime))
                .filter(a -> allowDistant ? (oldAttr == null || isDistantSuburban(a.getDistrict()) == oldDistant) : !isDistantSuburban(a.getDistrict()))
                .map(a -> new ScoredCandidate(a, scoreCandidate(a, context, constraints, intent)))
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed()
                        .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                .map(ScoredCandidate::attraction)
                .limit(3)
                .toList();
    }

    private boolean isDistantSuburban(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足") || district.contains("江津");
    }

    private boolean isDiningStop(Map<String, Object> stop) {
        if (stop == null) return false;
        String type = String.valueOf(stop.get("type"));
        String category = String.valueOf(stop.get("category"));
        String icon = String.valueOf(stop.get("icon"));
        String name = String.valueOf(stop.get("name"));
        String id = String.valueOf(stop.get("id"));
        return "DINING".equalsIgnoreCase(type)
                || "美食".equals(category)
                || "餐".equals(icon)
                || id.contains("dining")
                || name.contains("餐推荐")
                || name.contains("【早餐】")
                || name.contains("【午餐】")
                || name.contains("【晚餐】");
    }

    public static boolean isGenericDiningCraving(String name) {
        if (name == null || name.isBlank()) return true;
        String trimmed = name.trim().replaceAll("^[【\\[“\"']|[】\\]”\"']$", "").trim();
        if (trimmed.isBlank()) return true;

        // 1. 如果包含明确的分店标识 (如 "解放碑店", "(总店)", "黄桷小区店")，或者明确的商户后缀，且不是纯品类词，则为具体餐厅
        boolean hasBranchSuffix = trimmed.matches(".*[（(][^）)]*(?:店|分店|总店)[）)]$")
                || trimmed.matches(".+(?:分店|总店|旗舰店)$");
        if (hasBranchSuffix) {
            return false;
        }

        // 2. 常见泛化/模糊修饰短语
        if (trimmed.matches(".*(附近|周边|同片区|其他|别的|或者|还是|随便|推荐|老字号|中餐|正餐|美食|快餐|吃点|来点|来个|吃个|想吃|想喝|去吃|品尝).*")) {
            return true;
        }
        if (trimmed.contains("或")) {
            return true;
        }

        // 3. 以“的”结尾的修饰短语（如“点甜食的”、“吃甜的”、“吃辣的”、“清淡的”、“好吃的”）
        if (trimmed.endsWith("的")) {
            return true;
        }

        // 4. 纯品类名、风味名、菜品名（不是具体门店商户）
        if (trimmed.matches("^(?:重庆|地道|特色|传统|老|正宗)?(?:火锅|老火锅|九宫格|串串|串串香|烧烤|烤肉|烤鱼|江湖菜|川菜|小面|重庆小面|面条|面馆|抄手|豆花|米线|酸辣粉|小吃|街头小吃|甜食|点甜食|甜点|糖水|冰粉|凉糕|糍粑|点心|蛋糕|烘焙|下午茶|茶歇|休闲茶歇|盖碗茶|品茗|喝茶|茶馆|茶楼|茶室|老茶馆|咖啡|奶茶|早点|早餐|午餐|中餐|晚餐|夜宵|宵夜|正餐|大排档|汤锅|羊肉汤|蹄花|特色菜|私房菜)$")) {
            return true;
        }

        // 5. 动宾短语或泛意图（如“吃火锅”、“吃甜食”、“喝茶”、“喝下午茶”、“吃小面”、“吃烧烤”）
        if (trimmed.matches("^(?:想|要|去|来|选)?(?:吃|喝|尝|品)(?:点|个|下)?(?:火锅|甜食|甜点|糖水|烧烤|烤肉|小吃|小面|江湖菜|川菜|中餐|快餐|正餐|茶|咖啡|奶茶|冰粉|凉糕).*")) {
            return true;
        }

        return false;
    }

    private boolean isDiningIntent(PlanAdjustmentIntent intent) {
        if (intent == null) return false;
        String reqName = safe(intent.getRequestedVenueName());
        String replName = safe(intent.getReplacementPlaceName());
        String rawMsg = safe(intent.getRawMessage());
        String combined = (reqName + " " + replName + " " + rawMsg).toLowerCase(Locale.ROOT);
        if (combined.matches(".*(甜点|甜食|吃甜|糖水|冰粉|凉糕|糍粑|麻花|点心|蛋糕|烘焙|小吃|美食|餐饮|下午茶|茶歇|咖啡|老茶馆|盖碗茶|夜市|烧烤|火锅|老火锅|九宫格|串串|江湖菜|正餐|午餐|晚餐|早点|早餐|小面|歇脚|喝茶).*")) {
            return true;
        }
        if (intent.getPreferences() != null && intent.getPreferences().contains("FOOD")) {
            return true;
        }
        return false;
    }

    private String extractDistrictFromStop(Map<String, Object> stop) {
        if (stop == null) return "渝中区";
        String district = String.valueOf(stop.getOrDefault("district", ""));
        if (!district.isBlank() && !"待确认".equals(district) && district.endsWith("区")) return district;
        String venueId = String.valueOf(stop.getOrDefault("venueId", ""));
        if (!venueId.isBlank() && !"null".equals(venueId) && attractionService != null) {
            try {
                Attraction a = attractionService.get(venueId);
                if (a != null && a.getDistrict() != null && !a.getDistrict().isBlank()) {
                    return a.getDistrict();
                }
            } catch (Exception ignored) {}
        }
        String name = String.valueOf(stop.getOrDefault("name", ""));
        if (attractionService != null && !name.isBlank()) {
            try {
                for (Attraction a : attractionService.list(null, null)) {
                    if (a.getName().equals(name) || (!a.getName().isBlank() && (name.contains(a.getName()) || a.getName().contains(name)))) {
                        if (a.getDistrict() != null && !a.getDistrict().isBlank()) {
                            return a.getDistrict();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        String address = String.valueOf(stop.getOrDefault("address", ""));
        String text = address + " " + name;
        if (text.contains("磁器口") || text.contains("歌乐山") || text.contains("三峡广场") || text.contains("白公馆")
                || text.contains("渣滓洞") || text.contains("大学城") || text.contains("重庆大学") || text.contains("重大")
                || text.contains("平顶山") || text.contains("龙泉洞") || text.contains("融汇温泉")) {
            return "沙坪坝区";
        }
        if (text.contains("解放碑") || text.contains("洪崖洞") || text.contains("朝天门") || text.contains("李子坝")
                || text.contains("十八梯") || text.contains("山城步道") || text.contains("两路口") || text.contains("大坪")
                || text.contains("石油路") || text.contains("鹅岭") || text.contains("三峡博物馆") || text.contains("大礼堂")
                || text.contains("中山四路")) {
            return "渝中区";
        }
        if (text.contains("观音桥") || text.contains("九街") || text.contains("江北嘴") || text.contains("大剧院")
                || text.contains("北仓") || text.contains("鎏嘉码头")) {
            return "江北区";
        }
        if (text.contains("南山") || text.contains("南滨路") || text.contains("弹子石") || text.contains("一棵树")
                || text.contains("老君洞") || text.contains("龙门浩")) {
            return "南岸区";
        }
        for (String d : List.of("万州区", "黔江区", "涪陵区", "渝中区", "大渡口区", "江北区", "沙坪坝区", "九龙坡区", "南岸区", "北碚区", "綦江区", "大足区", "渝北区", "巴南区", "长寿区", "江津区", "合川区", "永川区", "南川区", "璧山区", "铜梁区", "潼南区", "荣昌区", "开州区", "梁平区", "武隆区")) {
            if (text.contains(d) || text.contains(d.substring(0, 2))) return d;
        }
        return "沙坪坝区".equals(stop.get("district")) ? "沙坪坝区" : "渝中区";
    }

    private Attraction resolveStopAnchor(Map<String, Object> stop) {
        if (stop == null) return null;
        Object venueId = stop.get("venueId");
        if (venueId != null && !venueId.toString().isBlank() && itineraryBuilder != null) {
            Attraction anchor = itineraryBuilder.attraction(String.valueOf(venueId));
            if (anchor != null) return anchor;
        }
        Object locObj = stop.get("location");
        if (locObj != null && locObj.toString().contains(",")) {
            try {
                return Attraction.builder()
                        .name(String.valueOf(stop.getOrDefault("name", "")))
                        .district(extractDistrictFromStop(stop))
                        .location(locObj.toString())
                        .build();
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isOpeningCompatible(Attraction attraction, String slotTime) {
        if (attraction == null || slotTime == null || slotTime.isBlank()) return true;
        int slotMinutes = parseTimeMinutes(slotTime);
        if (slotMinutes <= 0) return true;

        // 跨午夜拦截：当天排程大于等于 24:00 (1440 分钟) 或携带跨天标记视为不可行
        if (slotMinutes >= 24 * 60 || slotTime.contains("(+")) {
            return false;
        }

        // 索道与高空观光设施官方营业时间门禁：通常 08:00 - 22:00（21:30 停止检票/售票）
        String attrName = safe(attraction.getName());
        String attrCat = safe(attraction.getCategory());
        boolean isCableway = attrName.contains("索道") || attrCat.contains("索道")
                || (attraction.getTags() != null && attraction.getTags().contains("索道"));
        if (isCableway) {
            if (slotMinutes < 8 * 60 || slotMinutes >= 22 * 60) {
                return false;
            }
        }

        // 博物馆/陈列馆/科技馆类通常 17:00 闭馆
        String bestTime = safe(attraction.getBestTime());
        String category = safe(attraction.getCategory());
        String name = safe(attraction.getName());
        if (bestTime.contains("17:00") || category.contains("博物馆") || name.contains("博物馆") || name.contains("陈列馆")) {
            if (slotMinutes >= 17 * 60) {
                return false;
            }
        }

        return !(bestTime.contains("17:00") && isEveningSlot(slotTime));
    }

    private boolean isEveningSlot(String time) {
        if (time == null) return false;
        return time.contains("18:") || time.contains("19:") || time.contains("20:")
                || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    private boolean isMorningSlot(String time) {
        if (time == null) return false;
        return time.contains("07:") || time.contains("08:") || time.contains("09:")
                || time.contains("早上") || time.contains("早晨") || time.contains("晨间");
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

    private int scoreCandidate(Attraction candidate, Attraction old, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        return scoreCandidate(candidate, CandidateContext.of(old), constraints, intent);
    }

    private int scoreCandidate(Attraction candidate, CandidateContext context, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        int score = 0;
        Attraction old = context == null ? null : context.oldAttr();
        boolean preferSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && (intent.getRawMessage().contains("同片区") || intent.getRawMessage().contains("同区")));

        if (old != null && safe(candidate.getDistrict()).equals(old.getDistrict())) {
            score += preferSameDistrict ? 100 : 30;
        } else if (preferSameDistrict) {
            score -= 100;
        }

        if (constraints != null && "低".equals(constraints.getWalkingTolerance()) && "低".equals(candidate.getWalkDifficulty())) score += 25;
        if (old != null && safe(candidate.getCategory()).equals(old.getCategory())) score += 15;
        if (constraints != null && "带父母".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "少走路")) score += 20;
        if (constraints != null && "带孩子".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("亲子", "科普")) score += 20;

        if (intent != null && intent.getPreferences() != null) {
            for (String p : intent.getPreferences()) {
                if ("INDOOR".equalsIgnoreCase(p)) {
                    if (Boolean.TRUE.equals(candidate.getIndoor()) || "INDOOR".equals(candidate.getEffectiveEnvironment())) {
                        score += 70;
                    } else if (!"MIXED".equals(candidate.getEffectiveEnvironment())) {
                        score -= 80;
                    }
                }
                if ("LOW_WALKING".equalsIgnoreCase(p) && "低".equals(candidate.getWalkDifficulty())) score += 30;
                if ("HISTORY".equalsIgnoreCase(p) && ("人文".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("历史", "古建", "文物", "古镇"))) score += 30;
                if ("NIGHT_VIEW".equalsIgnoreCase(p) && ("夜景".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("夜景", "临江"))) score += 30;
                if ("FOOD".equalsIgnoreCase(p) && ("美食".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("美食", "老字号"))) score += 30;
                if ("NATURE".equalsIgnoreCase(p) && ("自然".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("自然", "峡谷", "喀斯特"))) score += 30;
            }
        }

        // 空间几何距离打分与超距惩罚
        double distKm = calculateDistanceToContext(candidate, context);
        if (distKm <= 3.5) {
            score += 45; // 紧凑邻近加分
        } else if (distKm <= 6.0) {
            score += 20; // 局部辐射圈加分
        } else if (distKm > 6.0) {
            score -= (int) Math.round((distKm - 6.0) * 12); // 超出 6km 阶梯扣分
        }

        // 限时规划（如 <= 360分钟 / 6小时）：出行时间极为紧缺，对远距离施加强力惩罚
        boolean isShortTimeBudget = constraints != null && constraints.getTimeBudgetMinutes() > 0
                && constraints.getTimeBudgetMinutes() <= 360;
        if (isShortTimeBudget) {
            if (distKm > 8.0) {
                score -= 80;
            }
            if (distKm > 12.0) {
                score -= 200; // 严厉淘汰，坚决防止短途规划被拉扯到对角线城区
            }
        }

        return score;
    }

    private double calculateDistanceToContext(Attraction candidate, CandidateContext context) {
        if (candidate == null || candidate.getLocation() == null || !candidate.getLocation().contains(",")) {
            return 8.0;
        }
        String candLoc = candidate.getLocation();
        List<Double> distances = new ArrayList<>();

        if (context != null) {
            if (context.oldAttr() != null && context.oldAttr().getLocation() != null && context.oldAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.oldAttr().getLocation()));
            }
            if (context.prevAttr() != null && context.prevAttr().getLocation() != null && context.prevAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.prevAttr().getLocation()));
            } else if (context.startCoordinate() != null && context.startCoordinate().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.startCoordinate()));
            }
            if (context.nextAttr() != null && context.nextAttr().getLocation() != null && context.nextAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.nextAttr().getLocation()));
            }
        }

        if (distances.isEmpty()) {
            return 5.0;
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        for (double d : distances) {
            sum += d;
            if (d < min) min = d;
        }
        double avg = sum / distances.size();
        return avg * 0.6 + min * 0.4;
    }

    private Attraction resolveStopAttraction(Map<String, Object> stop) {
        if (stop == null) return null;
        String venueId = String.valueOf(stop.get("venueId"));
        Attraction attr = itineraryBuilder.attraction(venueId);
        if (attr != null) return attr;
        String loc = String.valueOf(stop.getOrDefault("location", ""));
        return Attraction.builder()
                .id(venueId)
                .name(String.valueOf(stop.get("name")))
                .district(extractDistrictFromStop(stop))
                .location(loc)
                .build();
    }

    private int findBestMatchingDayForDistrict(Map<String, Object> trip, String district) {
        if (trip == null || district == null || district.isBlank() || "待确认".equals(district)) return -1;
        List<Map<String, Object>> days = days(trip);
        if (days == null || days.isEmpty()) return -1;

        String normDist = district.replaceAll("区$", "");

        int bestDay = -1;
        int maxMatches = 0;

        for (Map<String, Object> day : days) {
            int dayNum = number(day.get("day"));
            List<Map<String, Object>> stops = dayStops(day);
            int matches = 0;
            for (Map<String, Object> stop : stops) {
                String stopDist = extractDistrictFromStop(stop);
                if (stopDist != null && (stopDist.contains(normDist) || normDist.contains(stopDist.replaceAll("区$", "")))) {
                    matches++;
                }
                String addr = String.valueOf(stop.getOrDefault("address", ""));
                if (addr.contains(normDist)) {
                    matches++;
                }
            }
            if (matches > maxMatches) {
                maxMatches = matches;
                bestDay = dayNum;
            }
        }
        return maxMatches > 0 ? bestDay : -1;
    }

    private String extractStartCoordinate(Map<String, Object> draftTrip, TravelConstraints constraints) {
        if (draftTrip != null) {
            if (draftTrip.get("spatialPlan") instanceof Map<?, ?> sp) {
                Object sc = sp.get("resolvedStartCoordinate");
                if (sc != null && !String.valueOf(sc).isBlank()) {
                    return String.valueOf(sc);
                }
            }
            if (draftTrip.get("planContext") instanceof Map<?, ?> pc) {
                Object sc = pc.get("startingCoordinate");
                if (sc != null && !String.valueOf(sc).isBlank()) {
                    return String.valueOf(sc);
                }
            }
        }
        return null;
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

        String slotTime = String.valueOf(originalStop.getOrDefault("time", originalStop.getOrDefault("startTime", "")));

        List<Attraction> candidates = attractionService.list(null, null).stream()
                .filter(a -> !used.contains(a.getId()))
                .filter(a -> original == null || !a.getId().equals(original.getId()))
                .filter(a -> "INDOOR".equals(a.getEffectiveEnvironment()) || "MIXED".equals(a.getEffectiveEnvironment()))
                .filter(a -> !isAvoided(a, constraints))
                .filter(a -> isOpeningCompatible(a, slotTime))
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

        // 1. 优先识别餐饮、茶馆、盖碗茶、甜品、小吃问答，杜绝返回空洞占位文案或误导性景点兜底
        boolean isDiningOrTeaInquiry = rawText.matches(".*(茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|原盅茶|甜食|甜品|糖水|冰粉|凉糕|糍粑|火锅|九宫格|老火锅|串串|烧烤|烤肉|烤脑花|夜市|小吃|美食|特产|特色菜|江湖菜|川菜|小面|面馆|面条|抄手|豆花|早餐|早点|午餐|晚餐|宵夜|夜宵|吃|喝|咖啡|下午茶|小憩).*");
        if (isDiningOrTeaInquiry) {
            return answerDiningOrTeaQuestion(rawText, intent, trip);
        }

        if (stopId != null && !stopId.isBlank()) {
            StopLocation location = locateStop(trip, stopId);
            if (location != null) {
                String type = safe(location.stop().get("type"));
                String name = String.valueOf(location.stop().get("name"));
                String runtimeVenueId = safe(location.stop().get("venueId"));
                boolean isDining = "DINING".equalsIgnoreCase(type)
                        || Boolean.TRUE.equals(location.stop().get("isDining"))
                        || name.matches(".*(餐|饭|馆|烧烤|火锅|小吃|面馆|串串|抄手|烤肉|大排档).*")
                        || runtimeVenueId.matches(".*(dining|restaurant|food).*");
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && runtimeVenueId.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(runtimeVenueId);
                    if (det == null) {
                        return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                    }
                    return runtimeAttractionDetailService.answer(runtimeVenueId);
                }
                String reason = String.valueOf(location.stop().getOrDefault("recommendationReason", ""));
                String summary = String.valueOf(location.stop().getOrDefault("summary", ""));
                String time = String.valueOf(location.stop().getOrDefault("time", ""));
                String aiGuide = String.valueOf(location.stop().getOrDefault("aiGuide", ""));
                String detail = reason.isBlank() ? summary : reason;
                if (!aiGuide.isBlank()) detail += "\n\n✦ 深度游玩导览：" + aiGuide;
                return "【" + name + "】安排在 " + time + "。" + detail;
            }
        }

        for (Map<?, ?> stop : allStops(trip)) {
            String name = String.valueOf(stop.get("name"));
            String venue = String.valueOf(stop.get("venueId"));
            String type = safe(stop.get("type"));
            boolean isDining = "DINING".equalsIgnoreCase(type)
                    || Boolean.TRUE.equals(stop.get("isDining"))
                    || name.matches(".*(餐|饭|馆|烧烤|火锅|小吃|面馆|串串|抄手|烤肉|大排档).*")
                    || venue.matches(".*(dining|restaurant|food).*");
            if (rawText.contains(name) || (venueId != null && venueId.equals(venue))) {
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && venue.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(venue);
                    if (det == null) {
                        return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                    }
                    return runtimeAttractionDetailService.answer(venue);
                }
                String reason = String.valueOf(stop.get("recommendationReason") == null ? "" : stop.get("recommendationReason"));
                String summary = String.valueOf(stop.get("summary") == null ? "" : stop.get("summary"));
                String time = String.valueOf(stop.get("time") == null ? "" : stop.get("time"));
                String aiGuide = String.valueOf(stop.get("aiGuide") == null ? "" : stop.get("aiGuide"));
                String detail = reason.isBlank() ? summary : reason;
                if (!aiGuide.isBlank()) detail += "\n\n✦ 深度游玩导览：" + aiGuide;
                return "【" + name + "】安排在 " + time + "。" + detail;
            }
        }

        for (Attraction a : attractionService.list(null, null)) {
            if (rawText.contains(a.getName()) || (venueId != null && venueId.equals(a.getId()))) {
                return "【" + a.getName() + "】" + a.getSummary() + "（门票：" + a.getTicket() + "，建议游玩：" + a.getDuration() + "）。" + a.getIntro();
            }
        }

        if (runtimeAttractionDetailService != null) {
            String reference = extractPlaceReference(rawText, intent);
            if (!reference.isBlank()) {
                String ans = runtimeAttractionDetailService.answerReference(reference);
                if (ans != null && !ans.contains("暂时没有从高德核验")) {
                    return ans;
                }
                return answerDiningOrTeaQuestion(reference, intent, trip);
            }
        }

        return "关于重庆旅游景点，您可以随时在行程中点击卡片提问，或告诉我具体景点名称。";
    }

    private String answerDiningOrTeaQuestion(String rawText, PlanAdjustmentIntent intent, Map<String, Object> trip) {
        if (diningVenueService == null) {
            diningVenueService = new DiningVenueService();
        }

        // 1. 识别行政区
        String district = null;
        if (rawText.contains("南岸") || rawText.contains("南滨路") || rawText.contains("龙门浩") || rawText.contains("南山") || rawText.contains("弹子石") || rawText.contains("上新街")) {
            district = "南岸区";
        } else if (rawText.contains("渝中") || rawText.contains("解放碑") || rawText.contains("洪崖洞") || rawText.contains("朝天门") || rawText.contains("十八梯") || rawText.contains("戴家巷") || rawText.contains("李子坝") || rawText.contains("鹅岭")) {
            district = "渝中区";
        } else if (rawText.contains("沙坪坝") || rawText.contains("磁器口") || rawText.contains("歌乐山") || rawText.contains("三峡广场")) {
            district = "沙坪坝区";
        } else if (rawText.contains("九龙坡") || rawText.contains("黄桷坪") || rawText.contains("杨家坪")) {
            district = "九龙坡区";
        } else if (rawText.contains("江北") || rawText.contains("观音桥") || rawText.contains("北仓") || rawText.contains("九街") || rawText.contains("大剧院")) {
            district = "江北区";
        } else if (rawText.contains("渝北")) {
            district = "渝北区";
        } else if (rawText.contains("武隆")) {
            district = "武隆区";
        } else if (rawText.contains("大足")) {
            district = "大足区";
        }

        if (district == null && trip != null) {
            for (Map<?, ?> s : allStops(trip)) {
                String d = safe(s.get("district"));
                if (!d.isBlank() && !"待确认".equals(d) && d.endsWith("区")) {
                    district = d;
                    break;
                }
            }
        }
        if (district == null || district.isBlank()) {
            district = "渝中区";
        }

        // 尝试从当前选中站点或行程中提取定位坐标
        String coordinate = null;
        if (intent != null && intent.getTargetStopId() != null && !intent.getTargetStopId().isBlank()) {
            StopLocation loc = locateStop(trip, intent.getTargetStopId());
            if (loc != null && loc.stop() != null) {
                coordinate = safe(loc.stop().get("location"));
            }
        }

        // 2. 识别偏好主题与关键词
        boolean isTea = rawText.matches(".*(茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|原盅茶|老茶馆).*");
        boolean isSweet = rawText.matches(".*(甜食|甜品|糖水|冰粉|凉糕|糍粑|甜点).*");
        boolean isHotpot = rawText.matches(".*(火锅|串串|九宫格|老火锅).*");
        boolean isNoodle = rawText.matches(".*(小面|面馆|面条|牛肉面|抄手|早餐|早点).*");

        String category;
        String kw;
        String headerTitle;
        String tipText;

        if (isTea) {
            category = "TEA_BREAK";
            kw = "茶馆";
            headerTitle = "为您推荐【" + district + "】及周边的特色老茶馆与品茗小憩好去处：";
            tipText = "💡 悠悠贴士：如需在动线中安排品茗歇脚，您可以随时告诉我“在路线中增加一处老茶馆停留”或在行程中点击卡片一键添加！";
        } else if (isSweet) {
            category = "SWEET";
            kw = "甜品";
            headerTitle = "重庆有许多温润可口的手工传统特色甜品与糖水铺，清凉解乏、温润暖胃，特别适合游玩间隙小憩：";
            tipText = "💡 悠悠贴士：如需加入行程，您可以随时对我说“路线中加个甜水铺”或“来碗冰粉”，我来为您顺路规划！";
        } else if (isHotpot) {
            category = "HOTPOT";
            kw = "火锅";
            headerTitle = "为您精选【" + district + "】及周边地道品质老火锅推荐：";
            tipText = "💡 悠悠贴士：带长辈可嘱咐店家选择“微辣牛油”或“鸳鸯锅”，配上清凉手工红糖冰粉解辣更舒适。随时可提出换餐或加站！";
        } else if (isNoodle) {
            category = "BREAKFAST";
            kw = "小面";
            headerTitle = "为您推荐【" + district + "】地道好口碑的重庆小面与早点：";
            tipText = "💡 悠悠贴士：晨间赶路可嘱咐店家二两干熘少辣加蛋，配上一杯热唯怡豆奶更暖胃。";
        } else {
            category = "DINING";
            kw = "特色美食";
            headerTitle = "为您检索到【" + district + "】附近的特色餐饮与地道美食推荐：";
            tipText = "💡 悠悠贴士：您可以随时对我说“把午餐/晚餐换成这家”或“在路线中增加此餐”，我会自动优化时间排程！";
        }

        // 3. 高德实景搜索优先，动态获取商户（绝不使用硬编码兜底）
        List<DiningVenue> venues = diningVenueService.searchLiveDiningOrTea(district, coordinate, kw, category, 3);
        if (venues == null || venues.isEmpty()) {
            venues = diningVenueService.listAll().stream().limit(3).toList();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(headerTitle).append("\n\n");

        int idx = 1;
        for (DiningVenue v : venues) {
            sb.append(idx++).append(". 【").append(v.getName()).append("】（").append(v.getDistrict()).append("）\n");
            if (isTea) {
                sb.append("   • 招牌茶品：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "特色盖碗茶与茶点").append("\n");
            } else if (isSweet) {
                sb.append("   • 招牌必吃：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "手工红糖冰粉、老红糖凉糕").append("\n");
            } else if (isHotpot) {
                sb.append("   • 招牌必点：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "经典牛油红锅、鲜毛肚").append("\n");
            } else if (isNoodle) {
                sb.append("   • 招牌品类：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "地道重庆小面、红油抄手").append("\n");
            } else {
                sb.append("   • 招牌菜品：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "店内招牌特色菜").append("\n");
            }
            sb.append("   • 游玩体验：").append(v.getSummary() != null ? v.getSummary() : "交通便捷，环境舒适。")
              .append("（").append(v.getAverageCost() != null ? v.getAverageCost() : "消费适中")
              .append("，").append(v.getRecommendationReason() != null ? v.getRecommendationReason() : "长辈适老友好")
              .append("）\n\n");
        }

        sb.append(tipText);
        return sb.toString();
    }

    private String extractPlaceReference(String rawText, PlanAdjustmentIntent intent) {
        String explicit = firstNonBlank(intent == null ? null : intent.getTargetAttractionId(),
                intent == null ? null : intent.getReplacementPlaceName(),
                intent == null ? null : intent.getRequestedVenueName());
        if (explicit != null && !explicit.startsWith("cq-")) return explicit;
        if (rawText == null) return "";
        java.util.regex.Matcher bracket = java.util.regex.Pattern.compile("【([^】]+)】|[“‘\"']([^”’\"']+)[”’\"']").matcher(rawText);
        if (bracket.find()) return firstNonBlank(bracket.group(1), bracket.group(2));
        return "";
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
        if (stop != null && ("DINING".equals(stop.get("type")) || "餐".equals(stop.get("icon")))) {
            return true;
        }
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

    private String resolveTripCity(Map<String, Object> trip, PlannerSessionRepositoryPort.StoredSession session, PlanAdjustmentIntent intent) {
        if (intent != null) {
            String venue = safeText(intent.getRequestedVenueName());
            String raw = safeText(intent.getRawMessage());
            if (venue != null && venue.contains("成都") || raw != null && raw.contains("成都")) return "成都市";
            if (venue != null && venue.contains("西安") || raw != null && raw.contains("西安")) return "西安市";
            if (venue != null && venue.contains("重庆") || raw != null && raw.contains("重庆")) return "重庆市";
        }
        if (session != null) {
            String prompt = safeText(session.prompt());
            if (prompt != null && prompt.contains("成都")) return "成都市";
            if (prompt != null && prompt.contains("西安")) return "西安市";
            if (prompt != null && prompt.contains("重庆")) return "重庆市";
            if (session.constraints() != null) {
                Object dest = session.constraints().get("destination");
                if (dest != null && !String.valueOf(dest).isBlank()) {
                    String d = String.valueOf(dest);
                    if (d.contains("成都")) return "成都市";
                    if (d.contains("西安")) return "西安市";
                    if (d.contains("重庆")) return "重庆市";
                }
            }
        }
        if (trip != null) {
            Object cityObj = trip.get("city");
            if (cityObj != null && !String.valueOf(cityObj).isBlank()) {
                String c = String.valueOf(cityObj);
                if (c.contains("成都")) return "成都市";
                if (c.contains("西安")) return "西安市";
                if (c.contains("重庆")) return "重庆市";
            }
            Object destObj = trip.get("destination");
            if (destObj != null && !String.valueOf(destObj).isBlank()) {
                String d = String.valueOf(destObj);
                if (d.contains("成都")) return "成都市";
                if (d.contains("西安")) return "西安市";
                if (d.contains("重庆")) return "重庆市";
            }
            for (Map<String, Object> day : days(trip)) {
                for (Map<String, Object> stop : dayStops(day)) {
                    String name = safeText(String.valueOf(stop.getOrDefault("name", "")));
                    String dist = safeText(String.valueOf(stop.getOrDefault("district", "")));
                    if ((name != null && name.contains("成都")) || (dist != null && (dist.contains("成都") || dist.matches(".*(锦江|青羊|金牛|武侯|成华|高新|天府新区|双流|郫都|温江|新都|龙泉驿|都江堰).*")))) {
                        return "成都市";
                    }
                    if ((name != null && name.contains("西安")) || (dist != null && (dist.contains("西安") || dist.matches(".*(碑林|雁塔|新城|莲湖|未央|灞桥|长安|临潼).*")))) {
                        return "西安市";
                    }
                }
            }
        }
        return "重庆市";
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

    private int parseTimeMinutes(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return 720;
        try {
            String clean = timeStr.replaceAll("[^0-9:]", "").trim();
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                int hours = Integer.parseInt(parts[0]);
                int mins = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return hours * 60 + mins;
            }
        } catch (Exception ignored) {}
        return 720;
    }

    private int parseDurationMinutes(String durationStr) {
        if (durationStr == null || durationStr.isBlank()) return 50;
        try {
            Matcher m = Pattern.compile("(\\d+)").matcher(durationStr);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return 50;
    }

    private String formatTime(int totalMinutes) {
        if (totalMinutes >= 24 * 60) {
            int overDays = totalMinutes / (24 * 60);
            int rem = totalMinutes % (24 * 60);
            return String.format(Locale.ROOT, "%02d:%02d(+%dd)", rem / 60, rem % 60, overDays);
        }
        int norm = ((totalMinutes % (24 * 60)) + (24 * 60)) % (24 * 60);
        int h = norm / 60;
        int m = norm % 60;
        return String.format(Locale.ROOT, "%02d:%02d", h, m);
    }

    /**
     * 级联推迟后序站点时间（Ripple Timing Recalculation），彻底消除时钟倒流与时间重叠。
     * 从 startIndex 开始，依据前序站点的结束时间 + 缓冲时长（默认 15 分钟），依次顺延调整后续站点的 time 与 startTime。
     */
    private void rippleRecalculateStopTimes(List<Map<String, Object>> stops, int startIndex) {
        if (stops == null || startIndex <= 0 || startIndex >= stops.size()) {
            return;
        }
        int maxIterations = Math.min(stops.size(), 50);
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < startIndex; i++) {
            String sId = safe(stops.get(i).get("id"));
            if (!sId.isBlank()) seenIds.add(sId);
        }
        for (int i = startIndex; i < stops.size() && i < maxIterations; i++) {
            Map<String, Object> prev = stops.get(i - 1);
            Map<String, Object> curr = stops.get(i);
            String currId = safe(curr.get("id"));
            if (!currId.isBlank() && !seenIds.add(currId)) {
                // 侦测到环形重复节点，截断顺延防止拓扑死锁循环
                break;
            }
            String prevTimeStr = String.valueOf(prev.getOrDefault("time", ""));
            if (prevTimeStr.isBlank()) {
                prevTimeStr = String.valueOf(prev.getOrDefault("startTime", ""));
            }
            int prevStart = parseTimeMinutes(prevTimeStr);
            int prevDur = parseDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
            if (prevDur <= 0) prevDur = 50;
            int nextStart = prevStart + prevDur + 15;
            String newSlotTime = formatTime(nextStart);
            curr.put("time", newSlotTime);
            curr.put("startTime", newSlotTime);
        }
    }

    private Integer extractExplicitTimeMinutes(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m1 = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(text);
        if (m1.find()) {
            int h = Integer.parseInt(m1.group(1));
            int m = Integer.parseInt(m1.group(2));
            if (h >= 0 && h <= 24 && m >= 0 && m < 60) {
                return h * 60 + m;
            }
        }
        Matcher m2 = Pattern.compile("(晚上|夜间|今晚|下午|上午|早上|早晨)?\\s*(十[一二]|[一二三四五六七八九十]|\\d{1,2})\\s*(?:点|时)(?:整)?\\s*(半|三刻|一刻|\\d{1,2}分)?").matcher(text);
        if (m2.find()) {
            String period = m2.group(1);
            String hourStr = m2.group(2);
            String minStr = m2.group(3);
            int hour = parseChineseOrArabicHour(hourStr);
            if (hour >= 0) {
                if ("晚上".equals(period) || "夜间".equals(period) || "今晚".equals(period) || "下午".equals(period)) {
                    if (hour < 12) {
                        hour += 12;
                    }
                }
                int minute = 0;
                if ("半".equals(minStr)) {
                    minute = 30;
                } else if ("一刻".equals(minStr)) {
                    minute = 15;
                } else if ("三刻".equals(minStr)) {
                    minute = 45;
                } else if (minStr != null && minStr.endsWith("分")) {
                    try {
                        minute = Integer.parseInt(minStr.replace("分", ""));
                    } catch (Exception ignored) {}
                }
                return hour * 60 + minute;
            }
        }
        return null;
    }

    private int parseChineseOrArabicHour(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {}
        return switch (s) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            default -> -1;
        };
    }

    private record ApplyReplay(String requestFingerprint, PlannerService.ServiceResult result) {}
    private record StopLocation(Map<String, Object> day, List<Map<String, Object>> stops, int index, Map<String, Object> stop) {}
    private record ScoredCandidate(Attraction attraction, int score) {}
    private record RainCandidateScore(Attraction attraction, double score) {}
    private record CandidateContext(Attraction oldAttr, Attraction prevAttr, Attraction nextAttr, String startCoordinate) {
        public static CandidateContext of(Attraction oldAttr) {
            return new CandidateContext(oldAttr, null, null, null);
        }
    }
}


package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大模型对话式调整意图提取与结构化解析服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：调用大模型解析复杂、隐晦的用户自然语言意图，并与规则分类器执行一致性核验与置信度熔断。
 */
@Service
public class LlmIntentExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentExtractor.class);
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("(?:第\\s*)?([一二两三四五12345])\\s*(?:个|项|号|方案)?");
    private static final Pattern EXPLICIT_DAY_PATTERN = Pattern.compile("(第[一二三四五六七八九十0-9]+天|day\\s*[0-9]+|今天|明天|后天|首日|第一天|末日|最后一天)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_COUNT_PATTERN = Pattern.compile("少(?:推荐|安排)?\\s*([一二两三四五1234567890]+)\\s*个|少两个|少两个景点");

    private static final String SYSTEM_PROMPT = """
            你是“渝游智策”行程详情页的自然语言意图理解器。
            根据用户对当前行程的调整诉求或提问，结合给出的行程上下文（天数、站点名与ID、当前选定状态），提取结构化意图 JSON。

            你必须仅输出一个合法的 JSON 对象，不要包含 Markdown 围栏代码块（```json）或其他解释文字。

            JSON 结构与字段定义：
            {
              "operation": "REPLACE_STOP | ADD_STOP | REMOVE_STOP | REPLAN_DAY | REDUCE_DENSITY | APPLY_REPLACEMENT | QA | UNKNOWN",
              "intentType": "同 operation 映射值，如 SUGGEST_REPLACEMENTS, ADD_STOP, REMOVE_STOP, REPLAN_DAY, REDUCE_DAY_DENSITY, PLACE_QUESTION, CLARIFICATION, UNKNOWN",
              "scope": "STOP | DAY | TRIP",
              "targetDayReference": "第二天/明天/首日/8月30日 等原始词或 null",
              "targetDay": 整数(如1, 2, 3)或 null,
              "targetStopReference": "李子坝/这个景点/三峡博物馆 等原始词或 null",
              "targetStopId": "若能从上下文站点列表中明确匹配到具体stopId则填入(如 day2-liziba)否则null",
              "requestedVenueName": "用户想要加入或提问的具体景点名称(如 北仓)或 null",
              "replacementPlaceName": "用户明确指定的替换目标景点名称(如 重庆大剧院外围广场)或 null",
              "replacementPlaceId": "若上下文或景点目录中能明确匹配替换目标ID则填入，否则null",
              "preferences": ["INDOOR", "LOW_WALKING", "HISTORY", "NIGHT_VIEW", "FOOD", "CULTURE", "RELAXED"],
              "conditions": ["RAIN", "HIGH_TEMPERATURE", "CROWDED"],
              "candidateOrdinal": 整数(1,2,3)或 null,
              "requestedReductionCount": 整数或 null,
              "confidence": 0.0 到 1.0 的浮点数,
              "requiresClarification": true 或 false,
              "clarificationQuestion": "当关键信息缺失导致无法生成确定性调整方案时的单轮澄清问题，否则为 null"
            }

            意图分类指南：
            1. REPLACE_STOP (SUGGEST_REPLACEMENTS)：用户想替换、更换某个景点（如“换掉李子坝”、“这个景点不想去，有替换方案吗”）。
            2. ADD_STOP：用户想在某天添加或增加景点（如“第二天加上北仓”、“第1天加个室内景点”）。
            3. REMOVE_STOP：用户想删除或移除某个景点（如“删除李子坝”、“把这个景点去掉”）。
            4. REPLAN_DAY：用户想对某一天进行整体重排或条件重排（如“重新规划第二天，少走路”、“第二天下雨”、“改一下第二天全部行程”）。
            5. REDUCE_DENSITY (REDUCE_DAY_DENSITY)：用户觉得太赶、太累或想少安排景点（如“今天太赶了，少推荐一个”、“第二天太累了”）。
            6. QA (PLACE_QUESTION)：用户只是询问关于某个景点或行程的问题（如“为什么推荐洪崖洞？”、“三峡博物馆几点开门”），不想修改行程。
            7. APPLY_REPLACEMENT：用户要求应用候选方案（如“换成第一个”、“选方案二”）。
            8. UNKNOWN：无法识别的请求，或要求生成非本旅行领域的内容。

            消歧与澄清原则：
            - 若用户说“把第二天景点改一下”或“改第二天”，目标天数明确但未说明具体诉求，请设置 requiresClarification=true，并在 clarificationQuestion 中给出清晰选项。
            - 若用户说“换掉这个景点”且上下文中有 selectedStopId，信息完整，无需追问。
            """;

    private final IntentModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final ConversationIntentClassifier fallbackClassifier;
    private final boolean enabled;
    private final boolean credentialsAvailable;
    private final double minimumConfidence;
    private final long timeoutMs;
    private final Executor modelExecutor;

    /** Production constructor: reuse the existing Spring AI OpenAI-compatible ChatClient. */
    @Autowired
    public LlmIntentExtractor(ChatClient.Builder builder,
                              ObjectMapper objectMapper,
                              ConversationIntentClassifier fallbackClassifier,
                              @Value("${planner.llm.intent.enabled:false}") boolean enabled,
                              @Value("${planner.llm.intent.minimum-confidence:0.75}") double minimumConfidence,
                              @Value("${planner.llm.intent.timeout-ms:3500}") long timeoutMs,
                              @Value("${spring.ai.openai.api-key:}") String apiKey,
                              @Qualifier("plannerLlmExecutor") Executor modelExecutor) {
        ChatClient chatClient = builder.build();
        this.modelClient = (systemPrompt, userPrompt) -> chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .call()
                .content();
        this.objectMapper = objectMapper;
        this.fallbackClassifier = fallbackClassifier;
        this.enabled = enabled;
        this.credentialsAvailable = apiKey != null && !apiKey.isBlank();
        this.minimumConfidence = normalizeMinimumConfidence(minimumConfidence);
        this.timeoutMs = Math.max(500L, timeoutMs);
        this.modelExecutor = modelExecutor;
    }

    /** Test constructor allowing a fake provider without making network calls. */
    LlmIntentExtractor(IntentModelClient modelClient,
                       ObjectMapper objectMapper,
                       ConversationIntentClassifier fallbackClassifier,
                       boolean enabled,
                       boolean credentialsAvailable,
                       double minimumConfidence,
                       long timeoutMs) {
        this(modelClient, objectMapper, fallbackClassifier, enabled, credentialsAvailable,
                minimumConfidence, timeoutMs, Runnable::run);
    }

    /** Test constructor allowing an explicit executor boundary. */
    LlmIntentExtractor(IntentModelClient modelClient,
                       ObjectMapper objectMapper,
                       ConversationIntentClassifier fallbackClassifier,
                       boolean enabled,
                       boolean credentialsAvailable,
                       double minimumConfidence,
                       long timeoutMs,
                       Executor modelExecutor) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.fallbackClassifier = fallbackClassifier;
        this.enabled = enabled;
        this.credentialsAvailable = credentialsAvailable;
        this.minimumConfidence = normalizeMinimumConfidence(minimumConfidence);
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.modelExecutor = modelExecutor;
    }

    public LlmIntentExtractionResult extract(String message, PlanPageContext context) {
        return extract(message, context, null);
    }

    public LlmIntentExtractionResult extract(String message, PlanPageContext context, Map<String, Object> trip) {
        long started = System.nanoTime();
        String text = message == null ? "" : message.trim();
        PlanPageContext effectiveContext = context == null ? new PlanPageContext() : context;
        PlanAdjustmentIntent deterministic = fallbackClassifier.classify(text, effectiveContext);

        if (text.isBlank()) {
            return fallback(deterministic, null, "DETERMINISTIC_FALLBACK", started);
        }
        if (!enabled) {
            return fallback(deterministic, "FEATURE_DISABLED", "DETERMINISTIC_FALLBACK", started);
        }
        if (!credentialsAvailable) {
            return fallback(deterministic, "CREDENTIAL_UNAVAILABLE", "DETERMINISTIC_FALLBACK", started);
        }

        String userPrompt = buildUserPrompt(text, effectiveContext, trip);
        String response;
        CompletableFuture<String> modelFuture = null;
        try {
            modelFuture = CompletableFuture.supplyAsync(() -> modelClient.complete(SYSTEM_PROMPT, userPrompt), modelExecutor);
            response = modelFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return fallback(deterministic, "OVERLOADED", "DETERMINISTIC_FALLBACK", started);
        } catch (TimeoutException e) {
            if (modelFuture != null) modelFuture.cancel(true);
            return fallback(deterministic, "TIMEOUT", "DETERMINISTIC_FALLBACK", started);
        } catch (InterruptedException e) {
            if (modelFuture != null) modelFuture.cancel(true);
            Thread.currentThread().interrupt();
            return fallback(deterministic, "TIMEOUT", "DETERMINISTIC_FALLBACK", started);
        } catch (Exception e) {
            return fallback(deterministic, "PROVIDER_ERROR", "DETERMINISTIC_FALLBACK", started);
        }

        try {
            PlanAdjustmentIntent llmIntent = parse(response, text, effectiveContext);
            if (llmIntent.getConfidence() < minimumConfidence) {
                return fallback(deterministic, "LOW_CONFIDENCE", "DETERMINISTIC_FALLBACK", started);
            }
            PlanAdjustmentIntent merged = merge(llmIntent, deterministic, effectiveContext, text);
            validate(merged, effectiveContext);
            return success(merged, started);
        } catch (Exception e) {
            return fallback(deterministic, "INVALID_OUTPUT", "DETERMINISTIC_FALLBACK", started);
        }
    }

    private PlanAdjustmentIntent parse(String response, String text, PlanPageContext context) throws Exception {
        if (response == null || response.isBlank()) throw new IllegalArgumentException("empty structured output");
        Matcher matcher = JSON_OBJECT.matcher(response.trim());
        if (!matcher.find()) throw new IllegalArgumentException("missing JSON object");
        JsonNode root = objectMapper.readTree(matcher.group());
        if (root == null || !root.isObject()) throw new IllegalArgumentException("structured output is not an object");

        String opValue = textValue(root, "operation");
        if (opValue == null) opValue = textValue(root, "intentType");
        if (opValue == null) throw new IllegalArgumentException("missing operation/intentType");

        ConversationIntentType type = mapOperationToType(opValue);
        Double confidence = numberValue(root, "confidence");
        if (confidence == null || confidence.isNaN() || confidence.isInfinite() || confidence < 0 || confidence > 1) {
            confidence = 0.85;
        }

        String scope = textValue(root, "scope");
        String targetDayRef = textValue(root, "targetDayReference");
        Integer targetDay = integerValue(root, "targetDay");
        String targetStopRef = textValue(root, "targetStopReference");
        String targetStopId = textValue(root, "targetStopId");
        String requestedVenue = textValue(root, "requestedVenueName");
        String replacementPlaceName = textValue(root, "replacementPlaceName");
        String replacementPlaceId = textValue(root, "replacementPlaceId");
        Integer candidateOrdinal = integerValue(root, "candidateOrdinal");
        Integer requestedReductionCount = integerValue(root, "requestedReductionCount");
        String condition = textValue(root, "condition");
        String clarificationQuestion = textValue(root, "clarificationQuestion");
        boolean reqClarification = root.has("requiresClarification") && root.get("requiresClarification").asBoolean();

        List<String> preferences = new ArrayList<>();
        if (root.has("preferences") && root.get("preferences").isArray()) {
            root.get("preferences").forEach(p -> {
                if (p.isTextual() && !p.asText().isBlank()) preferences.add(p.asText().trim().toUpperCase(Locale.ROOT));
            });
        }

        List<String> conditions = new ArrayList<>();
        if (condition != null && !condition.isBlank()) conditions.add(condition);
        if (root.has("conditions") && root.get("conditions").isArray()) {
            root.get("conditions").forEach(c -> {
                if (c.isTextual() && !c.asText().isBlank() && !conditions.contains(c.asText().trim().toUpperCase(Locale.ROOT))) {
                    conditions.add(c.asText().trim().toUpperCase(Locale.ROOT));
                }
            });
        }

        return PlanAdjustmentIntent.builder()
                .type(type)
                .operation(opValue)
                .scope(scope)
                .targetDayReference(targetDayRef)
                .dayNumber(targetDay)
                .targetStopReference(targetStopRef)
                .targetStopId(targetStopId)
                .requestedVenueName(requestedVenue)
                .replacementPlaceName(replacementPlaceName)
                .replacementPlaceId(replacementPlaceId)
                .targetAttractionId(type == ConversationIntentType.ADD_STOP ? (requestedVenue != null ? requestedVenue : targetStopRef) : null)
                .candidateIndex(candidateOrdinal)
                .optionId(type == ConversationIntentType.APPLY_REPLACEMENT && candidateOrdinal != null
                        ? "option-" + candidateOrdinal : null)
                .reduceCount(requestedReductionCount)
                .condition(condition)
                .preferences(preferences)
                .conditions(conditions)
                .pinnedStopIds(new ArrayList<>(safePinned(context)))
                .proposalId(safeText(context.getActiveProposalId()))
                .clarificationQuestion(clarificationQuestion)
                .confidence(confidence)
                .requiresClarification(reqClarification || type == ConversationIntentType.CLARIFICATION)
                .rawMessage(text)
                .build();
    }

    private static ConversationIntentType mapOperationToType(String op) {
        String normalized = op.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "REPLACE_STOP", "SUGGEST_REPLACEMENTS" -> ConversationIntentType.SUGGEST_REPLACEMENTS;
            case "ADD_STOP" -> ConversationIntentType.ADD_STOP;
            case "REMOVE_STOP" -> ConversationIntentType.REMOVE_STOP;
            case "REPLAN_DAY_FOR_CONDITION" -> ConversationIntentType.REPLAN_DAY_FOR_CONDITION;
            case "REPLAN_DAY" -> ConversationIntentType.REPLAN_DAY;
            case "REDUCE_DENSITY", "REDUCE_DAY_DENSITY" -> ConversationIntentType.REDUCE_DAY_DENSITY;
            case "APPLY_REPLACEMENT" -> ConversationIntentType.APPLY_REPLACEMENT;
            case "QA", "PLACE_QUESTION" -> ConversationIntentType.PLACE_QUESTION;
            case "CLARIFICATION" -> ConversationIntentType.CLARIFICATION;
            default -> ConversationIntentType.UNKNOWN;
        };
    }

    private PlanAdjustmentIntent merge(PlanAdjustmentIntent llm,
                                       PlanAdjustmentIntent deterministic,
                                       PlanPageContext context,
                                       String text) {
        boolean explicitDay = EXPLICIT_DAY_PATTERN.matcher(text).find();
        boolean explicitCount = EXPLICIT_COUNT_PATTERN.matcher(text).find();
        Integer explicitOrdinal = extractOrdinal(text);

        Integer day = explicitDay && deterministic.getDayNumber() != null
                ? deterministic.getDayNumber()
                : llm.getDayNumber() != null ? llm.getDayNumber()
                : deterministic.getDayNumber() != null ? deterministic.getDayNumber() : context.getActiveDay();

        Integer count = explicitCount && deterministic.getReduceCount() != null
                ? deterministic.getReduceCount() : llm.getReduceCount();
        if (count == null) count = deterministic.getReduceCount();

        Integer candidateIndex = explicitOrdinal != null ? explicitOrdinal : llm.getCandidateIndex();
        if (candidateIndex == null) candidateIndex = deterministic.getCandidateIndex();

        String targetAttraction = safeText(deterministic.getTargetAttractionId()) != null
                ? deterministic.getTargetAttractionId() : llm.getTargetAttractionId();
        String replacementPlace = safeText(deterministic.getReplacementPlaceId()) != null
                ? deterministic.getReplacementPlaceId() : llm.getReplacementPlaceId();
        String replacementPlaceName = safeText(deterministic.getReplacementPlaceName()) != null
                ? deterministic.getReplacementPlaceName() : llm.getReplacementPlaceName();
        String requestedVenue = safeText(deterministic.getRequestedVenueName()) != null
                ? deterministic.getRequestedVenueName() : llm.getRequestedVenueName();
        String targetStopReference = safeText(deterministic.getTargetStopReference()) != null
                ? deterministic.getTargetStopReference() : llm.getTargetStopReference();
        String targetDayReference = safeText(deterministic.getTargetDayReference()) != null
                ? deterministic.getTargetDayReference() : llm.getTargetDayReference();
        String selectedStop = safeText(context.getSelectedStopId());
        String targetStop = safeText(targetAttraction) != null ? null
                : safeText(deterministic.getTargetStopId()) != null ? deterministic.getTargetStopId()
                : safeText(llm.getTargetStopId()) != null ? llm.getTargetStopId() : selectedStop;
        String condition = deterministic.getType() == ConversationIntentType.REPLAN_DAY_FOR_CONDITION
                && safeText(deterministic.getCondition()) != null
                ? deterministic.getCondition() : llm.getCondition();
        List<String> preferences = mergedValues(deterministic.getPreferences(), llm.getPreferences());
        List<String> conditions = mergedValues(deterministic.getConditions(), llm.getConditions());
        if (condition != null && !condition.isBlank() && !conditions.contains(condition.toUpperCase(Locale.ROOT))) {
            conditions.add(condition.toUpperCase(Locale.ROOT));
        }
        boolean deterministicExactReplacement = deterministic.getType() == ConversationIntentType.SUGGEST_REPLACEMENTS
                && safeText(deterministic.getReplacementPlaceId()) != null;
        // 页面明确选中站点后请求“同片区/室内/低步行替换候选”时，目标已由 UI
        // 完整提供。模型可以补充偏好，但不能退化为“请先选择景点”。
        boolean deterministicSelectedReplacement = deterministic.getType() == ConversationIntentType.SUGGEST_REPLACEMENTS
                && safeText(deterministic.getTargetStopId()) != null;
        boolean deterministicNarrativeReplan = deterministic.getType() == ConversationIntentType.REPLAN_DAY
                && "TRIP".equalsIgnoreCase(deterministic.getScope());
        ConversationIntentType type = deterministicExactReplacement || deterministicSelectedReplacement || deterministicNarrativeReplan
                ? deterministic.getType() : llm.getType();
        if (type == ConversationIntentType.REPLAN_DAY && conditions.contains("RAIN")) {
            type = ConversationIntentType.REPLAN_DAY_FOR_CONDITION;
            condition = "RAIN";
        }

        String optionId = type == ConversationIntentType.APPLY_REPLACEMENT && candidateIndex != null
                ? "option-" + candidateIndex : llm.getOptionId();
        String proposalId = safeText(context.getActiveProposalId());
        if (proposalId == null) proposalId = llm.getProposalId();

        boolean deterministicOperationWins = deterministicExactReplacement || deterministicSelectedReplacement || deterministicNarrativeReplan;
        return PlanAdjustmentIntent.builder()
                .type(type)
                .operation(deterministicOperationWins ? deterministic.getOperation()
                        : (llm.getOperation() != null ? llm.getOperation() : deterministic.getOperation()))
                .scope(deterministicOperationWins ? deterministic.getScope()
                        : (llm.getScope() != null ? llm.getScope() : deterministic.getScope()))
                .targetDayReference(targetDayReference)
                .dayNumber(day)
                .targetStopReference(targetStopReference)
                .targetStopId(targetStop)
                .targetAttractionId(targetAttraction)
                .requestedVenueName(requestedVenue)
                .replacementPlaceName(replacementPlaceName)
                .replacementPlaceId(replacementPlace)
                .candidateIndex(candidateIndex)
                .optionId(optionId)
                .reduceCount(count)
                .condition(condition)
                .preferences(preferences)
                .conditions(conditions)
                .pinnedStopIds(new ArrayList<>(safePinned(context)))
                .proposalId(proposalId)
                .timeSlot(llm.getTimeSlot())
                .clarificationQuestion(llm.getClarificationQuestion())
                .confidence(llm.getConfidence())
                .requiresClarification(deterministicOperationWins
                        ? deterministic.isRequiresClarification()
                        : (llm.isRequiresClarification() || type == ConversationIntentType.CLARIFICATION))
                .rawMessage(text)
                .build();
    }

    private List<String> mergedValues(List<String> deterministic, List<String> llmValues) {
        List<String> merged = new ArrayList<>();
        for (String value : deterministic == null ? List.<String>of() : deterministic) {
            if (value != null && !value.isBlank()) merged.add(value.trim().toUpperCase(Locale.ROOT));
        }
        for (String value : llmValues == null ? List.<String>of() : llmValues) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isBlank() && !merged.contains(normalized)) merged.add(normalized);
        }
        return merged;
    }

    private void validate(PlanAdjustmentIntent intent, PlanPageContext context) {
        if (intent == null || intent.getType() == null || intent.getType() == ConversationIntentType.UNKNOWN) {
            throw new IllegalArgumentException("missing or unknown intent");
        }
        if (intent.getDayNumber() != null && (intent.getDayNumber() < 1 || intent.getDayNumber() > 31)) {
            throw new IllegalArgumentException("invalid target day");
        }
        if (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT
                && (intent.getCandidateIndex() == null || intent.getCandidateIndex() < 1 || intent.getCandidateIndex() > 3)) {
            throw new IllegalArgumentException("invalid replacement candidate");
        }
        if (intent.getType() == ConversationIntentType.REDUCE_DAY_DENSITY
                && (intent.getReduceCount() == null || intent.getReduceCount() < 1 || intent.getReduceCount() > 10)) {
            throw new IllegalArgumentException("invalid reduction count");
        }
        if (intent.getType() == ConversationIntentType.REPLAN_DAY_FOR_CONDITION
                && safeText(intent.getCondition()) == null) {
            throw new IllegalArgumentException("missing condition");
        }
        if (intent.getType() == ConversationIntentType.CLARIFICATION
                && safeText(intent.getClarificationQuestion()) == null) {
            throw new IllegalArgumentException("missing clarification question");
        }
        if (intent.getType() == ConversationIntentType.APPLY_REPLACEMENT
                && safeText(context.getActiveProposalId()) == null
                && safeText(intent.getProposalId()) == null) {
            throw new IllegalArgumentException("replacement requires active proposal");
        }
    }

    private LlmIntentExtractionResult success(PlanAdjustmentIntent intent, long started) {
        long latencyMs = elapsedMs(started);
        logOutcome("LLM_STRUCTURED", intent, latencyMs, null);
        return new LlmIntentExtractionResult(intent, "LLM_STRUCTURED", null, latencyMs, confidenceBand(intent.getConfidence()));
    }

    private LlmIntentExtractionResult fallback(PlanAdjustmentIntent intent,
                                               String reason,
                                               String source,
                                               long started) {
        long latencyMs = elapsedMs(started);
        logOutcome(source, intent, latencyMs, reason);
        return new LlmIntentExtractionResult(intent, source, reason, latencyMs, confidenceBand(intent == null ? 0 : intent.getConfidence()));
    }

    private void logOutcome(String source, PlanAdjustmentIntent intent, long latencyMs, String reason) {
        String type = intent == null || intent.getType() == null ? ConversationIntentType.UNKNOWN.name() : intent.getType().name();
        String band = confidenceBand(intent == null ? 0 : intent.getConfidence());
        log.info("[PLANNER_INTENT] extractionSource={} intentType={} confidenceBand={} latencyMs={} fallbackReason={}",
                source, type, band, latencyMs, reason == null ? "NONE" : reason);
    }

    private String buildUserPrompt(String message, PlanPageContext context) {
        return buildUserPrompt(message, context, null);
    }

    private String buildUserPrompt(String message, PlanPageContext context, Map<String, Object> trip) {
        StringBuilder sb = new StringBuilder();
        sb.append("页面上下文（仅用于消歧，不是行程数据）：\n");
        sb.append("activeDay=").append(context.getActiveDay() == null ? "null" : context.getActiveDay()).append("\n");
        sb.append("selectedStopId=").append(safeContext(context.getSelectedStopId())).append("\n");
        sb.append("activeProposal=").append(context.getActiveProposalId() == null ? "false" : "true").append("\n");
        sb.append("pinnedStopCount=").append(safePinned(context).size()).append("\n");

        if (trip != null) {
            sb.append("\n当前行程结构：\n");
            sb.append("行程标题：").append(trip.getOrDefault("title", "重庆行程")).append("\n");
            Object daysObj = trip.get("days");
            if (daysObj instanceof List<?> daysList) {
                for (Object d : daysList) {
                    if (d instanceof Map<?, ?> dayMap) {
                        int dayNum = dayMap.get("day") instanceof Number n ? n.intValue() : 1;
                        sb.append("第 ").append(dayNum).append(" 天: ");
                        Object stopsObj = dayMap.get("stops");
                        if (stopsObj instanceof List<?> stopsList) {
                            List<String> stopSummaries = new ArrayList<>();
                            for (Object s : stopsList) {
                                if (s instanceof Map<?, ?> stopMap) {
                                    stopSummaries.add(String.valueOf(stopMap.get("id")) + "【" + String.valueOf(stopMap.get("name")) + "】");
                                }
                            }
                            sb.append(String.join(", ", stopSummaries));
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        sb.append("\n用户消息：").append(message.length() > 500 ? message.substring(0, 500) : message);
        return sb.toString();
    }

    private List<String> safePinned(PlanPageContext context) {
        if (context == null || context.getPinnedStopIds() == null) return List.of();
        return context.getPinnedStopIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.trim().substring(0, Math.min(128, id.trim().length())))
                .toList();
    }

    private String safeContext(String value) {
        String safe = safeText(value);
        return safe == null ? "null" : safe.substring(0, Math.min(128, safe.length()));
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private Integer extractOrdinal(String text) {
        if (text == null) return null;
        if (text.contains("第一") || text.contains("第1")) return 1;
        if (text.contains("第二") || text.contains("第2")) return 2;
        if (text.contains("第三") || text.contains("第3")) return 3;
        if (text.contains("第四") || text.contains("第4")) return 4;
        if (text.contains("第五") || text.contains("第5")) return 5;
        Matcher matcher = ORDINAL_PATTERN.matcher(text);
        if (!matcher.find()) return null;
        return switch (matcher.group(1)) {
            case "一", "1" -> 1;
            case "二", "两", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            default -> null;
        };
    }

    private String textValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isValueNode()) return null;
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer integerValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) return null;
        if (node.isInt() || node.isLong()) return node.intValue();
        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Double numberValue(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || !node.isNumber()) return null;
        return node.doubleValue();
    }

    private long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private String confidenceBand(double confidence) {
        if (confidence >= 0.85) return "HIGH";
        if (confidence >= minimumConfidence) return "MEDIUM";
        return "LOW";
    }

    private double normalizeMinimumConfidence(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.75;
        return Math.min(1.0, Math.max(0.0, value));
    }

    @FunctionalInterface
    interface IntentModelClient {
        String complete(String systemPrompt, String userPrompt);
    }

    public record LlmIntentExtractionResult(
            PlanAdjustmentIntent intent,
            String intentExtractionSource,
            String llmFallbackReason,
            long latencyMs,
            String confidenceBand
    ) {
        public Map<String, Object> metadata() {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("intentType", intent == null || intent.getType() == null ? "UNKNOWN" : intent.getType().name());
            metadata.put("intentExtractionSource", intentExtractionSource);
            metadata.put("fallbackReason", llmFallbackReason == null ? "" : llmFallbackReason);
            metadata.put("confidenceBand", confidenceBand);
            metadata.put("latencyMs", latencyMs);
            metadata.put("llmFallbackReason", llmFallbackReason == null ? "" : llmFallbackReason);
            metadata.put("intentConfidenceBand", confidenceBand);
            metadata.put("intentLatencyMs", latencyMs);
            return metadata;
        }
    }
}

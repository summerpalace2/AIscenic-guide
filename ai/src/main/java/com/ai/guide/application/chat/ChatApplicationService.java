package com.ai.guide.application.chat;

import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.application.chat.dto.ChatStreamEvent;
import com.ai.guide.application.chat.dto.ChatStructuredCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.chat.model.ScenicResponse;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.chat.service.ChatDomainPolicy;
import com.ai.guide.domain.chat.service.ChatContextBudgetPolicy;
import com.ai.guide.domain.chat.service.IntentRadarService;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.domain.chat.service.SlotTrackingService;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * AI 导游对话应用服务 (Chat Application Service)
 *
 * 架构定位：COLA 应用层 (Application Layer)。
 * 核心职责：统筹编排一次完整的导游对话操作，协调知识库检索、意图分析、偏好追踪、
 * 上下文压缩、大模型流式调用及历史会话持久化，将核心业务流程与控制器彻底解耦。
 */
@Service
public class ChatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChatApplicationService.class);

    private final LanguageModelPort chatModelGateway;
    private final ChatConversationMemoryPort conversationMemory;
    private final SlotTrackingService slotTrackingService;
    private final IntentService intentService;
    private final SentimentService sentimentService;
    private final AnalyticsService analyticsService;
    private final ChatHistoryContextCoordinator historyContextCoordinator;
    private final ChatRetrievalCoordinator chatRetrievalCoordinator;
    private final ChatPromptContextAssembler promptContextAssembler;
    private ChatStreamOutputAdapter chatStreamOutputAdapter = new ChatStreamOutputAdapter();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private TravelMemoryService travelMemoryService;

    @Autowired(required = false)
    private PreferencesService preferencesService;

    @Autowired(required = false)
    private IntentRadarService intentRadarService;

    @Value("${chat.stream.timeout-ms}")
    private long streamTimeoutMs;

    private static final String SYSTEM_PROMPT = ChatDomainPolicy.SYSTEM_PROMPT;
    private static final Pattern CHITCHAT_PATTERN = Pattern.compile("^(好的|嗯|哦|哈哈|谢谢|再见|拜拜|晚安|明白了|行|可以|对|是的|没错|好吧|好哒|好|ok|yes|no|yeah|lol|thx|thanks|bye|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你好|您好)$", Pattern.CASE_INSENSITIVE);

    @Autowired
    public ChatApplicationService(LanguageModelPort chatModelGateway,
                                  ChatConversationMemoryPort conversationMemory,
                                  SlotTrackingService slotTrackingService,
                                  IntentService intentService,
                                  SentimentService sentimentService,
                                  AnalyticsService analyticsService,
                                  ChatHistoryContextCoordinator historyContextCoordinator,
                                  ChatRetrievalCoordinator chatRetrievalCoordinator,
                                  ChatPromptContextAssembler promptContextAssembler) {
        this.conversationMemory = conversationMemory;
        this.slotTrackingService = slotTrackingService;
        this.intentService = intentService;
        this.sentimentService = sentimentService;
        this.analyticsService = analyticsService;
        this.historyContextCoordinator = historyContextCoordinator;
        this.chatRetrievalCoordinator = chatRetrievalCoordinator;
        this.promptContextAssembler = promptContextAssembler;
        this.chatModelGateway = chatModelGateway;
    }

    @Autowired(required = false)
    public void setChatStreamOutputAdapter(ChatStreamOutputAdapter adapter) {
        if (adapter != null) this.chatStreamOutputAdapter = adapter;
    }

    /**
     * 流式对话编排入口
     */
    public Flux<ChatStreamEvent> streamChat(ChatStreamCommand command) {
        String userId = command.userId();
        String username = command.username();
        String role = command.role();
        long pipelineTimeout = Math.max(1000L, streamTimeoutMs + 15000L);

        return Flux.defer(() -> {
            var writeEpoch = new java.util.concurrent.atomic.AtomicReference<String>();
            return Flux.defer(() -> {
            if (userId == null) {
                UserContext.clear();
            } else {
                UserContext.set(userId, username, role);
            }
            try {
                writeEpoch.set(conversationMemory.currentWriteEpoch(buildSessionKey(command.sessionId(),userId)));
                return streamChatInternal(command,writeEpoch.get())
                        .doFinally(signal -> UserContext.clear());
            } catch (Throwable error) {
                UserContext.clear();
                return Flux.error(error);
            } finally { UserContext.clear(); }
        })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(pipelineTimeout))
                .onErrorResume(error -> {
                    boolean timedOut = error instanceof TimeoutException;
                    String sessionKey = buildSessionKey(command.sessionId(), userId);
                    String fallbackReply = error instanceof TravelMemoryService.MemoryContextOverflowException || error instanceof ChatContextBudgetPolicy.ContextBudgetException || error instanceof ChatContextBudgetPolicy.ContextReadException
                            ? error.getMessage() : timedOut ? "本次请求等待超时，可重新提问。" : "AI 服务暂时不可用，请稍后重试";
                    try {
                        if(writeEpoch.get()!=null) conversationMemory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.ASSISTANT, fallbackReply)),writeEpoch.get());
                    } catch(RuntimeException historyUnavailable) { log.warn("[CHAT_HISTORY] fallback persistence unavailable; session={}",sessionKey); }
                    log.warn("[CHAT_STREAM_PIPELINE_END] session={} kind={} latencyMs={}",
                            sessionKey, timedOut ? "PIPELINE_TIMEOUT" : "PIPELINE_ERROR", pipelineTimeout);
                    return Flux.just(
                            new ChatStreamEvent("error", fallbackReply),
                            new ChatStreamEvent("done", "complete"));
                });
            });
    }

    private Flux<ChatStreamEvent> streamChatInternal(ChatStreamCommand command,String writeEpoch) {
        String message = command.message();
        String sessionId = command.sessionId();
        String mode = command.mode();
        String plannerSessionId = command.plannerSessionId();
        String tripId = command.tripId();
        String currentVersion = command.currentVersion();
        String activeDay = command.activeDay();
        String activeStopId = command.activeStopId();
        String activeStopName = command.activeStopName();
        String planContext = command.planContext();

        String userId = command.userId();
        String sessionKey = buildSessionKey(sessionId, userId);
        historyContextCoordinator.rememberCurrentConstraints(sessionKey, message,writeEpoch);
        boolean hasPlannerContext = plannerSessionId != null && !plannerSessionId.isBlank();

        log.info("[CHAT_STREAM_START] mode={} session={} plannerSession={} trip={} version={} messageLength={}",
                mode, sessionKey, plannerSessionId, tripId, currentVersion, message == null ? 0 : message.length());

        // 0. 自动槽位追踪（普通聊天）
        if (!hasPlannerContext && userId != null && !userId.isBlank()) {
            try {
                slotTrackingService.extractAndSave(userId, message);
            } catch (Exception ignored) {}
        }

        // 1. 情感 + 意图
        Ctx ctx = analyze(message);
        var recentHistory = conversationMemory.loadHistory(sessionKey, 50);
        if (recentHistory == null) throw new ChatContextBudgetPolicy.ContextReadException();
        String retrievalCity = chatRetrievalCoordinator.resolveCity(message, planContext, activeStopName, recentHistory.messages());

        // 2. RAG 知识检索决策
        ChatRetrievalCoordinator.ChatRetrieval retrieval;
        try {
            if (shouldSkipKnowledge(message)) {
                retrieval = ChatRetrievalCoordinator.ChatRetrieval.notNeeded("简短回应不需要知识库检索。");
            } else {
                retrieval = chatRetrievalCoordinator.retrieveChat(message, "deep".equals(mode), retrievalCity);
            }
        } catch (Exception e) {
            log.warn("[Chat] 检索异常，安全回退: {}", e.getMessage());
            retrieval = ChatRetrievalCoordinator.unknownRetrieval("RETRIEVAL_UNAVAILABLE", "知识检索暂不可用，当前无法核实所问结论。");
        }

        String context = ChatDomainPolicy.protectRetrievedContext(retrieval.context());
        if (context.startsWith("__JAVA_RAG_UNKNOWN__") && (!"待核实".equals(retrieval.status()) || !context.equals(retrieval.context()))) {
            retrieval = new ChatRetrievalCoordinator.ChatRetrieval(context, "待核实", retrieval.source(),
                    "背景资料已隔离，所问结论仍无法确认。", false, List.of());
        }
        if (!chatRetrievalCoordinator.usesFixedAnswer(retrieval) && !chatRetrievalCoordinator.isNonFactual(retrieval)) {
            context = chatRetrievalCoordinator.appendNearbyPoiContext(context, message, activeDay, activeStopId, activeStopName, retrievalCity);
            context = chatRetrievalCoordinator.appendTargetPlacePoiContext(context, message, activeStopId, activeStopName, retrievalCity);
        }
        debugLogContext(message, context);

        log.info("[CHAT_STREAM_RAG_READY] session={} status={} source={} verified={}",
                sessionKey, retrieval.status(), retrieval.source(), retrieval.verified());

        final String metadata = chatMetadataJson(mode, retrieval.status(), retrieval.source(), retrieval.reason(),
                retrieval.verified(), retrieval.citations(), ctx.intent(), message, plannerSessionId, tripId, currentVersion);

        // 3. 构建多轮上下文消息
        String modelContext = ("不可用".equals(retrieval.status()) && (context == null || context.isBlank()))
                ? "__JAVA_RAG_UNAVAILABLE__ " + retrieval.reason() : context;
        boolean unknownConclusion = chatRetrievalCoordinator.usesFixedAnswer(retrieval);
        String assistantMessage = unknownConclusion ? "" : promptContextAssembler.plannerContextMessage(message, plannerSessionId, tripId, currentVersion);
        List<LanguageModelMessage> allMessages = unknownConclusion ? List.of() : promptContextAssembler.assemble(modelContext, assistantMessage, sessionKey, ctx.sentiment(), planContext, hasPlannerContext, userId, travelMemoryService, preferencesService);
        conversationMemory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.USER, message)),writeEpoch);

        // 4. 流式组装
        Flux<ChatStreamEvent> metadataFlux = Flux.just(new ChatStreamEvent("meta", metadata));

        SentimentService.Sentiment sentiment = ctx.sentiment();
        Flux<ChatStreamEvent> sentimentFlux = sentiment == SentimentService.Sentiment.NEUTRAL ? Flux.empty() :
                Flux.just(new ChatStreamEvent("sentiment", sentiment.name().toLowerCase()));

        Flux<ChatStreamEvent> stream = chatStreamOutputAdapter.adapt(
                unknownConclusion ? Flux.just(chatRetrievalCoordinator.fixedAnswer(retrieval)) : chatModelGateway.stream(allMessages),
                streamTimeoutMs, sessionKey, sessionId, message,
                sentiment, ctx.intent(), conversationMemory, analyticsService,writeEpoch);

        Flux<ChatStreamEvent> doneFlux = Flux.just(new ChatStreamEvent("done", "complete"));

        return metadataFlux.concatWith(sentimentFlux).concatWith(stream).concatWith(doneFlux);
    }

    /**
     * 结构化对话编排入口
     */
    public Result<ScenicResponse> chatStructured(ChatStructuredCommand command) {
        String userId = command.userId();
        String sessionId = command.sessionId();
        String message = command.message();
        String sessionKey = buildSessionKey(sessionId, userId);
        String writeEpoch;
        try { writeEpoch=conversationMemory.currentWriteEpoch(sessionKey);historyContextCoordinator.rememberCurrentConstraints(sessionKey, message,writeEpoch); }
        catch (ChatContextBudgetPolicy.ContextBudgetException overflow) { return Result.error(422, overflow.getMessage()); }
        catch (ChatContextBudgetPolicy.ContextReadException unavailable) { return Result.error(503, unavailable.getMessage()); }

        slotTrackingService.extractAndSave(userId, message);

        Ctx ctx = analyze(message);
        ChatRetrievalCoordinator.ChatRetrieval retrieval;
        try {
            var history = conversationMemory.loadHistory(sessionKey, 50);
            if (history == null) return Result.error(503, "对话历史暂不可用");
            String city = chatRetrievalCoordinator.resolveCity(message, "", "", history.messages());
            retrieval = shouldSkipKnowledge(message)
                    ? ChatRetrievalCoordinator.ChatRetrieval.notNeeded("闲聊不需要知识库检索。")
                    : chatRetrievalCoordinator.retrieveChat(message, "deep".equals(command.mode()), city);
        } catch (RuntimeException unavailable) {
            retrieval = ChatRetrievalCoordinator.unknownRetrieval("RETRIEVAL_UNAVAILABLE", "知识检索暂不可用。");
        }
        String context = ChatDomainPolicy.protectRetrievedContext(retrieval.context());
        if (context.startsWith("__JAVA_RAG_UNKNOWN__") && (!"待核实".equals(retrieval.status()) || !context.equals(retrieval.context())))
            retrieval = new ChatRetrievalCoordinator.ChatRetrieval(context,"待核实",retrieval.source(),"所问结论暂无法核实。",false,List.of());
        List<LanguageModelMessage> allMessages;
        try {
            allMessages = chatRetrievalCoordinator.usesFixedAnswer(retrieval) ? List.of()
                    : promptContextAssembler.assemble(context, message, sessionKey, ctx.sentiment(), "", false, userId, travelMemoryService, preferencesService);
        } catch (TravelMemoryService.MemoryContextOverflowException | ChatContextBudgetPolicy.ContextBudgetException overflow) {
            return Result.error(422, overflow.getMessage());
        } catch (ChatContextBudgetPolicy.ContextReadException unavailable) {
            return Result.error(503, unavailable.getMessage());
        }
        conversationMemory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.USER, message)),writeEpoch);

        ScenicResponse response = chatRetrievalCoordinator.usesFixedAnswer(retrieval)
                ? new ScenicResponse(List.of(), chatRetrievalCoordinator.fixedAnswer(retrieval))
                : chatModelGateway.complete(allMessages, ScenicResponse.class);

        if (response != null) {
            conversationMemory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.ASSISTANT, response.toString())),writeEpoch);
            if (analyticsService != null) {
                analyticsService.logService(sessionId, message, ctx.sentiment().name(), ctx.intent().name(), 0);
            }
            return Result.success("查询成功", response);
        }
        return Result.error(500, "AI 未返回有效数据");
    }

    private String buildSessionKey(String sessionId, String userId) {
        if (userId == null || userId.isBlank()) {
            return "guest:" + (sessionId == null || sessionId.isBlank() || "default".equals(sessionId)
                    ? java.util.UUID.randomUUID().toString() : sessionId);
        }
        return userId + ":" + sessionId;
    }

    private void debugLogContext(String message, String context) {
        log.debug("==================================================");
        log.debug("【调试日志】用户问题: " + message);
        log.debug("--------------------------------------------------");
        log.debug("【AI 接收到的背景知识原文】：");
        if (context != null && !context.isEmpty()) {
            log.debug(context);
        }
        log.debug("==================================================");
    }

    private static ChatConversationMemoryPort.StoredMessage storedMessage(ChatConversationMemoryPort.Role role, String content) {
        return new ChatConversationMemoryPort.StoredMessage(role, content);
    }

    private String chatMetadataJson(String mode, String retrievalStatus, String retrievalSource,
                                    String retrievalReason, boolean verified, List<?> citations,
                                    IntentService.Intent intent, String message, String plannerSessionId, String tripId,
                                    String currentVersion) {
        var generation = new java.util.LinkedHashMap<String, Object>();
        generation.put("status", "已连接");
        generation.put("provider", "服务端模型");
        generation.put("model", "服务端已配置模型");
        var assistant = new java.util.LinkedHashMap<String, Object>();
        assistant.put("intent", assistantIntent(intent, message));
        assistant.put("requiresConfirmation", "MODIFY_PLAN".equals(assistantIntent(intent, message))
                || "UPDATE_PREFERENCE".equals(assistantIntent(intent, message)));
        assistant.put("plannerSessionId", promptContextAssembler.safeContext(plannerSessionId));
        assistant.put("tripId", promptContextAssembler.safeContext(tripId));
        assistant.put("currentVersion", promptContextAssembler.safeContext(currentVersion));

        List<IntentRadarService.ActionableSuggestion> suggestions = List.of();
        IntentRadarService.DetectedPreference detectedPref = null;
        if (intentRadarService != null) {
            IntentRadarService.RadarResult radar = intentRadarService.scan(message);
            if (radar != null) {
                detectedPref = radar.preference();
                suggestions = radar.suggestions() == null ? List.of() : radar.suggestions();
                if (detectedPref != null) {
                    assistant.put("detectedPreference", Map.of(
                            "domain", detectedPref.domain(),
                            "trait", detectedPref.trait(),
                            "confidence", detectedPref.confidence()
                    ));
                }
                if (!suggestions.isEmpty()) {
                    assistant.put("actionableSuggestions", suggestions);
                }
            }
        }

        var retrieval = new java.util.LinkedHashMap<String, Object>();
        retrieval.put("mode", "deep".equals(mode) ? "deep" : "normal");
        retrieval.put("status", retrievalStatus);
        retrieval.put("source", retrievalSource);
        retrieval.put("verified", verified);
        retrieval.put("reason", retrievalReason);
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("generation", generation);
        payload.put("assistant", assistant);
        payload.put("retrieval", retrieval);
        payload.put("citations", citations == null ? List.of() : citations);
        if (detectedPref != null) {
            payload.put("detectedPreference", Map.of(
                    "domain", detectedPref.domain(),
                    "trait", detectedPref.trait(),
                    "confidence", detectedPref.confidence()
            ));
        }
        if (!suggestions.isEmpty()) {
            payload.put("actionableSuggestions", suggestions);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("无法序列化对话元数据", e);
            return "{\"generation\":{\"status\":\"已连接\"},\"retrieval\":{\"status\":\"未知\"}}";
        }
    }

    private String assistantIntent(IntentService.Intent intent, String message) {
        String text = message == null ? "" : message;
        if (text.matches(".*(替换|换掉|换一个|移除|删除|加入|添加|调整|改成|不要这个).*")) return "MODIFY_PLAN";
        if (text.matches(".*(记住|以后|偏好|喜欢|不喜欢|默认|习惯).*")) return "UPDATE_PREFERENCE";
        if (text.matches(".*(为什么这样安排|解释.*行程|规划依据|安排依据).*")) return "EXPLAIN_PLAN";
        if (intent == null) return "TRAVEL_QA";
        return switch (intent) {
            case CHITCHAT, COMPLAINT -> "VENT_OR_SMALL_TALK";
            case NAVIGATION, QA, UNKNOWN -> "TRAVEL_QA";
        };
    }

    private record Ctx(SentimentService.Sentiment sentiment, IntentService.Intent intent, boolean isNeg) {}

    private Ctx analyze(String message) {
        var s = sentimentService.analyze(message);
        var i = intentService.classify(message);
        return new Ctx(s, i, s == SentimentService.Sentiment.NEGATIVE || i == IntentService.Intent.COMPLAINT);
    }

    private boolean shouldSkipKnowledge(String message) {
        if (message == null) return false;
        return CHITCHAT_PATTERN.matcher(message.trim()).find();
    }

}


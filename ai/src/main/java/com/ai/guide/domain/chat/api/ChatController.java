package com.ai.guide.domain.chat.api;

import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.chat.service.ChatDomainPolicy;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.domain.chat.service.RedisChatMemory;
import com.ai.guide.domain.chat.service.SlotTrackingService;
import com.ai.guide.domain.rag.service.ParallelRagService;
import com.ai.guide.domain.rag.service.QueryDecompositionService;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.ai.guide.domain.rag.service.ScenicDataImportService;
import com.ai.guide.domain.planner.service.AmapRouteService;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.chat.model.ScenicResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * AI 文旅导游流式对话与意图分发控制器 (Chat Controller)
 *
 * 所属领域：domain.chat (AI 导游流式交互域)
 * 架构职责：作为 AI 导游对话的核心交互通道，基于 Spring AI 与响应式编程 (Project Reactor Flux) 实现 Server-Sent Events (SSE) 流式分发，完成多轮意图识别、槽位追踪与文旅知识检索增强。
 *
 * 核心对外接口与关键方法：
 * 1. {@link #chatStream}: SSE 流式对话端点。
 *    - 关键参数：message (用户输入文本), sessionId (客户端会话标识), mode (normal=标准模式 / deep=深度检索增强), plannerSessionId (可选关联的规划会话), tripId (可选关联的正式行程), currentVersion (当前版本)。
 *    - 返回结果：Flux<ServerSentEvent<String>> 流式事件流（包含 meta 元数据包、text 内容块、done 结束标记与异常 event）。
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    // Chat pipeline 身份绑定：渝游智策 / 重庆智慧文旅 AI 决策助手。
    // 统一提示词和旧领域防护集中在 ChatDomainPolicy 中。

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;
    private final RedisChatMemory redisChatMemory;
    private final SlotTrackingService slotTrackingService;
    private final IntentService intentService;
    private final SentimentService sentimentService;
    private final AnalyticsService analyticsService;

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private QueryDecompositionService queryDecompositionService;

    @Autowired
    private ParallelRagService parallelRagService;

    @Autowired(required = false)
    private AmapRouteService amapRouteService;

    @Autowired(required = false)
    private TravelMemoryService travelMemoryService;

    @Autowired(required = false)
    private PreferencesService preferencesService;

    private static final String SYSTEM_PROMPT = ChatDomainPolicy.SYSTEM_PROMPT;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${chat.stream.timeout-ms:45000}")
    private long streamTimeoutMs;

    /** 构造 ChatClient 和注入 Redis 记忆组件 */
    public ChatController(ChatClient.Builder builder, RedisChatMemory redisChatMemory,
                          SlotTrackingService slotTrackingService,
                          IntentService intentService, SentimentService sentimentService,
                                                    AnalyticsService analyticsService,
                          @Qualifier("compressionExecutor") ExecutorService compressionExecutor) {
        this.redisChatMemory = redisChatMemory;
        this.slotTrackingService = slotTrackingService;
        this.intentService = intentService;
        this.sentimentService = sentimentService;
        this.analyticsService = analyticsService;
        this.compressionExecutor = compressionExecutor;
        this.chatClient = builder.build();
    }

    /** 将知识库上下文拼入用户消息（双模式：有知识=问答 / 无知识=闲聊） */
    private String buildUserPrompt(String context, String message) {
        if (context != null && context.startsWith("__JAVA_RAG_UNAVAILABLE__")) {
            String reason = context.substring("__JAVA_RAG_UNAVAILABLE__".length()).trim();
            return String.format("""
                    【检索状态】：不可用
                    【原因】：%s
                    【用户发言】：%s

                    请不要编造或断言任何未核验的重庆景区、路线、票价或天气事实；可以明确说明当前检索不可用，并继续帮助用户整理旅行约束。
                    """, reason, message);
        }
        if (ChatDomainPolicy.isLegacyCorpusMarker(context)) {
            return String.format("""
                    【用户发言】：%s

                    背景知识说明：非重庆旧景区资料已隔离。请以“渝游智策 / 重庆智慧文旅 AI 决策助手”身份，帮助用户梳理重庆行程与偏好需求。
                    """, message);
        }
        if (context != null && !context.isBlank() && !context.startsWith("__JAVA_RAG_UNAVAILABLE__")) {
            return String.format("""
                    请基于下方【背景知识】与重庆旅游事实进行专业答复。结构清晰，条理分明，结合用户行程偏好给出景点、美食与游览建议。

                    问题：%s

                    背景知识：%s
                    """, message, context);
        }
        // 通用问答与友好推荐模式
        return String.format("""
                【用户发言】：%s

                请以贴心的“渝游智策·重庆文旅 AI 决策助手”身份进行专业解答与建议：
                - 若用户询问景点、美食、路线或游玩建议，结合重庆真实地标与巴渝风土人情给出实用推荐，并引导用户微调或补充行程
                - 若涉及打招呼或闲聊，简洁温暖回应
                - 结构清晰，排版舒适，使用 Markdown 格式展现重点
                """, message);
    }

    /** 调试日志 */
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

    /**
     * 多账户安全：将会话 ID 与用户绑定
     * 格式：userId:sessionId，确保用户只能访问自己的会话
     */
    private String buildSessionKey(String sessionId) {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank()) {
            return "guest:" + (sessionId == null || sessionId.isBlank() ? "default" : sessionId);
        }
        return userId + ":" + sessionId;
    }

    /**
     * 流式对话接口
     * 流程：意图分析 → 知识检索（可选）→ 构建消息 → 流式响应 → 保存历史
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestParam("message") String message,
                                @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                @RequestParam(value = "mode", defaultValue = "normal") String mode,
                                @RequestParam(value = "plannerSessionId", defaultValue = "") String plannerSessionId,
                                @RequestParam(value = "tripId", defaultValue = "") String tripId,
                                 @RequestParam(value = "currentVersion", defaultValue = "") String currentVersion,
                                 @RequestParam(value = "activeDay", defaultValue = "") String activeDay,
                                 @RequestParam(value = "activeStopId", defaultValue = "") String activeStopId,
                                 @RequestParam(value = "activeStopName", defaultValue = "") String activeStopName,
                                 @RequestParam(value = "planContext", defaultValue = "") String planContext) {
        String userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        String role = UserContext.getRole();
        long pipelineTimeout = Math.max(1000L, streamTimeoutMs + 15000L);

        // Controller preparation includes blocking RAG/Redis/provider setup. Defer it
        // onto a bounded scheduler so the HTTP response can still terminate when
        // any pre-stream dependency stops responding.
        return Flux.defer(() -> {
            if (userId == null) UserContext.clear();
            else UserContext.set(userId, username, role);
            try {
                return chatStreamInternal(message, sessionId, mode, plannerSessionId, tripId,
                        currentVersion, activeDay, activeStopId, activeStopName, planContext)
                        .doFinally(signal -> UserContext.clear());
            } catch (Throwable error) {
                UserContext.clear();
                return Flux.error(error);
            }
        })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(pipelineTimeout))
                .onErrorResume(error -> {
                    boolean timedOut = error instanceof TimeoutException;
                    log.warn("[CHAT_STREAM_PIPELINE_END] session={} kind={} latencyMs={}",
                            buildSessionKey(sessionId), timedOut ? "PIPELINE_TIMEOUT" : "PIPELINE_ERROR", pipelineTimeout);
                    return Flux.just(
                            ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data(timedOut ? "本次请求等待超时，可重新提问。" : "AI 服务暂时不可用，请稍后重试")
                                    .build(),
                            ServerSentEvent.<String>builder().event("done").data("complete").build());
                });
    }

    private Flux<ServerSentEvent<String>> chatStreamInternal(String message, String sessionId, String mode,
                                 String plannerSessionId, String tripId, String currentVersion,
                                 String activeDay, String activeStopId, String activeStopName, String planContext) {
        String userId = UserContext.getUserId();
        String sessionKey = buildSessionKey(sessionId);
        boolean hasPlannerContext = plannerSessionId != null && !plannerSessionId.isBlank();
        log.info("[CHAT_STREAM_START] mode={} session={} plannerSession={} trip={} version={} messageLength={}",
                mode, sessionKey, plannerSessionId, tripId, currentVersion, message == null ? 0 : message.length());

        // 0. 自动槽位仅用于普通聊天。规划会话使用本次规划的偏好快照，
        // 绝不能把“以前带父母出游”等历史槽位写入或带入当前情侣/亲子等行程。
        if (!hasPlannerContext && userId != null && !userId.isBlank()) {
            try {
                slotTrackingService.extractAndSave(userId, message);
            } catch (Exception ignored) {}
        }

        // 1. 情感 + 意图（只计算一次）
        Ctx ctx = analyze(message);

        // 2. 通过 Java 结构化 RAG 边界检索；Browser 只接收状态、引用和模型文本。
        ChatRetrieval retrieval;
        try {
            if ("deep".equals(mode)) {
                retrieval = retrieveChat(message, true);
            } else if ("你好".equals(message.trim()) || "您好".equals(message.trim())) {
                retrieval = ChatRetrieval.notNeeded("问候语不需要知识库检索。");
            } else if (ctx.intent() == IntentService.Intent.CHITCHAT) {
                retrieval = ChatRetrieval.notNeeded("闲聊内容不需要知识库检索。");
            } else if (ctx.intent() == IntentService.Intent.COMPLAINT) {
                log.info("[Chat] 负面情绪/投诉 detected, skipping knowledge");
                retrieval = ChatRetrieval.notNeeded("投诉或情绪表达不需要知识库检索。");
            } else if (shouldSkipKnowledge(message)) {
                retrieval = ChatRetrieval.notNeeded("简短回应不需要知识库检索。");
            } else {
                retrieval = retrieveChat(message, false);
            }
        } catch (Exception e) {
            log.warn("[Chat] 检索异常，安全回退: {}", e.getMessage());
            retrieval = new ChatRetrieval("", "不可用", "java-rag", "知识检索暂不可用，模型基于基础知识回答。", false, List.of());
        }
        String context = ChatDomainPolicy.protectRetrievedContext(retrieval.context());
        context = appendNearbyPoiContext(context, message, activeDay, activeStopId, activeStopName);
        debugLogContext(message, context);
        log.info("[CHAT_STREAM_RAG_READY] session={} status={} source={} verified={}",
                sessionKey, retrieval.status(), retrieval.source(), retrieval.verified());
        final String metadata = chatMetadataJson(mode, retrieval.status(), retrieval.source(), retrieval.reason(),
                retrieval.verified(), retrieval.citations(), ctx.intent(), message, plannerSessionId, tripId, currentVersion);

        // 3. 构建 messages（使用 sessionKey 存取历史）。规划上下文只作为
        // 当前草稿的用户提供信息，模型不得把它当成已提交的 Trip 变更。
        String modelContext = "不可用".equals(retrieval.status())
                ? "__JAVA_RAG_UNAVAILABLE__ " + retrieval.reason() : context;
        String assistantMessage = plannerContextMessage(message, plannerSessionId, tripId, currentVersion);
        List<Message> allMessages = buildMessages(modelContext, assistantMessage, sessionKey, ctx, planContext, hasPlannerContext);
        redisChatMemory.addAsync(sessionKey, List.of(new UserMessage(message)));

        // 4. 用于累积完整回复文本的容器
        StringBuilder fullReply = new StringBuilder();
        long streamStart = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean firstChunk = new java.util.concurrent.atomic.AtomicBoolean(true);

        // 5. 元数据与情感事件：先告诉 Browser 本轮是否真的经过 Java 检索，再发送模型文本。
        Flux<ServerSentEvent<String>> metadataFlux = Flux.just(ServerSentEvent.<String>builder()
                .event("meta")
                .data(metadata)
                .build());
        SentimentService.Sentiment sentiment = ctx.sentiment();
        Flux<ServerSentEvent<String>> sentimentFlux = sentiment == SentimentService.Sentiment.NEUTRAL ? Flux.empty() :
            Flux.just(ServerSentEvent.<String>builder()
                .event("sentiment")
                .data(sentiment.name().toLowerCase())
                .build());

        // 6. AI 流式回复
        Flux<ServerSentEvent<String>> stream = chatClient.prompt()
                .messages(allMessages)
                .stream()
                .content()
                .timeout(Duration.ofMillis(Math.max(1000L, streamTimeoutMs)))
                .filter(content -> content != null && !content.isEmpty())
                .map(content -> {
                    if (firstChunk.compareAndSet(true, false)) {
                        log.info("[CHAT_STREAM_FIRST_CHUNK] session={} latencyMs={}", sessionKey,
                                System.currentTimeMillis() - streamStart);
                    }
                    fullReply.append(content);
                    return ServerSentEvent.builder(content).event("text").build();
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("[CHAT_STREAM_END] session={} kind=EMPTY_PROVIDER_STREAM latencyMs={}",
                            sessionKey, System.currentTimeMillis() - streamStart);
                    return Flux.just(ServerSentEvent.<String>builder()
                            .event("error")
                            .data("AI 服务未返回内容，请稍后重试。")
                            .build());
                }))
                .doOnComplete(() -> {
                    String reply = fullReply.toString();
                    if (!reply.isEmpty()) {
                        redisChatMemory.addAsync(sessionKey, List.of(new AssistantMessage(reply)));
                        log.info("[CHAT_STREAM_COMPLETE] session={} latencyMs={} replyLength={}", sessionKey,
                                System.currentTimeMillis() - streamStart, reply.length());
                        // 记录分析日志
                        long duration = System.currentTimeMillis() - streamStart;
                        if (analyticsService != null) {
                            analyticsService.logService(sessionId, message, sentiment.name(), ctx.intent().name(), duration);
                        }
                    }
                })
                .onErrorResume(error -> {
                        long durationMs = System.currentTimeMillis() - streamStart;
                        boolean timedOut = error instanceof TimeoutException;
                        log.warn("[CHAT_STREAM_END] session={} kind={} latencyMs={}",
                                sessionKey, timedOut ? "TIMEOUT" : "PROVIDER_ERROR", durationMs);
                        return Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data(timedOut ? "本次回复等待超时，你可以重新提问。" : "AI 服务暂时不可用，请稍后重试")
                                .build());
                    });

        Flux<ServerSentEvent<String>> doneFlux = Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data("complete")
                .build());
        return metadataFlux.concatWith(sentimentFlux).concatWith(stream).concatWith(doneFlux);
    }

    /**
     * 构建消息列表（含系统提示、上下文压缩、知识库上下文、用户偏好）
     * 多账户：使用 userId 读取槽位偏好
     */
    private List<Message> buildMessages(String context, String message, String sessionKey, Ctx ctx,
                                        String planContext, boolean hasPlannerContext) {
        String userId = UserContext.getUserId();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        // 获取历史消息（使用 sessionKey）
        List<Message> allHistory = redisChatMemory.get(sessionKey, 50);

        // 上下文压缩
        var compressed = getContextWithCompressedHistory(sessionKey, allHistory, userId);
        messages.addAll(ChatDomainPolicy.filterLegacyMessages(compressed));

        // 规划聊天优先使用该规划 ID 对应的偏好快照；普通聊天才使用长期自动槽位。
        // 两种上下文不叠加，避免历史偏好污染当前行程。
        if (hasPlannerContext) {
            String currentPlanContext = planPreferencePrompt(planContext);
            if (!currentPlanContext.isEmpty()) messages.add(new SystemMessage(currentPlanContext));
        } else {
            String memoryContext = travelMemoryService == null || !travelMemoryEnabled(userId) ? "" : travelMemoryService.promptContext(userId);
            if (!memoryContext.isEmpty()) messages.add(new SystemMessage(memoryContext));
            String slotContext = slotTrackingService.toPromptContext(userId);
            if (slotContext != null && !slotContext.isEmpty()
                    && !ChatDomainPolicy.containsLegacyDomain(slotContext)) {
                messages.add(new SystemMessage(slotContext));
            }
        }

        // 双模式：有知识=问答 / 无知识=闲聊
        String userPrompt = buildUserPrompt(context, message);
        messages.add(new UserMessage(userPrompt));

        // Pipeline 性能日志：总量
        log.debug("[Pipeline] buildMessages sessionId={} | pipeline={}, compressed={}",
                sessionKey, allHistory.size(), compressed.size());
        return messages;
    }

    /** Long-term memory remains opt-in: an account can keep records without recalling them. */
    private boolean travelMemoryEnabled(String userId) {
        if (preferencesService == null || userId == null || userId.isBlank() || UserContext.isAnonymous()) return false;
        try {
            return Boolean.TRUE.equals(preferencesService.get(userId).legacyMetadata().get("travelMemoryEnabled"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** 当前规划的偏好快照：只在该 plannerSessionId 的聊天中使用，不会沉淀为长期记忆。 */
    @SuppressWarnings("unchecked")
    private String planPreferencePrompt(String rawPlanContext) {
        if (rawPlanContext == null || rawPlanContext.isBlank()) return "";
        try {
            Map<String, Object> values = objectMapper.readValue(rawPlanContext, Map.class);
            List<String> entries = new ArrayList<>();
            addPlanPreference(entries, "同行人", values.get("companions"));
            addPlanPreference(entries, "步行强度", values.get("walkingTolerance"));
            addPlanPreference(entries, "兴趣", values.get("interests"));
            addPlanPreference(entries, "交通", values.get("transportPreference"));
            addPlanPreference(entries, "餐饮", values.get("dietPreference"));
            addPlanPreference(entries, "住宿区域", values.get("stayArea"));
            if (entries.isEmpty()) return "";
            return "【当前行程偏好快照（仅本次行程有效）】\n"
                    + String.join("；", entries)
                    + "。\n回答、推荐和调整必须优先遵循这份快照；不要把它写成用户长期偏好，也不要使用冲突的历史画像。";
        } catch (Exception error) {
            log.debug("[CHAT_PLAN_CONTEXT] 忽略无效的行程偏好快照");
            return "";
        }
    }

    private void addPlanPreference(List<String> entries, String label, Object value) {
        if (value == null) return;
        String normalized;
        if (value instanceof List<?> list) {
            normalized = list.stream().map(String::valueOf).filter(item -> !item.isBlank()).limit(12).reduce((a, b) -> a + "、" + b).orElse("");
        } else {
            normalized = String.valueOf(value).trim();
        }
        if (!normalized.isBlank() && !"未提供".equals(normalized) && !"UNSPECIFIED".equalsIgnoreCase(normalized)) {
            entries.add(label + "=" + normalized);
        }
    }

    /**
     * 上下文压缩（多账户：使用 userId 读取偏好）
     */
    private List<Message> getContextWithCompressedHistory(String sessionKey, List<Message> allHistory, String userId) {
        String summary = getSummary(sessionKey);
        int compressedCount = getCompressedCount(sessionKey);

        List<Message> result = new ArrayList<>();
        if (allHistory.size() <= 12) {
            result.addAll(allHistory);
            return result;
        }

        // 有摘要：摘要 + 最近 12 条
        if (summary != null && !summary.isEmpty()) {
            result.add(new SystemMessage("[历史对话摘要] " + summary));
            result.addAll(allHistory.subList(Math.max(0, allHistory.size() - 12), allHistory.size()));
            return result;
        }

        // 无摘要：异步压缩旧消息（不阻塞响应）
        List<Message> toCompress = allHistory.subList(0, allHistory.size() - 12);
        List<Message> recent = allHistory.subList(allHistory.size() - 12, allHistory.size());

        // 提交压缩任务到线程池，不等待结果
        compressionExecutor.submit(() -> {
            String newSummary = compressMessages(toCompress, userId);
            if (newSummary != null) {
                saveSummary(sessionKey, newSummary);
                incCompressedCount(sessionKey);
            }
        });
        // 压缩完成前，直接展示最近 12 条
        result.addAll(recent);
        return result;
    }

    private String compressMessages(List<Message> messages, String userId) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (ChatDomainPolicy.containsLegacyDomain(msg.getContent())) continue;
            String role = msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        try {
            String result = chatClient.prompt()
                .messages(
                    new SystemMessage("你是对话摘要生成器。生成150字内中文摘要。"),
                    new UserMessage(sb.toString()))
                .call().content();
            if (result == null || result.isBlank() || ChatDomainPolicy.containsLegacyDomain(result)) {
                return "对话已发生";
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private String getSummary(String sessionKey) {
        try {
            return redisChatMemory.getSummary(sessionKey);
        } catch (Exception e) { return null; }
    }

    private void saveSummary(String sessionKey, String summary) {
        redisChatMemory.saveSummary(sessionKey, summary);
    }

    private int getCompressedCount(String sessionKey) {
        try {
            return redisChatMemory.getCompressedCount(sessionKey);
        } catch (Exception e) { return 0; }
    }

    /** 压缩次数 +1（用于判断何时触发压缩） */
    private void incCompressedCount(String sessionKey) {
        try {
            int count = getCompressedCount(sessionKey);
            redisChatMemory.saveCompressedCount(sessionKey, count + 1);
        } catch (Exception ignored) {}
    }

    /** 结构化对话接口：返回 Result<ScenicResponse>，同时记录对话历史 */
    @GetMapping("/chat/structured")
    public Result<ScenicResponse> chatStructured(@RequestParam("message") String message,
                                         @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                         @RequestParam(value = "mode", defaultValue = "normal") String mode) {
        String userId = UserContext.getUserId();
        String sessionKey = buildSessionKey(sessionId);

        // 槽位提取（使用 userId）
        slotTrackingService.extractAndSave(userId, message);

        String context = ChatDomainPolicy.protectRetrievedContext(
                scenicDataImportService.queryKnowledge(message, 800));
        Ctx ctx = analyze(message);
        List<Message> allMessages = buildMessages(context, message, sessionKey, ctx, "", false);
        redisChatMemory.addAsync(sessionKey, List.of(new UserMessage(message)));

        ScenicResponse response = chatClient.prompt()
                .messages(allMessages)
                .call()
                .entity(ScenicResponse.class);

        if (response != null) {
            redisChatMemory.addAsync(sessionKey, List.of(new AssistantMessage(response.toString())));
            if (analyticsService != null) {
                analyticsService.logService(sessionId, message, ctx.sentiment().name(), ctx.intent().name(), 0);
            }
            return Result.success("查询成功", response);
        }
        return Result.error(500, "AI 未返回有效数据");
    }

    private ChatRetrieval retrieveChat(String message, boolean deep) {
        try {
            java.util.Map<String, Object> result = ragRetrievalService.retrieve(
                    message, "重庆", java.util.Collections.emptyMap(), deep);
            StringBuilder context = new StringBuilder();
            Object factsValue = result == null ? null : result.get("facts");
            if (factsValue instanceof List<?> facts) {
                for (Object item : facts) {
                    if (item instanceof java.util.Map<?, ?> fact) {
                        Object value = fact.get("value");
                        if (value != null && !String.valueOf(value).isBlank()) {
                            if (context.length() > 0) context.append("\n---\n");
                            context.append(value);
                        }
                    }
                }
            }
            boolean verified = result != null && Boolean.TRUE.equals(result.get("ok"))
                    && Boolean.TRUE.equals(result.get("verified")) && context.length() > 0;
            String status = verified ? "已返回" : "不可用";
            String source = result == null ? "java-rag" : String.valueOf(result.getOrDefault("source", "java-rag"));
            String reason = result == null ? "Java RAG 未返回结果。"
                    : String.valueOf(result.getOrDefault("reason", "Java 未返回可核验知识上下文。"));
            Object citationsValue = result == null ? null : result.get("citations");
            List<?> citations = citationsValue instanceof List<?> list ? list : List.of();
            return new ChatRetrieval(context.toString(), status, source, reason, verified, citations);
        } catch (Exception e) {
            log.warn("[Chat] structured RAG unavailable: {}", e.getMessage());
            return new ChatRetrieval("", "不可用", "java-rag",
                    "Java 检索服务暂不可用，模型不得把未核验内容当作景区事实。", false, List.of());
        }
    }

    private String appendNearbyPoiContext(String base, String message, String activeDay, String activeStopId, String activeStopName) {
        String text = message == null ? "" : message;
        boolean food = text.matches(".*(附近|周边).*(吃|餐|美食|饭店|餐厅).*" );
        boolean play = text.matches(".*(附近|周边).*(玩|好玩|游玩|打卡).*" );
        if ((!food && !play) || activeStopName == null || activeStopName.isBlank() || amapRouteService == null) return base;
        String keywords = activeStopName.trim() + (food ? " 附近餐饮" : " 附近景点");
        AmapRouteService.PoiOutcome outcome = amapRouteService.searchPoi(keywords, "重庆市");
        if (outcome.status() != AmapRouteService.OutcomeStatus.SUCCESS || outcome.candidates().isEmpty()) {
            return base + "\n【当前行程位置】第" + activeDay + "天 · " + activeStopName + "。附近实时推荐暂不可用，请基于已知资料回答并提示用户可稍后查看地图。";
        }
        StringBuilder pois = new StringBuilder("\n【当前行程位置】第").append(activeDay).append("天 · ").append(activeStopName).append("。\n【实时周边推荐】");
        for (AmapResponseNormalizer.PoiCandidate poi : outcome.candidates().stream().limit(5).toList()) {
            pois.append("\n- ").append(poi.name()).append("（").append(poi.type()).append("，").append(poi.address()).append("）");
        }
        pois.append("\n【回答约束】仅推荐以上实时结果；不要编造价格、营业状态或距离，提醒商家信息可能变化。");
        return base + pois;
    }

    private String plannerContextMessage(String message, String plannerSessionId, String tripId, String currentVersion) {
        if ((plannerSessionId == null || plannerSessionId.isBlank())
                && (tripId == null || tripId.isBlank())) return message;
        return String.format("""
                【当前行程助手上下文】当前页面存在一份待调整的行程草稿。规划会话=%s；正式保存行程=%s；当前版本=%s。
                “正式保存行程=未提供”只表示草稿尚未保存为正式行程，不代表当前草稿为空。请基于页面中的行程上下文回答或提出修改建议，但不要声称已经修改、保存或提交；任何变更必须等待用户确认并由 Java 行程接口执行。
                【用户请求】%s
                """, safeContext(plannerSessionId), safeContext(tripId), safeContext(currentVersion), message);
    }

    private String safeContext(String value) {
        return value == null ? "未提供" : value.replaceAll("[\\r\\n]", "").substring(0, Math.min(128, value.length()));
    }

    /** SSE 首事件的机器可读状态。只报告 Java 当前请求的事实，不把空上下文伪装成已核验知识。 */
    private String chatMetadataJson(String mode, String retrievalStatus, String retrievalSource,
                                    String retrievalReason, boolean verified, List<?> citations,
                                    IntentService.Intent intent, String message, String plannerSessionId, String tripId,
                                    String currentVersion) {
        var generation = new java.util.LinkedHashMap<String, Object>();
        generation.put("status", "已连接");
        generation.put("provider", "Java ChatClient");
        generation.put("model", "服务端已配置模型");
        var assistant = new java.util.LinkedHashMap<String, Object>();
        assistant.put("intent", assistantIntent(intent, message));
        assistant.put("requiresConfirmation", "MODIFY_PLAN".equals(assistantIntent(intent, message))
                || "UPDATE_PREFERENCE".equals(assistantIntent(intent, message)));
        assistant.put("plannerSessionId", safeContext(plannerSessionId));
        assistant.put("tripId", safeContext(tripId));
        assistant.put("currentVersion", safeContext(currentVersion));
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

    private record ChatRetrieval(String context, String status, String source, String reason,
                                 boolean verified, List<?> citations) {
        private static ChatRetrieval notNeeded(String reason) {
            return new ChatRetrieval("", "未查询", "none", reason, false, List.of());
        }
    }

    /** 单次请求上下文：情感和意图只计算一次 */
    private record Ctx(SentimentService.Sentiment sentiment, IntentService.Intent intent, boolean isNeg) {}

    private Ctx analyze(String message) {
        var s = sentimentService.analyze(message);
        var i = intentService.classify(message);
        return new Ctx(s, i, s == SentimentService.Sentiment.NEGATIVE || i == IntentService.Intent.COMPLAINT);
    }

    /**
     * 合并并行检索结果和 base query 结果（去重，并行优先）
     */
    private String mergeContexts(String parallelContext, String baseContext) {
        if (parallelContext == null || parallelContext.isBlank()) return baseContext != null ? baseContext : "";
        if (baseContext == null || baseContext.isBlank()) return parallelContext;
        Set<String> seen = new HashSet<>();
        List<String> merged = new ArrayList<>();
        for (String frag : parallelContext.split("\\n---\\n")) {
            String norm = frag.replaceAll("\\s+", "").toLowerCase();
            if (norm.length() > 10 && seen.add(norm)) merged.add(frag);
        }
        for (String frag : baseContext.split("\\n---\\n")) {
            String norm = frag.replaceAll("\\s+", "").toLowerCase();
            if (norm.length() > 10 && seen.add(norm)) merged.add(frag);
        }
        if (merged.size() > 15) merged = merged.subList(0, 15);
        return String.join("\n---\n", merged);
    }

    /** 闲聊匹配预编译正则（避免每次调用编译） */
    private final ExecutorService compressionExecutor;


    private static final Pattern CHITCHAT_PATTERN = Pattern.compile("^(好的|嗯|哦|哈哈|谢谢|再见|拜拜|晚安|明白了|行|可以|对|是的|没错|好吧|好哒|好|ok|yes|no|yeah|lol|thx|thanks|bye|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你好|您好)$", Pattern.CASE_INSENSITIVE);

    /** 判断是否跳过知识库检索 */
    private boolean shouldSkipKnowledge(String message) {
        if (message == null) return false;
        return CHITCHAT_PATTERN.matcher(message.trim()).find();
    }
}

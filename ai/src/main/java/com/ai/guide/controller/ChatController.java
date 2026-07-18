package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.model.ScenicResponse;
import com.ai.guide.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * 对话系统入口控制器
 * 请求流程：问候判定 → 知识库检索 → 构建上下文 → 调用大模型 → 保存历史
 *
 * 多账户改造：
 * - 槽位提取/偏好读取使用 UserContext.getUserId()
 * - 会话历史使用 userId:sessionId 组合作为 key
 * - analytics 日志记录 userId
 *
 * 返回格式说明
 *   /chat —— 返回纯文本 Markdown，前端直接 innerHTML 渲染
 *   /chat/stream —— SSE 流式推送（text/event-stream），非 JSON，不能用 Result 包装
 *   /chat/structured —— 返回 Result<ScenicResponse>，标准 JSON
 *
 * /chat 和 /chat/stream 不用 Result 包装的原因：
 * 前者输出 Markdown 原文由前端 marked.js 渲染，
 * 后者走 SSE 协议逐块推送，Content-Type 是 text/event-stream 不是 application/json
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ChatController {

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
    private QueryDecompositionService queryDecompositionService;

    @Autowired
    private ParallelRagService parallelRagService;

    private static final String SYSTEM_PROMPT = """
        # 角色: 灵山智慧导游"小导"，有情感、懂历史的江南导游

        # 行为准则
        1. **承接上下文**：拥有对话记忆，像老朋友聊天，追问时结合之前的回答。
        2. **关联性**：只在用户问景点/餐厅/政策时从背景知识提取信息。
        3. **精准度优先**：紧扣用户需求，推荐类问题可适当展开多个维度，封闭性问题简洁精准。
        4. **按需回答**：打招呼、纯感受表达（"好玩""不好玩"）时<b>不要推荐任何项目</b>——只共情回应。除非用户明确说"推荐""介绍"。
        5. **回答自然**：承接用户问题顺势答复。结尾加猜你想问（必须空一行），格式：💡猜你想问：1. xxx 2. xxx

        # 槽位感知
        有【已知用户偏好】时<b>以此为第一优先级</b>。如用户偏好"美食+半天"，模糊请求也直接推美食。缺失关键信息时先追问再推荐

        # 排版
        每个项目名: ### 数字. 名称（单个不用数字），属性用 `- **属性**：值` 无序列表，严禁同行写两个属性
        模板: ### 1. 名称 换行 - **价格**：内容 换行 - **特色**：内容

        # 禁令
        严禁"(空一行)""回车"等描述文字，严禁编造任何知识库中不存在的信息（酒店名称/价格/距离/时间/政策等），知识库未覆盖时必须明确说\"暂无相关信息\"，不得编造看似合理的内容，哪怕用户追问也要保持一致；严禁编造价格政策，严禁标题放列表符号后面，严禁把打招呼变成长篇介绍
        """;

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
        // 无知识上下文 → 闲聊模式（不引用知识库）
        if (context == null || context.isEmpty()) {
            return String.format("""
                【用户发言】：%s

                这是闲聊/问候/情感表达。请：
                - 像朋友一样自然回应，不要引用任何知识库
                - 简洁温暖（1-2句话），不啰嗦推荐
                - 不要提"知识库""暂无""官方咨询"等机械措辞
                - 可顺势问一句引导话题，如"有什么想了解的随时问我～"
                """, message);
        }
        // 有知识上下文 → 问答模式
        return String.format("""
            请严格基于下方【背景知识】回答。开放性问题多维度展开，封闭性问题简洁精准。

            问题：%s

            背景知识：%s
            """, message, context);
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
        return userId + ":" + sessionId;
    }

    /**
     * 流式对话接口
     * 流程：意图分析 → 知识检索（可选）→ 构建消息 → 流式响应 → 保存历史
     */
    @GetMapping("/chat/stream")
    public Flux<ServerSentEvent<String>> chatStream(@RequestParam("message") String message,
                                @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                @RequestParam(value = "mode", defaultValue = "normal") String mode) {
        String userId = UserContext.getUserId();
        String sessionKey = buildSessionKey(sessionId);
        log.info("[DEBUG] chatStream mode={}, userId={}, sessionId={}, message={}",
                mode, userId, sessionId, message);

        // 0. 槽位提取（使用 userId 而非 sessionId）
        slotTrackingService.extractAndSave(userId, message);

        // 1. 情感 + 意图（只计算一次）
        Ctx ctx = analyze(message);

        // 2. 判断是否需要知识库检索
        String context;
        if ("deep".equals(mode)) {
            // 深度模式：使用 Agentic RAG（子问题拆解 + 并行检索）
            var decomposed = queryDecompositionService.decompose(message);
            if (decomposed != null && decomposed.needSearch() && !decomposed.isEmpty()) {
                context = parallelRagService.search(decomposed.subQueries());
                log.info("[Deep] Agentic RAG: {} sub-queries, context_len={}", decomposed.subQueries().size(), context.length());
            } else {
                context = scenicDataImportService.queryKnowledge(message, 1500);
                log.info("[Deep] Simple query: context_len={}", context.length());
            }
        } else if ("你好".equals(message.trim()) || "您好".equals(message.trim())) {
            context = "";
        } else if (ctx.intent() == IntentService.Intent.CHITCHAT) {
            context = "";
        } else if (ctx.intent() == IntentService.Intent.COMPLAINT) {
            log.info("[Chat] 负面情绪/投诉 detected, skipping knowledge");
            context = "";
        } else if (shouldSkipKnowledge(message)) {
            context = "";
        } else {
            context = scenicDataImportService.queryKnowledge(message, 800);
        }
        debugLogContext(message, context);

        // 3. 构建 messages（使用 sessionKey 存取历史）
        List<Message> allMessages = buildMessages(context, message, sessionKey, ctx);
        redisChatMemory.addAsync(sessionKey, List.of(new UserMessage(message)));

        // 4. 用于累积完整回复文本的容器
        StringBuilder fullReply = new StringBuilder();
        long streamStart = System.currentTimeMillis();

        // 5. 情感事件
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
                .map(content -> {
                    fullReply.append(content);
                    return ServerSentEvent.builder(content).build();
                })
                .doOnComplete(() -> {
                    String reply = fullReply.toString();
                    if (!reply.isEmpty()) {
                        redisChatMemory.addAsync(sessionKey, List.of(new AssistantMessage(reply)));
                        log.debug("[历史] 已保存流式对话 session=" + sessionKey + " reply_len=" + reply.length());
                        // 记录分析日志
                        long duration = System.currentTimeMillis() - streamStart;
                        if (analyticsService != null) {
                            analyticsService.logService(sessionId, message, sentiment.name(), ctx.intent().name(), duration);
                        }
                    }
                })
                .doOnError(e -> log.error("[流式] 异常: {}", e.getMessage()));

        return sentimentFlux.concatWith(stream);
    }

    /**
     * 构建消息列表（含系统提示、上下文压缩、知识库上下文、用户偏好）
     * 多账户：使用 userId 读取槽位偏好
     */
    private List<Message> buildMessages(String context, String message, String sessionKey, Ctx ctx) {
        String userId = UserContext.getUserId();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));

        // 获取历史消息（使用 sessionKey）
        List<Message> allHistory = redisChatMemory.get(sessionKey, 50);

        // 上下文压缩
        var compressed = getContextWithCompressedHistory(sessionKey, allHistory, userId);
        messages.addAll(compressed);

        // 用户偏好（使用 userId）
        String slotContext = slotTrackingService.toPromptContext(userId);
        if (slotContext != null && !slotContext.isEmpty()) {
            messages.add(new SystemMessage(slotContext));
        }

        // 双模式：有知识=问答 / 无知识=闲聊
        String userPrompt = buildUserPrompt(context, message);
        messages.add(new UserMessage(userPrompt));

        // Pipeline 性能日志：总量
        log.debug("[Pipeline] buildMessages sessionId={} | pipeline={}, compressed={}",
                sessionKey, allHistory.size(), compressed.size());
        return messages;
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
            String role = msg.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        try {
            String result = chatClient.prompt()
                .messages(
                    new SystemMessage("你是对话摘要生成器。生成150字内中文摘要。"),
                    new UserMessage(sb.toString()))
                .call().content();
            return (result != null && !result.isBlank()) ? result : "对话已发生";
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

        String context = scenicDataImportService.queryKnowledge(message, 800);
        Ctx ctx = analyze(message);
        List<Message> allMessages = buildMessages(context, message, sessionKey, ctx);
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

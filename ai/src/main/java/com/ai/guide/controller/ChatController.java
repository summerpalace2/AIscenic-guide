package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.model.ScenicResponse;
import com.ai.guide.service.IntentService;
import com.ai.guide.service.RedisChatMemory;
import com.ai.guide.service.ScenicDataImportService;
import com.ai.guide.service.SentimentService;
import com.ai.guide.service.SlotTrackingService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话系统入口控制器
 * 请求流程：问候判定 → 知识库检索 → 构建上下文 → 调用大模型 → 保存历史
 *
 *返回格式说明
 *   /chat —— 返回纯文本 Markdown，前端直接 innerHTML 渲染
 *   /chat/stream —— SSE 流式推送（text/event-stream），非 JSON，不能用 Result 包装
 *   /chat/structured —— 返回 Result;ScenicResponse;，标准 JSON
 *
 *
 * /chat 和 /chat/stream 不用 Result 包装的原因：
 * 前者输出 Markdown 原文由前端 marked.js 渲染，
 * 后者走 SSE 协议逐块推送，Content-Type 是 text/event-stream 不是 application/json
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;
    private final RedisChatMemory redisChatMemory;
    private final SlotTrackingService slotTrackingService;
    private final IntentService intentService;
    private final SentimentService sentimentService;

    @Autowired
    private ScenicDataImportService scenicDataImportService;

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
        严禁"(空一行)""回车"等描述文字，严禁编造价格政策，严禁标题放列表符号后面，严禁把打招呼变成长篇介绍
        """;

    /** 构造 ChatClient 和注入 Redis 记忆组件 */
    public ChatController(ChatClient.Builder builder, RedisChatMemory redisChatMemory,
                          SlotTrackingService slotTrackingService,
                          IntentService intentService, SentimentService sentimentService) {
        this.redisChatMemory = redisChatMemory;
        this.slotTrackingService = slotTrackingService;
        this.intentService = intentService;
        this.sentimentService = sentimentService;
        this.chatClient = builder.build();
    }

    /** 将知识库上下文拼入用户消息 */
    private String buildUserPrompt(String context, String message) {
        return String.format("""
            请基于提供的背景知识回答。开放性问题（推荐/建议/路线等）可适当展开多个维度，封闭性问题简洁精准。
            
            【背景知识】：
            %s
            
            【用户提问】：
            %s
            
            要求：条理清晰，使用 Markdown 列表和加粗。推荐类问题给出 3-5 条推荐，每条含名称/位置/价格/特色；简洁问题直接回答。
            """, (context == null || context.isEmpty()) ? "无匹配知识" : context, message);
    }

    /** 判断是否需要跳过知识库检索（闲聊 + 投诉 + 问候） */
    private boolean shouldSkipKnowledge(String message) {
        IntentService.Intent intent = intentService.classify(message);
        return intent == IntentService.Intent.CHITCHAT || intent == IntentService.Intent.COMPLAINT;
    }

    /** 控制台调试输出：记录当前问题和送给大模型的背景知识原文 */
    private void debugLogContext(String message, String context) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("【调试日志】用户问题: " + message);
        System.out.println("-".repeat(50));
        System.out.println("【AI 接收到的背景知识原文】：\n" + context);
        System.out.println("=".repeat(50) + "\n");
    }

    /**
     * 构建完整的 messages 列表：系统提示词（含槽位）+ 历史对话 + 当前用户消息
     *
     * 顺序逻辑：先读历史 → 再保存本轮，避免本轮 user 消息重复出现在上下文中
     */
    private List<Message> buildMessages(String context, String message, String sessionId, Ctx ctx) {
        List<Message> allMessages = new ArrayList<>();
        String systemWithSlots = SYSTEM_PROMPT
                + sentimentService.toPromptHint(ctx.sentiment);
        if (!ctx.isNeg) systemWithSlots += slotTrackingService.toPromptContext(sessionId);
        allMessages.add(new SystemMessage(systemWithSlots));
        allMessages.addAll(redisChatMemory.get(sessionId, ctx.isNeg ? 3 : 20));
        allMessages.add(new UserMessage(buildUserPrompt(context, message)));
        return allMessages;
    }

    /** 普通对话接口：问候判定 → 槽位提取 → 检索 → 构建上下文 → 调用大模型 → 保存历史 */
    @GetMapping("/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "你好") String message,
                       @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        // 0. 从用户消息中提取槽位（兴趣/时间/人群等），存入 Redis
        slotTrackingService.extractAndSave(sessionId, message);

        //判断是不是需要使用RAG
        String context = shouldSkipKnowledge(message) ? "" : scenicDataImportService.queryKnowledge(message);
        debugLogContext(message, context);

        // 1. 先构建 messages（此时历史中不含本轮 user）
        Ctx ctx = analyze(message);
        List<Message> allMessages = buildMessages(context, message, sessionId, ctx);

        // 2. 异步保存用户消息（不阻塞响应）
        redisChatMemory.addAsync(sessionId, List.of(new UserMessage(message)));

        // 3. 调用 AI
        String reply = chatClient.prompt()
                .messages(allMessages)
                .call()
                .content();

        // 4. 异步保存助手回复
        redisChatMemory.addAsync(sessionId, List.of(new AssistantMessage(reply)));
        System.out.println("[历史] 已保存对话 session=" + sessionId + "...");

        return reply;
    }

    /** 流式对话接口：SSE 推送，流结束后自动保存完整回复到 Redis */
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {

        slotTrackingService.extractAndSave(sessionId, message);

        String context = shouldSkipKnowledge(message) ? "" : scenicDataImportService.queryKnowledge(message);
        debugLogContext(message, context);

        // 1. 构建 messages + 异步保存用户消息
        Ctx ctx = analyze(message);
        List<Message> allMessages = buildMessages(context, message, sessionId, ctx);
        redisChatMemory.addAsync(sessionId, List.of(new UserMessage(message)));

        // 2. 用于累积完整回复文本的容器
        StringBuilder fullReply = new StringBuilder();

        // 3. 情感事件（SSE event:sentiment 通道，前端监听后驱动数字人，不出现在文本中）
        SentimentService.Sentiment sentiment = sentimentService.analyze(message);
        Flux<ServerSentEvent<String>> sentimentFlux = sentiment == SentimentService.Sentiment.NEUTRAL ? Flux.empty() :
            Flux.just(ServerSentEvent.<String>builder()
                .event("sentiment")
                .data(sentiment.name().toLowerCase())
                .build());

        // 4. AI 流式回复
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
                        redisChatMemory.addAsync(sessionId, List.of(new AssistantMessage(reply)));
                        System.out.println("[历史] 已保存流式对话 session=" + sessionId + " reply_len=" + reply.length());
                    }
                })
                .doOnError(e -> System.err.println("[流式] 异常: " + e.getMessage()));

        return sentimentFlux.concatWith(stream);
    }

    /** 结构化对话接口：返回 Result;ScenicResponse;，同时记录对话历史 */
    @GetMapping("/chat/structured")
    public Result<ScenicResponse> chatStructured(@RequestParam("message") String message,
                                         @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        slotTrackingService.extractAndSave(sessionId, message);

        String context = scenicDataImportService.queryKnowledge(message);
        Ctx ctx = analyze(message);
        List<Message> allMessages = buildMessages(context, message, sessionId, ctx);
        redisChatMemory.addAsync(sessionId, List.of(new UserMessage(message)));

        ScenicResponse response = chatClient.prompt()
                .messages(allMessages)
                .call()
                .entity(ScenicResponse.class);

        if (response != null) {
            redisChatMemory.addAsync(sessionId, List.of(new AssistantMessage(response.toString())));
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
}

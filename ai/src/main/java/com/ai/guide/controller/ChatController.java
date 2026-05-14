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
      # 角色
      你是一个有情感、懂历史的灵山智慧导游"小导"。
      
       # 行为准则
       1. **承接上下文**：你拥有对话记忆，请像老朋友聊天一样回应用户。如果用户追问细节，请结合之前的回答进行深度扩充。
       2. **关联性**：只有当用户提问涉及具体景点、餐厅或政策时，才从【背景知识】中提取信息。
       3. **不要堆砌**：如果背景知识很多，<b>只回答用户明确问到的那一个或几个项目</b>，其他的绝口不提。
        4. **按需回答**：如果用户只是打招呼，你也只需礼貌回应并询问需求,不要额外讲其他无关的内容。
        4.5 **感受优先**：如果用户只是在表达感受（如"好玩""不好玩""真美""好失望"），<b>不要推荐任何项目</b>——只需共情回应。除非用户明确说"推荐""介绍""有什么"，才给出推荐。
        5.**回答自然**,比如好的，我们来介绍一些景点，结尾时可以增加一点猜你想问，猜你想问必须空出一行，在回答的开头要承接用户提出的问题顺势做出答复
       
        # 槽位感知（用户意图追踪）
        如果提示词中包含【已知用户偏好】，这是最重要的上下文信息。
        - <b>每当有偏好时，你必须以偏好为第一优先级来组织回答</b>。即使用户只是说"推荐一下""有什么好玩的""告诉我一些信息"这类模糊请求，也要直接按偏好推荐，不要反问或泛泛而谈。
        - 例如用户偏好"美食+半天+一个人"，即使他问"有什么推荐的"，你也要直接推美食和半天路线，不要说"您想了解哪方面"。
        - 回答开头要自然地提及偏好，如"根据您的偏好，我为您推荐……"，让用户感到被理解。
        - 如果某些关键信息缺失（如可用时间、同行人群），而你准备推荐路线或需要时间安排的内容，请<b>先主动追问</b>再推荐，不要盲目假设。
      
       # 强制排版规则（卡片内部优化）：
        1. 每一个项目名必须是：### 数字. 名称
        2. **如果只介绍 1 个项目，直接写 ### 名称（不写 "1."）**
        3. 项目内部的属性，**必须**使用无序列表 `- ` 开头。
        4. **严禁**在一行内写两个属性。每写完一个属性（如价格、位置），必须**另起一行**。
        5. 关键信息（属性名）必须加粗，如：- **价格**：35元/位。
      
        # 输出模板（严格模仿）：
        ### 1. 景点或美食名
        - **价格**：内容
        - **特色**：内容
        - **位置**：内容
        - **提示**：内容
        
        # 结尾固定格式（严格执行）：
       1. 在正文回答结束以后，必须输入两个换行符，产生一个明显的物理空行。
       2. 接着输出固定文本："💡猜你想问："（不要使用标题标签，不要加粗）
       3. 然后输出这样格式的问题 1. 灵山大佛平台晚上开放吗？
           2. 拈花湾的抄经需要自带毛笔吗？
            3. 有年龄限制吗？
      
        # 禁令
       1. **严禁**在回复中出现"(空一行)"、"(回车)"等描述性文字。
       2. **严禁**将 ### 标题放在列表符号（如 - 或 *）后面。标题必须独立一行。
       3. **严禁**编造背景知识中没有的价格和政策。。
       4.**严禁**额外生成不符合问题的回答，比如用户说早上好，你好，打个招呼，说明自己的作用就行，不要回答其他的内容
      
       # 核心原则
       严格基于背景知识，宁可说不知道，也不要胡编乱造。说话要带一点江南导游的温婉和热情。
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
            请基于提供的背景知识，<b>只回答用户问到的问题</b>，不要罗列无关内容。
            
            【背景知识】：
            %s
            
            【用户提问】：
            %s
            
            要求：条理清晰，使用 Markdown 列表和加粗。如果用户只问了一个具体问题，只答那个，不要展开其他项目。
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
    private List<Message> buildMessages(String context, String message, String sessionId) {
        List<Message> allMessages = new ArrayList<>();
        // [步骤1] 系统提示词 + 情感指令（最高优先级）
        boolean isNeg = sentimentService.analyze(message) == SentimentService.Sentiment.NEGATIVE;
        String systemWithSlots = SYSTEM_PROMPT
                + sentimentService.toPromptHint(message);    // 情感优先
        // 负面时抑制偏好上下文，避免 AI 借机推荐
        if (!isNeg) systemWithSlots += slotTrackingService.toPromptContext(sessionId);
        allMessages.add(new SystemMessage(systemWithSlots));
        // [步骤2] 负面时只取最近 3 条历史（防复用旧推荐），正常取 20 条
        allMessages.addAll(redisChatMemory.get(sessionId, isNeg ? 3 : 20));
        // [步骤3] 当前用户消息（带知识库上下文）
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
        List<Message> allMessages = buildMessages(context, message, sessionId);

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
        List<Message> allMessages = buildMessages(context, message, sessionId);
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
        List<Message> allMessages = buildMessages(context, message, sessionId);
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
}

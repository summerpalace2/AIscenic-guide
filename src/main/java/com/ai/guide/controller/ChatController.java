package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.model.ScenicResponse;
import com.ai.guide.service.RedisChatMemory;
import com.ai.guide.service.ScenicDataImportService;
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
 *   /chat/structured —— 返回 Result&lt;ScenicResponse&gt;，标准 JSON
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

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    private static final String SYSTEM_PROMPT = """
      # 角色
      你是一个有情感、懂历史的灵山智慧导游"小导"。
      
       # 行为准则
       1. **承接上下文**：你拥有对话记忆，请像老朋友聊天一样回应用户。如果用户追问细节，请结合之前的回答进行深度扩充。
       2. **关联性**：只有当用户提问涉及具体景点、餐厅或政策时，才从【背景知识】中提取信息。
       3. **不要堆砌**：如果背景知识很多，优先回答用户问的那部分，其他的可以简单提示。
       4. **按需回答**：如果用户只是打招呼，你也只需礼貌回应并询问需求,不要额外讲其他无关的内容。
       5.**回答自然**,比如好的，我们来介绍一些景点，结尾时可以增加一点猜你想问，猜你想问必须空出一行，在回答的开头要承接用户提出的问题顺势做出答复
       
      
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
    public ChatController(ChatClient.Builder builder, RedisChatMemory redisChatMemory) {
        this.redisChatMemory = redisChatMemory;
        this.chatClient = builder.build();
    }

    /** 将知识库上下文拼入用户消息，组装成完整提示词 */
    private String buildUserPrompt(String context, String message) {
        return String.format("""
            请基于提供的背景知识，详细回答用户问题。
            
            【背景知识】：
            %s
            
            【用户提问】：
            %s
            
            要求：条理清晰，使用 Markdown 列表和加粗，确保回答内容全面，不要遗漏任何细节。
            """, (context == null || context.isEmpty()) ? "无匹配知识" : context, message);
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
     * 构建完整的 messages 列表：系统提示词 + 历史对话 + 当前用户消息
     * <p>
     * <b>顺序逻辑：</b>先读历史 → 再保存本轮，避免本轮 user 消息重复出现在上下文中
     * （如果在读历史之前就保存了本轮 user，那么紧接着 get() 时会把这个 user 也读出来，造成重复）
     */
    private List<Message> buildMessages(String context, String message, String sessionId) {
        List<Message> allMessages = new ArrayList<>();
        // [步骤1] 系统提示词：定义导游角色和行为规则
        allMessages.add(new SystemMessage(SYSTEM_PROMPT));
        // [步骤2] 加载最近 20 条历史（此时还不包含本轮消息，避免重复）
        allMessages.addAll(redisChatMemory.get(sessionId, 20));
        // [步骤3] 当前用户消息（带知识库上下文，包装在 user prompt 模板中）
        allMessages.add(new UserMessage(buildUserPrompt(context, message)));
        return allMessages;
    }

    /** 普通对话接口：问候判定 → 检索 → 构建上下文 → 调用大模型 → 保存历史 */
    @GetMapping("/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "你好") String message,
                       @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        String context = isGreeting(message) ? "" : scenicDataImportService.queryKnowledge(message);
        debugLogContext(message, context);

        // 1. 先构建 messages（此时历史中不含本轮 user）
        List<Message> allMessages = buildMessages(context, message, sessionId);

        // 2. 再保存用户消息（存储原始问题，不含知识库上下文）
        redisChatMemory.add(sessionId, List.of(new UserMessage(message)));

        // 3. 调用 AI
        String reply = chatClient.prompt()
                .messages(allMessages)
                .call()
                .content();

        // 4. 保存助手回复
        redisChatMemory.add(sessionId, List.of(new AssistantMessage(reply)));
        System.out.println("[历史] 已保存对话 session=" + sessionId + " user=" + message.substring(0, Math.min(20, message.length())) + " reply=" + reply.substring(0, Math.min(30, reply.length())));

        return reply;
    }

    /** 流式对话接口：SSE 推送，流结束后自动保存完整回复到 Redis */
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {

        String context = isGreeting(message) ? "" : scenicDataImportService.queryKnowledge(message);
        debugLogContext(message, context);

        // 1. 构建 messages + 保存用户消息（同 chat 逻辑）
        List<Message> allMessages = buildMessages(context, message, sessionId);
        redisChatMemory.add(sessionId, List.of(new UserMessage(message)));

        // 2. 用于累积完整回复文本的容器
        StringBuilder fullReply = new StringBuilder();

        // 3. 流式调用
        return chatClient.prompt()
                .messages(allMessages)
                .stream()
                .content()
                .map(content -> {
                    fullReply.append(content);
                    return ServerSentEvent.builder(content).build();
                })
                .doOnComplete(() -> {
                    // 流结束后保存完整回复
                    String reply = fullReply.toString();
                    if (!reply.isEmpty()) {
                        redisChatMemory.add(sessionId, List.of(new AssistantMessage(reply)));
                        System.out.println("[历史] 已保存流式对话 session=" + sessionId + " reply_len=" + reply.length());
                    }
                })
                .doOnError(e -> System.err.println("[流式] 异常: " + e.getMessage()));
    }

    /** 结构化对话接口：返回 Result&lt;ScenicResponse&gt;，同时记录对话历史 */
    @GetMapping("/chat/structured")
    public Result<ScenicResponse> chatStructured(@RequestParam String message,
                                         @RequestParam(value = "sessionId", defaultValue = "default") String sessionId) {
        String context = scenicDataImportService.queryKnowledge(message);
        List<Message> allMessages = buildMessages(context, message, sessionId);
        redisChatMemory.add(sessionId, List.of(new UserMessage(message)));

        ScenicResponse response = chatClient.prompt()
                .messages(allMessages)
                .call()
                .entity(ScenicResponse.class);

        if (response != null) {
            redisChatMemory.add(sessionId, List.of(new AssistantMessage(response.toString())));
            return Result.success("查询成功", response);
        }
        return Result.error(500, "AI 未返回有效数据");
    }

    /** 问候语判定：简短问候、自我介绍、道谢道别等跳过知识库检索 */
    private boolean isGreeting(String message) {
        if (message == null) return true;
        String msg = message.trim();
        if (msg.length() > 15) return false;
        return msg.matches(".*(你好|早上好|下午好|晚上好|嗨|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你能做什么|谢谢|再见|拜拜|晚安).*")
                || msg.matches("^[a-zA-Z]+$") && msg.length() <= 6;
    }
}

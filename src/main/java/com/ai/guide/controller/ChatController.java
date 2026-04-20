package com.ai.guide.controller;

import com.ai.guide.model.ScenicResponse;
import com.ai.guide.service.ScenicDataImportService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.util.Logger;
import reactor.util.Loggers;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatClient chatClient;
    private static final Logger log = Loggers.getLogger(ChatController.class);

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    // 增加内存存储（实际企业级可用 RedisChatMemory）
    private final InMemoryChatMemory chatMemory = new InMemoryChatMemory();

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("""
      # 角色
      你是一个有情感、懂历史的灵山智慧导游“小导”。
      
       # 行为准则
       1. **承接上下文**：你拥有对话记忆，请像老朋友聊天一样回应用户。如果用户追问细节，请结合之前的回答进行深度扩充。
       2. **关联性**：只有当用户提问涉及具体景点、餐厅或政策时，才从【背景知识】中提取信息。
       3. **不要堆砌**：如果背景知识很多，优先回答用户问的那部分，其他的可以简单提示。
       4. **按需回答**：如果用户只是打招呼，你也只需礼貌回应并询问需求,不能添加格外的内容，能添加格外的内容，能添加格外的内容，比如额外对某些景点的介绍。
      
       # 强制排版规则（卡片内部优化）：
        1. 每一个项目名必须是：### 数字. 名称
        2. 项目内部的属性，**必须**使用无序列表 `- ` 开头。
        3. **严禁**在一行内写两个属性。每写完一个属性（如价格、位置），必须**另起一行**。
        4. 关键信息（属性名）必须加粗，如：- **价格**：35元/位。
      
        # 输出模板（严格模仿）：
        ### 1. 景点或美食名
        - **价格**：内容
        - **特色**：内容
        - **位置**：内容
        - **提示**：内容
      
        # 禁令
       1. **严禁**在回复中出现“(空一行)”、“(回车)”等描述性文字。
        2. **严禁**将 ### 标题放在列表符号（如 - 或 *）后面。标题必须独立一行。
       3. **严禁**编造背景知识中没有的价格和政策。。
      
       # 核心原则
       严格基于背景知识，宁可说不知道，也不要胡编乱造。说话要带一点江南导游的温婉和热情。
      """)
                // 关键：在构造时配置记忆顾问
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build();
    }

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

    /**
     * 【修改点 4：终极调试手段】
     * 每一个请求都会在 IDEA 控制台清晰地打印出 AI 看到的原文
     */
    private void debugLogContext(String message, String context) {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("【调试日志】用户问题: " + message);
        System.out.println("-".repeat(50));
        System.out.println("【AI 接收到的背景知识原文】：\n" + context);
        System.out.println("=".repeat(50) + "\n");
    }

    @GetMapping("/chat")
    public String chat(@RequestParam(value = "message", defaultValue = "你好") String message) {
        String context = scenicDataImportService.queryKnowledge(message);

        // 执行调试打印
        debugLogContext(message, context);

        return chatClient.prompt()
                .user(buildUserPrompt(context, message))
                .call()
                .content();
    }

    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatStream(@RequestParam(value = "message", defaultValue = "你好") String message) {
        String context = scenicDataImportService.queryKnowledge(message);

        // 执行调试打印
        debugLogContext(message, context);

        return chatClient.prompt()
                .user(buildUserPrompt(context, message))
                .stream()
                .content()
                .map(content -> ServerSentEvent.builder(content).build());
    }
    @GetMapping("/chat/structured")
    public ScenicResponse chatStructured(@RequestParam String message) {
        String context = scenicDataImportService.queryKnowledge(message);

        return chatClient.prompt()
                .user(u -> u.text("""
                你是一个导游。请基于背景知识，提取出所有相关项目并填入表格。
                
                【背景知识】：{context}
                【用户提问】：{message}
                """)
                        .param("context", context)
                        .param("message", message))
                // 核心约束：强制 AI 吐出 ScenicResponse 格式的 JSON
                .call()
                .entity(ScenicResponse.class);
    }
}
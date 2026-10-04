package com.ai.guide.adapter.chat.web;

import com.ai.guide.application.chat.ChatApplicationService;
import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.application.chat.dto.ChatStructuredCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.chat.model.ScenicResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * AI 文旅导游流式对话适配器控制器 (Chat Controller)
 *
 * 架构定位：COLA 适配层 (Adapter Layer) - Web 协议入口。
 * 核心职责：仅负责接收外部 HTTP 请求，进行参数解构、安全上下文提取与 DTO 转换，
 * 然后将用例委派给应用服务层 {@link ChatApplicationService}，输出严格保真的 SSE / JSON 协议。
 */
@RestController
@RequestMapping("/ai")
public class ChatController {

    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    /**
     * SSE 流式对话端点
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestParam("message") String message,
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

        ChatStreamCommand command = ChatStreamCommand.of(
                message, sessionId, mode, plannerSessionId, tripId, currentVersion,
                activeDay, activeStopId, activeStopName, planContext,
                userId, username, role
        );

        return chatApplicationService.streamChat(command);
    }

    /**
     * 结构化对话接口
     */
    @GetMapping("/chat/structured")
    public Result<ScenicResponse> chatStructured(
            @RequestParam("message") String message,
            @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
            @RequestParam(value = "mode", defaultValue = "normal") String mode) {

        String userId = UserContext.getUserId();
        ChatStructuredCommand command = ChatStructuredCommand.of(message, sessionId, mode, userId);

        return chatApplicationService.chatStructured(command);
    }
}

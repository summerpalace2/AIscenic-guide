package com.ai.guide.adapter.chat.web;

import com.ai.guide.application.chat.ChatApplicationService;
import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.application.chat.dto.ChatStreamEvent;
import com.ai.guide.application.chat.dto.ChatStructuredCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.chat.model.ScenicResponse;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
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

        return toSse(chatApplicationService.streamChat(command));
    }

    public record ChatStreamRequest(
            String message,
            String sessionId,
            String mode,
            String plannerSessionId,
            String tripId,
            String currentVersion,
            String activeDay,
            String activeStopId,
            String activeStopName,
            String planContext
    ) {}

    /**
     * SSE 流式对话端点（POST 请求，避免超长大模型上下文与敏感数据产生 414 URI Too Long）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStreamPost(@RequestBody(required = false) ChatStreamRequest request) {
        ChatStreamRequest req = request != null ? request : new ChatStreamRequest("", "default", "normal", "", "", "", "", "", "", "");
        String userId = UserContext.getUserId();
        String username = UserContext.getUsername();
        String role = UserContext.getRole();

        ChatStreamCommand command = ChatStreamCommand.of(
                req.message() == null ? "" : req.message(),
                req.sessionId() == null ? "default" : req.sessionId(),
                req.mode() == null ? "normal" : req.mode(),
                req.plannerSessionId() == null ? "" : req.plannerSessionId(),
                req.tripId() == null ? "" : req.tripId(),
                req.currentVersion() == null ? "" : req.currentVersion(),
                req.activeDay() == null ? "" : req.activeDay(),
                req.activeStopId() == null ? "" : req.activeStopId(),
                req.activeStopName() == null ? "" : req.activeStopName(),
                req.planContext() == null ? "" : req.planContext(),
                userId, username, role
        );

        return toSse(chatApplicationService.streamChat(command));
    }

    private Flux<ServerSentEvent<String>> toSse(Flux<ChatStreamEvent> events) {
        return events.map(event -> ServerSentEvent.<String>builder()
                .event(event.event())
                // JSON protects leading whitespace and newlines in individual model tokens from SSE framing.
                .data("text".equals(event.event()) && event.data() != null
                        ? "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(event.data())) + "\""
                        : event.data())
                .build());
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

package com.ai.guide.adapter.agent.web;

import com.ai.guide.application.agent.AgentApplicationService;
import com.ai.guide.application.agent.dto.AgentConfirmCommand;
import com.ai.guide.application.agent.dto.AgentConfirmResult;
import com.ai.guide.application.agent.dto.AgentStreamCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文旅智能体统一 Web/SSE 适配层控制器（Agent Controller）。
 *
 * <p>架构定位：COLA 适配层（adapter.agent.web）。
 * 对外提供统一的 ReAct 流式推理端点与人机协同（HIL）确认提交端点：
 * <ul>
 *   <li>{@code POST /ai/agent/v1/stream}：输出结构化 SSE 事件流（{@code agent_step} / {@code tool_result} / {@code confirm_request} / {@code citations} / {@code message} / {@code done} / {@code error}）；</li>
 *   <li>{@code POST /ai/agent/v1/confirm}：提交用户对写操作挂起单的“确认执行”或“取消”决策。</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai/agent/v1")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentApplicationService agentApplicationService;
    private final ObjectMapper objectMapper;

    public AgentController(AgentApplicationService agentApplicationService, ObjectMapper objectMapper) {
        this.agentApplicationService = agentApplicationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /** 流式推理请求 DTO */
    public record AgentStreamRequest(
            String message,
            String sessionId,
            String mode,
            String plannerSessionId,
            String tripId,
            Integer baseRevision,
            String activeCity,
            Integer activeDay,
            String activeStopId,
            String activeStopName
    ) {
    }

    /** 人机确认提交请求 DTO */
    public record AgentConfirmRequest(
            String confirmId,
            Boolean approved,
            String sessionId
    ) {
    }

    /**
     * 统一智能体 SSE 流式对话端点。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestBody(required = false) AgentStreamRequest request,
            @RequestHeader(value = "X-Plan-Session-Token", required = false) String plannerToken) {
        AgentStreamRequest req = request != null
                ? request
                : new AgentStreamRequest("", "default", "normal", "", "", null, "重庆", null, "", "");

        AgentStreamCommand command = AgentStreamCommand.of(
                req.message(),
                req.sessionId(),
                req.mode(),
                req.plannerSessionId(),
                plannerToken,
                req.tripId(),
                req.baseRevision(),
                req.activeCity(),
                req.activeDay(),
                req.activeStopId(),
                req.activeStopName(),
                UserContext.getUserId(),
                UserContext.getUsername(),
                UserContext.getRole(),
                UserContext.isAnonymous()
        );

        return agentApplicationService.streamAgent(command)
                .map(this::toSse);
    }

    /**
     * 人机协同（HIL）确认提交端点。
     */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody(required = false) AgentConfirmRequest request) {
        AgentConfirmRequest req = request != null ? request : new AgentConfirmRequest("", Boolean.FALSE, "default");
        AgentConfirmCommand command = new AgentConfirmCommand(
                req.confirmId(),
                Boolean.TRUE.equals(req.approved()),
                req.sessionId(),
                UserContext.getUserId(),
                UserContext.getUsername(),
                UserContext.getRole(),
                UserContext.isAnonymous()
        );
        AgentConfirmResult result = agentApplicationService.confirmAction(command);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }

    private ServerSentEvent<String> toSse(AgentStepEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", event.event());
        envelope.put("step", event.step());
        envelope.put("phase", event.phase());
        envelope.put("toolName", event.toolName());
        envelope.put("message", event.message());
        envelope.put("data", event.data());
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException ex) {
            log.warn("[AgentController] SSE 事件序列化失败 event={}: {}", event.event(), ex.getMessage());
            json = "{\"event\":\"error\",\"message\":\"事件序列化失败\"}";
        }
        return ServerSentEvent.<String>builder()
                .event(event.event())
                .data(json)
                .build();
    }
}

package com.ai.guide.application.agent;

import com.ai.guide.application.agent.dto.AgentConfirmCommand;
import com.ai.guide.application.agent.dto.AgentConfirmResult;
import com.ai.guide.application.agent.dto.AgentStreamCommand;
import com.ai.guide.application.chat.ChatHistoryContextCoordinator;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.agent.gateway.AgentConfirmationPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentPendingConfirmation;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文旅智能体应用服务（Agent Application Service）。
 *
 * <p>架构定位：COLA 应用层（application.agent）。
 * 统筹管理两条核心主链路：
 * <ol>
 *   <li>{@link #streamAgent}：加载受控对话记忆、装配可信上下文、驱动 {@link TravelAgentOrchestrator} 流式推理并在完成后持久化精简摘要；</li>
 *   <li>{@link #confirmAction}：在用户显式点击确认或取消时校验确认单归属与时效，通过 {@link AgentToolRegistry} 受控执行高风险写操作。</li>
 * </ol>
 */
@Service
public class AgentApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AgentApplicationService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TravelAgentOrchestrator orchestrator;
    private final AgentToolRegistry toolRegistry;
    private final AgentConfirmationPort confirmationPort;
    private final ChatConversationMemoryPort conversationMemory;
    private final ChatHistoryContextCoordinator historyContextCoordinator;
    private final ObjectMapper objectMapper;
    private final long streamTimeoutMs;

    @Autowired
    public AgentApplicationService(
            TravelAgentOrchestrator orchestrator,
            AgentToolRegistry toolRegistry,
            AgentConfirmationPort confirmationPort,
            @Autowired(required = false) ChatConversationMemoryPort conversationMemory,
            @Autowired(required = false) ChatHistoryContextCoordinator historyContextCoordinator,
            ObjectMapper objectMapper,
            @Value("${chat.stream.timeout-ms:45000}") long streamTimeoutMs) {
        this.orchestrator = orchestrator;
        this.toolRegistry = toolRegistry;
        this.confirmationPort = confirmationPort;
        this.conversationMemory = conversationMemory;
        this.historyContextCoordinator = historyContextCoordinator;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.streamTimeoutMs = Math.max(5000L, streamTimeoutMs);
    }

    /**
     * 执行智能体流式对话，并自动维护会话记忆与超时兜底。
     */
    public Flux<AgentStepEvent> streamAgent(AgentStreamCommand command) {
        if (command == null || command.message().isBlank()) {
            return Flux.just(
                    AgentStepEvent.error("请先输入您的问题或行程需求。"),
                    AgentStepEvent.done()
            );
        }

        long pipelineTimeout = Math.max(5000L, streamTimeoutMs + 15000L);
        return Flux.defer(() -> {
            String prevUserId = UserContext.getUserId();
            String prevUsername = UserContext.getUsername();
            String prevRole = UserContext.getRole();
            try {
                bindContext(command.userId(), command.username(), command.role(), command.anonymous());
                String sessionKey = buildSessionKey(command.sessionId(), command.userId(), command.anonymous());
                String writeEpoch = resolveWriteEpoch(sessionKey);

                AgentExecutionContext executionContext = new AgentExecutionContext(
                        command.userId(),
                        command.anonymous(),
                        command.sessionId(),
                        command.plannerSessionId(),
                        command.plannerAccessToken(),
                        command.tripId(),
                        command.baseRevision(),
                        command.activeCity(),
                        command.activeDay(),
                        command.activeStopId(),
                        command.activeStopName(),
                        "deep".equalsIgnoreCase(command.mode())
                );

                List<LanguageModelMessage> history = loadBoundedHistory(sessionKey, command.message(), writeEpoch);
                appendMemorySafe(sessionKey, ChatConversationMemoryPort.Role.USER, command.message(), writeEpoch);

                StringBuilder answerBuffer = new StringBuilder();
                AtomicReference<String> pendingSummary = new AtomicReference<>();

                return orchestrator.runStream(command.message(), history, executionContext)
                        .doOnNext(event -> {
                            if ("message".equals(event.event()) && !event.message().isBlank()) {
                                answerBuffer.append(event.message());
                            } else if ("confirm_request".equals(event.event())) {
                                pendingSummary.set("[待确认操作] " + event.message());
                            }
                        })
                        .doOnComplete(() -> {
                            String toSave = !answerBuffer.isEmpty()
                                    ? answerBuffer.toString()
                                    : pendingSummary.get();
                            if (toSave != null && !toSave.isBlank()) {
                                appendMemorySafe(sessionKey, ChatConversationMemoryPort.Role.ASSISTANT, toSave, writeEpoch);
                            }
                        })
                        .doFinally(signal -> UserContext.clear());
            } finally {
                restoreContext(prevUserId, prevUsername, prevRole);
            }
        })
                .timeout(Duration.ofMillis(pipelineTimeout))
                .onErrorResume(error -> {
                    boolean timedOut = error instanceof TimeoutException;
                    String msg = timedOut ? "本次智能体推理超时，请缩小问题范围后重试。" : "智能体服务暂时不可用，请稍后重试。";
                    log.warn("[AgentService] 流式执行失败 kind={} msg={}",
                            timedOut ? "TIMEOUT" : "ERROR", error.getMessage());
                    return Flux.just(AgentStepEvent.error(msg), AgentStepEvent.done());
                });
    }

    /**
     * 处理前端提交的人机确认（批准执行或拒绝取消）。
     */
    public AgentConfirmResult confirmAction(AgentConfirmCommand command) {
        if (command == null || command.confirmId() == null || command.confirmId().isBlank()) {
            return AgentConfirmResult.failure(400, "MISSING_CONFIRM_ID", "缺少确认单标识 confirmId。");
        }
        AgentConfirmationPort.ConsumeResult consumed =
                confirmationPort.consumeIfOwned(command.confirmId(), command.ownerKey());
        switch (consumed.status()) {
            case NOT_FOUND -> {
                return AgentConfirmResult.failure(404, "CONFIRM_NOT_FOUND", "确认单不存在或已失效，请重新发起请求。");
            }
            case FORBIDDEN -> {
                return AgentConfirmResult.failure(403, "CONFIRM_FORBIDDEN", "无权操作其他用户或会话的确认请求。");
            }
            case EXPIRED -> {
                return AgentConfirmResult.failure(410, "CONFIRM_EXPIRED", "确认请求已过期，请重新生成预览后再确认。");
            }
            case ALREADY_CONSUMED -> {
                return AgentConfirmResult.failure(409, "CONFIRM_ALREADY_CONSUMED", "该确认请求已被处理，请勿重复提交。");
            }
            case CONSUMED -> {
                // 校验通过，继续下述分支执行
            }
        }

        AgentPendingConfirmation pending = consumed.confirmation();
        AgentExecutionContext ctx = pending.executionContext();
        String sessionKey = buildSessionKey(
                ctx == null ? command.sessionId() : ctx.sessionId(),
                command.userId(),
                command.anonymous()
        );
        String writeEpoch = resolveWriteEpoch(sessionKey);

        if (!command.approved()) {
            appendMemorySafe(sessionKey, ChatConversationMemoryPort.Role.ASSISTANT,
                    "[已取消确认] 用户取消了“" + pending.summary() + "”，行程与偏好保持不变。", writeEpoch);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("confirmId", pending.confirmId());
            body.put("approved", false);
            body.put("cancelled", true);
            body.put("modified", false);
            body.put("tool", pending.toolName());
            body.put("message", "已取消本次修改，当前行程与偏好均未改变。");
            return AgentConfirmResult.ok(body);
        }

        String prevUserId = UserContext.getUserId();
        String prevUsername = UserContext.getUsername();
        String prevRole = UserContext.getRole();
        AgentToolResult result;
        try {
            bindContext(command.userId(), command.username(), command.role(), command.anonymous());
            result = toolRegistry.execute(pending.toolName(), pending.argumentsJson(), ctx);
        } finally {
            restoreContext(prevUserId, prevUsername, prevRole);
        }

        Map<String, Object> parsedPayload = parseJsonSafe(result.jsonPayload());
        appendMemorySafe(sessionKey, ChatConversationMemoryPort.Role.ASSISTANT,
                "[确认执行结果] " + result.summary(), writeEpoch);

        if (!result.ok()) {
            String errorCode = parsedPayload.get("error") == null ? "CONFIRM_EXECUTION_FAILED"
                    : String.valueOf(parsedPayload.get("error"));
            String message = parsedPayload.get("message") == null ? result.summary()
                    : String.valueOf(parsedPayload.get("message"));
            int status = switch (errorCode) {
                case "REVISION_CONFLICT" -> 409;
                case "NOT_FOUND" -> 404;
                case "LOGIN_REQUIRED", "UNAUTHORIZED" -> 401;
                case "FORBIDDEN" -> 403;
                default -> 422;
            };
            Map<String, Object> errBody = new LinkedHashMap<>();
            errBody.put("ok", false);
            errBody.put("confirmId", pending.confirmId());
            errBody.put("approved", true);
            errBody.put("modified", false);
            errBody.put("tool", pending.toolName());
            errBody.put("error", errorCode);
            errBody.put("message", message);
            errBody.put("data", parsedPayload);
            return new AgentConfirmResult(status, errBody);
        }

        Map<String, Object> okBody = new LinkedHashMap<>();
        okBody.put("ok", true);
        okBody.put("confirmId", pending.confirmId());
        okBody.put("approved", true);
        okBody.put("modified", true);
        okBody.put("tool", pending.toolName());
        okBody.put("message", result.summary());
        okBody.put("data", parsedPayload);
        return AgentConfirmResult.ok(okBody);
    }

    private List<LanguageModelMessage> loadBoundedHistory(String sessionKey, String userMessage, String writeEpoch) {
        try {
            if (historyContextCoordinator != null) {
                if (writeEpoch != null) {
                    historyContextCoordinator.rememberCurrentConstraints(sessionKey, userMessage, writeEpoch);
                }
                List<LanguageModelMessage> ctx = historyContextCoordinator.boundedContext(sessionKey);
                return ctx == null ? List.of() : ctx;
            }
            if (conversationMemory != null) {
                var snapshot = conversationMemory.loadHistory(sessionKey, 20);
                if (snapshot != null && snapshot.messages() != null) {
                    return snapshot.messages().stream()
                            .filter(m -> m.role() != ChatConversationMemoryPort.Role.SYSTEM)
                            .map(m -> new LanguageModelMessage(
                                    m.role() == ChatConversationMemoryPort.Role.ASSISTANT
                                            ? LanguageModelMessage.Role.ASSISTANT
                                            : LanguageModelMessage.Role.USER,
                                    m.content()))
                            .toList();
                }
            }
        } catch (Exception ex) {
            log.warn("[AgentService] 读取历史上下文异常，降级为无历史启动 session={} error={}", sessionKey, ex.getMessage());
        }
        return List.of();
    }

    private String resolveWriteEpoch(String sessionKey) {
        if (conversationMemory == null) {
            return null;
        }
        try {
            return conversationMemory.currentWriteEpoch(sessionKey);
        } catch (Exception ex) {
            log.debug("[AgentService] 获取 writeEpoch 失败 session={}: {}", sessionKey, ex.getMessage());
            return null;
        }
    }

    private void appendMemorySafe(String sessionKey, ChatConversationMemoryPort.Role role, String content, String writeEpoch) {
        if (conversationMemory == null || content == null || content.isBlank()) {
            return;
        }
        try {
            conversationMemory.appendConfirmed(
                    sessionKey,
                    List.of(new ChatConversationMemoryPort.StoredMessage(role, content)),
                    writeEpoch
            );
        } catch (Exception ex) {
            log.debug("[AgentService] 写入会话记忆跳过 session={}: {}", sessionKey, ex.getMessage());
        }
    }

    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, MAP_TYPE);
            return map == null ? Map.of() : map;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String buildSessionKey(String sessionId, String userId, boolean anonymous) {
        String effectiveSid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        if (anonymous || userId == null || userId.isBlank()) {
            return "guest:" + effectiveSid;
        }
        return userId + ":" + effectiveSid;
    }

    private static void bindContext(String userId, String username, String role, boolean anonymous) {
        if (anonymous || userId == null || userId.isBlank()) {
            UserContext.clear();
        } else {
            UserContext.set(userId, username, role);
        }
    }

    private static void restoreContext(String userId, String username, String role) {
        if (userId == null) {
            UserContext.clear();
        } else {
            UserContext.set(userId, username, role);
        }
    }
}

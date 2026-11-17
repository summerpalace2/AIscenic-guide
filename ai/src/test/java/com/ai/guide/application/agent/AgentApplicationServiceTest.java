package com.ai.guide.application.agent;

import com.ai.guide.application.agent.dto.AgentConfirmCommand;
import com.ai.guide.application.agent.dto.AgentConfirmResult;
import com.ai.guide.application.agent.dto.AgentStreamCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.agent.gateway.AgentConfirmationPort;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolCall;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import com.ai.guide.infrastructure.agent.InMemoryAgentConfirmationAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AgentApplicationService} 集成单元测试：
 * 验证流式编排 -> 生成真实 confirmId 挂起 -> 用户取消 / 跨用户越权拦截 / 批准后在可信 UserContext 下执行写入。
 */
class AgentApplicationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanup() {
        UserContext.clear();
    }

    @Test
    void hilFlowSupportsApproveCancelAndRejectsForbiddenOrReplayConfirmations() {
        AtomicInteger writeExecCount = new AtomicInteger(0);
        AgentToolPort hilWriteTool = new AgentToolPort() {
            private final AgentToolSpec spec = new AgentToolSpec(
                    "confirm_apply_trip_adjustment",
                    "应用行程调整",
                    "{\"type\":\"object\",\"properties\":{\"proposalId\":{\"type\":\"string\"}}}",
                    true
            );

            @Override
            public AgentToolSpec spec() {
                return spec;
            }

            @Override
            public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
                assertEquals("user-alice", UserContext.getUserId());
                assertEquals("user-alice", context.userId());
                writeExecCount.incrementAndGet();
                return AgentToolResult.success("confirm_apply_trip_adjustment",
                        "{\"ok\":true,\"applied\":true,\"currentVersion\":5}",
                        "行程已成功应用至 v5");
            }
        };

        LanguageModelPort model = new LanguageModelPort() {
            @Override
            public Publisher<String> stream(List<LanguageModelMessage> messages) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T complete(List<LanguageModelMessage> messages, Class<T> responseType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String completeText(List<LanguageModelMessage> messages) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LanguageModelCompletion completeMeasured(List<LanguageModelMessage> messages, double temperature) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LanguageModelToolTurn completeWithTools(List<LanguageModelMessage> messages,
                                                           List<LanguageModelToolDefinition> tools,
                                                           double temperature) {
                return new LanguageModelToolTurn(
                        "",
                        List.of(new LanguageModelToolCall(
                                "call-hil-1",
                                "confirm_apply_trip_adjustment",
                                "{\"proposalId\":\"prop-1\",\"changeSummary\":\"替换为三峡博物馆\"}"
                        )),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        12
                );
            }
        };

        AgentToolRegistry registry = new AgentToolRegistry(List.of(hilWriteTool), MAPPER);
        AgentConfirmationPort confirmationPort = new InMemoryAgentConfirmationAdapter();
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(model, registry, MAPPER, confirmationPort);
        AgentApplicationService service = new AgentApplicationService(
                orchestrator, registry, confirmationPort, null, null, MAPPER, 30_000L);

        AgentStreamCommand cmd = AgentStreamCommand.of(
                "确认替换方案", "sess-1", "normal", "ps-1", "tok-1", "trip-1", 4,
                "重庆", 2, "", "", "user-alice", "alice", "USER", false);

        List<AgentStepEvent> events = service.streamAgent(cmd).collectList().block();
        assertNotNull(events);
        assertEquals(0, writeExecCount.get(), "流结束时尚未确认，严禁提前写入");

        AgentStepEvent confirmEvt = events.stream()
                .filter(e -> "confirm_request".equals(e.event()))
                .findFirst()
                .orElseThrow();
        String confirmId = String.valueOf(confirmEvt.data().get("confirmId"));
        assertTrue(confirmId.startsWith("cfg_"));
        assertEquals("替换为三峡博物馆", confirmEvt.message());

        // 1. 他人越权提交返回 403 且不执行
        AgentConfirmResult forbidden = service.confirmAction(new AgentConfirmCommand(
                confirmId, true, "sess-1", "user-bob", "bob", "USER", false));
        assertEquals(403, forbidden.statusCode());
        assertEquals(0, writeExecCount.get());

        // 2. 本人批准执行并返回 200
        AgentConfirmResult approved = service.confirmAction(new AgentConfirmCommand(
                confirmId, true, "sess-1", "user-alice", "alice", "USER", false));
        assertEquals(200, approved.statusCode());
        assertEquals(Boolean.TRUE, approved.body().get("modified"));
        assertEquals(1, writeExecCount.get());
        assertNull(UserContext.getUserId(), "执行结束后应恢复并清理 ThreadLocal");

        // 3. 重复提交返回 409，不能再次写入
        AgentConfirmResult replay = service.confirmAction(new AgentConfirmCommand(
                confirmId, true, "sess-1", "user-alice", "alice", "USER", false));
        assertEquals(409, replay.statusCode());
        assertEquals(1, writeExecCount.get());

        // 4. 再起一次挂起，验证取消操作返回 modified=false 且不执行写工具
        List<AgentStepEvent> secondEvents = service.streamAgent(cmd).collectList().block();
        String confirmId2 = String.valueOf(secondEvents.stream()
                .filter(e -> "confirm_request".equals(e.event())).findFirst().orElseThrow()
                .data().get("confirmId"));
        AgentConfirmResult cancelled = service.confirmAction(new AgentConfirmCommand(
                confirmId2, false, "sess-1", "user-alice", "alice", "USER", false));
        assertEquals(200, cancelled.statusCode());
        assertEquals(Boolean.FALSE, cancelled.body().get("modified"));
        assertEquals(Boolean.TRUE, cancelled.body().get("cancelled"));
        assertEquals(1, writeExecCount.get());
    }
}

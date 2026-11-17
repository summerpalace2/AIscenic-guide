package com.ai.guide.adapter.agent.web;

import com.ai.guide.application.agent.AgentApplicationService;
import com.ai.guide.application.agent.dto.AgentConfirmCommand;
import com.ai.guide.application.agent.dto.AgentConfirmResult;
import com.ai.guide.application.agent.dto.AgentStreamCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AgentController} Web/SSE 适配层单元测试。
 */
class AgentControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AgentApplicationService service;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        service = mock(AgentApplicationService.class);
        controller = new AgentController(service, MAPPER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void streamEndpointForwardsTrustedContextAndSerializesStructuredSse() throws Exception {
        UserContext.set("u-88", "traveler", "USER");
        when(service.streamAgent(any(AgentStreamCommand.class))).thenReturn(Flux.just(
                AgentStepEvent.agentStep(1, "reasoning", "", "正在分析问题…"),
                AgentStepEvent.confirmRequest(1, "confirm_apply_trip_adjustment", "请确认更换站点",
                        Map.of("confirmId", "cfg_123", "tool", "confirm_apply_trip_adjustment")),
                AgentStepEvent.done()
        ));

        var req = new AgentController.AgentStreamRequest(
                "把明天下午换成博物馆", "chat-1", "deep", "ps-9", "trip-9", 5,
                "重庆", 2, "stop-5", "洪崖洞");
        List<ServerSentEvent<String>> sse = controller.stream(req, "guest-token-xyz").collectList().block();
        assertNotNull(sse);
        assertEquals(3, sse.size());
        assertEquals("confirm_request", sse.get(1).event());

        JsonNode payload = MAPPER.readTree(sse.get(1).data());
        assertEquals("confirm_apply_trip_adjustment", payload.path("toolName").asText());
        assertEquals("cfg_123", payload.path("data").path("confirmId").asText());

        ArgumentCaptor<AgentStreamCommand> captor = ArgumentCaptor.forClass(AgentStreamCommand.class);
        verify(service).streamAgent(captor.capture());
        AgentStreamCommand cmd = captor.getValue();
        assertEquals("u-88", cmd.userId());
        assertFalse(cmd.anonymous());
        assertEquals("guest-token-xyz", cmd.plannerAccessToken());
        assertEquals(5, cmd.baseRevision());
        assertEquals("ps-9", cmd.plannerSessionId());
    }

    @Test
    void confirmEndpointDelegatesDecisionAndMapsStatusCode() {
        UserContext.set("u-88", "traveler", "USER");
        when(service.confirmAction(any(AgentConfirmCommand.class)))
                .thenReturn(AgentConfirmResult.ok(Map.of("ok", true, "modified", true)));

        ResponseEntity<?> res = controller.confirm(
                new AgentController.AgentConfirmRequest("cfg_123", Boolean.TRUE, "chat-1"));
        assertEquals(200, res.getStatusCode().value());

        ArgumentCaptor<AgentConfirmCommand> captor = ArgumentCaptor.forClass(AgentConfirmCommand.class);
        verify(service).confirmAction(captor.capture());
        assertEquals("cfg_123", captor.getValue().confirmId());
        assertTrue(captor.getValue().approved());
        assertEquals("u-88", captor.getValue().userId());
    }
}

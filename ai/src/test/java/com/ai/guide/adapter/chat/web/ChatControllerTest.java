package com.ai.guide.adapter.chat.web;

import com.ai.guide.application.chat.ChatApplicationService;
import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.application.chat.dto.ChatStreamEvent;
import com.ai.guide.application.chat.dto.ChatStructuredCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.chat.model.ScenicResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatControllerTest {

    private ChatApplicationService chatApplicationService;
    private ChatController chatController;

    @BeforeEach
    void setUp() {
        chatApplicationService = mock(ChatApplicationService.class);
        chatController = new ChatController(chatApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testChatStreamDelegatesToApplicationService() {
        UserContext.set("user-123", "alice", "USER");

        when(chatApplicationService.streamChat(any(ChatStreamCommand.class)))
                .thenReturn(Flux.just(new ChatStreamEvent("meta", "{}")));

        Flux<ServerSentEvent<String>> result = chatController.chatStream(
                "推荐重庆火锅",
                "session-001",
                "normal",
                "plan-session-99",
                "trip-888",
                "v2",
                "1",
                "stop-10",
                "洪崖洞",
                "{\"companions\":\"friends\"}"
        );

        var events = result.collectList().block();
        assertNotNull(events);
        assertEquals("meta", events.get(0).event());
        assertEquals("{}", events.get(0).data());

        ArgumentCaptor<ChatStreamCommand> captor = ArgumentCaptor.forClass(ChatStreamCommand.class);
        verify(chatApplicationService, times(1)).streamChat(captor.capture());

        ChatStreamCommand command = captor.getValue();
        assertEquals("推荐重庆火锅", command.message());
        assertEquals("session-001", command.sessionId());
        assertEquals("normal", command.mode());
        assertEquals("plan-session-99", command.plannerSessionId());
        assertEquals("trip-888", command.tripId());
        assertEquals("v2", command.currentVersion());
        assertEquals("1", command.activeDay());
        assertEquals("stop-10", command.activeStopId());
        assertEquals("洪崖洞", command.activeStopName());
        assertEquals("{\"companions\":\"friends\"}", command.planContext());
        assertEquals("user-123", command.userId());
        assertEquals("alice", command.username());
        assertEquals("USER", command.role());
    }

    @Test
    void postSseSerializationPreservesWhitespaceTokensAndJsonEventContracts() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var tokens = java.util.List.of("<action_chip", " ", "action=", "\"查看局部调整\"", " ",
                "icon=\"map\">", "\n", "Plan", " ", "A", "\r\n", "</action_chip>");
        var source = new java.util.ArrayList<ChatStreamEvent>();
        source.add(new ChatStreamEvent("meta", "{\"sessionId\":\"stable\"}"));
        source.add(new ChatStreamEvent("progress", "{\"stage\":\"ready\"}"));
        tokens.forEach(token -> source.add(new ChatStreamEvent("text", token)));
        when(chatApplicationService.streamChat(any(ChatStreamCommand.class))).thenReturn(Flux.fromIterable(source));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(chatController)
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .build();
        var pending = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/ai/chat/stream")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .accept(org.springframework.http.MediaType.TEXT_EVENT_STREAM)
                .content("{\"message\":\"test\",\"sessionId\":\"stable\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        var raw = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var reconstructed = new StringBuilder();
        int textCount = 0;
        for (String frame : raw.split("\\r?\\n\\r?\\n")) {
            String event = "", data = "";
            for (String line : frame.split("\\r?\\n")) {
                if (line.startsWith("event:")) event = line.substring(6).replaceFirst("^ ", "");
                if (line.startsWith("data:")) data = line.substring(5).replaceFirst("^ ", "");
            }
            if ("text".equals(event)) {
                reconstructed.append(mapper.readValue(data, String.class));
                textCount++;
            } else if ("meta".equals(event)) {
                assertEquals("stable", mapper.readTree(data).get("sessionId").asText());
            } else if ("progress".equals(event)) {
                assertEquals("ready", mapper.readTree(data).get("stage").asText());
            }
        }
        assertEquals(tokens.size(), textCount);
        assertEquals(String.join("", tokens), reconstructed.toString());
    }

    @Test
    void testChatStructuredDelegatesToApplicationService() {
        UserContext.set("user-456", "bob", "USER");

        ScenicResponse fakeResponse = new ScenicResponse();
        when(chatApplicationService.chatStructured(any(ChatStructuredCommand.class)))
                .thenReturn(Result.success("查询成功", fakeResponse));

        Result<ScenicResponse> result = chatController.chatStructured("解放碑怎么去", "session-002", "deep");

        assertNotNull(result);
        assertTrue(result.isSuccess());

        ArgumentCaptor<ChatStructuredCommand> captor = ArgumentCaptor.forClass(ChatStructuredCommand.class);
        verify(chatApplicationService, times(1)).chatStructured(captor.capture());

        ChatStructuredCommand command = captor.getValue();
        assertEquals("解放碑怎么去", command.message());
        assertEquals("session-002", command.sessionId());
        assertEquals("deep", command.mode());
        assertEquals("user-456", command.userId());
    }
}

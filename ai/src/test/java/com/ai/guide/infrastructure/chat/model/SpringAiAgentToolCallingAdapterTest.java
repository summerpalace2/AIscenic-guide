package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolCall;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SpringAiChatModelAdapter#completeWithTools} 单元测试：
 * 验证 {@code proxyToolCalls=true}、工具定义有序映射、{@code Role.TOOL}/{@code AssistantMessage.ToolCall} 双向转换及异常边界。
 */
class SpringAiAgentToolCallingAdapterTest {

    @Test
    void completeWithToolsConfiguresProxyToolCallsDeterministicToolsAndExtractsToolCalls() {
        var fixture = new Fixture();
        AssistantMessage assistantOutput = new AssistantMessage(
                "",
                Map.of(),
                List.of(new AssistantMessage.ToolCall("call-1", "function", "query_weather_forecast", "{\"city\":\"重庆\"}"))
        );
        ChatResponse response = new ChatResponse(
                List.of(new Generation(assistantOutput)),
                ChatResponseMetadata.builder()
                        .withModel("deepseek-chat")
                        .withUsage(usage(120L, 18L, 138L))
                        .build()
        );
        when(fixture.call.chatResponse()).thenReturn(response);

        List<LanguageModelMessage> messages = List.of(
                new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, "系统提示词"),
                new LanguageModelMessage(LanguageModelMessage.Role.USER, "明天适合去哪里？"),
                LanguageModelMessage.assistantToolCalls("", List.of(
                        new LanguageModelToolCall("call-prev", "search_poi_and_dining", "{\"query\":\"博物馆\"}")
                )),
                LanguageModelMessage.toolResult("call-prev", "search_poi_and_dining", "{\"ok\":true}")
        );
        List<LanguageModelToolDefinition> tools = List.of(
                new LanguageModelToolDefinition("query_weather_forecast", "查询天气预报", "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                new LanguageModelToolDefinition("search_poi_and_dining", "检索POI", "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}")
        );

        LanguageModelToolTurn turn = fixture.adapter.completeWithTools(messages, tools, 0.2d);

        // 1. 验证返回的工具调用及 Token 计量信息
        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call-1", turn.toolCalls().get(0).id());
        assertEquals("query_weather_forecast", turn.toolCalls().get(0).name());
        assertEquals("{\"city\":\"重庆\"}", turn.toolCalls().get(0).argumentsJson());
        assertEquals(new LanguageModelUsage(120L, 18L, 138L), turn.usage());
        assertEquals("deepseek-chat", turn.model());

        // 2. 验证 OpenAiChatOptions 开启 proxyToolCalls=true 且保留声明顺序的工具定义
        ArgumentCaptor<ChatOptions> optionsCaptor = ArgumentCaptor.forClass(ChatOptions.class);
        verify(fixture.request).options(optionsCaptor.capture());
        assertInstanceOf(OpenAiChatOptions.class, optionsCaptor.getValue());
        OpenAiChatOptions openAiOptions = (OpenAiChatOptions) optionsCaptor.getValue();
        assertEquals(Boolean.TRUE, openAiOptions.getProxyToolCalls());
        assertEquals(0.2d, openAiOptions.getTemperature());
        List<OpenAiApi.FunctionTool> capturedTools = openAiOptions.getTools();
        assertNotNull(capturedTools);
        assertEquals(2, capturedTools.size());
        assertEquals("query_weather_forecast", capturedTools.get(0).function().name());
        assertEquals("查询天气预报", capturedTools.get(0).function().description());
        assertEquals("object", capturedTools.get(0).function().parameters().get("type"));
        assertEquals("search_poi_and_dining", capturedTools.get(1).function().name());

        // 3. 验证消息序列转换：SYSTEM -> USER -> ASSISTANT(toolCalls) -> TOOL(ToolResponseMessage)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(fixture.request).messages(msgCaptor.capture());
        List<Message> springMessages = msgCaptor.getValue();
        assertEquals(4, springMessages.size());
        assertInstanceOf(SystemMessage.class, springMessages.get(0));
        assertInstanceOf(UserMessage.class, springMessages.get(1));
        assertInstanceOf(AssistantMessage.class, springMessages.get(2));
        AssistantMessage prevAssistant = (AssistantMessage) springMessages.get(2);
        assertEquals(1, prevAssistant.getToolCalls().size());
        assertEquals("call-prev", prevAssistant.getToolCalls().get(0).id());
        assertInstanceOf(ToolResponseMessage.class, springMessages.get(3));
        ToolResponseMessage toolMsg = (ToolResponseMessage) springMessages.get(3);
        assertEquals("call-prev", toolMsg.getResponses().get(0).id());
        assertEquals("search_poi_and_dining", toolMsg.getResponses().get(0).name());
        assertEquals("{\"ok\":true}", toolMsg.getResponses().get(0).responseData());
    }

    @Test
    void invalidTemperatureInvalidSchemaAndEmptyResponseFailFast() {
        var fixture = new Fixture();
        List<LanguageModelMessage> msgs = List.of(new LanguageModelMessage(LanguageModelMessage.Role.USER, "hi"));

        // 非法 temperature 直接拒绝，不触发远端调用
        assertThrows(IllegalArgumentException.class, () -> fixture.adapter.completeWithTools(msgs, List.of(), -0.5d));
        assertThrows(IllegalArgumentException.class, () -> fixture.adapter.completeWithTools(msgs, List.of(), 2.5d));
        verify(fixture.client, never()).prompt();

        // 非法工具 JSON Schema 快速失败
        List<LanguageModelToolDefinition> badTools = List.of(
                new LanguageModelToolDefinition("bad_tool", "desc", "{not-json}")
        );
        assertThrows(IllegalArgumentException.class, () -> fixture.adapter.completeWithTools(msgs, badTools, 0.2d));

        // 空响应抛出 IllegalStateException
        doReturn(null).when(fixture.call).chatResponse();
        assertThrows(IllegalStateException.class, () -> fixture.adapter.completeWithTools(msgs, List.of(), 0.2d));
    }

    private static Usage usage(Long prompt, Long completion, Long total) {
        return new Usage() {
            @Override
            public Long getPromptTokens() {
                return prompt;
            }

            @Override
            public Long getGenerationTokens() {
                return completion;
            }

            @Override
            public Long getTotalTokens() {
                return total;
            }
        };
    }

    private static class Fixture {
        final ChatClient client = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        final SpringAiChatModelAdapter adapter;

        @SuppressWarnings("unchecked")
        Fixture() {
            var builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(client);
            when(client.prompt()).thenReturn(request);
            when(request.messages(any(List.class))).thenReturn(request);
            when(request.options(any(ChatOptions.class))).thenReturn(request);
            when(request.call()).thenReturn(call);
            adapter = new SpringAiChatModelAdapter(builder);
        }
    }
}

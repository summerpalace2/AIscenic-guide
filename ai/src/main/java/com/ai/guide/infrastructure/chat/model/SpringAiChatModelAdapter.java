package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolCall;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Spring AI implementation of the chat model capability boundary. */
@Component
public class SpringAiChatModelAdapter implements LanguageModelPort {

    private static final TypeReference<Map<String, Object>> SCHEMA_MAP_TYPE = new TypeReference<>() {};

    private final ChatClient chatClient;
    private final boolean disableThinking;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiChatModelAdapter(ChatClient.Builder builder) {
        this(builder, false);
    }

    @Autowired
    public SpringAiChatModelAdapter(ChatClient.Builder builder,
            @Value("${rag.semantic.disable-thinking:false}") boolean disableThinking) {
        this.chatClient = Objects.requireNonNull(builder, "builder").build();
        this.disableThinking = disableThinking;
    }

    @Override
    public Flux<String> stream(List<LanguageModelMessage> messages) {
        return chatClient.prompt().messages(toSpringAiMessages(messages)).stream().content();
    }

    @Override
    public <T> T complete(List<LanguageModelMessage> messages, Class<T> responseType) {
        return chatClient.prompt().messages(toSpringAiMessages(messages)).call().entity(responseType);
    }

    @Override
    public String completeText(List<LanguageModelMessage> messages) {
        return chatClient.prompt().messages(toSpringAiMessages(messages)).call().content();
    }

    @Override
    public LanguageModelCompletion completeMeasured(List<LanguageModelMessage> messages, double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be finite and between 0 and 2");
        }
        long started = System.nanoTime();
        var options = disableThinking
                ? OpenAiChatOptions.builder().withTemperature(temperature)
                    .withHttpHeaders(java.util.Map.of(MeasuredThinkingRequestInterceptor.MARKER, "true")).build()
                : ChatOptionsBuilder.builder().withTemperature(temperature).build();
        var response = chatClient.prompt().messages(toSpringAiMessages(messages))
                .options(options).call().chatResponse();
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Provider returned no completion");
        }
        var metadata = response.getMetadata();
        LanguageModelUsage usage = extractUsage(metadata == null ? null : metadata.getUsage());
        String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank()
                ? null : metadata.getModel();
        return new LanguageModelCompletion(response.getResult().getOutput().getContent(), usage,
                model, elapsedMs, usage.available());
    }

    /**
     * 执行带工具声明的单轮推理。
     * 通过 {@code withProxyToolCalls(true)} 禁用 Spring AI 内部自动循环，
     * 将原始 {@code toolCalls} 交还给上层编排器以掌控最大步数、SSE 进度推送与人工确认边界。
     */
    @Override
    public LanguageModelToolTurn completeWithTools(List<LanguageModelMessage> messages,
                                                     List<LanguageModelToolDefinition> tools,
                                                     double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature 必须为 0 到 2 之间的有限数值");
        }
        long started = System.nanoTime();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .withTemperature(temperature)
                .withProxyToolCalls(true);
        if (tools != null && !tools.isEmpty()) {
            optionsBuilder.withTools(tools.stream().map(this::toSpringTool).toList());
        }
        var response = chatClient.prompt()
                .messages(toSpringAiMessages(messages))
                .options(optionsBuilder.build())
                .call()
                .chatResponse();
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型未返回有效工具推理响应");
        }
        AssistantMessage output = response.getResult().getOutput();
        List<LanguageModelToolCall> calls = output.getToolCalls() == null
                ? List.of()
                : output.getToolCalls().stream()
                    .map(tc -> new LanguageModelToolCall(tc.id(), tc.name(), tc.arguments()))
                    .toList();
        var metadata = response.getMetadata();
        LanguageModelUsage usage = extractUsage(metadata == null ? null : metadata.getUsage());
        String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank()
                ? null : metadata.getModel();
        return new LanguageModelToolTurn(output.getContent(), calls, usage, model, elapsedMs);
    }

    private static LanguageModelUsage extractUsage(Usage sdkUsage) {
        return sdkUsage == null || sdkUsage instanceof EmptyUsage
                ? LanguageModelUsage.unknown()
                : new LanguageModelUsage(knownCount(sdkUsage.getPromptTokens()),
                    knownCount(sdkUsage.getGenerationTokens()), knownCount(sdkUsage.getTotalTokens()));
    }

    private OpenAiApi.FunctionTool toSpringTool(LanguageModelToolDefinition toolDef) {
        Map<String, Object> schemaMap;
        try {
            schemaMap = objectMapper.readValue(toolDef.inputJsonSchema(), SCHEMA_MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalArgumentException("工具 JSON Schema 解析失败: " + toolDef.name(), e);
        }
        return new OpenAiApi.FunctionTool(
                new OpenAiApi.FunctionTool.Function(toolDef.description(), toolDef.name(), schemaMap));
    }

    private static Long knownCount(Long count) {
        // M3 OpenAiUsage coerces absent fields to zero. Preserve that ambiguity as unknown;
        // its total can also be an SDK sum, so these are SDK metadata, not raw response fields.
        return count == null || count <= 0 ? null : count;
    }

    private List<Message> toSpringAiMessages(List<LanguageModelMessage> messages) {
        return messages.stream().map(message -> switch (message.role()) {
            case SYSTEM -> (Message) new SystemMessage(message.content());
            case USER -> (Message) new UserMessage(message.content());
            case ASSISTANT -> {
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    List<AssistantMessage.ToolCall> springCalls = message.toolCalls().stream()
                            .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.argumentsJson()))
                            .toList();
                    yield (Message) new AssistantMessage(message.content(), Map.of(), springCalls);
                }
                yield (Message) new AssistantMessage(message.content());
            }
            case TOOL -> (Message) new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(
                            message.toolCallId() == null ? "" : message.toolCallId(),
                            message.toolName() == null ? "" : message.toolName(),
                            message.content())));
        }).toList();
    }
}

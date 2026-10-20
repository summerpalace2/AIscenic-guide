package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/** Spring AI implementation of the chat model capability boundary. */
@Component
public class SpringAiChatModelAdapter implements LanguageModelPort {

    private final ChatClient chatClient;
    private final boolean disableThinking;

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
        Usage sdkUsage = metadata == null ? null : metadata.getUsage();
        LanguageModelUsage usage = sdkUsage == null || sdkUsage instanceof EmptyUsage
                ? LanguageModelUsage.unknown()
                : new LanguageModelUsage(knownCount(sdkUsage.getPromptTokens()),
                    knownCount(sdkUsage.getGenerationTokens()), knownCount(sdkUsage.getTotalTokens()));
        String model = metadata == null || metadata.getModel() == null || metadata.getModel().isBlank()
                ? null : metadata.getModel();
        return new LanguageModelCompletion(response.getResult().getOutput().getContent(), usage,
                model, elapsedMs, usage.available());
    }

    private static Long knownCount(Long count) {
        // M3 OpenAiUsage coerces absent fields to zero. Preserve that ambiguity as unknown;
        // its total can also be an SDK sum, so these are SDK metadata, not raw response fields.
        return count == null || count <= 0 ? null : count;
    }

    private List<Message> toSpringAiMessages(List<LanguageModelMessage> messages) {
        return messages.stream().map(message -> switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
        }).map(Message.class::cast).toList();
    }
}

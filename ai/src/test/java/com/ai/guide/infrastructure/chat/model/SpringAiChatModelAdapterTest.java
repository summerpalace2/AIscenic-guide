package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.chat.model.ScenicResponse;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiChatModelAdapterTest {

    private ChatClient client;
    private ChatClient.ChatClientRequestSpec request;
    private ChatClient.Builder builder;
    private SpringAiChatModelAdapter adapter;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        client = mock(ChatClient.class);
        request = mock(ChatClient.ChatClientRequestSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(request);
        when(request.messages(any(List.class))).thenReturn(request);
        adapter = new SpringAiChatModelAdapter(builder);
    }

    @Test
    void streamingReturnsProviderFluxAndPropagatesCancellation() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> provider = Flux.<String>never().doOnCancel(() -> cancelled.set(true));
        var messages = List.of(new LanguageModelMessage(LanguageModelMessage.Role.USER, "你好"));
        var result = mock(ChatClient.StreamResponseSpec.class);
        when(request.stream()).thenReturn(result);
        when(result.content()).thenReturn(provider);

        adapter.stream(messages).subscribe().dispose();

        assertTrue(cancelled.get());
        var translated = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(request).messages(translated.capture());
        var providerMessages = (List<Message>) translated.getValue();
        assertEquals("你好", providerMessages.get(0).getContent());
        assertEquals(org.springframework.ai.chat.messages.MessageType.USER, providerMessages.get(0).getMessageType());
        verify(result).content();
    }

    @Test
    void typedCompletionDelegatesRequestedResponseType() {
        var messages = List.of(new LanguageModelMessage(LanguageModelMessage.Role.USER, "推荐景点"));
        var response = mock(ChatClient.CallResponseSpec.class);
        var expected = new ScenicResponse();
        when(request.call()).thenReturn(response);
        when(response.entity(ScenicResponse.class)).thenReturn(expected);

        assertSame(expected, adapter.complete(messages, ScenicResponse.class));
        verify(request).messages(any(List.class));
        verify(response).entity(ScenicResponse.class);
    }

    @Test
    void textCompletionReturnsProviderTextAndDoesNotSwallowFailure() {
        var messages = List.of(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, "摘要要求"),
                new LanguageModelMessage(LanguageModelMessage.Role.USER, "生成摘要"));
        var response = mock(ChatClient.CallResponseSpec.class);
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("摘要");
        assertEquals("摘要", adapter.completeText(messages));

        RuntimeException failure = new IllegalStateException("provider unavailable");
        when(request.call()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> adapter.completeText(messages)));
    }
}

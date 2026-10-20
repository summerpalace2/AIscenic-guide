package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LanguageModelMeasurementTest {
    private static final List<LanguageModelMessage> MESSAGES = List.of(
            new LanguageModelMessage(LanguageModelMessage.Role.USER, "判定资料能否回答问题"));

    @Test
    void requestTemperatureIsScopedAndMetadataIsRetained() {
        var fixture = new Fixture();
        Usage usage = usage(17L, 5L, 22L);
        when(fixture.call.chatResponse()).thenReturn(response(usage));
        when(fixture.call.content()).thenReturn("ordinary completion");

        var measured = fixture.adapter.completeMeasured(MESSAGES, 0);
        assertEquals("answer", measured.text());
        assertEquals(new LanguageModelUsage(17L, 5L, 22L), measured.usage());
        assertEquals("provider-model", measured.model());
        assertTrue(measured.measurementAvailable());
        assertTrue(measured.elapsedMs() >= 0);
        var options = org.mockito.ArgumentCaptor.forClass(ChatOptions.class);
        verify(fixture.request).options(options.capture());
        assertEquals(0.0, options.getValue().getTemperature());
        assertNull(options.getValue().getModel());

        clearInvocations(fixture.request);
        assertEquals("ordinary completion", fixture.adapter.completeText(MESSAGES));
        verify(fixture.request, never()).options(any(ChatOptions.class));
    }

    @Test
    void defaultEmptySdkUsageDoesNotBecomeZeroConsumption() {
        var fixture = new Fixture();
        when(fixture.call.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation("answer"))));
        var measured = fixture.adapter.completeMeasured(MESSAGES, 0);
        assertEquals(LanguageModelUsage.unknown(), measured.usage());
        assertFalse(measured.measurementAvailable());
        assertNull(measured.model());
    }

    @Test
    void zeroCountsRemainUnknownBecauseSdkConflatesZeroWithAbsentFields() {
        var fixture = new Fixture();
        when(fixture.call.chatResponse()).thenReturn(response(usage(0L, 7L, 7L)));
        var measured = fixture.adapter.completeMeasured(MESSAGES, 0);
        assertNull(measured.usage().promptTokens());
        assertEquals(7L, measured.usage().completionTokens());
        assertTrue(measured.measurementAvailable());
    }

    @Test
    void absentMetadataAndNullCountsRemainUnknown() {
        var fixture = new Fixture();
        when(fixture.call.chatResponse()).thenReturn(response(usage(null, null, null)));
        assertFalse(fixture.adapter.completeMeasured(MESSAGES, 0).measurementAvailable());
        when(fixture.call.chatResponse()).thenReturn(new ChatResponse(List.of(new Generation("answer")), null));
        assertFalse(fixture.adapter.completeMeasured(MESSAGES, 0).measurementAvailable());
    }

    @Test
    void failureAndEmptyResponseAreNotReportedAsMeasuredSuccess() {
        var fixture = new Fixture();
        RuntimeException failure = new IllegalStateException("provider unavailable");
        when(fixture.call.chatResponse()).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class,
                () -> fixture.adapter.completeMeasured(MESSAGES, 0)));
        doReturn(null).when(fixture.call).chatResponse();
        assertThrows(IllegalStateException.class, () -> fixture.adapter.completeMeasured(MESSAGES, 0));
    }

    @Test
    void invalidTemperatureFailsBeforeProviderCall() {
        var fixture = new Fixture();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 2.1}) {
            assertThrows(IllegalArgumentException.class, () -> fixture.adapter.completeMeasured(MESSAGES, value));
        }
        verify(fixture.client, never()).prompt();
    }

    @Test
    void legacyPortDefaultPreservesTextButDoesNotInventUsageOrModel() {
        LanguageModelPort legacy = new LanguageModelPort() {
            public Publisher<String> stream(List<LanguageModelMessage> messages) { throw new UnsupportedOperationException(); }
            public <T> T complete(List<LanguageModelMessage> messages, Class<T> type) { throw new UnsupportedOperationException(); }
            public String completeText(List<LanguageModelMessage> messages) { return "legacy answer"; }
        };
        var measured = legacy.completeMeasured(MESSAGES, 0);
        assertEquals("legacy answer", measured.text());
        assertFalse(measured.measurementAvailable());
        assertEquals(LanguageModelUsage.unknown(), measured.usage());
        assertNull(measured.model());
        assertThrows(IllegalArgumentException.class, () -> legacy.completeMeasured(MESSAGES, Double.NaN));
    }

    @Test
    void domainRecordsRejectNegativeOrInconsistentMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> new LanguageModelUsage(-1L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new LanguageModelUsage(null, -1L, null));
        assertThrows(IllegalArgumentException.class, () -> new LanguageModelUsage(null, null, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LanguageModelCompletion("text", LanguageModelUsage.unknown(), null, -1, false));
        assertThrows(IllegalArgumentException.class,
                () -> new LanguageModelCompletion("text", LanguageModelUsage.unknown(), null, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> new LanguageModelCompletion("text", new LanguageModelUsage(1L, null, null), null, 0, false));
    }

    private static ChatResponse response(Usage usage) {
        return new ChatResponse(List.of(new Generation("answer")), ChatResponseMetadata.builder()
                .withUsage(usage).withModel("provider-model").build());
    }

    private static Usage usage(Long prompt, Long completion, Long total) {
        return new Usage() {
            public Long getPromptTokens() { return prompt; }
            public Long getGenerationTokens() { return completion; }
            public Long getTotalTokens() { return total; }
        };
    }

    private static class Fixture {
        final ChatClient client = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        final ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        final SpringAiChatModelAdapter adapter;

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

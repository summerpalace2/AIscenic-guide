package com.ai.guide.domain.chat.service;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.chat.gateway.ChatSummaryStorePort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextCompressionServiceTest {
    private final ChatSummaryStorePort summaryStore = mock(ChatSummaryStorePort.class);
    private final LanguageModelPort modelGateway = mock(LanguageModelPort.class);
    private final SlotTrackingService slots = mock(SlotTrackingService.class);

    @Test
    void compressesOlderMessagesAndPersistsCursorThroughPorts() {
        var service = new ContextCompressionService(summaryStore, modelGateway, slots, 2);
        when(modelGateway.completeText(anyList())).thenReturn("保留用户的重庆行程偏好");
        when(slots.toPromptContext("u1")).thenReturn("");

        List<LanguageModelMessage> result = service.getContext("s1", history("one", "two", "three", "four"), "u1");

        assertEquals(3, result.size());
        assertEquals(LanguageModelMessage.Role.SYSTEM, result.get(0).role());
        assertEquals("three", result.get(1).content());
        assertEquals("four", result.get(2).content());
        verify(summaryStore).saveSummary("s1", "保留用户的重庆行程偏好", 2);
        verify(summaryStore).incrementCompressionCount("s1");
    }

    @Test
    void providerFailureKeepsRecentMessagesAndDoesNotPersistASummary() {
        var service = new ContextCompressionService(summaryStore, modelGateway, slots, 2);
        when(modelGateway.completeText(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
        when(slots.toPromptContext("u1")).thenReturn("");

        List<LanguageModelMessage> result = service.getContext("s1", history("one", "two", "three", "four"), "u1");

        assertEquals(3, result.size());
        assertEquals(LanguageModelMessage.Role.SYSTEM, result.get(0).role());
        assertEquals("three", result.get(1).content());
        assertEquals("four", result.get(2).content());
        verify(summaryStore, never()).saveSummary(eq("s1"), org.mockito.ArgumentMatchers.anyString(), eq(2));
    }

    private static List<LanguageModelMessage> history(String... messages) {
        return java.util.Arrays.stream(messages).map(message -> new LanguageModelMessage(LanguageModelMessage.Role.USER, message)).toList();
    }
}

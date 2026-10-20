package com.ai.guide.application.chat;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatLongConversationLifecycleTest {
    @Test
    void sixtyTurnsRetainExplicitRetractionOutsideRecentWindowWithoutErasingUnrelatedAllergy() {
        var memory = mock(ChatConversationMemoryPort.class);
        var model = mock(LanguageModelPort.class);
        var executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return CompletableFuture.completedFuture(null);
        });
        var history = new ArrayList<ChatConversationMemoryPort.StoredMessage>();
        var facts = new HashMap<String, Long>();
        var summary = new AtomicReference<String>();
        var cursor = new AtomicLong();
        when(memory.loadHistory(eq("s"), eq(50))).thenAnswer(call ->
                new ChatConversationMemoryPort.HistorySnapshot(history.size(), List.copyOf(history.subList(Math.max(0, history.size() - 50), history.size()))));
        when(memory.loadSummarySnapshot("s")).thenAnswer(call -> new ChatConversationMemoryPort.SummarySnapshot(
                summary.get(), cursor.get(), ordered(facts), Map.copyOf(facts)));
        when(memory.rememberCriticalConstraints(eq("s"), anyList(), anyInt(), anyLong(),org.mockito.ArgumentMatchers.nullable(String.class))).thenAnswer(call -> {
            List<String> incoming = call.getArgument(1);
            long sequence = call.getArgument(3);
            for (int i = 0; i < incoming.size(); i++) facts.merge(incoming.get(i), sequence + i, Math::max);
            return ordered(facts);
        });
        doAnswer(call -> { summary.set(call.getArgument(1)); cursor.set(call.getArgument(2)); return null; })
                .when(memory).saveSummarySnapshot(eq("s"), anyString(), anyLong(),org.mockito.ArgumentMatchers.nullable(String.class));
        when(model.completeText(anyList())).thenReturn("文化旅行讨论摘要"); // Intentionally omits safety facts.
        var coordinator = new ChatHistoryContextCoordinator(memory, model, executor, new JsonTaxonomyAdapter());
        String original = "腿脚不便，不能爬楼梯；另外花生过敏";
        String retraction = "现在已经康复，可以正常爬楼梯，取消之前的行动限制，饮食要求保持";
        List<LanguageModelMessage> context = List.of();
        for (int turn = 0; turn < 60; turn++) {
            String user = turn == 0 ? original : turn == 5 ? retraction : "第" + turn + "轮：继续讨论这次旅行";
            coordinator.rememberCurrentConstraints("s", user);
            context = coordinator.boundedContext("s");
            history.add(new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.USER, user));
            history.add(new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.ASSISTANT, "本轮旅行建议"));
            assertTrue(context.size() <= 27, "bounded history plus summary, limit notice and safety facts");
        }
        assertEquals(120, history.size());
        assertTrue(cursor.get() > 80, "compression advances rather than repeatedly summarizing the prefix");
        assertFalse(context.stream().skip(1).anyMatch(message -> message.content().equals(retraction)), "retraction is outside the recent window");
        String safety = context.get(0).content();
        assertTrue(safety.contains(original));
        assertTrue(safety.contains(retraction));
        assertTrue(safety.indexOf(original) < safety.indexOf(retraction), "latest correction follows original restriction");
        assertTrue(safety.contains("花生过敏"), "mobility correction does not delete the allergy fact");
        verify(model, atLeast(1)).completeText(anyList());
    }

    @Test
    void revisionSignalWithoutExistingSafetyFactsDoesNotCreatePermanentRestrictions() {
        var memory = mock(ChatConversationMemoryPort.class);
        when(memory.loadSummarySnapshot("s")).thenReturn(new ChatConversationMemoryPort.SummarySnapshot(null, 0, List.of(), Map.of()));
        var coordinator = new ChatHistoryContextCoordinator(memory, mock(LanguageModelPort.class), mock(ExecutorService.class), new JsonTaxonomyAdapter());
        coordinator.rememberCurrentConstraints("s", "取消之前的博物馆推荐");
        verify(memory, never()).rememberCriticalConstraints(anyString(), anyList(), anyInt(), anyLong(),org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void providerFailureRetainsAlreadyVisibleAnswerForTheNextFollowUp() {
        var memory = mock(ChatConversationMemoryPort.class);
        var output = new ChatStreamOutputAdapter();
        var events = output.adapt(Flux.concat(Flux.just("方案A：河边休息"), Flux.error(new IllegalStateException("fixture"))),
                2000, "s", "s", "给我两个方案", SentimentService.Sentiment.NEUTRAL, IntentService.Intent.QA,
                memory, null).collectList().block();
        assertNotNull(events);
        assertEquals(List.of("text", "error"), events.stream().map(event -> event.event()).toList());
        verify(memory).appendConfirmed(eq("s"), argThat(messages -> messages.size() == 1
                && messages.get(0).content().startsWith("方案A：河边休息")
                && messages.get(0).content().contains("回答未完成")),org.mockito.ArgumentMatchers.nullable(String.class));
    }

    private static List<String> ordered(Map<String, Long> facts) {
        return facts.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
    }
}

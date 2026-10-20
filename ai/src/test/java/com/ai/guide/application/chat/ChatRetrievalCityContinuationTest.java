package com.ai.guide.application.chat;

import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.domain.chat.service.SlotTrackingService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatRetrievalCityContinuationTest {
    @Test
    void actualChatServiceRoutesRagAndNearbySearchToLatestUserCityWithinItsOwnSession() {
        var memory = mock(ChatConversationMemoryPort.class);
        var model = mock(LanguageModelPort.class);
        var rag = mock(RagRetrievalService.class);
        var geo = mock(GeoRouteGatewayPort.class);
        var detail = mock(RuntimeAttractionDetailService.class);
        var taxonomy = new JsonTaxonomyAdapter();
        var slots = mock(SlotTrackingService.class);
        var intent = mock(IntentService.class);
        var sentiment = mock(SentimentService.class);
        var coordinator = new ChatHistoryContextCoordinator(memory, model, mock(ExecutorService.class), taxonomy);
        var retrieval = new ChatRetrievalCoordinator(rag, null, taxonomy, geo, detail);
        var service = new ChatApplicationService(model, memory, slots, intent, sentiment, null, coordinator,
                retrieval, new ChatPromptContextAssembler(new ObjectMapper(), coordinator, slots, sentiment));
        ReflectionTestUtils.setField(service, "streamTimeoutMs", 5000L);
        when(model.stream(anyList())).thenReturn(Flux.just("离线供应商回复"));
        when(intent.classify(anyString())).thenReturn(IntentService.Intent.QA);
        when(sentiment.analyze(anyString())).thenReturn(SentimentService.Sentiment.NEUTRAL);
        // Explicit successful envelope: this test measures routing, not semantic answerability.
        when(rag.retrieve(anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(Map.of(
                "ok",true,"retrievalStatus","RELEVANT","facts",List.of(Map.of("value","城市路由测试资料"))));
        when(geo.searchPoi(anyString(), anyString())).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "fixture", Instant.now()));
        when(memory.loadSummarySnapshot(anyString())).thenReturn(new ChatConversationMemoryPort.SummarySnapshot(null, 0, List.of(), Map.of()));
        when(memory.loadHistory("guest:a", 50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(4, List.of(
                user("我要去北京旅行"), assistant("也可以介绍西安"), user("更正目的地为杭州"), assistant("旧资料包含北京"))));
        when(memory.loadHistory("guest:b", 50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(1, List.of(user("我要去武汉旅行"))));
        when(memory.loadHistory("guest:empty", 50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(0, List.of()));

        ask(service, "a", "那里有哪些景点", "");
        verify(rag).retrieve("那里有哪些景点", "杭州市", Map.of(), false);
        verify(geo).searchPoi(eq("杭州市附近景点"), eq("杭州市"));
        ask(service, "b", "那里有哪些景点", "");
        verify(rag).retrieve("那里有哪些景点", "武汉市", Map.of(), false);
        ask(service, "a", "那里有哪些景点", "{\"city\":\"北京\"}");
        verify(rag).retrieve("那里有哪些景点", "北京市", Map.of(), false);
        ask(service, "a", "武汉有哪些景点", "{\"city\":\"北京\"}");
        verify(rag).retrieve("武汉有哪些景点", "武汉市", Map.of(), false);
        ask(service, "a", "“某个公园”附近有哪些景点", "");
        verify(detail).answerReference("某个公园", "杭州市");
        clearInvocations(rag, geo, detail);
        when(rag.retrieve(anyString(), eq(""), anyMap(), anyBoolean())).thenReturn(Map.of(
                "ok", true, "retrievalStatus", "UNKNOWN", "reasonCode", "DESTINATION_REQUIRED"));
        ask(service, "empty", "那里有哪些景点", "");
        ask(service, "a", "不要去北京", "");
        ask(service, "a", "北京还是杭州", "");
        verify(rag).retrieve("那里有哪些景点", "", Map.of(), false);
        verify(rag).retrieve("不要去北京", "", Map.of(), false);
        verify(rag).retrieve("北京还是杭州", "", Map.of(), false);
        verifyNoInteractions(geo, detail);
    }

    @Test
    void negativeCityAndAmbiguityDoNotReviveEarlierDestinationOrTaxonomyDefault() {
        var retrieval = new ChatRetrievalCoordinator(null, null, new JsonTaxonomyAdapter());
        var previous = List.of(user("我要去北京"));
        assertEquals("", retrieval.resolveCity("不要去北京", "", "", previous));
        assertEquals("", retrieval.resolveCity("北京还是杭州", "", "", previous));
        assertEquals("杭州市", retrieval.resolveCity("不是北京，改为杭州", "", "", previous));
        assertEquals("", retrieval.resolveCity("那里呢", "", "", List.of()));
    }

    @Test
    void namedPoiLookupPassesTheResolvedCityToGeoProvider() {
        var geo = mock(GeoRouteGatewayPort.class);
        when(geo.isConfigured()).thenReturn(true);
        when(geo.searchPoi("同名公园", "杭州市")).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "fixture", Instant.now()));
        new RuntimeAttractionDetailService(geo).answerReference("同名公园", "杭州市");
        verify(geo).searchPoi("同名公园", "杭州市");
    }

    private static void ask(ChatApplicationService service, String session, String message, String context) {
        var command = ChatStreamCommand.of(message, session, "normal", context.isBlank() ? "" : "owned-plan", "", "", "", "", "", context, null, null, null);
        var events = service.streamChat(command).collectList().block(Duration.ofSeconds(3));
        assertNotNull(events);
        assertFalse(events.stream().anyMatch(event -> event.event().equals("error")));
    }
    private static ChatConversationMemoryPort.StoredMessage user(String text) { return new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.USER, text); }
    private static ChatConversationMemoryPort.StoredMessage assistant(String text) { return new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.ASSISTANT, text); }
}

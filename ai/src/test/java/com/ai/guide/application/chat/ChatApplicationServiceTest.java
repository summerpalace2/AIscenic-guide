package com.ai.guide.application.chat;

import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.infrastructure.chat.memory.RedisChatMemory;
import com.ai.guide.domain.chat.service.SlotTrackingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.OutcomeStatus;
import com.ai.guide.domain.planner.model.PoiOutcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatApplicationServiceTest {

    @Test
    void structuredMemoryOverflowReturnsActionableErrorBeforeModelCall() {
        var memories = mock(com.ai.guide.domain.memory.service.TravelMemoryService.class);
        var preferences = mock(com.ai.guide.domain.preferences.service.PreferencesService.class);
        var profile = mock(com.ai.guide.domain.preferences.model.UserPreferences.class);
        when(profile.legacyMetadata()).thenReturn(java.util.Map.of("travelMemoryEnabled", true));
        when(preferences.get("u")).thenReturn(profile);
        var scenic = mock(com.ai.guide.domain.rag.service.ScenicDataImportService.class);
        when(scenic.queryKnowledge(anyString(), anyInt())).thenReturn("");
        when(memories.promptContext("u")).thenThrow(new com.ai.guide.domain.memory.service.TravelMemoryService.MemoryContextOverflowException());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService, "travelMemoryService", memories);
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService, "preferencesService", preferences);
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService, "scenicDataImportService", scenic);
        UserContext.set("u", "USER");
        var result = chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of("旅行建议", "s", "normal", "u"));
        assertEquals(422, result.getCode()); assertTrue(result.getMessage().contains("精简画像"));
        verify(chatClient, never()).prompt();
    }

    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;
    private RedisChatMemory redisChatMemory;
    private SlotTrackingService slotTrackingService;
    private IntentService intentService;
    private SentimentService sentimentService;
    private AnalyticsService analyticsService;
    private ExecutorService compressionExecutor;
    private ChatApplicationService chatApplicationService;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        when(chatClientBuilder.build()).thenReturn(chatClient);

        redisChatMemory = mock(RedisChatMemory.class);
        slotTrackingService = mock(SlotTrackingService.class);
        intentService = mock(IntentService.class);
        sentimentService = mock(SentimentService.class);
        analyticsService = mock(AnalyticsService.class);
        compressionExecutor = Executors.newSingleThreadExecutor();

        when(sentimentService.analyze(anyString())).thenReturn(SentimentService.Sentiment.POSITIVE);
        when(intentService.classify(anyString())).thenReturn(IntentService.Intent.QA);

        chatApplicationService = new ChatApplicationService(
                chatClientBuilder,
                redisChatMemory,
                slotTrackingService,
                intentService,
                sentimentService,
                analyticsService,
                compressionExecutor
        );
        when(redisChatMemory.historySnapshot(anyString(), eq(50))).thenReturn(new RedisChatMemory.HistorySnapshot(0, List.of()));
        when(redisChatMemory.summarySnapshot(anyString())).thenReturn(new RedisChatMemory.SummarySnapshot(null, 0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        compressionExecutor.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStreamChatFlowWithMetaTextAndDone() {
        var chatPromptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(chatClient.prompt()).thenReturn(chatPromptSpec);
        when(chatPromptSpec.messages(any(List.class))).thenReturn(chatPromptSpec);
        when(chatPromptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("欢迎来到", "美丽山城", "重庆！"));

        ChatStreamCommand command = ChatStreamCommand.of(
                "你好",
                "session-test-01",
                "normal",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "test-user",
                "tester",
                "USER"
        );

        Flux<ServerSentEvent<String>> flux = chatApplicationService.streamChat(command);

        List<ServerSentEvent<String>> events = flux.collectList().block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // 首事件必须是 meta
        ServerSentEvent<String> firstEvent = events.get(0);
        assertEquals("meta", firstEvent.event());
        assertNotNull(firstEvent.data());
        assertTrue(firstEvent.data().contains("\"status\":\"已连接\""));

        // 次事件是 sentiment (因为 mock 为 POSITIVE)
        assertEquals("sentiment", events.get(1).event());
        assertEquals("positive", events.get(1).data());

        // 中间事件是 text
        assertEquals("text", events.get(2).event());
        assertEquals("欢迎来到", events.get(2).data());
        assertEquals("text", events.get(3).event());
        assertEquals("美丽山城", events.get(3).data());
        assertEquals("text", events.get(4).event());
        assertEquals("重庆！", events.get(4).data());

        // 末尾事件是 done
        ServerSentEvent<String> lastEvent = events.get(events.size() - 1);
        assertEquals("done", lastEvent.event());
        assertEquals("complete", lastEvent.data());

        // 验证对话记忆异步保存
        verify(redisChatMemory, atLeastOnce()).addAsync(eq("test-user:session-test-01"), any(List.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStreamChatExtractsExplicitTargetLocationDynamically() {
        GeoRouteGatewayPort geoRouteGateway = mock(GeoRouteGatewayPort.class);
        chatApplicationService.setGeoRouteGateway(geoRouteGateway);

        when(geoRouteGateway.searchPoi(eq("合川 附近火锅"), eq("重庆市")))
                .thenReturn(new PoiOutcome(OutcomeStatus.SUCCESS, List.of(
                        new com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer.PoiCandidate(
                                "poi-hc-01", "合川老火锅", new com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer.Coordinate(106.276, 29.972), "合川区南津街", "餐饮服务", null, null, null, null, null
                        )
                ), "ok", java.time.Instant.now()));

        var chatPromptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(chatPromptSpec);
        when(chatPromptSpec.messages(any(List.class))).thenReturn(chatPromptSpec);
        when(chatPromptSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("合川有很多好吃的火锅。"));

        ChatStreamCommand command = ChatStreamCommand.of(
                "合川有哪些好吃的火锅",
                "session-test-02",
                "normal",
                "plan-01",
                "trip-01",
                "1",
                "1",
                "day1-hongyadong",
                "洪崖洞",
                "",
                "test-user",
                "tester",
                "USER"
        );

        Flux<ServerSentEvent<String>> flux = chatApplicationService.streamChat(command);
        List<ServerSentEvent<String>> events = flux.collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);

        // 核心断言：必须准确将非内置区县“合川”动态识别为 anchor，而非被卡片上的“洪崖洞”给幽灵劫持！
        verify(geoRouteGateway, atLeastOnce()).searchPoi(eq("合川 附近火锅"), eq("重庆市"));
    }
    @Test
    @SuppressWarnings("unchecked")
    void rollingSummaryIncludesUncoveredMiddleAndThenCompressesIt() throws Exception {
        var prompt=mock(ChatClient.ChatClientRequestSpec.class);var call=mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(prompt);when(prompt.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(prompt);when(prompt.call()).thenReturn(call);when(call.content()).thenReturn("新摘要");
        var history=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        for(int n=0;n<20;n++)history.add(new org.springframework.ai.chat.messages.UserMessage("新消息"+n));
        when(redisChatMemory.historySnapshot("s",50)).thenReturn(new RedisChatMemory.HistorySnapshot(100,history));
        when(redisChatMemory.summarySnapshot("s")).thenReturn(new RedisChatMemory.SummarySnapshot("此前摘要",80));
        var method=ChatApplicationService.class.getDeclaredMethod("getContextWithCompressedHistory",String.class,List.class,String.class);method.setAccessible(true);
        var result=(List<org.springframework.ai.chat.messages.Message>)method.invoke(chatApplicationService,"s",history,"u");
        assertEquals(21,result.size());assertTrue(result.stream().anyMatch(m->m.getContent().equals("新消息0")),"摘要提交前不能丢掉新消息中段");
        verify(redisChatMemory,timeout(3000)).saveSummarySnapshot(eq("s"),eq("新摘要"),eq(88L));
    }
    @Test
    @SuppressWarnings("unchecked")
    void recoveryCursorAdvancesOnlyTheContiguousBoundedBatchWhileModelUsesRecentWindow() throws Exception {
        var prompt=mock(ChatClient.ChatClientRequestSpec.class);var call=mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(prompt);when(prompt.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(prompt);when(prompt.call()).thenReturn(call);when(call.content()).thenReturn("滚动摘要");
        var tail=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();var prefix=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();
        for(int n=80;n<100;n++)prefix.add(new org.springframework.ai.chat.messages.UserMessage("消息"+n));
        for(int n=100;n<150;n++)tail.add(new org.springframework.ai.chat.messages.UserMessage("消息"+n));
        when(redisChatMemory.historySnapshot("s",50)).thenReturn(new RedisChatMemory.HistorySnapshot(150,tail));
        when(redisChatMemory.summarySnapshot("s")).thenReturn(new RedisChatMemory.SummarySnapshot("旧摘要",80));when(redisChatMemory.getRange("s",80,20)).thenReturn(prefix);
        var method=ChatApplicationService.class.getDeclaredMethod("getContextWithCompressedHistory",String.class,List.class,String.class);method.setAccessible(true);
        var result=(List<org.springframework.ai.chat.messages.Message>)method.invoke(chatApplicationService,"s",tail,"u");
        assertFalse(result.stream().anyMatch(m->m.getContent().equals("消息80")),"后台恢复队列不能无限灌入模型窗口");assertTrue(result.stream().anyMatch(m->m.getContent().equals("消息149")));
        assertTrue(result.stream().anyMatch(m->m.getContent().contains("历史窗口已限额")));
        verify(redisChatMemory,timeout(3000)).saveSummarySnapshot("s","滚动摘要",112L);
        verify(redisChatMemory,never()).messageCount(anyString());
    }
    @Test
    void emptySummaryDoesNotMarkMessagesCoveredAndCanRetry() throws Exception {
        var prompt=mock(ChatClient.ChatClientRequestSpec.class);var call=mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(prompt);when(prompt.messages(any(org.springframework.ai.chat.messages.Message[].class))).thenReturn(prompt);when(prompt.call()).thenReturn(call);when(call.content()).thenReturn(" ");
        var history=new java.util.ArrayList<org.springframework.ai.chat.messages.Message>();for(int n=0;n<20;n++)history.add(new org.springframework.ai.chat.messages.UserMessage("message"+n));
        when(redisChatMemory.historySnapshot("s",50)).thenReturn(new RedisChatMemory.HistorySnapshot(100,history));when(redisChatMemory.summarySnapshot("s")).thenReturn(new RedisChatMemory.SummarySnapshot("previous",80));
        var method=ChatApplicationService.class.getDeclaredMethod("getContextWithCompressedHistory",String.class,List.class,String.class);method.setAccessible(true);
        method.invoke(chatApplicationService,"s",history,"u");compressionExecutor.submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        verify(redisChatMemory,never()).saveSummarySnapshot(anyString(),anyString(),anyLong());
        when(call.content()).thenReturn("valid summary");method.invoke(chatApplicationService,"s",history,"u");compressionExecutor.submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        verify(redisChatMemory).saveSummarySnapshot("s","valid summary",88L);
    }
}

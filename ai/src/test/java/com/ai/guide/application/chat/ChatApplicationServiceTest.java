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
}

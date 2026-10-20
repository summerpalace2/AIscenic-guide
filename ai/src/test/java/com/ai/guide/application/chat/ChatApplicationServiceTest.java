package com.ai.guide.application.chat;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;

import com.ai.guide.application.chat.dto.ChatStreamCommand;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.domain.chat.service.SlotTrackingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ai.guide.application.chat.dto.ChatStreamEvent;
import reactor.core.publisher.Flux;

import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ChatApplicationServiceTest {
    @Test void partialContaminationIsDiscardedBeforeFixedAnswerReadsClaims() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","PARTIAL","claims",List.of(
                        java.util.Map.of("type","RULE","subject","样本馆","attribute","规则","decision","RELEVANT",
                                "value","LEGACY_CORPUS_CONTAMINATION 被隔离的正文"),
                        java.util.Map.of("type","ACTUAL","subject","样本馆","attribute","当前情况","decision","UNKNOWN"))));
        var coordinator=new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        var events=chatApplicationService.streamChat(ChatStreamCommand.of("北京规则和当前情况","partial-poison","normal",
                "","","","","","","","u","tester","USER")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("目前无法确认")));
        assertFalse(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("被隔离的正文")));
        verify(chatModelGateway,never()).stream(anyList());
    }
    @Test void longNonFactualEmotionUsesNormalGenerationInBothEndpointsWithoutPoiLookup() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","NON_FACTUAL"));
        var coordinator=spy(new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()));
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        when(chatModelGateway.stream(anyList())).thenReturn(Flux.just("听起来你很累，可以先休息一下。"));
        when(chatModelGateway.complete(anyList(),eq(com.ai.guide.domain.chat.model.ScenicResponse.class)))
                .thenReturn(new com.ai.guide.domain.chat.model.ScenicResponse(List.of(),"先缓一缓，不必急着赶行程。"));
        String message="今天走得好累啊，我不想再赶行程了，只希望有人听我说说心情";
        var events=chatApplicationService.streamChat(ChatStreamCommand.of(message,"emotion-stream","normal",
                "","","","","","","","u","tester","USER")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("听起来")));
        var result=chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of(
                message,"emotion-structured","normal","u"));
        assertTrue(result.getData().getSummary().contains("缓一缓"));
        verify(coordinator,never()).appendNearbyPoiContext(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        verify(coordinator,never()).appendTargetPlacePoiContext(anyString(),anyString(),anyString(),anyString(),anyString());
        var prompt=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatModelGateway).stream(prompt.capture());
        assertTrue(prompt.getValue().toString().contains("不要编造景点"));
        verify(rag,times(2)).retrieve(eq(message),eq(""),anyMap(),eq(false));
    }
    @Test void partialClaimsUseFixedAnswerInBothEndpointsAndCannotAcquirePoiFacts() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","PARTIAL","claims",List.of(
                        java.util.Map.of("type","RULE","subject","样本馆","attribute","规则","decision","RELEVANT","value","须预约"),
                        java.util.Map.of("type","ACTUAL","subject","样本馆","attribute","今天实际开放","decision","UNKNOWN"))));
        var coordinator=spy(new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()));
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        var events=chatApplicationService.streamChat(ChatStreamCommand.of("北京规则和今天实际情况","partial-stream","normal",
                "","","","","","","","u","tester","USER")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("须预约")));
        var result=chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of(
                "北京规则和今天实际情况","partial-structured","normal","u"));
        assertTrue(result.getData().getSummary().contains("今天实际开放：目前无法确认"));
        verify(chatModelGateway,never()).stream(anyList());verify(chatModelGateway,never()).complete(anyList(),any());
        verify(coordinator,never()).appendNearbyPoiContext(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void ambiguousFollowupCannotUseCoarseChitchatExemption() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","UNKNOWN","facts",List.of()));
        var coordinator=new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        when(intentService.classify(anyString())).thenAnswer(call->new IntentService().classify(call.getArgument(0)));
        when(conversationMemory.loadHistory(anyString(),eq(50))).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(1,List.of(
                new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.USER,"北京这个馆能带宠物吗"))));
        assertEquals(IntentService.Intent.CHITCHAT,new IntentService().classify("可以吗"));
        var events=chatApplicationService.streamChat(ChatStreamCommand.of("可以吗","followup-stream","normal",
                "","","","","","","","u","tester","USER")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("目前无法确认")));
        var result=chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of(
                "对吗","followup-structured","normal","u"));
        assertTrue(result.getData().getSummary().contains("目前无法确认"));
        verify(rag).retrieve(eq("可以吗"),eq("北京市"),anyMap(),eq(false));
        verify(rag).retrieve(eq("对吗"),eq("北京市"),anyMap(),eq(false));
        verify(chatModelGateway,never()).stream(anyList());verify(chatModelGateway,never()).complete(anyList(),any());
    }
    @Test void structuredEndpointAlsoFailsClosedWithoutModelGeneration() {
        var coordinator = new ChatRetrievalCoordinator(null,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        var result=chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of(
                "北京这个馆今晚开门吗","structured-unknown","normal","u"));
        assertNotNull(result.getData());assertTrue(result.getData().getSummary().contains("目前无法确认"));
        assertTrue(result.getData().getItems().isEmpty());
        verify(chatModelGateway,never()).complete(anyList(),any());
    }
    @Test void unavailableRetrievalCannotBeWashedByPoiOrGeneration() {
        for (String scenario : List.of("missing-city", "unavailable", "exception")) {
            var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
            if (scenario.equals("exception")) when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean()))
                    .thenThrow(new IllegalStateException("offline"));
            else when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean()))
                    .thenReturn(scenario.equals("missing-city")
                            ? java.util.Map.of("ok",true,"retrievalStatus","UNKNOWN","reasonCode","DESTINATION_REQUIRED")
                            : java.util.Map.of("ok",false,"retrievalStatus","UNAVAILABLE"));
            var coordinator=spy(new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()));
            doReturn("AMAP content that must not override UNKNOWN").when(coordinator)
                    .appendNearbyPoiContext(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
            org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
            String query=scenario.equals("missing-city")?"这个馆能带宠物吗":"北京这个馆能带宠物吗";
            var command=ChatStreamCommand.of(query,"unavailable-"+scenario,"normal","","","","","","","","u","tester","USER");
            var events=chatApplicationService.streamChat(command).collectList().block(Duration.ofSeconds(5));
            assertNotNull(events);
            assertTrue(events.stream().anyMatch(e->e.event().equals("meta")&&e.data().contains("待核实")));
            assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("无法确认")));
            if(scenario.equals("missing-city")) assertTrue(events.stream().anyMatch(e->e.data().contains("补充目的地城市")));
            verify(coordinator,never()).appendNearbyPoiContext(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
        }
        verify(chatModelGateway,never()).stream(anyList());
        verify(conversationMemory,atLeast(6)).appendConfirmed(anyString(),anyList(),nullable(String.class));
    }
    @Test
    void supportedContaminationDowngradesBothMetadataAndGeneration() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","RELEVANT","facts",List.of(java.util.Map.of("value","LEGACY_CORPUS_CONTAMINATION"))));
        var coordinator=new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        var command=ChatStreamCommand.of("北京开放时间","supported-isolated","normal","","","","","","","","u","tester","USER");
        var events=chatApplicationService.streamChat(command).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(e->e.event().equals("meta")&&e.data().contains("待核实")));
        assertTrue(events.stream().anyMatch(e->e.event().equals("text")&&e.data().contains("目前无法确认")));
        verify(chatModelGateway,never()).stream(anyList());
    }
    @Test
    void unknownConclusionUsesBackgroundWithoutAnotherGenerationCall() {
        var rag=mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","UNKNOWN","facts",List.of(),"backgroundFacts",List.of(java.util.Map.of("value","常规20点亮灯"))));
        var coordinator=new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",coordinator);
        var command=ChatStreamCommand.of("北京今晚有烟花吗","unknown-background","normal","","","","","","","","u","tester","USER");
        var events=chatApplicationService.streamChat(command).collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event->event.event().equals("text")&&event.data().contains("目前无法确认")));
        assertFalse(events.stream().anyMatch(event->event.event().equals("text")&&event.data().contains("常规20点亮灯")));
        verify(chatModelGateway,never()).stream(anyList());
    }

    @Test
    void structuredMemoryOverflowReturnsActionableErrorBeforeModelCall() {
        var memories = mock(com.ai.guide.domain.memory.service.TravelMemoryService.class);
        var preferences = mock(com.ai.guide.domain.preferences.service.PreferencesService.class);
        var profile = mock(com.ai.guide.domain.preferences.model.UserPreferences.class);
        when(profile.legacyMetadata()).thenReturn(java.util.Map.of("travelMemoryEnabled", true));
        when(preferences.get("u")).thenReturn(profile);
        var scenic = mock(ScenicKnowledgePort.class);
        when(scenic.queryKnowledge(anyString(), anyInt())).thenReturn("");
        when(memories.promptContext("u")).thenThrow(new com.ai.guide.domain.memory.service.TravelMemoryService.MemoryContextOverflowException());
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService, "travelMemoryService", memories);
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService, "preferencesService", preferences);
        chatRetrievalCoordinator.setScenicDataImportService(scenic);
        var rag = mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","RELEVANT","facts",List.of(java.util.Map.of("value","fixture fact"))));
        org.springframework.test.util.ReflectionTestUtils.setField(chatApplicationService,"chatRetrievalCoordinator",
                new ChatRetrievalCoordinator(rag,null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()));
        UserContext.set("u", "USER");
        var result = chatApplicationService.chatStructured(com.ai.guide.application.chat.dto.ChatStructuredCommand.of("北京旅行建议", "s", "normal", "u"));
        assertEquals(422, result.getCode()); assertTrue(result.getMessage().contains("精简画像"));
        verifyNoInteractions(chatModelGateway);
    }

    private LanguageModelPort chatModelGateway;
    private ChatConversationMemoryPort conversationMemory;
    private SlotTrackingService slotTrackingService;
    private IntentService intentService;
    private SentimentService sentimentService;
    private AnalyticsService analyticsService;
    private ExecutorService compressionExecutor;
    private ChatRetrievalCoordinator chatRetrievalCoordinator;
    private ChatApplicationService chatApplicationService;

    @BeforeEach
    void setUp() {
        chatModelGateway = mock(LanguageModelPort.class);

        conversationMemory = mock(ChatConversationMemoryPort.class);
        slotTrackingService = mock(SlotTrackingService.class);
        intentService = mock(IntentService.class);
        sentimentService = mock(SentimentService.class);
        analyticsService = mock(AnalyticsService.class);
        compressionExecutor = Executors.newSingleThreadExecutor();
        var taxonomy = mock(com.ai.guide.domain.taxonomy.gateway.TaxonomyPort.class);
        when(taxonomy.getCityAliases()).thenReturn(java.util.Map.of("重庆", "重庆", "合川", "重庆"));
        when(taxonomy.getHints("defaultDestination")).thenReturn(List.of("重庆"));
        when(taxonomy.getHints("chatFoodIntent")).thenReturn(List.of("火锅", "吃"));
        when(taxonomy.getHints("chatHotpotIntent")).thenReturn(List.of("火锅"));
        when(taxonomy.getHints("chatNearbyIntent")).thenReturn(List.of("附近"));
        when(taxonomy.getHints("chatNearbySearchPrefix")).thenReturn(List.of("附近"));
        when(taxonomy.getHints("chatNearbyDiningSuffix")).thenReturn(List.of("附近餐饮"));
        when(taxonomy.getHints("chatNearbyAttractionSuffix")).thenReturn(List.of("附近景点"));
        when(taxonomy.getFlavorAliases()).thenReturn(List.of(
                new com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias(
                        "火锅", List.of("火锅"), "火锅", List.of("火锅"))));
        chatRetrievalCoordinator = new ChatRetrievalCoordinator(null, null, taxonomy, null, null);

        when(sentimentService.analyze(anyString())).thenReturn(SentimentService.Sentiment.POSITIVE);
        when(intentService.classify(anyString())).thenReturn(IntentService.Intent.QA);

        var historyCoordinator = new ChatHistoryContextCoordinator(conversationMemory, chatModelGateway, compressionExecutor, taxonomy);
        chatApplicationService = new ChatApplicationService(
                chatModelGateway,
                conversationMemory,
                slotTrackingService,
                intentService,
                sentimentService,
                analyticsService,
                historyCoordinator,
                chatRetrievalCoordinator,
                new ChatPromptContextAssembler(new com.fasterxml.jackson.databind.ObjectMapper(), historyCoordinator, slotTrackingService, sentimentService)
        );
        when(conversationMemory.loadHistory(anyString(), eq(50))).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(0, List.of()));
        when(conversationMemory.loadSummarySnapshot(anyString())).thenReturn(new ChatConversationMemoryPort.SummarySnapshot(null, 0, List.of(), java.util.Map.of()));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        compressionExecutor.shutdown();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStreamChatFlowWithMetaTextAndDone() {
        when(chatModelGateway.stream(anyList())).thenReturn(Flux.just("欢迎来到", "美丽山城", "重庆！"));

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

        Flux<ChatStreamEvent> flux = chatApplicationService.streamChat(command);

        List<ChatStreamEvent> events = flux.collectList().block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertFalse(events.isEmpty());

        // 首事件必须是 meta
        ChatStreamEvent firstEvent = events.get(0);
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
        ChatStreamEvent lastEvent = events.get(events.size() - 1);
        assertEquals("done", lastEvent.event());
        assertEquals("complete", lastEvent.data());

        // 验证对话记忆异步保存
        verify(conversationMemory, atLeastOnce()).appendConfirmed(eq("test-user:session-test-01"), anyList(),org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStreamChatExtractsExplicitTargetLocationDynamically() {
        var rag = mock(com.ai.guide.domain.rag.service.RagRetrievalService.class);
        when(rag.retrieve(anyString(),anyString(),anyMap(),anyBoolean())).thenReturn(java.util.Map.of(
                "ok",true,"retrievalStatus","RELEVANT","facts",List.of(java.util.Map.of("value","常规餐饮来源"))));
        org.springframework.test.util.ReflectionTestUtils.setField(chatRetrievalCoordinator,"ragRetrievalService",rag);
        GeoRouteGatewayPort geoRouteGateway = mock(GeoRouteGatewayPort.class);
        chatRetrievalCoordinator.setGeoRouteGateway(geoRouteGateway);

        when(geoRouteGateway.searchPoi(eq("合川附近火锅"), eq("重庆市")))
                .thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(
                        new com.ai.guide.domain.geo.model.GeoPoiCandidate(
                                "poi-hc-01", "合川老火锅", new com.ai.guide.domain.geo.model.GeoCoordinate(106.276, 29.972), "合川区南津街", "餐饮服务", null, null, null, null, null
                        )
                ), "ok", java.time.Instant.now()));

        when(chatModelGateway.stream(anyList())).thenReturn(Flux.just("合川有很多好吃的火锅。"));

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

        Flux<ChatStreamEvent> flux = chatApplicationService.streamChat(command);
        List<ChatStreamEvent> events = flux.collectList().block(Duration.ofSeconds(5));
        assertNotNull(events);

        // 核心断言：必须准确将非内置区县“合川”动态识别为 anchor，而非被卡片上的“洪崖洞”给幽灵劫持！
        verify(geoRouteGateway, atLeastOnce()).searchPoi(eq("合川附近火锅"), eq("重庆市"));
    }
    @Test
    @SuppressWarnings("unchecked")
    void rollingSummaryIncludesUncoveredMiddleAndThenCompressesIt() throws Exception {
        when(chatModelGateway.completeText(anyList())).thenReturn("新摘要");
        var history=new java.util.ArrayList<com.ai.guide.domain.ai.model.LanguageModelMessage>();
        for(int n=0;n<20;n++)history.add(new com.ai.guide.domain.ai.model.LanguageModelMessage(com.ai.guide.domain.ai.model.LanguageModelMessage.Role.USER,"新消息"+n));
        when(conversationMemory.loadHistory("s",50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(100,stored(history)));
        when(conversationMemory.loadSummarySnapshot("s")).thenReturn(new ChatConversationMemoryPort.SummarySnapshot("此前摘要",80,List.of(),java.util.Map.of()));
        var result=((ChatHistoryContextCoordinator)org.springframework.test.util.ReflectionTestUtils.getField(chatApplicationService,"historyContextCoordinator")).boundedContext("s");
        assertEquals(21,result.size());assertTrue(result.stream().anyMatch(m->m.content().equals("新消息0")),"摘要提交前不能丢掉新消息中段");
        verify(conversationMemory,timeout(3000)).saveSummarySnapshot(eq("s"),eq("新摘要"),eq(88L),org.mockito.ArgumentMatchers.nullable(String.class));
    }
    @Test
    @SuppressWarnings("unchecked")
    void recoveryCursorAdvancesOnlyTheContiguousBoundedBatchWhileModelUsesRecentWindow() throws Exception {
        when(chatModelGateway.completeText(anyList())).thenReturn("滚动摘要");
        var tail=new java.util.ArrayList<com.ai.guide.domain.ai.model.LanguageModelMessage>();var prefix=new java.util.ArrayList<com.ai.guide.domain.ai.model.LanguageModelMessage>();
        for(int n=80;n<100;n++)prefix.add(new com.ai.guide.domain.ai.model.LanguageModelMessage(com.ai.guide.domain.ai.model.LanguageModelMessage.Role.USER,"消息"+n));
        for(int n=100;n<150;n++)tail.add(new com.ai.guide.domain.ai.model.LanguageModelMessage(com.ai.guide.domain.ai.model.LanguageModelMessage.Role.USER,"消息"+n));
        when(conversationMemory.loadHistory("s",50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(150,stored(tail)));
        when(conversationMemory.loadSummarySnapshot("s")).thenReturn(new ChatConversationMemoryPort.SummarySnapshot("旧摘要",80,List.of(),java.util.Map.of()));when(conversationMemory.readRange("s",80,20)).thenReturn(stored(prefix));
        var result=((ChatHistoryContextCoordinator)org.springframework.test.util.ReflectionTestUtils.getField(chatApplicationService,"historyContextCoordinator")).boundedContext("s");
        assertFalse(result.stream().anyMatch(m->m.content().equals("消息80")),"后台恢复队列不能无限灌入模型窗口");assertTrue(result.stream().anyMatch(m->m.content().equals("消息149")));
        assertTrue(result.stream().anyMatch(m->m.content().contains("历史窗口已限额")));
        verify(conversationMemory,timeout(3000)).saveSummarySnapshot("s","滚动摘要",112L,null);
    }
    @Test
    void emptySummaryDoesNotMarkMessagesCoveredAndCanRetry() throws Exception {
        when(chatModelGateway.completeText(anyList())).thenReturn(" ", "valid summary");
        var history=new java.util.ArrayList<com.ai.guide.domain.ai.model.LanguageModelMessage>();for(int n=0;n<20;n++)history.add(new com.ai.guide.domain.ai.model.LanguageModelMessage(com.ai.guide.domain.ai.model.LanguageModelMessage.Role.USER,"message"+n));
        when(conversationMemory.loadHistory("s",50)).thenReturn(new ChatConversationMemoryPort.HistorySnapshot(100,stored(history)));when(conversationMemory.loadSummarySnapshot("s")).thenReturn(new ChatConversationMemoryPort.SummarySnapshot("previous",80,List.of(),java.util.Map.of()));
        var coordinator=(ChatHistoryContextCoordinator)org.springframework.test.util.ReflectionTestUtils.getField(chatApplicationService,"historyContextCoordinator");
        coordinator.boundedContext("s");compressionExecutor.submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        verify(conversationMemory,never()).saveSummarySnapshot(anyString(),anyString(),anyLong(),org.mockito.ArgumentMatchers.nullable(String.class));
        coordinator.boundedContext("s");compressionExecutor.submit(()->{}).get(3,java.util.concurrent.TimeUnit.SECONDS);
        verify(conversationMemory).saveSummarySnapshot("s","valid summary",88L,null);
    }

    private static List<ChatConversationMemoryPort.StoredMessage> stored(List<com.ai.guide.domain.ai.model.LanguageModelMessage> messages) {
        return messages.stream().map(message -> new ChatConversationMemoryPort.StoredMessage(
                ChatConversationMemoryPort.Role.valueOf(message.role().name()), message.content())).toList();
    }
}

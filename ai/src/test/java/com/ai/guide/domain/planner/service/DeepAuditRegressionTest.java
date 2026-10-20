package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.chat.service.ChatHistoryService;
import com.ai.guide.domain.chat.model.ChatHistoryNotFoundException;
import com.ai.guide.domain.chat.gateway.ChatHistoryStorePort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.infrastructure.memory.repository.JdbcMemoryRepository;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepAuditRegressionTest {
    @TempDir Path directory;
    @AfterEach void clearIdentity() { UserContext.clear(); }
    JdbcTemplate database() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("test.db"));
        var properties = new Properties(); properties.setProperty("busy_timeout", "5000"); properties.setProperty("journal_mode", "WAL");
        ds.setConnectionProperties(properties);
        var jdbc = new JdbcTemplate(ds);
        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        return jdbc;
    }
    @Test void foreignHistoryCannotBeReadOrDeleted() {
        ChatHistoryStorePort store = mock(ChatHistoryStorePort.class);
        when(store.ownsSession("a", "a:b:secret")).thenReturn(false);
        var history = new ChatHistoryService(store, new ObjectMapper()); UserContext.set("a", "USER");
        assertThrows(ChatHistoryNotFoundException.class, () -> history.getMessages("b:secret"));
        assertThrows(ChatHistoryNotFoundException.class, () -> history.deleteSession("b:secret"));
        verify(store, never()).getMessages(anyString()); verify(store, never()).deleteSession(anyString(), anyString());
    }
    @Test void ownHistorySupportsRawAndListedScopedId() {
        ChatHistoryStorePort store = mock(ChatHistoryStorePort.class);
        when(store.ownsSession("a", "a:chat")).thenReturn(true);
        when(store.getMessages("a:chat")).thenReturn(List.of("{\"type\":\"user\",\"content\":\"mine\"}"));
        var history = new ChatHistoryService(store, new ObjectMapper()); UserContext.set("a", "USER");
        assertEquals(1,history.getMessages("chat").size()); assertEquals(1,history.getMessages("a:chat").size());
        history.deleteSession("a:chat"); verify(store).deleteSession("a", "a:chat");
    }
    @Test void capabilityClaimIsAtomicAndRevokesAnonymousAccess() throws Exception {
        var repository = new PlannerSessionRepository(database(),new ObjectMapper());
        var draft = repository.create("ANONYMOUS","guest", "prompt",Map.of(),AppliedPreferencesSnapshot.empty(),Map.of("days",List.of()),"key","fp");
        assertNull(repository.claimAnonymous(draft.session().sessionId(),"wrong","attacker"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            Callable<PlannerSessionRepository.StoredSession> a = () -> {gate.await();return repository.claimAnonymous(draft.session().sessionId(),draft.accessToken(),"a");};
            Callable<PlannerSessionRepository.StoredSession> b = () -> {gate.await();return repository.claimAnonymous(draft.session().sessionId(),draft.accessToken(),"b");};
            var first = executor.submit(a); var second = executor.submit(b); gate.countDown();
            var winners = Arrays.asList(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,winners.stream().filter(Objects::nonNull).count());
            var winner = winners.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertNotNull(repository.claimAnonymous(winner.sessionId(),draft.accessToken(),winner.ownerId()));
            assertNull(repository.findAnonymousByAccessToken(draft.accessToken()));
            assertEquals(1,repository.revisionCount(winner.sessionId()));
        } finally {executor.shutdownNow();}
    }
    @Test void dateFilteringRejectsMondayClosureButAllowsTuesday() {
        var museum=Attraction.builder().id("m").bestTime("09:00-17:00，周一闭馆").build();
        var ranking=new CandidateRankingEngine(new RouteCostProvider(null));
        assertFalse(ranking.isOpeningCompatible(museum,"12:00","2026-10-05",null));
        assertTrue(ranking.isOpeningCompatible(museum,"12:00","2026-10-06",null));
    }
    @Test void compoundRequestIsClarifiedWithoutPollutingEntityOrDiscardingClauses() {
        var attractions=List.of(
                Attraction.builder().id("cq-jiefangbei").name("解放碑").amapQuery(Map.of("matches",List.of("解放碑"))).build(),
                Attraction.builder().id("cq-museum").name("三峡博物馆").amapQuery(Map.of("matches",List.of("三峡博物馆"))).build());
        var catalog=new com.ai.guide.domain.attraction.service.AttractionService(new com.ai.guide.domain.attraction.gateway.AttractionCatalogPort() {
            @Override public List<Attraction> find(String district,String category){return attractions;}
            @Override public Attraction findById(String id){return attractions.stream().filter(a->a.getId().equals(id)).findFirst().orElse(null);}
        });
        var classifier=new ConversationIntentClassifier(catalog,new com.ai.guide.domain.attraction.service.AttractionAliasMatcher(catalog));
        var intent=classifier.classify("把第一天的解放碑换成三峡博物馆，顺便下午加个茶歇，晚上如果下雨就都换成室内",new PlanPageContext());
        assertTrue(intent.isRequiresClarification()); assertEquals("CLARIFICATION",intent.getOperation());
        assertNull(intent.getReplacementPlaceName());
        assertTrue(intent.getClarificationQuestion().contains("三峡博物馆"));
        assertTrue(intent.getClarificationQuestion().contains("茶歇"));
        assertTrue(intent.getClarificationQuestion().contains("下雨"));
        assertFalse(classifier.classify("把解放碑换成三峡博物馆",new PlanPageContext()).isRequiresClarification());
    }
    @Test void unknownRoadConnectivityNeverAssertsWalking() {
        var a=Attraction.builder().id("a").location("106.577,29.556").build();
        var b=Attraction.builder().id("b").location("106.582,29.556").build();
        var cost=new RouteCostProvider(null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()).estimateFromCoordinates(a,b,"walking");
        assertNotEquals("WALKING",cost.transportMode()); assertEquals(RouteCost.RouteDataStatus.ESTIMATED,cost.status());
        assertTrue(cost.reason().contains("待核验")); assertTrue(cost.duration().toMinutes()>=15);
    }
    @Test void coordinateEstimateRejectsDifferentExplicitCities() {
        var a=Attraction.builder().id("a").location("116.397,39.908").amapQuery(Map.of("city", "北京市")).build();
        var b=Attraction.builder().id("b").location("120.155,30.274").amapQuery(Map.of("city", "杭州市")).build();
        var cost=new RouteCostProvider(null,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()).estimateFromCoordinates(a,b,"driving");
        assertEquals(RouteCost.RouteDataStatus.UNAVAILABLE,cost.status());
        assertTrue(cost.reason().contains("跨城市"));
    }
    @Test void promptBudgetPreservesRestrictionsAndLeavesStoredProfileComplete() throws Exception {
        var jdbc=database();var service=new TravelMemoryService(new JdbcMemoryRepository(jdbc));
        for(int i=0;i<120;i++) jdbc.update("INSERT INTO travel_memory(id,user_id,category,content,source_type,source_ref,status,confidence,created_at,updated_at) VALUES(?,?,'CUSTOM',?,'USER','','CONFIRMED',1,0,0)","m"+i,"u","偏好"+i+"旅途说明".repeat(50));
        jdbc.update("INSERT INTO travel_memory(id,user_id,category,content,source_type,source_ref,status,confidence,created_at,updated_at) VALUES('allergy','u','DIET','对花生严重过敏','USER','','CONFIRMED',1,0,0)");
        jdbc.update("INSERT INTO travel_memory(id,user_id,category,content,source_type,source_ref,status,confidence,created_at,updated_at) VALUES('restriction','u','DIET','饮食清淡不辣','USER','','CONFIRMED',1,0,0)");
        String prompt=service.promptContext("u");
        assertTrue(prompt.length()<=6000); assertTrue(prompt.contains("对花生严重过敏")); assertTrue(prompt.contains("未注入"));
        assertTrue(prompt.contains("饮食清淡不辣"));
        assertTrue(service.synthesizePrependPrompt("u",false).length()>20000);
        assertEquals(122,jdbc.queryForObject("SELECT COUNT(*) FROM travel_memory",Integer.class));
    }
    @Test void safetyOverflowFailsBeforeModelInsteadOfTruncating() throws Exception {
        var jdbc=database();
        for(int i=0;i<40;i++) jdbc.update("INSERT INTO travel_memory(id,user_id,category,content,source_type,source_ref,status,confidence,created_at,updated_at) VALUES(?,?,'ACCESSIBILITY',?,'USER','','CONFIRMED',1,0,0)","m"+i,"u","安全限制"+i+"限制说明".repeat(50));
        assertThrows(IllegalArgumentException.class,()->new TravelMemoryService(new JdbcMemoryRepository(jdbc)).promptContext("u"));
    }
    @Test void localRetrievalFindsOldRelevantDocumentsAndBoundsExcerpts() throws Exception {
        var jdbc=database();
        for(int i=0;i<210;i++)jdbc.update("INSERT INTO kb_document(id,title,content,tags,updated_at,scope) VALUES(?,?,?,'',?,'GLOBAL')","irrelevant"+i,"无关信息","普通内容",1000+i);
        jdbc.update("INSERT INTO kb_document(id,title,content,tags,updated_at,scope) VALUES('relevant','历史资料',?,'',0,'GLOBAL')","内容".repeat(3000)+"蒙民伟理科馆");
        var rows=com.ai.guide.testing.KnowledgeTestPorts.service(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), com.ai.guide.testing.KnowledgeTestPorts.noIndex()).searchLocalKnowledge("蒙民伟理科馆",5);
        assertEquals(1,rows.size()); assertEquals("relevant",rows.get(0).get("id")); assertTrue(rows.get(0).get("content").toString().length()<=4096);
        assertTrue(rows.get(0).get("content").toString().contains("蒙民伟理科馆"), "正文尾部的匹配不能被预算截掉");
    }
}

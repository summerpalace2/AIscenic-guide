package com.ai.guide.domain.planner.service;

import com.ai.guide.common.config.KnowledgeDbConfig;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.chat.service.ChatHistoryService;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.core.*;
import org.springframework.web.server.ResponseStatusException;
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
        var config = new KnowledgeDbConfig();
        for (var entry : Map.of("configuredType","sqlite","configuredUrl","jdbc:sqlite:"+directory.resolve("test.db")).entrySet()) {
            var field=KnowledgeDbConfig.class.getDeclaredField(entry.getKey());field.setAccessible(true);field.set(config,entry.getValue());
        }
        config.knowledgeDataSource();
        var create = KnowledgeDbConfig.class.getDeclaredMethod("createSchema", JdbcTemplate.class, boolean.class);
        create.setAccessible(true); create.invoke(config, jdbc, true);
        return jdbc;
    }
    @Test @SuppressWarnings("unchecked") void foreignHistoryCannotBeReadOrDeleted() {
        RedisTemplate<String,String> redis = mock(RedisTemplate.class);
        ZSetOperations<String,String> sets = mock(ZSetOperations.class); when(redis.opsForZSet()).thenReturn(sets);
        when(sets.score(anyString(), anyString())).thenReturn(null);
        var history = new ChatHistoryService(redis, new ObjectMapper()); UserContext.set("a", "USER");
        assertThrows(ResponseStatusException.class, () -> history.getMessages("b:secret"));
        assertThrows(ResponseStatusException.class, () -> history.deleteSession("b:secret"));
        verify(redis, never()).opsForList(); verify(redis, never()).delete(anyString());
    }
    @Test @SuppressWarnings("unchecked") void ownHistorySupportsRawAndListedScopedId() {
        RedisTemplate<String,String> redis = mock(RedisTemplate.class);
        ZSetOperations<String,String> sets = mock(ZSetOperations.class);
        ListOperations<String,String> lists = mock(ListOperations.class);
        when(redis.opsForZSet()).thenReturn(sets); when(redis.opsForList()).thenReturn(lists);
        when(sets.score("chat:sessions:a", "a:chat")).thenReturn(1d);
        when(lists.range("chat:history:a:chat",0,-1)).thenReturn(List.of("{\"type\":\"user\",\"content\":\"mine\"}"));
        var history = new ChatHistoryService(redis, new ObjectMapper()); UserContext.set("a", "USER");
        assertEquals(1,history.getMessages("chat").size()); assertEquals(1,history.getMessages("a:chat").size());
        history.deleteSession("a:chat"); verify(redis).delete("chat:history:a:chat");
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
        var classifier=new ConversationIntentClassifier();
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
        var cost=new RouteCostProvider(null).estimateFromCoordinates(a,b,"walking");
        assertNotEquals("WALKING",cost.transportMode()); assertEquals(RouteCost.RouteDataStatus.ESTIMATED,cost.status());
        assertTrue(cost.reason().contains("待核验")); assertTrue(cost.duration().toMinutes()>=15);
    }
    @Test void promptBudgetPreservesRestrictionsAndLeavesStoredProfileComplete() throws Exception {
        var jdbc=database();var service=new TravelMemoryService(jdbc);
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
        assertThrows(IllegalArgumentException.class,()->new TravelMemoryService(jdbc).promptContext("u"));
    }
    @Test void localRetrievalFindsOldRelevantDocumentsAndBoundsExcerpts() throws Exception {
        var jdbc=database();
        for(int i=0;i<210;i++)jdbc.update("INSERT INTO kb_document(id,title,content,tags,updated_at) VALUES(?,?,?,'',?)","irrelevant"+i,"无关信息","普通内容",1000+i);
        jdbc.update("INSERT INTO kb_document(id,title,content,tags,updated_at) VALUES('relevant','历史资料',?,'',0)","内容".repeat(3000)+"蒙民伟理科馆");
        var rows=new KnowledgeDocumentService(jdbc,null).searchLocalKnowledge("蒙民伟理科馆",5);
        assertEquals(1,rows.size()); assertEquals("relevant",rows.get(0).get("id")); assertTrue(rows.get(0).get("content").toString().length()<=4096);
        assertTrue(rows.get(0).get("content").toString().contains("蒙民伟理科馆"), "正文尾部的匹配不能被预算截掉");
    }
}

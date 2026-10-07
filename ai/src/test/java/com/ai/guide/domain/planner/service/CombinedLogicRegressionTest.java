package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.memory.service.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.infrastructure.preferences.repository.PreferencesRepository;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.ai.guide.infrastructure.planner.amap.*;
import com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated SQL and provider fixtures; no production data or paid model calls. */
class CombinedLogicRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void requiredTargetsSurviveServerCacheSerializationWithoutBeingInBrowserPayload() throws Exception {
        var proposal = PlanAdjustmentProposal.builder().requiredStopIdsByOption(Map.of("option-2",List.of("new-stop"))).build();
        var restored = mapper.readValue(mapper.writeValueAsString(proposal), PlanAdjustmentProposal.class);
        assertEquals(List.of("new-stop"),restored.getRequiredStopIdsByOption().get("option-2"));
        assertFalse(restored.asMap().containsKey("requiredStopIdsByOption"));
    }
    @Test void incompleteFullRouteDoesNotDisableItsValidSingleStopAlternative() throws Exception {
        var service = new PlanAdjustmentService(null,null,null,new RouteCostProvider(null),new com.ai.guide.domain.planner.engine.PlanVerifier(),null,new ProposalStore(),new ConversationIntentClassifier(),mapper);
        var status = PlanAdjustmentService.class.getDeclaredMethod("optionStatus",Map.class,Map.class,PlanAdjustmentIntent.class,TravelConstraints.class,List.class,String.class,List.class);status.setAccessible(true);
        var before = Map.<String,Object>of("days",List.of(Map.of("day",1,"stops",List.of())));
        var after = Map.<String,Object>of("days",List.of(Map.of("day",1,"stops",List.of(Map.of("id","added","name","first","time","18:00","duration","90分钟")))));
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1).build();
        var reasons = List.of("REQUESTED_STOP_NOT_RETAINED");
        assertEquals(false,((Map<?,?>)status.invoke(service,before,after,intent,new TravelConstraints(),reasons,"option-1",List.of("added","missing"))).get("canApply"));
        assertEquals(true,((Map<?,?>)status.invoke(service,before,after,intent,new TravelConstraints(),reasons,"option-2",List.of("added"))).get("canApply"));
        assertEquals(false,((Map<?,?>)status.invoke(service,before,after,intent,new TravelConstraints(),reasons,"option-2",List.of("missing"))).get("canApply"));
        var overlapping = Map.<String,Object>of("days",List.of(Map.of("day",1,"stops",List.of(
            Map.of("id","added","name","first","time","18:00","duration","90分钟"),
            Map.of("id","second","name","second","time","19:00","duration","90分钟")))));
        assertEquals(false,((Map<?,?>)status.invoke(service,before,overlapping,intent,new TravelConstraints(),List.of(),"option-2",List.of("added"))).get("canApply"));
    }
    @Test void correctedTimeReplacesInvalidClockAndSurvivesTheNextAnswer() {
        var store = new PendingIntentContinuations(); var context = PlanPageContext.builder().activeDay(1).build();
        var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REPLAN_DAY")
                .requiresClarification(true).missingFields(List.of("TIME_RANGE", "REPLAN_PREFERENCE"))
                .rawMessage("将18:00到17:00的行程优化").preferences(List.of("低步行")).dayNumber(1).build();
        store.remember("s",1,original);
        var corrected = store.resume("s",1,null,"19:00至21:00",context);
        assertEquals(1140, corrected.getTargetStartMinute()); assertEquals(1260,corrected.getTargetEndMinute());
        assertEquals(List.of("REPLAN_PREFERENCE"),corrected.getMissingFields()); assertTrue(corrected.isTimeScopeResolved());
        store.remember("s",1,corrected);
        var complete = store.resume("s",1,null,"少走路",context);
        AdjustmentTimeScopeResolver.resolve(complete,new TaxonomyProvider());
        assertFalse(complete.isRequiresClarification()); assertEquals(1140,complete.getTargetStartMinute());
        assertTrue(complete.isTimeScopeResolved());
        assertFalse(mapper.convertValue(complete,Map.class).containsKey("timeScopeResolved"));
    }
    @Test void targetAnswerKeepsOriginalReplacementAndAmbiguousAnswersKeepTheTask() {
        var store = new PendingIntentContinuations();var context = PlanPageContext.builder().activeDay(1).build();
        var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REPLACE_STOP")
                .requiresClarification(true).missingFields(List.of("TARGET_STOP")).replacementPlaceName("三峡博物馆")
                .replacementPlaceId("replacement-id").requestedVenueName("三峡博物馆").rawMessage("换成三峡博物馆").dayNumber(1).build();
        store.remember("s",1,original);var result = store.resume("s",1,null,"解放碑",context);
        assertEquals("三峡博物馆",result.getReplacementPlaceName()); assertEquals("replacement-id",result.getReplacementPlaceId());
        assertEquals("解放碑", result.getTargetStopReference());assertFalse(result.isRequiresClarification());
        var missingDay = original.toBuilder().missingFields(List.of("DAY_NUMBER")).build();store.remember("s",1,missingDay);
        assertSame(missingDay,store.resume("s",1,null,"随便",context));assertNotNull(store.pending("s"));
    }
    @Test void conflictOnlyRemovesTheSpiceClauseAndLongOriginalKeepsAllergy() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            jdbc.execute("CREATE TABLE travel_memory(id TEXT PRIMARY KEY,user_id TEXT,category TEXT,content TEXT,source_type TEXT,source_ref TEXT,status TEXT,confidence REAL,created_at INTEGER,updated_at INTEGER)");
            jdbc.execute("CREATE TABLE user_preferences(user_id TEXT PRIMARY KEY,schema_version INTEGER,revision INTEGER,interests_json TEXT,walking_tolerance TEXT,budget_json TEXT,companions TEXT,transport_preference TEXT,diet_preference TEXT,stay_area TEXT,legacy_metadata_json TEXT,created_at INTEGER,updated_at INTEGER)");
            var preferences = new PreferencesService(new PreferencesRepository(jdbc,mapper),(u,b,a,o)->{});
            var memory = new TravelMemoryService(jdbc,preferences,null,mapper,new MemoryPreferenceProjector(new TaxonomyProvider()));
            var old = memory.confirm("fixture","我对海鲜过敏，平常不吃辣，喜欢甜食","DIET","USER_EDIT");
            memory.confirm("fixture","我现在喜欢吃辣","DIET","USER_EDIT");
            var persisted = jdbc.queryForObject("SELECT content FROM travel_memory WHERE id=?",String.class,old.id());
            assertTrue(persisted.contains("海鲜过敏"));assertTrue(persisted.contains("喜欢甜食"));assertFalse(persisted.contains("不吃辣"));
            assertTrue(memory.promptContext("fixture").contains("海鲜过敏"));
            String longer = "旅行时偏好慢节奏。".repeat(30)+"我对花生严重过敏，不能吃花生。";
            String normalized = java.text.Normalizer.normalize(longer,java.text.Normalizer.Form.NFKC);
            assertEquals(normalized,memory.confirm("longer",longer,"DIET","USER_EDIT").content());
            assertEquals(normalized,memory.update("longer",memory.list("longer").get(0).id(),longer,"DIET").content());
            String prompt = "每天保留休息时间。".repeat(120)+"花生严重过敏";
            assertEquals(prompt,memory.saveUserCustomizedPrompt("manual",prompt));
            assertThrows(IllegalArgumentException.class,()->memory.saveUserCustomizedPrompt("manual","字".repeat(6001)));
            assertEquals(prompt,preferences.get("manual").legacyMetadata().get("userCustomizedPrompt"));
            memory.confirm("inseparable","不吃辣且花生过敏","DIET","USER_EDIT");
            assertThrows(IllegalArgumentException.class,()->memory.confirm("inseparable","喜欢吃辣","DIET","USER_EDIT"));
            assertEquals(1,memory.list("inseparable").size());assertTrue(memory.promptContext("inseparable").contains("花生过敏"));
        }
    }
    @Test void antipodalDistanceIsFiniteAndCannotBecomeFiveMinutes() throws Exception {
        String origin = "-138.32995546093468,10.148796237429352", destination = "41.670044539065316,-10.148796237429352";
        assertTrue(new RouteCostProvider(null).haversineDistanceKm(origin,destination)>20000);
        assertTrue(Double.isFinite(DiningVenueService.distanceKm(origin,destination)));
        var runtime = new RuntimeLocationPlanner(null,mapper,6000,40,null,null);
        var estimate = RuntimeLocationPlanner.class.getDeclaredMethod("estimateTravelMinutes",String.class,String.class);estimate.setAccessible(true);
        assertTrue((int)estimate.invoke(runtime,origin,destination)>1000);
        assertEquals(Double.POSITIVE_INFINITY,DiningVenueService.distanceKm("NaN,0",destination));
    }
    @Test void emptyRouteIsShortCachedButProviderFailureIsRetried() throws Exception {
        var client = mock(AmapWebServiceClient.class);when(client.isConfigured()).thenReturn(true);
        var count = new AtomicInteger();var now = new AtomicLong(1);
        when(client.get(nullable(java.net.URI.class))).thenAnswer(call -> {count.incrementAndGet();return mapper.readTree("{\"status\":\"1\",\"route\":{\"paths\":[],\"transits\":[]}}");});
        var service = new AmapRouteService(client,500,500,2,new AmapRequestCache(20,now::get),true,true,30000,30000,30000,2000);
        try {
            service.routePair("106.5,29.5","106.6,29.6","重庆","","","walking");int first=count.get();assertTrue(first>0);
            service.routePair("106.5,29.5","106.6,29.6","重庆","","","walking");assertEquals(first,count.get());
            now.set(5_000_000_002L);
            service.routePair("106.5,29.5","106.6,29.6","重庆","","","walking");assertTrue(count.get()>first);
        } finally {service.close();}
        var cache = new AmapRequestCache(10,now::get);var key = new AmapRequestCache.RequestKey("route",List.of("fixture"));
        var calls = new AtomicInteger();
        for(int i=0;i<2;i++)cache.getOrLoad(key,true,false,()->{calls.incrementAndGet();return "error";},value->0L);
        assertEquals(2,calls.get());
        cache.getOrLoad(key,true,false,()->{calls.incrementAndGet();return "good";},value->100L);
        assertEquals("good",cache.getOrLoad(key,true,false,()->"unexpected",value->100L));
    }
}

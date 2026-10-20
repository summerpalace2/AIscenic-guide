package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.security.JwtAuthFilter;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecondAuditRegressionTest {
 @TempDir Path temporary;
 @AfterEach void clearUser() { UserContext.clear(); }
 private JdbcTemplate database() throws Exception {
  var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+temporary.resolve("db.sqlite"));
  var jdbc=new JdbcTemplate(ds);new KnowledgeSchemaInitializer().initialize(jdbc,true);return jdbc;
 }
 @Test void compoundChoiceUsesOriginalFullOperationAndKeepsRemainingAfterApply() {
  var context=PlanPageContext.builder().activeDay(2).build();var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
  var original=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("把解放碑换成三峡博物馆，下午添加茶歇",context);
  var pending=store.remember("s",1,original);
  assertEquals(List.of("COMPOUND_ACTION_ORDER"),pending.intent().getMissingFields());
  var selected=store.resume("s",1,pending.intentId(),"执行第一个",context);
  assertNotNull(selected);assertEquals("REPLACE_STOP",selected.getOperation());assertTrue(selected.getRawMessage().contains("解放碑"));
  store.afterApply("s",2);
  var next=store.resume("s",2,null,"执行下一个",context);assertNotNull(next);assertEquals("ADD_STOP",next.getOperation());
 }
 @Test void ambiguousCompoundReplyKeepsClarificationAndStaleRevisionCannotResume() {
  var context=new PlanPageContext();var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
  var p=store.remember("s",1,com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("删除解放碑，添加茶歇",context));
  assertTrue(store.resume("s",1,p.intentId(),"随便",context).isRequiresClarification());
  assertNull(store.resume("s",2,p.intentId(),"执行第一个",context));
 }
 @Test void crossMidnightRoundTripAndRippleRetainDayOffset() {
  var time=new ItineraryTimeEngine();time.setUnknownTransferEstimateMinutes(30);assertEquals(1485,time.parseTimeMinutes(time.formatTime(1485)));
  List<Map<String,Object>> stops=new ArrayList<>();
  for(String slot:List.of("23:30","待安排","待安排")) stops.add(new LinkedHashMap<>(Map.of("time",slot,"duration","60分钟","id","repeated")));
  time.rippleRecalculateStopTimes(stops,1);assertEquals("02:30(+1d)",stops.get(2).get("time"));
 }
 @Test void verifiedZeroAndRealRouteDurationAreUsedInsteadOfBuffer() {
  var time=new ItineraryTimeEngine();var a=new LinkedHashMap<String,Object>(Map.of("venueId","same","time","09:00","duration","60分钟"));
  var b=new LinkedHashMap<String,Object>(Map.of("venueId","same","duration","60分钟"));
  var c=new LinkedHashMap<String,Object>(Map.of("venueId","other","resolvedRouteCost",Map.of("durationMinutes",37,"routeDataStatus","VERIFIED_AMAP")));
  time.rippleRecalculateStopTimes(new ArrayList<>(List.of(a,b,c)),1);assertEquals("10:00",b.get("time"));assertEquals("11:37",c.get("time"));
 }
 @Test void suppliedOpeningIntervalsAndActualArrivalWeekdayAreAuthoritative() {
  var policy=new CandidateRankingEngine(new RouteCostProvider(null));var time=new ItineraryTimeEngine();
  var night=Attraction.builder().openingHours("17:00-02:00").build();assertTrue(policy.isOpeningCompatible(night,"19:00",time));assertTrue(policy.isOpeningCompatible(night,"01:00(+1d)",time));assertFalse(policy.isOpeningCompatible(night,"02:00",time));
  var venue=Attraction.builder().bestTime("周二闭馆，09:00-17:00").build();assertFalse(policy.isOpeningCompatible(venue,"12:00","2026-10-06",time));assertTrue(policy.isOpeningCompatible(venue,"12:00","2026-10-07",time));
  assertTrue(OpeningHoursPolicy.isClosed("周一闭馆","2026-10-04","00:30(+1d)"));
  assertTrue(policy.isOpeningCompatible(Attraction.builder().name("博物馆").build(),"19:00",time));
 }
 @Test void anonymousPostStreamHasSamePermissionAsGet() throws Exception {
  var filter=new JwtAuthFilter(null,null,null);
  for(String method:List.of("GET","POST")) {var res=new MockHttpServletResponse();boolean[] reached={false};filter.doFilter(new MockHttpServletRequest(method,"/ai/chat/stream"),res,(r,s)->reached[0]=true);assertTrue(reached[0]);}
 }
 @Test void claimKeepsOriginalReplayMappingAndCollisionFailsExplicitly() throws Exception {
  var jdbc=database();var repo=new PlannerSessionRepository(jdbc,new ObjectMapper());
  var first=repo.create("ANONYMOUS","guest-a","p",Map.of(),AppliedPreferencesSnapshot.empty(),Map.of(),"key","fp");
  assertNotNull(repo.claimAnonymous(first.session().sessionId(),first.accessToken(),"user"));
  assertEquals(first.session().sessionId(),repo.findByIdempotency("USER","user","key").sessionId());
  assertNull(repo.claimAnonymous(first.session().sessionId(),"wrong","other"));
  var second=repo.create("ANONYMOUS","guest-b","p",Map.of(),AppliedPreferencesSnapshot.empty(),Map.of(),"key","fp");
  assertNotNull(repo.claimAnonymous(second.session().sessionId(),second.accessToken(),"user"));
  assertThrows(ResponseStatusException.class,()->repo.findByIdempotency("USER","user","key"));
 }
 @Test void replayDoesNotBuildOrHydrateAgainAndBadGuestCapabilityDoesNotCompute() throws Exception {
  var parser=mock(TravelConstraintParserPort.class);var constraints=TravelConstraints.builder().destination("测试城市").durationDays(1).build();
  when(parser.parse(anyString())).thenReturn(constraints);when(parser.applyOverrides(any(),anyMap())).thenReturn(constraints);when(parser.applyDerivedTransportFallback(any())).thenReturn(constraints);
  var preferences=mock(PlannerPreferencesResolver.class);when(preferences.resolve(any(),anyBoolean())).thenReturn(new PlannerPreferencesResolver.Resolution(constraints,AppliedPreferencesSnapshot.empty()));
  var builder=mock(ItineraryBuilderPort.class);when(builder.build(anyInt(),any())).thenReturn(new LinkedHashMap<>(Map.of("days",List.of())));
  var gateway=mock(RouteGatewayPort.class);var repo=new PlannerSessionRepository(database(),new ObjectMapper());
  var service=new PlannerService(parser,builder,repo,null,new ObjectMapper(),gateway,preferences,null,null,true);
  UserContext.set("user","USER");var request=new PlanRequest();request.setPrompt("p");request.setIdempotencyKey("key");
  String first=String.valueOf(service.create(request).body().get("sessionId"));assertEquals(first,service.create(request).body().get("sessionId"));verify(builder,times(1)).build(anyInt(),any());verify(gateway,times(1)).hydrate(any());
  var parallelRequest=new PlanRequest();parallelRequest.setPrompt("p");parallelRequest.setIdempotencyKey("parallel-key");
  var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
  try {
   java.util.concurrent.Callable<String> task=()->{UserContext.set("user","USER");try{return String.valueOf(service.create(parallelRequest).body().get("sessionId"));}finally{UserContext.clear();}};
   var a=workers.submit(task);var b=workers.submit(task);assertEquals(a.get(),b.get());
   verify(builder,times(2)).build(anyInt(),any());verify(gateway,times(2)).hydrate(any());
  } finally {workers.shutdownNow();}
  UserContext.clear();request.setSessionAccessToken("forged");assertEquals(401,service.create(request).status());verify(builder,times(2)).build(anyInt(),any());
 }
 @Test void englishWordsRemainSeparateAndLaterParagraphCanWinExcerpt() throws Exception {
  var jdbc=database();var service=com.ai.guide.testing.KnowledgeTestPorts.service(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), com.ai.guide.testing.KnowledgeTestPorts.noIndex());
  jdbc.update("INSERT INTO kb_document(id,title,content,tags,status,updated_at,scope) VALUES('en','Guide',?,'','active',1,'GLOBAL')","Hongyadong introduction. "+"preface ".repeat(1000)+"Hongyadong ticket opening information");
  var rows=service.searchLocalKnowledge("Hongyadong ticket opening",5);assertEquals(1,rows.size());String excerpt=String.valueOf(rows.get(0).get("content"));assertTrue(excerpt.contains("ticket opening"));assertTrue(excerpt.length()<=4096);
 }
}

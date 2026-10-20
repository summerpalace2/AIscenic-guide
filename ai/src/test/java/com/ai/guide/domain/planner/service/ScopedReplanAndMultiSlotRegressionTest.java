package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.Duration;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
class ScopedReplanAndMultiSlotRegressionTest {
 static ObjectMapper mapper=new ObjectMapper();
 static Map<String,Object> stop(String id,String venue,String name,String time,String duration) {
  return new LinkedHashMap<>(Map.of("id",id,"venueId",venue,"name",name,"time",time,"duration",duration,"district","中心区"));
 }
 static Map<String,Object> trip(List<Map<String,Object>> stops) {
  return new LinkedHashMap<>(Map.of("city","测试城","days",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day",1,"date","2026-10-07","stops",new ArrayList<>(stops)))))));
 }
 record Fixture(PlanAdjustmentService service,ProposalRepositoryPort store,PlannerSessionRepositoryPort repo) {
  PlannerService.ServiceResult preview(String text,String pending) {
   return service.preview("audit-session",PlanConversationRequestDto.builder().message(text).baseRevision(1)
    .context(PlanPageContext.builder().activeDay(1).pendingIntentId(pending).build()).build());
  }
  PlannerService.ServiceResult apply(PlannerService.ServiceResult preview) { return apply(preview, false); }
  PlannerService.ServiceResult apply(PlannerService.ServiceResult preview, boolean force) {
   return service.apply("audit-session",ApplyAdjustmentRequestDto.builder().proposalId(preview.body().get("proposalId").toString())
    .optionId("option-1").baseRevision(1).forceApply(force).build());
  }
 }
 static Fixture fixture(Map<String,Object> trip,PlanAdjustmentIntent intent,int newToProtectedMinutes) {
  return fixture(trip,intent,newToProtectedMinutes,false);
 }
 static Fixture fixture(Map<String,Object> trip,PlanAdjustmentIntent intent,int newToProtectedMinutes,boolean offlineAfterFirst) {
  var a=Attraction.builder().id("test-a").location("116.30,39.90").name("测试广场").duration("90分钟").district("中心区").category("夜景").tags(List.of("夜景")).summary("开放广场").intro("介绍").build();
  var b=Attraction.builder().id("test-b").location("116.31,39.91").name("测试码头").duration("90分钟").district("中心区").summary("开放码头").intro("介绍").build();
  var anchor=Attraction.builder().id("anchor").location("116.32,39.92").name("测试公园").duration("60分钟").district("中心区").summary("公园").intro("介绍").build();
  var attractions=mock(AttractionService.class); when(attractions.list(null,null)).thenReturn(List.of(a,b,anchor));
  for(var v:List.of(a,b,anchor)) v.setCity("测试城");
  when(attractions.list("测试城",null,null)).thenReturn(List.of(a,b,anchor));
  for(var v:List.of(a,b,anchor)) when(attractions.get(v.getId())).thenReturn(v);
  var builder=mock(ItineraryBuilderPort.class); when(builder.attractionServiceList()).thenReturn(List.of(a,b,anchor));
  when(builder.createStop(any(),anyString(),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(call -> {
   Attraction v=call.getArgument(0); var created=stop(call.getArgument(1),v.getId(),v.getName(),call.getArgument(2),call.getArgument(3));
   created.put("location",v.getLocation()); return created;
  });
  var costs=mock(RouteCostProvider.class);
  when(costs.resolveFinalEdge(any(),any(),anyString())).thenAnswer(call -> {
   Attraction from=call.getArgument(0),to=call.getArgument(1);
   int minutes="test-a".equals(from.getId()) && "anchor".equals(to.getId()) ? newToProtectedMinutes : 15;
   return new RouteCost(Duration.ofMinutes(minutes),1000,0,"TRANSIT",RouteCost.RouteDataStatus.ESTIMATED,"isolated route port");
  });
  var repo=mock(PlannerSessionRepositoryPort.class);
  var session=new StoredSession("audit-session","USER","audit-owner","","","",Map.of(),AppliedPreferencesSnapshot.empty(),trip,1,1,"DRAFT",1,1);
  when(repo.find(anyString(),eq("USER"),eq("audit-owner"),anyString())).thenReturn(session);
  when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenAnswer(call ->
   new MutationResult("UPDATED",new StoredSession("audit-session","USER","audit-owner","","","",Map.of(),AppliedPreferencesSnapshot.empty(),call.getArgument(1),2,2,"DRAFT",1,2)));
  var extractor=mock(LlmIntentExtractor.class);
  when(extractor.extract(anyString(),any(),anyMap())).thenAnswer(call -> {
   var extracted=intent.toBuilder().rawMessage(call.getArgument(0)).build();
   return new LlmIntentExtractor.LlmIntentExtractionResult(extracted,"AUDIT_STRUCTURED_PORT",null,0,"HIGH");
  });
  if(intent.isRequiresClarification()) {
   String modelReply;
   try { modelReply=mapper.writeValueAsString(Map.of("operation",intent.getOperation(),"scope","DAY","missingFields",intent.getMissingFields(),
    "requiresClarification",true,"clarificationQuestion",intent.getClarificationQuestion(),"confidence",0.95)); }
   catch(Exception error) {throw new RuntimeException(error);}
   var calls = new java.util.concurrent.atomic.AtomicInteger();
   extractor=new LlmIntentExtractor((system,user)-> {
    if (offlineAfterFirst && calls.getAndIncrement()>0) throw new IllegalStateException("controlled follow-up provider failure");
    return modelReply;
   },mapper,new ConversationIntentClassifier(),true,true,0.75,500);
  }
  var taxonomy=new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
  var verifier=new PlanVerifier(taxonomy,taxonomy); var store=new CaffeineRedisProposalRepository();
  var service=new PlanAdjustmentService(repo,builder,attractions,costs,verifier,new RouteAwarePlanner(costs,verifier,com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer()),store,extractor,mapper,new PlannerMetricsLogger());
  service.setDiningScheduling(taxonomy,new MealGapSolver(costs,new ItineraryTimeEngine()),null);
  return new Fixture(service,store,repo);
 }
 @BeforeEach void authorize() { UserContext.set("audit-owner","USER"); }
 @AfterEach void clear() { UserContext.clear(); }
 @SuppressWarnings("unchecked") static List<Map<String,Object>> allDays(Object trip) { return (List<Map<String,Object>>)((Map<?,?>)trip).get("days"); }
 @SuppressWarnings("unchecked") static List<Map<String,Object>> stops(Object day) { return (List<Map<String,Object>>)((Map<?,?>)day).get("stops"); }
 static String pendingId(PlannerService.ServiceResult response) { return ((Map<?,?>)response.body().get("pendingIntent")).get("intentId").toString(); }
 static Map<String,Object> threeDays() {
  var initial=trip(List.of(stop("park","anchor","测试公园","10:00","60分钟")));
  for(int day=2;day<=3;day++) allDays(initial).add(new LinkedHashMap<>(Map.of("day",day,"date","2026-10-0"+(6+day),
   "stops",new ArrayList<>(List.of(stop("day"+day+"-park","anchor","测试公园","10:00","60分钟"))))));
  return initial;
 }
 static Fixture missingSlots(List<String> fields) {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("ADD_STOP").scope("DAY")
   .requiresClarification(true).missingFields(fields).clarificationQuestion("要添加哪个地点，以及第几天？").build();
  return fixture(threeDays(),intent,15);
 }
 static Fixture scopedReplan(int minutes) {
  var tail=stop("tail","anchor","测试公园","20:00","60分钟");
  tail.put("resolvedRouteCost",new RouteCost(Duration.ofMinutes(15),1000,0,"TRANSIT",RouteCost.RouteDataStatus.ESTIMATED,"old predecessor").asMap());
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REPLAN_DAY).operation("REPLAN_DAY").scope("DAY").dayNumber(1)
   .preferences(List.of("NIGHT_VIEW")).targetStartMinute(1080).targetEndMinute(1200).timeScopeResolved(true).build();
  return fixture(trip(List.of(stop("replace-me","test-b","测试码头","18:00","90分钟"),tail)),intent,minutes);
 }
 @Test void protectedBoundaryKeepsItsScheduleButUsesTheNewPredecessorCostAndRejectsBothApplyModes() {
  var f=scopedReplan(120);
  var preview=f.preview("将18:00到20:00的行程换成夜景，其他安排保留",null);
  var proposed=stops(allDays(preview.body().get("proposedTrip")).get(0));
  assertEquals("test-a",proposed.get(0).get("venueId"));
  assertEquals("anchor",proposed.get(1).get("venueId"));
  assertEquals("20:00",proposed.get(1).get("time"));
  assertEquals("60分钟",proposed.get(1).get("duration"));
  assertEquals(120L,((Number)((Map<?,?>)proposed.get(1).get("resolvedRouteCost")).get("durationMinutes")).longValue());
  assertEquals(false,preview.body().get("canApply"));
  assertTrue(preview.body().get("verification").toString().contains("TIME_OVERLAP"));
  assertEquals(400,f.apply(preview).status());
  assertEquals(400,f.apply(preview,true).status());
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
 @Test void normalScopedReplacementStillAppliesOrdinarilyAndWithForceFlag() {
  for(boolean force:List.of(false,true)) {
   var f=scopedReplan(15);var preview=f.preview("将18:00到20:00的行程换成夜景，其他安排保留",null);
   assertEquals(true,preview.body().get("feasible"));
   assertEquals(200,f.apply(preview,force).status());
   verify(f.repo,times(1)).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  }
 }
 @Test void locationFirstKeepsTheMissingDayAndOnlyAppliesAfterTheDateAnswer() {
  var f=missingSlots(List.of("TARGET_PLACE","DAY_NUMBER"));
  var first=f.preview("给某一天添加一个景点",null);
  assertEquals("LLM_STRUCTURED",first.body().get("intentExtractionSource"));
  var location=f.preview("测试广场",pendingId(first));
  assertEquals(true,location.body().get("requiresClarification"));
  assertNull(location.body().get("proposalId"));
  assertEquals(List.of("DAY_NUMBER"),((Map<?,?>)location.body().get("intent")).get("missingFields"));
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  var day=f.preview("第2天",pendingId(location));
  assertEquals(2,((Map<?,?>)day.body().get("intent")).get("dayNumber"));
  assertEquals(200,f.apply(day).status());
  var days=allDays(day.body().get("proposedTrip"));
  assertEquals(1,stops(days.get(0)).size());assertEquals(2,stops(days.get(1)).size());assertEquals(1,stops(days.get(2)).size());
 }
 @Test void dayFirstAndLocationFirstHaveTheSameFinalAction() {
  var f=missingSlots(List.of("DAY_NUMBER","TARGET_PLACE"));
  var first=f.preview("给某一天添加一个景点",null);var day=f.preview("第2天",pendingId(first));
  assertEquals(true,day.body().get("requiresClarification"));
  assertEquals(List.of("TARGET_PLACE"),((Map<?,?>)day.body().get("intent")).get("missingFields"));
  var location=f.preview("测试广场",pendingId(day));
  assertEquals(2,((Map<?,?>)location.body().get("intent")).get("dayNumber"));
  assertEquals(200,f.apply(location).status());
  assertEquals(2,stops(allDays(location.body().get("proposedTrip")).get(1)).size());
 }
 @Test void activeDayRemainsAValidDefaultWhenNoDaySlotWasRequired() {
  var f=missingSlots(List.of("TARGET_PLACE"));var first=f.preview("添加一个景点",null);
  var location=f.preview("测试广场",pendingId(first));
  assertEquals(1,((Map<?,?>)location.body().get("intent")).get("dayNumber"));
  assertEquals(200,f.apply(location).status());
 }
 static Map<String,Object> repeatedNamesWithRoomToRemove() {
  var trip=threeDays();
  for(var day:allDays(trip)) {
   int number=((Number)day.get("day")).intValue();
   stops(day).add(stop("day"+number+"-filler","test-b","测试码头","14:00","60分钟"));
  }
  return trip;
 }
 @Test void namedRemovalOnDayTwoNeverResolvesToTheSameNameOnDayOne() {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(2).targetStopReference("测试公园").build();
  var f=fixture(repeatedNamesWithRoomToRemove(),intent,15);
  var preview=f.preview("第2天删除测试公园",null);
  assertEquals("day2-park",((Map<?,?>)preview.body().get("intent")).get("targetStopId"));
  var proposed=allDays(preview.body().get("proposedTrip"));
  assertTrue(stops(proposed.get(0)).stream().anyMatch(s -> "park".equals(s.get("id"))));
  assertFalse(stops(proposed.get(1)).stream().anyMatch(s -> "day2-park".equals(s.get("id"))));
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
 @Test void duplicatedNamesWithinOneDayRequireAnExplicitCardSelection() {
  var trip=repeatedNamesWithRoomToRemove();
  stops(allDays(trip).get(1)).add(stop("day2-other-park","anchor","测试公园","17:00","60分钟"));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(2).targetStopReference("测试公园").build();
  var f=fixture(trip,intent,15);var preview=f.preview("第2天删除测试公园",null);
  assertEquals(true,preview.body().get("requiresClarification"));
  assertNull(preview.body().get("proposedTrip"));
  assertTrue(((List<?>)((Map<?,?>)preview.body().get("intent")).get("missingFields")).contains("TARGET_STOP"));
 }
 @Test void selectedStopFromAnotherDayCannotOverrideTheRequestedDay() {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(2).targetStopId("park").targetStopReference("这个景点").build();
  var f=fixture(repeatedNamesWithRoomToRemove(),intent,15);
  var preview=f.service.preview("audit-session",PlanConversationRequestDto.builder().message("第2天删除这个景点")
   .baseRevision(1).context(PlanPageContext.builder().activeDay(1).selectedStopId("park").build()).build());
  assertEquals(true,preview.body().get("requiresClarification"));assertNull(preview.body().get("proposedTrip"));
  var actual=(Map<?,?>)preview.body().get("intent");assertEquals(2,actual.get("dayNumber"));assertNull(actual.get("targetStopId"));
 }
 @Test void validConcreteStopIdStillDisambiguatesTwoVisitsToTheSameVenue() {
  var trip=repeatedNamesWithRoomToRemove();
  stops(allDays(trip).get(1)).add(stop("day2-other-park","anchor","测试公园","17:00","60分钟"));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(2).targetStopId("day2-other-park").targetStopReference("测试公园").build();
  var f=fixture(trip,intent,15);var preview=f.preview("第2天删除选中的测试公园",null);
  assertEquals("day2-other-park",((Map<?,?>)preview.body().get("intent")).get("targetStopId"));
  var proposed=stops(allDays(preview.body().get("proposedTrip")).get(1));
  assertTrue(proposed.stream().anyMatch(s -> "day2-park".equals(s.get("id"))));
  assertFalse(proposed.stream().anyMatch(s -> "day2-other-park".equals(s.get("id"))));
 }
 @Test void anotherExplicitVisitDoesNotRequireSelectingOneOfTheExistingVisits() {
  var trip=repeatedNamesWithRoomToRemove();
  stops(allDays(trip).get(1)).add(stop("day2-other-park","anchor","测试公园","17:00","60分钟"));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).operation("ADD_STOP")
   .scope("DAY").dayNumber(2).targetAttractionId("anchor").requestedVenueName("测试公园").build();
  var f=fixture(trip,intent,15);var preview=f.preview("第2天晚上再去测试公园",null);
  assertEquals(200,preview.status());
  assertFalse(Boolean.TRUE.equals(preview.body().get("requiresClarification")));
  assertEquals("ADD_STOP",((Map<?,?>)preview.body().get("intent")).get("operation"));
 }
 @Test void dayReplanningMayMentionSeveralExistingStopsWithoutAStopSelection() {
  var trip=repeatedNamesWithRoomToRemove();
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REPLAN_DAY).operation("REPLAN_DAY")
   .scope("DAY").dayNumber(2).preferences(List.of("NIGHT_VIEW")).build();
  var f=fixture(trip,intent,15);var preview=f.preview("第2天重新规划测试公园和测试码头，偏好夜景",null);
  assertEquals(200,preview.status());
  assertFalse(Boolean.TRUE.equals(preview.body().get("requiresClarification")));
  assertEquals("REPLAN_DAY",((Map<?,?>)preview.body().get("intent")).get("operation"));
 }
 @Test void storedPinsSurviveEmptyPageContextAndPreventApplyEvenIfModelOmitsThem() {
  var initial=trip(List.of(stop("protected","test-a","测试展馆","10:00","60分钟"),
   stop("other","anchor","测试公园","12:00","60分钟")));
  initial.put("pinnedStopIds",List.of("protected"));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(1).targetStopId("protected").targetStopReference("测试展馆").build();
  var f=fixture(initial,intent,15);
  var preview=f.preview("删除测试展馆",null);
  assertEquals(200,preview.status());
  assertFalse(Boolean.TRUE.equals(preview.body().get("canApply")));
  assertEquals(400,f.apply(preview,true).status());
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  assertEquals(List.of("protected"),PlannerPins.read(initial));
 }
 @Test void ordinaryPreviewDoesNotWritePersistentProtection() {
  var initial=trip(List.of(stop("protected","test-a","测试展馆","10:00","60分钟"),
   stop("other","anchor","测试公园","12:00","60分钟")));
  initial.put("pinnedStopIds",List.of("protected"));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("ADD_STOP")
   .scope("DAY").requiresClarification(true).missingFields(List.of("TARGET_STOP"))
   .clarificationQuestion("添加哪个地点？").build();
  var f=fixture(initial,intent,15);
  var request=PlanConversationRequestDto.builder().message("加个地方").baseRevision(1)
   .context(PlanPageContext.builder().activeDay(1).pinnedStopIds(List.of("other")).build()).build();
  f.service.preview("audit-session",request);
  assertEquals(List.of("protected","other"),request.getContext().getPinnedStopIds());
  assertEquals(List.of("protected"),PlannerPins.read(initial));
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
 @Test void socialAsideKeepsPendingIdAndDoesNotExecuteOldRemoval() {
  var f=missingSlots(List.of("TARGET_PLACE"));
  var first=f.preview("添加一个地方",null);
  String pending=pendingId(first);
  var aside=f.preview("我现在心情低落",pending);
  assertEquals("DIALOGUE",aside.body().get("type")); assertEquals(false,aside.body().get("modified"));
  assertEquals(true,aside.body().get("suspended")); assertEquals(pending,pendingId(aside));
  assertNull(aside.body().get("proposedTrip"));
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
 @Test void stationNameContainingAffectWordsIsNotInterceptedAsSocialAside() {
  var initial=trip(List.of(stop("mood","test-a","今天心情公园","10:00","60分钟"),stop("other","anchor","测试公园","12:00","60分钟")));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REMOVE_STOP")
   .scope("STOP").dayNumber(1).requiresClarification(true).missingFields(List.of("TARGET_STOP"))
   .clarificationQuestion("要删除哪个地点？").build();
  var f=fixture(initial,intent,15);
  var first=f.preview("删除一个景点",null);
  var answer=f.preview("今天心情公园",pendingId(first));
  assertNotEquals("DIALOGUE",answer.body().get("type"));
  assertEquals("REMOVE_STOP",((Map<?,?>)answer.body().get("intent")).get("operation"));
 }
 @Test void cancellationContainingTargetClearsTaskAndFollowingNewCommandIsIndependent() {
  var f=missingSlots(List.of("DAY_NUMBER","TARGET_PLACE"));
  var first=f.preview("添加一个地方",null);
  var cancelled=f.preview("算了，取消刚才添加某站的请求。",pendingId(first));
  assertEquals(true,cancelled.body().get("cancelled")); assertNull(cancelled.body().get("pendingIntent"));
  assertNull(cancelled.body().get("proposedTrip"));
 }
 @Test void dateAnswerAndAnotherActionPreviewFirstTaskAndDeclareDeferredWork() {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("ADD_STOP")
   .requestedVenueName("测试广场").targetAttractionId("test-a").requiresClarification(true)
   .missingFields(List.of("DAY_NUMBER")).clarificationQuestion("第几天？").build();
  var f=fixture(threeDays(),intent,15);
  var first=f.preview("添加测试广场",null);
  assertEquals(200,first.status(),first.body().toString());
  assertNotNull(first.body().get("pendingIntent"),first.body().toString());
  var answer=f.preview("10月8日，顺便删除测试公园",pendingId(first));
  assertEquals(200,answer.status(),answer.body().toString());
  assertNotNull(answer.body().get("intent"),answer.body().toString());
  assertEquals("ADD_STOP",((Map<?,?>)answer.body().get("intent")).get("operation"));
  assertEquals(2,((Map<?,?>)answer.body().get("intent")).get("dayNumber"));
  assertNotNull(answer.body().get("unexecutedActions"));
  assertTrue(String.valueOf(answer.body().get("message")).contains("尚未执行"));
 }
 @Test void aCompleteDatedNewCommandMustNotPreviewAnOldPendingRemoval() {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REMOVE_STOP")
   .targetStopReference("测试公园").requiresClarification(true).missingFields(List.of("DAY_NUMBER"))
   .clarificationQuestion("第几天？").build();
  var f=fixture(threeDays(),intent,15,true);
  var first=f.preview("删除测试公园",null);
  assertNotNull(first.body().get("pendingIntent"),first.body().toString());
  var answer=f.preview("10月8日添加测试广场",pendingId(first));
  assertEquals(200,answer.status(),answer.body().toString());
  assertNotNull(answer.body().get("intent"),answer.body().toString());
  assertEquals("ADD_STOP",((Map<?,?>)answer.body().get("intent")).get("operation"));
  assertEquals("DETERMINISTIC_FALLBACK",answer.body().get("intentExtractionSource"));
  assertEquals("PROVIDER_ERROR",answer.body().get("fallbackReason"));
  assertNull(answer.body().get("unexecutedActions"),answer.body().toString());
 }
 @Test void dateReplyDefersDensityOperationRecognizedByTheSharedClassifier() {
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("ADD_STOP")
   .requestedVenueName("测试广场").requiresClarification(true).missingFields(List.of("DAY_NUMBER"))
   .clarificationQuestion("第几天？").build();
  var f=fixture(threeDays(),intent,15);
  var first=f.preview("添加测试广场",null);
  var answer=f.preview("10月8日，顺便少安排一个景点",pendingId(first));
  assertEquals(200,answer.status(),answer.body().toString());
  assertEquals("ADD_STOP",((Map<?,?>)answer.body().get("intent")).get("operation"));
  assertEquals(List.of("顺便少安排一个景点"),answer.body().get("unexecutedActions"));
  assertTrue(String.valueOf(answer.body().get("message")).contains("尚未执行"));
 }
 @Test void replacementPreviewAllowsAbsentDisplayNameWithoutAliasLookupCrash() {
  var initial=trip(List.of(stop("replace-me","test-b","测试码头","10:00","60分钟"),
   stop("other","anchor","测试公园","14:00","60分钟")));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.SUGGEST_REPLACEMENTS).operation("REPLACE_STOP")
   .scope("STOP").dayNumber(1).targetStopId("replace-me").targetStopReference("测试码头")
   .replacementPlaceId("test-a").targetAttractionId("test-a").replacementPlaceName("测试广场").build();
  var f=fixture(initial,intent,15);
  var result=assertDoesNotThrow(() -> f.preview("把测试码头换成测试广场",null));
  assertEquals(200,result.status(),result.body().toString());
  assertNotNull(result.body().get("proposedTrip"),result.body().toString());
  // This toy route may reject all replacement candidates. It verifies the nullable
  // alias lookup path only; candidate-map null metadata is covered by the frozen holdout.
  assertNotNull(result.body().get("intent"),result.body().toString());
  assertEquals("REPLACE_STOP",((Map<?,?>)result.body().get("intent")).get("operation"));
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
}

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
class FourthPlanningTemporalRegressionTest {
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
  var a=Attraction.builder().id("test-a").location("116.30,39.90").name("测试广场").openingHours("00:00-24:00 每周四闭馆").duration("90分钟").district("中心区").category("夜景").tags(List.of("夜景")).summary("开放广场").intro("介绍").build();
  var b=Attraction.builder().id("test-b").location("116.31,39.91").name("测试码头").duration("90分钟").district("中心区").summary("开放码头").intro("介绍").build();
  var anchor=Attraction.builder().id("anchor").location("116.32,39.92").name("测试公园").duration("60分钟").district("中心区").summary("公园").intro("介绍").build();
  var attractions=mock(AttractionService.class); when(attractions.list(null,null)).thenReturn(List.of(a,b,anchor));
  for(var v:List.of(a,b,anchor)) v.setCity("测试城");
  when(attractions.list("测试城",null,null)).thenReturn(List.of(a,b,anchor));
  for(var v:List.of(a,b,anchor)) when(attractions.get(v.getId())).thenReturn(v);
  var builder=mock(ItineraryBuilderPort.class); when(builder.attractionServiceList()).thenReturn(List.of(a,b,anchor));
  when(builder.createStop(any(),anyString(),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(call -> {
   Attraction v=call.getArgument(0); var created=stop(call.getArgument(1),v.getId(),v.getName(),call.getArgument(2),call.getArgument(3));
   created.put("location",v.getLocation()); created.put("openingHours",v.getOpeningHours()); return created;
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
   extractor=new LlmIntentExtractor((system,user)->modelReply,mapper,new ConversationIntentClassifier(),true,true,0.75,500);
  }
  var verifier=new PlanVerifier(); var store=new CaffeineRedisProposalRepository();
  return new Fixture(new PlanAdjustmentService(repo,builder,attractions,costs,verifier,new RouteAwarePlanner(costs,verifier,com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer()),store,extractor,mapper,new PlannerMetricsLogger()),store,repo);
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
 @Test void completeVisitMustFitBoundedWindowAndBothApplyModesRejectOverflow() {
  var initial=trip(List.of(stop("park","anchor","测试公园","10:00","60分钟")));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).operation("ADD_STOP").scope("DAY")
   .dayNumber(1).targetAttractionId("test-a").targetStartMinute(1080).targetEndMinute(1140).timeScopeResolved(true).build();
  var f=fixture(initial,intent,15);var preview=f.preview("18:00到19:00添加测试广场",null);
  assertEquals(false,preview.body().get("canApply"));assertEquals(false,preview.body().get("feasible"));
  assertEquals(400,f.apply(preview).status());assertEquals(400,f.apply(preview,true).status());
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
  intent=intent.toBuilder().targetEndMinute(1170).build();f=fixture(initial,intent,15);
  preview=f.preview("18:00到19:30添加测试广场",null);assertEquals(true,preview.body().get("canApply"));
  assertEquals(200,f.apply(preview).status());
 }
 @Test void appendedDayUsesExistingDateAndKnownClosureCannotApply() {
  var initial=trip(List.of(stop("park","anchor","测试公园","10:00","60分钟")));
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).operation("ADD_STOP").scope("DAY")
   .dayNumber(2).targetAttractionId("test-a").timeScopeResolved(true).build();
  var f=fixture(initial,intent,15);var preview=f.preview("第2天18:00添加测试广场",null);
  assertEquals("2026-10-08",allDays(preview.body().get("proposedTrip")).get(1).get("date"));
  assertEquals(false,preview.body().get("canApply"));assertEquals(400,f.apply(preview).status());
  assertEquals(400,f.apply(preview,true).status());
  verify(f.repo,never()).update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList());
 }
 @Test void unknownTripDateDoesNotInventAClosureWeekday() {
  var initial=trip(List.of(stop("park","anchor","测试公园","10:00","60分钟")));
  allDays(initial).get(0).remove("date");
  var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).operation("ADD_STOP").scope("DAY")
   .dayNumber(2).targetAttractionId("test-a").timeScopeResolved(true).build();
  var f=fixture(initial,intent,15);var preview=f.preview("第2天18:00添加测试广场",null);
  assertFalse(allDays(preview.body().get("proposedTrip")).get(1).containsKey("date"));
  assertEquals(true,preview.body().get("canApply"));assertEquals(200,f.apply(preview).status());
 }
}

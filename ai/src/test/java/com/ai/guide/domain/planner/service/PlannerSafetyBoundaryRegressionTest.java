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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual preview/strategy/verification/application chain with isolated data/provider ports. */
class PlannerSafetyBoundaryRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void authorize() { UserContext.set("safety-owner", "USER"); }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void nextDaySingleStopCannotBeAppliedOrdinarilyOrForced() {
        var fixture = fixture("23:00", "30分钟", false);
        var preview = fixture.preview("新增测试广场");
        assertFalse((Boolean) option(preview, "option-1").get("canApply"));
        assertTrue(option(preview, "option-1").get("reasonCodes").toString().contains("MIDNIGHT_OVERFLOW"));
        assertEquals(400, fixture.apply(preview, "option-1", false).status());
        assertEquals(400, fixture.apply(preview, "option-1", true).status());
        verify(fixture.repo, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void singleStopStartingTodayButEndingTomorrowIsAlsoNonForceable() {
        var fixture = fixture("22:00", "60分钟", false);
        var preview = fixture.preview("新增测试广场");
        assertFalse((Boolean) option(preview, "option-1").get("feasible"));
        assertTrue(option(preview, "option-1").get("reasonCodes").toString().contains("MIDNIGHT_OVERFLOW"));
        assertEquals(400, fixture.apply(preview, "option-1", false).status());
        assertEquals(400, fixture.apply(preview, "option-1", true).status());
    }
    @Test void fullCrossNightRouteAndUnknownDurationAlternativeCannotBeApplied() {
        var fixture = fixture("20:30", "60分钟", false);
        var preview = fixture.preview("在测试公园之后接上测试广场和测试码头");
        assertFalse((Boolean) option(preview, "option-1").get("canApply"));
        assertFalse((Boolean) option(preview, "option-2").get("feasible"));
        assertEquals(400, fixture.apply(preview, "option-1", false).status());
        assertEquals(400, fixture.apply(preview, "option-1", true).status());
        assertEquals(400, fixture.apply(preview, "option-2", false).status());
        verify(fixture.repo, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void visitEndingExactlyAtMidnightRemainsValid() {
        var fixture = fixture("20:53", "60分钟", false);
        var preview = fixture.preview("新增测试广场");
        assertTrue((Boolean) option(preview, "option-1").get("feasible"));
        assertEquals(200, fixture.apply(preview, "option-1", false).status());
    }
    @Test void missingRouteEvidenceBlocksApplyingTimedInsertion() {
        var fixture = fixture("10:00", "60分钟", false, false);
        var preview = fixture.preview("新增测试广场");
        assertFalse((Boolean) option(preview, "option-1").get("canApply"));
        assertTrue(option(preview, "option-1").get("reasonCodes").toString().contains("ROUTE_DURATION_UNKNOWN"));
    }
    @Test void shortPreferenceReplyKeepsPinnedVenueAndCanStillChangeAnUnpinnedStop() {
        var fixture = fixture("10:00", "60分钟", true);
        var first = fixture.preview("重排第1天");
        Map<?,?> pending = (Map<?,?>) first.body().get("pendingIntent");
        var second = fixture.service.preview("safety-session", PlanConversationRequestDto.builder()
                .sessionId("safety-session").baseRevision(1).message("夜景")
                .context(PlanPageContext.builder().activeDay(1).pendingIntentId(pending.get("intentId").toString()).build()).build());
        var proposal = fixture.store.get(second.body().get("proposalId").toString());
        assertTrue(proposal.getIntent().getPinnedStopIds().contains("anchor-stop"));
        Map<String,Object> proposedTrip = mapper.convertValue(second.body().get("proposedTrip"), Map.class);
        List<Map<?,?>> stops = com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.allStops(proposedTrip);
        assertEquals("anchor", stops.stream().filter(s -> "anchor-stop".equals(s.get("id"))).findFirst().orElseThrow().get("venueId"));
        assertEquals("test-a", stops.stream().filter(s -> "unfixed-stop".equals(s.get("id"))).findFirst().orElseThrow().get("venueId"));
        assertEquals(200, fixture.apply(second, "option-1", false).status());
    }
    @Test void resetThenCapacityEvictionCannotReviveAnOldProposalInApply() {
        var fixture = fixture("10:00", "60分钟", false);
        var preview = fixture.preview("新增测试广场");
        fixture.service.preview("safety-session", PlanConversationRequestDto.builder().sessionId("safety-session").baseRevision(1)
                .context(PlanPageContext.builder().resetConversation(true).build()).build());
        var continuations = (PendingIntentContinuations) org.springframework.test.util.ReflectionTestUtils.getField(fixture.service, "continuations");
        for (int n = 0; n < 4096; n++) continuations.begin("other-" + n);
        assertEquals(409, fixture.apply(preview, "option-1", false).status());
        assertEquals(409, fixture.apply(preview, "option-1", true).status());
        verify(fixture.repo, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }

    @Test void applyRechecksCityScopeEvenWhenStoredAiProposalClaimsItIsApplicable() {
        var fixture = fixture("10:00", "60分钟", false);
        var preview = fixture.preview("新增测试广场");
        assertTrue((Boolean) option(preview, "option-1").get("canApply"));
        var proposal = fixture.store.get(preview.body().get("proposalId").toString());
        var foreign = Attraction.builder().id("foreign-ai").name("异城广场").city("另一城").build();
        when(fixture.builder.attraction("foreign-ai")).thenReturn(foreign);
        var proposed = proposal.getOptionPlans().get("option-1");
        var added = com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.allStops(proposed).stream()
                .filter(stop -> "test-a".equals(stop.get("venueId"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") var writable = (Map<String, Object>) (Map<?, ?>) added;
        writable.put("venueId", "foreign-ai");
        fixture.store.put(proposal);
        assertEquals(400, fixture.apply(preview, "option-1", false).status());
        assertEquals(400, fixture.apply(preview, "option-1", true).status());
        verify(fixture.repo, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }

    @SuppressWarnings("unchecked") private Map<String,Object> option(PlannerService.ServiceResult preview, String id) {
        return ((Map<String,Map<String,Object>>) preview.body().get("optionStatus")).get(id);
    }
    private Fixture fixture(String anchorTime, String anchorDuration, boolean followup) {
        return fixture(anchorTime, anchorDuration, followup, true);
    }
    private Fixture fixture(String anchorTime, String anchorDuration, boolean followup, boolean withCoordinates) {
        var aBuilder = Attraction.builder().id("test-a").name("测试广场").duration("90分钟").district("中心区").category("夜景").tags(List.of("夜景")).summary("开放广场").intro("介绍");
        var bBuilder = Attraction.builder().id("test-b").name("测试码头").duration("90分钟").district("中心区").summary("滨水步行").intro("介绍");
        var anchorBuilder = Attraction.builder().id("anchor").name("测试公园").duration(anchorDuration).district("中心区");
        if (withCoordinates) {
            aBuilder.location("116.30,39.90"); bBuilder.location("116.31,39.91"); anchorBuilder.location("116.32,39.92");
        }
        var a = aBuilder.build(); var b = bBuilder.build(); var anchor = anchorBuilder.build();
        for (var v : List.of(a,b,anchor)) v.setCity("测试城");
        var attractions = mock(AttractionService.class);
        when(attractions.list(null,null)).thenReturn(List.of(a,b,anchor));
        when(attractions.list("测试城",null,null)).thenReturn(List.of(a,b,anchor));
        for (var v : List.of(a,b,anchor)) when(attractions.get(v.getId())).thenReturn(v);
        var builder = mock(ItineraryBuilderPort.class, CALLS_REAL_METHODS);
        for (var value : List.of(a, b, anchor)) when(builder.attraction(value.getId())).thenReturn(value);
        when(builder.attractionServiceList()).thenReturn(List.of(a,b,anchor));
        when(builder.createStop(any(),anyString(),anyString(),anyString(),anyString(),nullable(String.class))).thenAnswer(call -> {
            Attraction v = call.getArgument(0);
            return new LinkedHashMap<String,Object>(Map.of("id",call.getArgument(1),"venueId",v.getId(),"name",v.getName(),
                    "time",call.getArgument(2),"duration",call.getArgument(3),"district",v.getDistrict()));
        });
        var costs = mock(RouteCostProvider.class);
        when(costs.resolveFinalEdge(any(),any(),anyString())).thenReturn(new RouteCost(Duration.ofMinutes(37),1000,0,"TRANSIT",RouteCost.RouteDataStatus.ESTIMATED,"隔离端口"));
        var repo = mock(PlannerSessionRepositoryPort.class);
        var stops = new ArrayList<Map<String,Object>>();
        stops.add(new LinkedHashMap<>(Map.of("id","anchor-stop","venueId","anchor","name","测试公园","time",anchorTime,"duration",anchorDuration,"district","中心区")));
        if (followup) stops.add(new LinkedHashMap<>(Map.of("id","unfixed-stop","venueId","test-b","name","测试码头","time","13:00","duration","90分钟","district","中心区")));
        var trip = new LinkedHashMap<String,Object>(Map.of("days",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day",1,"date","2026-10-07","stops",stops))))));
        trip.put("city", "测试城");
        var session = new StoredSession("safety-session","USER","safety-owner","","","",Map.of(),AppliedPreferencesSnapshot.empty(),trip,1,1,"DRAFT",1,1);
        when(repo.find(anyString(),eq("USER"),eq("safety-owner"),anyString())).thenReturn(session);
        when(repo.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList())).thenAnswer(call ->
                new MutationResult("UPDATED",new StoredSession("safety-session","USER","safety-owner","","","",Map.of(),AppliedPreferencesSnapshot.empty(),call.getArgument(1),2,2,"DRAFT",1,2)));
        var extractor = mock(LlmIntentExtractor.class);
        when(extractor.extract(anyString(),any(),anyMap())).thenAnswer(call -> {
            String raw = call.getArgument(0);
            var intent = followup ? PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REPLAN_DAY").scope("DAY").dayNumber(1)
                    .requiresClarification(true).missingFields(List.of("REPLAN_PREFERENCE")).clarificationQuestion("希望按什么兴趣重新规划？")
                    .pinnedStopIds(List.of("anchor-stop")).rawMessage(raw).build() :
                    PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).operation("ADD_STOP").scope("DAY").dayNumber(1)
                            .targetAttractionId("test-a").requestedVenueName("测试广场").rawMessage(raw).build();
            return new LlmIntentExtractor.LlmIntentExtractionResult(intent,"TEST_PORT",null,0,"HIGH");
        });
        var verifier = new PlanVerifier(); var store = new CaffeineRedisProposalRepository();
        var service = new PlanAdjustmentService(repo,builder,attractions,costs,verifier,new RouteAwarePlanner(costs,verifier,com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer()),store,extractor,com.ai.guide.testing.PlannerTaxonomyFixtures.classifier(),mapper,new PlannerMetricsLogger());
        service.setDiningScheduling(new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter(), new MealGapSolver(costs, new ItineraryTimeEngine()), null);
        return new Fixture(service,repo,store,builder);
    }
    private record Fixture(PlanAdjustmentService service, PlannerSessionRepositoryPort repo, ProposalRepositoryPort store, ItineraryBuilderPort builder) {
        PlannerService.ServiceResult preview(String message) {
            return service.preview("safety-session",PlanConversationRequestDto.builder().sessionId("safety-session").baseRevision(1).message(message)
                    .context(PlanPageContext.builder().activeDay(1).build()).build());
        }
        PlannerService.ServiceResult apply(PlannerService.ServiceResult preview, String option, boolean force) {
            return service.apply("safety-session",ApplyAdjustmentRequestDto.builder().proposalId(preview.body().get("proposalId").toString())
                    .optionId(option).baseRevision(1).forceApply(force).build());
        }
    }
}

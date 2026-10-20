package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.OperationDeadline;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.geo.model.*;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.trip.service.TripService;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real services/repositories, isolated SQLite from test configuration; only geo provider is controlled. */
@SpringBootTest(properties = "planner.request-timeout-ms=800")
class PartialPlanningPersistenceLifecycleTest {
    @Autowired PlannerService planner;
    @Autowired PlanAdjustmentService adjustments;
    @Autowired com.ai.guide.application.planner.PlannerApplicationService application;
    @Autowired TripService trips;
    @Autowired PlannerSessionRepositoryPort sessions;
    @Autowired JdbcTemplate jdbc;
    @MockBean RouteGatewayPort geo;
    private AmapRouteService provider;

    @Test void unavailableCandidatePreviewRemainsNoCandidate() throws Exception {
        var mapper = PlanAdjustmentService.class.getDeclaredMethod("mapPreviewExecution", PlannerService.ServiceResult.class);
        mapper.setAccessible(true);
        var body = Map.<String,Object>of("proposalId", "missing-candidate", "canApply", false,
                "candidateReplacements", List.of());
        var mapped = (AdjustmentPreviewExecution) mapper.invoke(adjustments, new PlannerService.ServiceResult(200,body));
        assertEquals(AdjustmentPreviewExecution.Outcome.NO_CANDIDATE, mapped.outcome());
    }

    @AfterEach void clear() { UserContext.clear(); if (provider != null) provider.close(); }

    @Test void partialGenerationSurvivesPreviewApplySaveOpenAndFurtherDeletion() {
        configureProvider(3);
        var created = create();
        assertEquals(200, created.status(), created.body().toString());
        assertPartial(created.body());
        String sessionId = String.valueOf(created.body().get("sessionId"));
        Map<String,Object> original = trip(created.body());
        assertEquals(2, stops(original).size());
        String removed = String.valueOf(stops(original).get(1).get("id"));
        var actor = new com.ai.guide.application.planner.PlanningActor(UserContext.getUserId(),false);
        var preview = application.converse(actor, sessionId, PlanConversationRequestDto.builder().baseRevision(1)
                .message("删除当前选中的站点").context(PlanPageContext.builder().activeDay(1).selectedStopId(removed).build()).build());
        assertEquals(com.ai.guide.application.planner.PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome(), preview.body().toString());
        assertPartial(preview.body());
        assertPartialTrip((Map<String,Object>)preview.body().get("proposedTrip"));
        assertEquals(2, stops(sessions.find(sessionId,"USER",UserContext.getUserId(),"").trip()).size(), "preview must not mutate authority");
        var applied = application.applyAdjustment(actor, sessionId, ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(preview.body().get("proposalId"))).baseRevision(1).build());
        assertEquals(com.ai.guide.application.planner.ApplyAdjustmentResult.Outcome.SUCCESS, applied.outcome(), applied.body().toString());
        assertPartial(applied.body());
        assertEquals(1, stops(trip(applied.body())).size());
        var saved = planner.save(sessionId, "");
        assertTrue(saved.status() < 300, saved.body().toString());
        String formalId = String.valueOf(saved.body().get("tripId"));
        assertPartialTrip(trips.get(UserContext.getUserId(), formalId).plan());
        var opened = planner.openFormalTrip(formalId);
        assertEquals(200, opened.status(), opened.body().toString());
        assertPartial(opened.body());
        String reopenedSession = String.valueOf(opened.body().get("sessionId"));
        assertNotEquals(sessionId, reopenedSession);
        var delete = new PlannerMutationRequest();
        delete.setOperation("delete"); delete.setExpectedVersion(1);
        delete.setStopId(String.valueOf(stops(trip(opened.body())).getFirst().get("id")));
        var continued = planner.mutateStops(reopenedSession, delete);
        assertEquals(200, continued.status(), continued.body().toString());
        assertPartialTrip(trip(continued.body()));
        assertTrue(stops(trip(continued.body())).isEmpty());
    }

    @Test void completeGenerationDoesNotAcquireDeadlineMarkersOnSaveAndOpen() {
        configureProvider(Integer.MAX_VALUE);
        var created = create();
        assertEquals(200, created.status());
        assertComplete(trip(created.body()));
        var saved = planner.save(String.valueOf(created.body().get("sessionId")), "");
        assertTrue(saved.status() < 300);
        var opened = planner.openFormalTrip(String.valueOf(saved.body().get("tripId")));
        assertEquals(200, opened.status());
        assertComplete(trip(opened.body()));
    }

    @Test void emptyTimeoutPersistsNeitherSessionNorFormalTrip() {
        configureProvider(1);
        int sessionCount = jdbc.queryForObject("SELECT COUNT(*) FROM planner_session", Integer.class);
        int tripCount = jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class);
        var response = create();
        assertEquals(504, response.status(), response.body().toString());
        assertEquals(sessionCount, jdbc.queryForObject("SELECT COUNT(*) FROM planner_session", Integer.class));
        assertEquals(tripCount, jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));
        assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos());
    }

    private PlannerService.ServiceResult create() {
        UserContext.set("partial-owner-" + UUID.randomUUID(), "USER");
        return planner.create(PlanRequest.builder().prompt("我在测试起点 上午8点出发 给我6小时的旅游规划")
                .constraints(Map.of("destination","测试市","durationDays",1)).idempotencyKey(UUID.randomUUID().toString()).build());
    }
    private void configureProvider(int delayedCall) {
        var calls = new AtomicInteger();
        var start = poi("start","测试起点",120);
        var candidates = List.of(poi("museum","测试博物馆",120.004),poi("park","测试公园",120.008),poi("street","测试老街",120.012));
        var segment = new GeoRouteSegment("WALKING",100,60,100,0,List.of(),"120,30;120.001,30",true);
        var verified = new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,segment,"","test",Instant.now());
        provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String q,String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,List.of(start),"",Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l,int r,String t,String k,String c,int pages) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,candidates,"",Instant.now()); }
            @Override public GeoRoutePairOutcome routePair(String o,String d,String c,String op,String dp,String pref) {
                if (calls.incrementAndGet() >= delayedCall) while (!OperationDeadline.expired()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                return new GeoRoutePairOutcome(verified,verified,verified,pref,false);
            }
        };
        var taxonomy = new JsonTaxonomyAdapter();
        var runtime = new RuntimeLocationPlanner(provider,new ObjectMapper(),6000,40,null,taxonomy);
        when(geo.planFromLocation(any(),anyInt())).thenAnswer(call -> runtime.plan(call.getArgument(0),call.getArgument(1)));
        when(geo.hydrate(anyMap())).thenReturn(new HydrationResult("TEST",0,0,0,false));
    }
    private static GeoPoiCandidate poi(String id,String name,double lon) { return new GeoPoiCandidate(id,name,new GeoCoordinate(lon,30),"测试市","风景名胜",null,null,id,null,null); }
    @SuppressWarnings("unchecked") private static Map<String,Object> trip(Map<String,Object> body) { return (Map<String,Object>)body.get("trip"); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> stops(Map<String,Object> trip) { return (List<Map<String,Object>>)((Map<?,?>)((List<?>)trip.get("days")).getFirst()).get("stops"); }
    private static void assertPartial(Map<String,Object> body) { assertEquals(true,body.get("degraded")); assertTrue(((List<?>)body.get("degradationReasons")).contains("PLANNING_DEADLINE_EXCEEDED"),body.toString()); if(body.containsKey("trip")) assertPartialTrip(trip(body)); }
    private static void assertPartialTrip(Map<String,Object> trip) { assertEquals(true,trip.get("degraded")); assertTrue(((List<?>)trip.get("degradationReasons")).contains("PLANNING_DEADLINE_EXCEEDED")); assertEquals("PARTIAL_TIMEOUT",((Map<?,?>)trip.get("spatialPlan")).get("status")); assertEquals(true,((Map<?,?>)trip.get("sourceStatus")).get("fallback")); }
    private static void assertComplete(Map<String,Object> trip) { assertFalse(((List<?>)trip.getOrDefault("degradationReasons",List.of())).contains("PLANNING_DEADLINE_EXCEEDED")); assertEquals("COMPLETED",((Map<?,?>)trip.get("spatialPlan")).get("status")); }
}

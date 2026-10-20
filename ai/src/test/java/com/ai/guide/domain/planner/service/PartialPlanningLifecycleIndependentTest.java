package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.OperationDeadline;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.geo.model.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Independent deadline/preview/open checks; supplier boundary is wholly local. */
class PartialPlanningLifecycleIndependentTest {
    static final String MARKER = "PLANNING_DEADLINE_EXCEEDED";
    final ObjectMapper mapper = new ObjectMapper();
    @AfterEach void clear() { UserContext.clear(); assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos()); }

    @Test void expiredDayWithoutAnyCompletedStopCannotCreateDurableSession() throws Exception {
        var repository = mock(PlannerSessionRepositoryPort.class);
        var gateway = mock(RouteGatewayPort.class);
        var builder = mock(ItineraryBuilderPort.class);
        when(gateway.planFromLocation(any(), anyInt())).thenAnswer(call -> {
            while (!OperationDeadline.expired()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
            return new LinkedHashMap<>(Map.of("days", List.of(Map.of("day", 1, "stops", List.of()))));
        });
        var service = new PlannerService(new TravelConstraintParser(), builder, repository, null, mapper, gateway,
                new PlannerPreferencesResolver(null, mapper), null);
        var timeout = PlannerService.class.getDeclaredField("requestTimeoutMs"); timeout.setAccessible(true); timeout.setLong(service, 80L);
        var request = new PlanRequest(); request.setPrompt("我在测试起点 上午8点出发 给我3小时的旅游规划");
        request.setConstraints(Map.of("destination", "测试市", "durationDays", 1));
        assertEquals(504, service.create(request).status());
        verify(repository, never()).create(any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(builder);
    }

    @Test void verifiedReplacementPreviewPreservesPartialMarkerAndNeverWritesOriginal() throws Exception {
        try (var fixture = new Fixture()) {
            var trip = fixture.partial();
            String before = mapper.writeValueAsString(trip);
            var intent = new PlanAdjustmentIntent(); intent.setType(ConversationIntentType.SUGGEST_REPLACEMENTS);
            intent.setTargetStopId(String.valueOf(stops(trip).getFirst().get("id")));
            intent.setReplacementPlaceName("替换博物馆"); intent.setRawMessage("替换这一站");
            var proposal = fixture.planner.adjust(trip, fixture.constraints, intent);
            assertNotNull(proposal);
            var options = (Map<?, ?>) proposal.get("optionPlans"); assertFalse(options.isEmpty());
            var preview = (Map<String,Object>) options.values().iterator().next();
            assertEquals(before, mapper.writeValueAsString(trip));
            assertEquals("替换博物馆", stops(preview).getFirst().get("name"));
            assertEquals("VERIFIED_AMAP", preview.get("routeDataStatus"));
            assertPartial(preview);
        }
    }

    @Test void successfulFreshRuntimeReplanCanClearOldTimeoutWithoutChangingOriginal() throws Exception {
        try (var fixture = new Fixture()) {
            var trip = fixture.partial(); String before = mapper.writeValueAsString(trip);
            var intent = new PlanAdjustmentIntent(); intent.setType(ConversationIntentType.REPLAN_DAY);
            var result = fixture.planner.adjust(trip, fixture.constraints, intent);
            var replanned = (Map<String,Object>) ((Map<?, ?>)result.get("optionPlans")).values().iterator().next();
            assertFalse(((List<?>)replanned.get("degradationReasons")).contains(MARKER));
            assertFalse(String.valueOf(((Map<?,?>)replanned.get("sourceStatus")).get("planning")).contains("超时"));
            assertFalse(stops(replanned).isEmpty());
            assertEquals(before, mapper.writeValueAsString(trip));
        }
    }

    @Test void formalOpenKeepsPartialProvenanceButUsesNewAuthoritativePlannerRevision() throws Exception {
        try (var fixture = new Fixture()) {
            var partial = fixture.partial(); partial.put("version", 7);
            var repository = mock(PlannerSessionRepositoryPort.class);
            var trips = mock(com.ai.guide.domain.trip.service.TripService.class);
            when(trips.get("owner", "formal-partial")).thenReturn(new com.ai.guide.domain.trip.model.Trip("formal-partial", "owner", "部分草稿", "ACTIVE", 3, 1, 1, partial));
            when(repository.create(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
                Map<String,Object> snapshot = call.getArgument(5);
                var session = new PlannerSessionRepositoryPort.StoredSession("new-authoritative-session", "USER", "owner", "", "", "",
                        call.getArgument(3), call.getArgument(4), snapshot, 1, 1, "DRAFT", 1, 1);
                return new PlannerSessionRepositoryPort.CreateResult("CREATED", session, "");
            });
            var open = new com.ai.guide.application.planner.PlannerOpenApplicationService(repository, trips, mapper, true);
            var result = open.openFormalTrip(new PlannerOpenActor("owner", false), "formal-partial", "independent-open");
            assertEquals(200, result.serviceStatus());
            var restored = (Map<String,Object>)result.body().get("trip");
            assertEquals("new-authoritative-session", result.body().get("sessionId"));
            assertEquals(1, restored.get("version")); assertEquals(7, restored.get("sourcePlannerRevision"));
            assertEquals(3, restored.get("sourceTripVersion")); assertEquals(7, partial.get("version"));
            assertEquals(stops(partial), stops(restored)); assertPartial(restored);
            assertEquals(true, result.body().get("degraded"));
            assertTrue(((List<?>)result.body().get("degradationReasons")).contains(MARKER));
        }
    }

    static List<Map<String,Object>> stops(Map<String,Object> trip) {
        return (List<Map<String,Object>>) ((Map<?,?>)((List<?>)trip.get("days")).getFirst()).get("stops");
    }
    static void assertPartial(Map<String,Object> trip) {
        assertTrue(((List<?>)trip.get("degradationReasons")).contains(MARKER));
        assertEquals(true, trip.get("degraded"));
        assertEquals(true, ((Map<?,?>)trip.get("sourceStatus")).get("fallback"));
        assertEquals("PARTIAL_TIMEOUT", ((Map<?,?>)trip.get("spatialPlan")).get("status"));
    }
    static class Fixture implements AutoCloseable {
        final AtomicInteger routes = new AtomicInteger(); final AtomicBoolean expire = new AtomicBoolean(true);
        final TravelConstraints constraints = new TravelConstraintParser().parse("我在测试起点 上午8点出发 给我6小时的旅游规划");
        final GeoPoiCandidate start = poi("start", "测试起点", 120);
        final List<GeoPoiCandidate> candidates = List.of(poi("museum", "测试博物馆",120.004),poi("park","测试公园",120.008),poi("other","测试老街",120.012));
        final GeoRouteSegment segment = new GeoRouteSegment("WALKING",100,60,100,0,List.of(),"120,30;120.001,30",true);
        final GeoRouteOutcome verified = new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,segment,"","local",Instant.now());
        final AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String q,String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,List.of(q.contains("替换")?poi("replacement","替换博物馆",120.016):start),"",Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l,int r,String t,String k,String c,int p) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,candidates,"",Instant.now()); }
            @Override public GeoRoutePairOutcome routePair(String o,String d,String c,String op,String dp,String pref) {
                if (routes.incrementAndGet()>1 && expire.get()) while(!OperationDeadline.expired()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                return new GeoRoutePairOutcome(verified,verified,verified,pref,false);
            }
        };
        final JsonTaxonomyAdapter taxonomy = new JsonTaxonomyAdapter();
        final RuntimeLocationPlanner planner = new RuntimeLocationPlanner(provider,new ObjectMapper(),6000,40,new DiningVenueService(null,provider,taxonomy),taxonomy);
        Map<String,Object> partial() {
            Map<String,Object> trip;
            try(var ignored=OperationDeadline.open(300)) { trip=planner.plan(constraints,1); }
            assertEquals(1,stops(trip).size()); assertPartial(trip); expire.set(false); return trip;
        }
        public void close() { provider.close(); }
        static GeoPoiCandidate poi(String id,String name,double lon) { return new GeoPoiCandidate(id,name,new GeoCoordinate(lon,30),"测试市","风景名胜",null,null,id,null,null); }
    }
}

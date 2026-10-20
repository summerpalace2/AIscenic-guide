package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.OperationDeadline;
import com.ai.guide.domain.geo.model.*;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePlanningDeadlineTest {
    @Test void emptyTimeoutCreatesNoSessionAndDoesNotFallBackToAnotherCityPlan() throws Exception {
        var parser = org.mockito.Mockito.spy(new TravelConstraintParser());
        org.mockito.Mockito.doAnswer(invocation -> {
            assertTrue(OperationDeadline.remainingNanos() < Long.MAX_VALUE);
            return invocation.callRealMethod();
        }).when(parser).parse(org.mockito.ArgumentMatchers.anyString());
        var builder = org.mockito.Mockito.mock(ItineraryBuilderPort.class);
        var repository = org.mockito.Mockito.mock(com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.class);
        var gateway = org.mockito.Mockito.mock(com.ai.guide.domain.planner.gateway.RouteGatewayPort.class);
        org.mockito.Mockito.when(gateway.planFromLocation(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    while (!OperationDeadline.expired()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    return Map.of("days", List.of());
                });
        var mapper = new ObjectMapper();
        var service = new PlannerService(parser, builder, repository, null, mapper, gateway,
                new PlannerPreferencesResolver(null, mapper), null);
        var timeout = PlannerService.class.getDeclaredField("requestTimeoutMs");
        timeout.setAccessible(true);
        timeout.setLong(service, 120L);
        var request = new com.ai.guide.domain.planner.model.PlanRequest();
        request.setPrompt("我在测试起点 上午8点出发 给我3小时的旅游规划");
        request.setConstraints(Map.of("destination", "测试市", "durationDays", 1));
        assertEquals(504, service.create(request).status());
        org.mockito.Mockito.verifyNoInteractions(builder);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos());
    }

    @Test void returnsPreviouslySelectedStopsAndExplicitPartialStatusWhenNextRouteUsesTheBudget() {
        var calls = new AtomicInteger();
        GeoPoiCandidate start = poi("start", "测试起点", 120);
        var candidates = List.of(poi("museum", "测试博物馆", 120.001), poi("park", "测试公园", 120.002), poi("other", "测试名胜", 120.003));
        var segment = new GeoRouteSegment("WALKING", 100, 60, 100, 0, List.of(), "120,30;120.001,30", true);
        var verified = new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS, segment, "", "test", Instant.now());
        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String q, String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l, int r, String t, String k, String c, int pages) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, candidates, "", Instant.now()); }
            @Override public GeoRoutePairOutcome routePair(String o, String d, String c, String op, String dp, String pref) {
                if (calls.incrementAndGet() > 1) {
                    while (!OperationDeadline.expired()) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                }
                return new GeoRoutePairOutcome(verified, verified, verified, pref, false);
            }
        };
        try {
            var taxonomy = new JsonTaxonomyAdapter();
            var planner = new RuntimeLocationPlanner(provider, new ObjectMapper(), 6000, 40,
                    new DiningVenueService(null, provider, taxonomy), taxonomy);
            var constraints = new TravelConstraintParser().parse("我在测试起点 上午8点出发 给我3小时的旅游规划");
            Map<String, Object> trip;
            try (var ignored = OperationDeadline.open(300)) { trip = planner.plan(constraints, 1); }
            var day = (Map<?, ?>) ((List<?>) trip.get("days")).get(0);
            assertEquals(1, ((List<?>) day.get("stops")).size());
            assertEquals(2, calls.get());
            assertEquals("PARTIAL_TIMEOUT", ((Map<?, ?>) trip.get("spatialPlan")).get("status"));
            assertEquals(true, trip.get("degraded"));
            assertTrue(((List<?>) trip.get("degradationReasons")).contains("PLANNING_DEADLINE_EXCEEDED"));
            assertTrue(String.valueOf(trip.get("summary")).contains("其余安排尚未完成"));
            assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos());
        } finally { provider.close(); }
    }

    private static GeoPoiCandidate poi(String id, String name, double longitude) {
        return new GeoPoiCandidate(id, name, new GeoCoordinate(longitude, 30), "测试市", "风景名胜", null, null, id, null, null);
    }
}

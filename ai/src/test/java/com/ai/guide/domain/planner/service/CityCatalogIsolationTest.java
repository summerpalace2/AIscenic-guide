package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CityCatalogIsolationTest {
    @Test
    void productionRequestWithForeignDestinationCannotUseDefaultCatalog() {
        RouteGatewayPort gateway = mock(RouteGatewayPort.class);
        var planner = planner(gateway, List.of(chongqingVenue()), true);
        var result = planner.shadow(ShadowPlanRequest.builder().prompt("2天，喜欢人文")
                .constraints(Map.of("destination", "北京市")).build());
        assertUnavailableCity(result, "北京市");
        verify(gateway, never()).planFromLocation(any(), anyInt());
    }

    @Test
    void unavailableRuntimePlanDoesNotFallbackToAnotherCity() {
        RouteGatewayPort gateway = mock(RouteGatewayPort.class);
        when(gateway.planFromLocation(any(), anyInt())).thenReturn(Map.of("days", List.of()));
        var planner = planner(gateway, List.of(chongqingVenue()), true);
        var result = planner.shadow(ShadowPlanRequest.builder().prompt("给我3小时路线")
                .constraints(Map.of("destination", "杭州", "startPlace", "测试起点", "timeBudgetMinutes", 180)).build());
        assertUnavailableCity(result, "杭州");
        verify(gateway).planFromLocation(any(), eq(1));
    }

    @Test
    void legacyRequestAlsoRespectsDestination() {
        var result = planner(mock(RouteGatewayPort.class), List.of(chongqingVenue()), false)
                .shadow(ShadowPlanRequest.builder().prompt("2天")
                        .constraints(Map.of("destination", "武汉")).build());
        assertUnavailableCity(result, "武汉");
        assertEquals("legacy-v0", result.body().get("plannerVersion"));
    }

    @Test
    void supportedCityUsesOnlyItsExplicitCatalogEntries() {
        Attraction beijing = Attraction.builder().id("bj-museum").name("北京测试博物馆").displayName("北京测试博物馆")
                .district("测试区").category("人文").duration("约 60 分钟").location("116.4,39.9")
                .summary("测试展陈").intro("测试展陈").tags(List.of("人文")).amapQuery(Map.of("city", "北京市")).build();
        AttractionService attractions = mock(AttractionService.class);
        when(attractions.list(any(), any())).thenReturn(List.of(chongqingVenue(), beijing));
        Map<?, ?> trip = itineraryBuilder(attractions).build(1, TravelConstraints.builder()
                .destination("北京").durationDays(1).arrivalAt("09:00").departureAt("18:00")
                .timeBudgetMinutes(540).interests(List.of("人文")).mustVisit(List.of("bj-museum")).build());
        List<?> days = (List<?>) trip.get("days");
        List<?> stops = (List<?>) ((Map<?, ?>) days.get(0)).get("stops");
        assertFalse(stops.isEmpty(), String.valueOf(trip));
        assertTrue(stops.stream().allMatch(stop -> "bj-museum".equals(((Map<?, ?>) stop).get("venueId"))));
        assertTrue(String.valueOf(trip.get("title")).contains("北京"));
        assertFalse(String.valueOf(trip).contains("山城") || String.valueOf(trip).contains("巴渝"), String.valueOf(trip));
    }

    @Test
    void missingDestinationNeverSelectsAnUnclassifiedCatalog() {
        AttractionService attractions = mock(AttractionService.class);
        when(attractions.list(any(), any())).thenReturn(List.of(chongqingVenue()));
        ItineraryBuilder builder = itineraryBuilder(attractions);
        for (String destination : new String[]{null, "", "未知城市", "芒市"}) {
            var trip = builder.build(1, TravelConstraints.builder().destination(destination).durationDays(1).build());
            assertTrue(((List<?>) trip.get("days")).stream().allMatch(day -> ((List<?>) ((Map<?, ?>) day).get("stops")).isEmpty()));
            assertEquals(List.of("CITY_CATALOG_UNAVAILABLE"), trip.get("degradationReasons"));
            if ("芒市".equals(destination)) assertTrue(String.valueOf(trip.get("title")).startsWith("芒市"));
        }
    }

    private PlannerService planner(RouteGatewayPort gateway, List<Attraction> catalog, boolean v1) {
        AttractionService attractions = mock(AttractionService.class);
        when(attractions.list(any(), any())).thenReturn(catalog);
        return new PlannerService(new TravelConstraintParser(), itineraryBuilder(attractions),
                mock(PlannerSessionRepositoryPort.class), mock(TripService.class), new ObjectMapper(), gateway,
                new PlannerPreferencesResolver(null, new ObjectMapper()), mock(PlanAdjustmentService.class),
                new PlannerMetricsLogger(), v1);
    }

    private ItineraryBuilder itineraryBuilder(AttractionService attractions) {
        var verifier = new PlanVerifier();
        var repairer = com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer();
        return new ItineraryBuilder(attractions, new RouteAwarePlanner(new RouteCostProvider(null), verifier, repairer,
                new com.ai.guide.domain.planner.service.PlannerMetricsLogger(), null,
                new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()),
                verifier, repairer, new PlanNarrativeService(), mock(com.ai.guide.domain.attraction.service.AttractionAliasMatcher.class));
    }

    private Attraction chongqingVenue() {
        return Attraction.builder().id("cq-museum").name("三峡博物馆").displayName("三峡博物馆")
                .district("渝中区").category("人文").duration("约 60 分钟").location("106.5,29.5")
                .summary("重庆测试展陈").intro("重庆测试展陈").tags(List.of("人文")).build();
    }

    private void assertUnavailableCity(PlannerService.ServiceResult result, String city) {
        assertEquals(200, result.status());
        Map<?, ?> trip = (Map<?, ?>) result.body().get("trip");
        assertEquals(city, trip.get("city"));
        assertTrue(((List<?>) trip.get("days")).stream().allMatch(day -> ((List<?>) ((Map<?, ?>) day).get("stops")).isEmpty()));
        assertFalse(String.valueOf(trip).contains("cq-museum") || String.valueOf(trip.get("title")).contains("重庆"));
        assertEquals("UNAVAILABLE", result.body().get("routeDataStatus"));
        assertEquals(true, result.body().get("degraded"));
        assertTrue(((List<?>) trip.get("degradationReasons")).contains("CITY_CATALOG_UNAVAILABLE"));
        if (!"legacy-v0".equals(result.body().get("plannerVersion"))) {
            assertTrue(((List<?>) result.body().get("degradationReasons")).contains("CITY_CATALOG_UNAVAILABLE"));
        }
    }
}

package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeoAndMealGapRegressionTest {
    private static final MealWindow LUNCH = new MealWindow(690, 810, 720, "12:00");

    @Test
    void cityMetadataIsAuthoritativeEvenWhenRestaurantNameContainsAnotherCity() {
        var intent = com.ai.guide.domain.planner.model.PlanAdjustmentIntent.builder().requestedVenueName("重庆火锅").build();
        assertEquals("北京市", PlanAdjustmentHelper.resolveTripCity(Map.of("city", "北京市"), null, intent));
        assertEquals("杭州", PlanAdjustmentHelper.resolveTripCity(Map.of(), TravelConstraints.builder().destination("杭州").build()));
        assertNull(PlanAdjustmentHelper.resolveTripCity(Map.of(), (TravelConstraints) null));
        assertEquals("", PlanAdjustmentHelper.extractDistrictFromStop(Map.of("name", "未知景点")));
    }

    @Test
    void unknownAnchorDoesNotInventCoordinateOrDistrict() {
        Attraction anchor = PlanAdjustmentHelper.resolveStopAnchor(Map.of("name", "未知景点"), null);
        assertNull(anchor.getLocation());
        assertEquals("", anchor.getDistrict());
        assertNull(PlanAdjustmentHelper.resolveDefaultCoordinate(Map.of(), null, List.of(Map.of("location", "NaN,NaN")), 50_000.0));
        assertFalse(PlanAdjustmentHelper.isValidCoordinate("116.3,39.9,unexpected"));
        assertFalse(PlanAdjustmentHelper.isValidCoordinate("Infinity,39"));
    }

    @Test
    void localCentroidAvoidsProviderAndColdStartUsesRequestedCity() {
        GeoRouteGatewayPort gateway = mock(GeoRouteGatewayPort.class);
        when(gateway.isConfigured()).thenReturn(true);
        var poi = new GeoPoiCandidate("gov", "杭州市政府", new GeoCoordinate(120.2, 30.2), "杭州", "政府", null, null, null, null, null);
        when(gateway.searchPoi("杭州市政府", "杭州")).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(poi), "", Instant.now()));
        var resolver = new DynamicGeoCentroidResolver(gateway);
        assertEquals("120.200000,30.200000", resolver.resolve(Map.of("city", "杭州"), null, List.of()));
        verify(gateway).searchPoi("杭州市政府", "杭州");
        clearInvocations(gateway);
        assertEquals("120.210000,30.210000", resolver.resolve(Map.of("city", "杭州"), null,
                List.of(Map.of("location", "120.2,30.2"), Map.of("location", "120.22,30.22"))));
        verifyNoInteractions(gateway);
    }

    @Test
    void coldStartProviderFailureAndInvalidResultRemainUnresolved() {
        GeoRouteGatewayPort gateway = mock(GeoRouteGatewayPort.class);
        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchPoi(anyString(), anyString())).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.TIMEOUT, List.of(), "timeout", Instant.now()));
        assertNull(new DynamicGeoCentroidResolver(gateway).resolveCityCenter("武汉市"));
        var poison = new GeoPoiCandidate("gov", "政府", new GeoCoordinate(Double.NaN, 30), "", "", null, null, null, null, null);
        when(gateway.searchPoi(anyString(), anyString())).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(poison), "", Instant.now()));
        assertNull(new DynamicGeoCentroidResolver(gateway).resolveCityCenter("西安市"));
    }

    @Test
    void fitAccountsForBothTravelEdgesAndKeepsSourceTimes() {
        RouteCostProvider costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(500, 10, 500, "WALKING", "test"));
        var stops = List.of(stop("morning", "10:30", "60分钟"), stop("afternoon", "13:00", "60分钟"));
        var gap = new MealGapSolver(costs, new ItineraryTimeEngine()).findGap(stops, meal(), 60, LUNCH, "walking", null).orElseThrow();
        assertEquals(1, gap.index());
        assertEquals(710, gap.startMinute());
        assertEquals(10, gap.inboundMinutes());
        assertEquals(10, gap.outboundMinutes());
        assertEquals("13:00", stops.get(1).get("time"));
    }

    @Test
    void fullLunchOccupancyAndInsufficientTravelGapAreInfeasible() {
        RouteCostProvider costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(1000, 20, 1000, "WALKING", "test"));
        var solver = new MealGapSolver(costs, new ItineraryTimeEngine());
        assertTrue(solver.findGap(List.of(stop("busy", "11:00", "180分钟")), meal(), 60, LUNCH, "walking", null).isEmpty());
        assertTrue(solver.findGap(List.of(stop("before", "10:30", "60分钟"), stop("after", "12:50", "180分钟")),
                meal(), 60, LUNCH, "walking", null).isEmpty());
    }

    @Test
    void prefersGapWithLowerAdjacentTravel() {
        RouteCostProvider costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenAnswer(invocation -> {
            Attraction from = invocation.getArgument(0);
            Attraction to = invocation.getArgument(1);
            int minutes = "far".equals(from.getId()) || "far".equals(to.getId()) ? 30 : 5;
            return RouteCost.estimated(500, minutes, 500, "WALKING", "test");
        });
        var stops = List.of(stop("far", "09:30", "60分钟"), stop("near", "12:40", "10分钟"));
        var gap = new MealGapSolver(costs, new ItineraryTimeEngine()).findGap(stops, meal(), 30, LUNCH, "walking", null).orElseThrow();
        assertEquals(2, gap.index());
        assertEquals(775, gap.startMinute());
    }

    @Test
    void invalidClockMissingCoordinateAndUnavailableTravelDoNotProduceFakeSchedules() {
        RouteCostProvider costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.unavailable("missing"));
        var solver = new MealGapSolver(costs, new ItineraryTimeEngine());
        assertTrue(solver.findGap(List.of(stop("bad", "待安排", "60分钟")), meal(), 60, LUNCH, "walking", null).isEmpty());
        assertTrue(solver.findGap(List.of(stop("bad", "25:00", "60分钟")), meal(), 60, LUNCH, "walking", null).isEmpty());
        assertTrue(solver.findGap(List.of(stop("known", "10:00", "60分钟")), meal(), 60, LUNCH, "walking", null).isEmpty());
        assertTrue(solver.findGap(List.of(), Attraction.builder().id("unknown").build(), 60, LUNCH, "walking", null).isEmpty());
    }

    @Test
    void hourDurationOccupiesTheWholeVisitAndUnknownDurationIsUnresolved() {
        RouteCostProvider costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(500, 10, 500, "WALKING", "test"));
        var solver = new MealGapSolver(costs, new ItineraryTimeEngine());
        assertTrue(solver.findGap(List.of(stop("long", "11:00", "2小时30分钟")), meal(), 60, LUNCH, "walking", null).isEmpty());
        assertTrue(solver.findGap(List.of(stop("unknown", "11:00", "待确认")), meal(), 60, LUNCH, "walking", null).isEmpty());
    }

    private Map<String, Object> stop(String id, String time, String duration) {
        return Map.of("venueId", id, "name", id, "time", time, "duration", duration, "location", "116.3,39.9");
    }

    private Attraction meal() {
        return Attraction.builder().id("meal").name("餐厅").location("116.31,39.91").build();
    }
}

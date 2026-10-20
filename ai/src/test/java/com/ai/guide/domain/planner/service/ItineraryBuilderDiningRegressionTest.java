package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItineraryBuilderDiningRegressionTest {
    @Test
    void afternoonOnlyInitialPlanDoesNotInventLunchBeforeTheDayBegins() {
        var dining = mock(DiningVenueService.class);
        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(option());
        var trip = builder(List.of(stop("afternoon", "14:30", "60分钟")), dining)
                .build(1, TravelConstraints.builder().durationDays(1).build());
        var stops = PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(trip, 1));
        assertEquals(1, stops.size());
        assertEquals("14:30", stops.get(0).get("time"));
        verify(dining, never()).resolveNearbyDining(any(), eq("LUNCH"), any(), any());
    }

    @Test
    void departureLabelKeepsExistingRouteEvidenceAndDoesNotProjectOtherCityLandmarks() {
        var first = stop("morning", "10:30", "60分钟");
        Map<String, Object> routeEvidence = Map.of("selected", Map.of("durationSeconds", 600, "distanceMeters", 500), "summary", "已有估算");
        first.put("routeFromPrevious", routeEvidence);
        var trip = builder(List.of(first), mock(DiningVenueService.class)).build(1,
                TravelConstraints.builder().durationDays(1).destination("武汉").startPlace("大学城").build());
        var day = PlanAdjustmentHelper.findDay(trip, 1);
        assertEquals(routeEvidence, PlanAdjustmentHelper.dayStops(day).get(0).get("routeFromPrevious"));
        assertFalse(String.valueOf(day.get("departureContext")).contains("磁器口"));
        var withoutEvidence = builder(List.of(stop("morning", "10:30", "60分钟")), mock(DiningVenueService.class)).build(1,
                TravelConstraints.builder().durationDays(1).destination("武汉").startPlace("大学城").build());
        var route = (Map<?, ?>) PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(withoutEvidence, 1)).get(0).get("routeFromPrevious");
        assertFalse(route.containsKey("selected"));
        assertTrue(String.valueOf(route.get("summary")).contains("待核验"));
    }

    @Test
    void diningFactoryDoesNotInventDistrictOrRouteOrOpeningHours() {
        var builder = builder(List.of(), mock(DiningVenueService.class));
        var stop = builder.createDiningStop(option(), "meal", "12:00", null);
        assertEquals("", stop.get("district"));
        assertEquals("UNAVAILABLE", stop.get("routeDataStatus"));
        assertFalse(stop.containsKey("routeFromPrevious"));
        assertEquals("营业时间待核验", stop.get("bestTime"));
    }

    @Test
    void initialBuildUsesAvailableGapAndRetainsAdjacentVisitTimesAndRouteEstimate() {
        var dining = mock(DiningVenueService.class);
        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(option());
        var original = List.of(stop("morning", "10:30", "60分钟"), stop("afternoon", "13:00", "60分钟"));
        var trip = builder(original, dining).build(1, TravelConstraints.builder().durationDays(1).destination("北京").build());
        var stops = PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(trip, 1));
        assertEquals(3, stops.size());
        assertEquals("10:30", stops.get(0).get("time"));
        assertEquals("11:50", stops.get(1).get("time"));
        assertEquals("13:00", stops.get(2).get("time"));
        assertEquals(600L, ((Map<?, ?>) stops.get(1).get("resolvedRouteCost")).get("durationSeconds"));
        assertEquals(600L, ((Map<?, ?>) stops.get(2).get("resolvedRouteCost")).get("durationSeconds"));
    }

    @Test
    void fullyBookedLunchAndPlaceholderPoiDoNotBecomeInsertedMeals() {
        var dining = mock(DiningVenueService.class);
        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(option());
        var builder = builder(List.of(stop("busy", "11:00", "180分钟")), dining);
        var trip = builder.build(1, TravelConstraints.builder().durationDays(1).build());
        var day = PlanAdjustmentHelper.findDay(trip, 1);
        assertEquals(1, PlanAdjustmentHelper.dayStops(day).size());
        assertTrue(((List<?>) day.get("diningDiagnostics")).contains("LUNCH:NO_FEASIBLE_MEAL_GAP"));
        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(new com.ai.guide.domain.dining.model.DiningOption(
                "dining-fallback-default", "附近用餐（待确认）", "待确认", "LUNCH", "待确认", "60分钟", "待确认", "未核验", "待确认", "gold", null));
        var unresolved = builder(List.of(stop("morning", "10:30", "60分钟")), dining)
                .build(1, TravelConstraints.builder().durationDays(1).build());
        assertEquals(1, PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(unresolved, 1)).size());

        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(null);
        var unavailable = builder(List.of(stop("morning", "10:30", "60分钟")), dining)
                .build(1, TravelConstraints.builder().durationDays(1).build());
        var unavailableDay = PlanAdjustmentHelper.findDay(unavailable, 1);
        assertEquals(1, PlanAdjustmentHelper.dayStops(unavailableDay).size());
        assertTrue(((List<?>) unavailableDay.get("diningDiagnostics")).contains("LUNCH:DINING_POI_UNRESOLVED"));
    }

    @Test
    void existingLunchIsNotDuplicatedAndExplicitDepartureLimitsMealWindow() {
        var dining = mock(DiningVenueService.class);
        when(dining.resolveNearbyDining(any(), eq("LUNCH"), any(), any())).thenReturn(option());
        var lunch = stop("existing", "12:00", "60分钟");
        lunch.put("type", "DINING");
        var trip = builder(List.of(stop("morning", "10:00", "60分钟"), lunch), dining)
                .build(1, TravelConstraints.builder().durationDays(1).departureAt("13:00").build());
        assertEquals(2, PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(trip, 1)).size());
        verify(dining, never()).resolveNearbyDining(any(), eq("LUNCH"), any(), any());
        var early = builder(List.of(stop("morning", "10:30", "60分钟")), dining)
                .build(1, TravelConstraints.builder().durationDays(1).departureAt("12:00").build());
        assertEquals(1, PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(early, 1)).size());
    }

    private ItineraryBuilder builder(List<Map<String, Object>> stops, DiningVenueService dining) {
        var service = mock(AttractionService.class);
        var catalog = new ArrayList<Attraction>();
        for (Map<String, Object> stop : stops) {
            Attraction attraction = Attraction.builder().id(String.valueOf(stop.get("venueId")))
                    .name(String.valueOf(stop.get("name"))).location("116.3,39.9").district("海淀区").duration(String.valueOf(stop.get("duration"))).build();
            catalog.add(attraction);
            when(service.get(attraction.getId())).thenReturn(attraction);
        }
        when(service.list(null, null)).thenReturn(catalog);
        var routes = mock(RouteAwarePlanner.class);
        Map<String, Object> day = new LinkedHashMap<>(Map.of("day", 1, "stops", new ArrayList<>(stops)));
        when(routes.planDays(anyInt(), any(), anyMap(), anyList(), any())).thenReturn(new ArrayList<>(List.of(day)));
        var builder = new ItineraryBuilder(service, routes, new PlanVerifier(), com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer(),
                mock(PlanNarrativeService.class), mock(AttractionAliasMatcher.class));
        var taxonomy = mock(TaxonomyPort.class);
        when(taxonomy.getMealWindow("LUNCH")).thenReturn(new com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow(690, 810, 720, "12:00"));
        when(taxonomy.getMealWindow("DINNER")).thenReturn(new com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow(1050, 1170, 1080, "18:00"));
        var costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(500, 10, 500, "WALKING", "test"));
        builder.setDiningScheduling(dining, taxonomy, new MealGapSolver(costs, new ItineraryTimeEngine()));
        return builder;
    }

    private Map<String, Object> stop(String id, String time, String duration) {
        return new LinkedHashMap<>(Map.of("id", id, "venueId", id, "name", id, "time", time, "duration", duration, "location", "116.3,39.9"));
    }

    private com.ai.guide.domain.dining.model.DiningOption option() {
        return new com.ai.guide.domain.dining.model.DiningOption("real-poi", "【午餐推荐】食肆", "待确认", "LUNCH", "待确认", "60分钟", "待确认", "餐厅", "测试", "gold", "116.31,39.91");
    }
}

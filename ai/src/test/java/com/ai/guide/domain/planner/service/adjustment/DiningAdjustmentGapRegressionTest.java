package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.dining.model.DiningOption;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiningAdjustmentGapRegressionTest {
    @Test
    void longStructuredDiningRequestWithTwoLandmarksDoesNotBecomeAScenicRouteAddition() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(option()));
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1)
                .rawMessage("上午先逛李子坝，再到鹅岭二厂，我只想增加驴肉火烧作为午餐，不要更改景点")
                .cuisineOrPlace("驴肉火烧").genericDiningCraving(true).timeSlot("LUNCH").build();
        var context = context(List.of(stop("morning", "10:00", "60分钟")), intent);
        strategy(dining).apply(context);
        assertTrue(context.isFeasible());
        assertEquals("DINING_ADD", context.getAdjustmentDiagnostics().get("replacementMode"));
        var stops = PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(context.getDraftTrip(), 1));
        assertEquals(2, stops.size());
        assertEquals("morning", stops.get(0).get("venueId"));
        assertEquals("DINING", stops.get(1).get("type"));
        verify(dining).resolveDiningReplacementCandidates(anyString(), anyString(), eq("驴肉火烧"), eq("LUNCH"), any(), any());
    }

    @Test
    void structuredLunchSlotOverridesRawBreakfastWordWhenLocatingMealTarget() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(option()));
        var breakfast = stop("breakfast", "08:00", "45分钟");
        breakfast.put("type", "DINING");
        breakfast.put("name", "【早餐推荐】旧早餐");
        var lunch = stop("lunch", "12:00", "45分钟");
        lunch.put("type", "DINING");
        lunch.put("name", "【午餐推荐】旧午餐");
        var dinner = stop("dinner", "18:00", "45分钟");
        dinner.put("type", "DINING");
        dinner.put("name", "【晚餐推荐】旧晚餐");
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.SUGGEST_REPLACEMENTS).dayNumber(1)
                .rawMessage("早餐我已经吃过，请换成驴肉火烧").cuisineOrPlace("驴肉火烧").genericDiningCraving(true).timeSlot("LUNCH").build();
        var context = context(List.of(breakfast, lunch, dinner), intent);
        var builder = mock(ItineraryBuilderPort.class);
        when(builder.createDiningStop(any(), anyString(), anyString(), anyString())).thenAnswer(invocation ->
                new LinkedHashMap<>(Map.of("id", invocation.getArgument(1), "name", "【午餐推荐】新餐厅", "type", "DINING", "time", invocation.getArgument(2))));
        var taxonomy = new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
        var grounder = new DiningCategoryGrounder(taxonomy);
        var costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(500, 10, 500, "WALKING", "test"));
        var strategy = new ReplaceStopAdjustmentStrategy(mock(AttractionService.class), builder, mock(RouteAwarePlanner.class),
                new PlanVerifier(), mock(CandidateRankingEngine.class), new ItineraryTimeEngine(), grounder);
        strategy.setDiningVenueService(dining);
        strategy.setDiningScheduling(taxonomy, new MealGapSolver(costs, new ItineraryTimeEngine()), null);
        strategy.apply(context);
        assertTrue(context.isFeasible());
        assertEquals("【午餐推荐】旧午餐", context.getAdjustmentDiagnostics().get("sourceStopName"));
        var stops = PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(context.getDraftTrip(), 1));
        assertEquals(3, stops.size());
        assertEquals("08:00", stops.get(0).get("time"));
        assertEquals("breakfast", stops.get(0).get("id"));
        assertEquals("lunch", stops.get(1).get("id"));
        assertEquals("【午餐推荐】新餐厅", stops.get(1).get("name"));
    }

    @Test
    void unrelatedProviderRestaurantDoesNotSatisfySpecificVenueRequest() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.searchLiveDiningOrTea(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn(List.of(
                com.ai.guide.domain.dining.model.DiningVenue.builder().id("real-id").name("其他品牌餐厅").location("116.3,39.9").build()));
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1)
                .cuisineOrPlace("目标餐厅").genericDiningCraving(false).timeSlot("LUNCH").build();
        var context = context(List.of(), intent);
        strategy(dining).apply(context);
        assertFalse(context.isFeasible());
        assertTrue(context.getReasonCodes().contains("DINING_POI_UNRESOLVED"));
        assertTrue(context.getCandidateReplacements().isEmpty());
        assertTrue(PlanAdjustmentHelper.matchesRequestedDiningVenue("目标餐厅", "目标餐厅（商场店）"));
    }

    @Test
    void directRestaurantMissNeverCreatesAClaimedVerifiedRestaurant() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.searchLiveDiningOrTea(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString())).thenReturn(List.of());
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1)
                .cuisineOrPlace("未知餐厅").genericDiningCraving(false).timeSlot("LUNCH").build();
        var context = context(List.of(), intent);
        strategy(dining).apply(context);
        assertFalse(context.isFeasible());
        assertTrue(context.getReasonCodes().contains("DINING_POI_UNRESOLVED"));
        assertTrue(context.getOptionPlans().isEmpty());
        assertTrue(context.getCandidateReplacements().isEmpty());
        verify(dining).searchLiveDiningOrTea(eq("北京市"), eq("116.300000,39.900000"), eq("未知餐厅"), eq("LUNCH"), eq(1), eq("北京市"));
    }

    @Test
    void structuredCuisineWithoutTextualFoodCueStillRoutesToDiningAndPreservesAppointments() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(option()));
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1)
                .targetStopId("morning").cuisineOrPlace("驴肉火烧").genericDiningCraving(true).timeSlot("LUNCH").build();
        var context = context(List.of(stop("morning", "10:30", "60分钟"), stop("afternoon", "13:00", "60分钟")), intent);
        strategy(dining).apply(context);
        assertTrue(context.isFeasible());
        assertEquals(1, context.getOptionPlans().size());
        var stops = PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(context.getDraftTrip(), 1));
        assertEquals(3, stops.size());
        assertEquals("10:30", stops.get(0).get("time"));
        assertEquals("13:00", stops.get(2).get("time"));
        assertEquals("DINING", stops.get(1).get("type"));
        assertEquals(600L, ((Map<?, ?>) stops.get(1).get("resolvedRouteCost")).get("durationSeconds"));
        assertEquals(600L, ((Map<?, ?>) stops.get(2).get("resolvedRouteCost")).get("durationSeconds"));
        assertEquals(2, PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(context.getSourceTrip(), 1)).size());
    }

    @Test
    void noMealGapReturnsInfeasibleWithoutCascadingVisits() {
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of(option()));
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).dayNumber(1)
                .cuisineOrPlace("螺蛳粉").genericDiningCraving(true).timeSlot("LUNCH").build();
        var context = context(List.of(stop("busy", "11:00", "180分钟")), intent);
        strategy(dining).apply(context);
        assertFalse(context.isFeasible());
        assertTrue(context.getReasonCodes().contains("NO_FEASIBLE_MEAL_GAP"));
        assertTrue(context.getOptionPlans().isEmpty());
        assertEquals("11:00", PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.findDay(context.getDraftTrip(), 1)).get(0).get("time"));
    }

    private AddStopAdjustmentStrategy strategy(DiningVenueService dining) {
        var builder = mock(ItineraryBuilderPort.class);
        when(builder.createDiningStop(any(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            DiningOption option = invocation.getArgument(0);
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("id", invocation.getArgument(1));
            stop.put("time", invocation.getArgument(2));
            stop.put("name", option.name());
            stop.put("type", "DINING");
            return stop;
        });
        var grounder = mock(DiningCategoryGrounder.class);
        when(grounder.resolveSlotType(any(PlanAdjustmentIntent.class))).thenReturn("LUNCH");
        when(grounder.resolveRequestedFlavor(any(PlanAdjustmentIntent.class), anyString())).thenAnswer(invocation ->
                ((PlanAdjustmentIntent) invocation.getArgument(0)).getCuisineOrPlace());
        when(grounder.isGenericDiningCraving(any(PlanAdjustmentIntent.class), any())).thenAnswer(invocation ->
                ((PlanAdjustmentIntent) invocation.getArgument(0)).getGenericDiningCraving());
        var taxonomy = mock(TaxonomyPort.class);
        when(taxonomy.getMealWindow("LUNCH")).thenReturn(new com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow(690, 810, 720, "12:00"));
        var center = mock(DynamicGeoCentroidResolver.class);
        when(center.resolve(any(), any(), any())).thenReturn("116.300000,39.900000");
        var costs = mock(RouteCostProvider.class);
        when(costs.calculate(any(), any(), any())).thenReturn(RouteCost.estimated(500, 10, 500, "WALKING", "test"));
        var times = new ItineraryTimeEngine();
        var strategy = new AddStopAdjustmentStrategy(mock(AttractionService.class), builder,
                mock(RouteAwarePlanner.class), mock(CandidateRankingEngine.class), times, grounder);
        strategy.setDiningVenueService(dining);
        strategy.setDiningScheduling(taxonomy, new MealGapSolver(costs, times), center);
        return strategy;
    }

    private AdjustmentContext context(List<Map<String, Object>> stops, PlanAdjustmentIntent intent) {
        Map<String, Object> trip = new LinkedHashMap<>(Map.of("city", "北京市", "days",
                new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day", 1, "stops", new ArrayList<>(stops)))))));
        var mapper = new ObjectMapper();
        return new AdjustmentContext(null, intent, PlanPageContext.builder().activeDay(1).build(), trip,
                PlanAdjustmentHelper.deepCopy(trip, mapper), TravelConstraints.builder().destination("北京市").build(), new LinkedHashMap<>(), mapper);
    }

    private Map<String, Object> stop(String id, String time, String duration) {
        return new LinkedHashMap<>(Map.of("id", id, "venueId", id, "name", id, "time", time, "duration", duration, "location", "116.3,39.9"));
    }

    private DiningOption option() {
        return new DiningOption("real-poi", "【午餐推荐】食肆", "待确认", "LUNCH", "待确认", "60分钟",
                "待核验", "餐厅", "测试候选", "gold", "116.31,39.91");
    }
}

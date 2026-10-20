package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MultiStopTimingRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void multiAdditionUsesActual37MinuteEdgeAndFull90MinuteVisits() {
        var fixture = fixture(false);
        var ctx = context(fixture, "晚上18:00加入测试广场和测试码头", 1, List.of(day(1)));
        fixture.strategy.apply(ctx);
        var stops = stops(ctx.getDraftTrip(), 0);
        assertEquals(2, stops.size());
        assertEquals("18:00", stops.get(0).get("time"));
        assertEquals("90分钟", stops.get(0).get("duration"));
        assertEquals("20:07", stops.get(1).get("time"));
        assertEquals("90分钟", stops.get(1).get("duration"));
        assertEquals("约 180 分钟", ctx.getCandidateReplacements().get(0).get("duration"));
        assertTrue(new PlanVerifier().verify(PlanAdjustmentHelper.days(ctx.getDraftTrip()), ctx.getConstraints()).feasible());
    }

    @Test void insertingAfterExisting90MinuteVisitDoesNotShortenItsOccupiedTime() {
        var fixture = fixture(false);
        var anchor = new LinkedHashMap<String,Object>(Map.of("id", "anchor", "venueId", "anchor", "name", "测试公园",
                "time", "18:00", "duration", "90分钟", "district", "中心区"));
        var d = day(1); d.put("stops", new ArrayList<>(List.of(anchor)));
        var ctx = context(fixture, "在测试公园之后接上测试广场和测试码头", 1, List.of(d));
        fixture.strategy.apply(ctx);
        var stops = stops(ctx.getDraftTrip(), 0);
        assertEquals("90分钟", stops.get(0).get("duration"));
        assertEquals("20:07", stops.get(1).get("time"));
        assertEquals("22:14", stops.get(2).get("time"));
    }

    @Test void lateMultiAdditionIsInfeasibleInsteadOfSilentlyShorteningVisits() {
        var fixture = fixture(false);
        var ctx = context(fixture, "晚上22:00加入测试广场和测试码头", 1, List.of(day(1)));
        fixture.strategy.apply(ctx);
        var stops = stops(ctx.getDraftTrip(), 0);
        assertEquals("90分钟", stops.get(0).get("duration"));
        assertEquals("00:07(+1d)", stops.get(1).get("time"));
        assertFalse(ctx.isFeasible());
        assertTrue(ctx.getReasonCodes().contains("MIDNIGHT_OVERFLOW"));
    }

    @Test void anUnanchoredAdditionRemainsUnscheduledInsteadOfInventingAnEveningClock() {
        var fixture = fixture(true);
        var ctx = context(fixture, "加入测试广场和测试码头", 1, List.of(day(1)));
        fixture.strategy.apply(ctx);
        var stops = stops(ctx.getDraftTrip(), 0);
        assertEquals(2, stops.size());
        assertNull(stops.get(0).get("time"));
        assertNull(stops.get(0).get("startTime"));
        assertNull(stops.get(1).get("time"));
    }

    @Test void repairedDayCopyIsConsumedAndInteriorDayDoesNotInheritArrivalOrDepartureBoundary() {
        var fixture = fixture(true);
        var ctx = context(fixture, "晚上18:00加入测试广场和测试码头", 2, List.of(day(1), day(2), day(3)));
        ctx.getConstraints().setArrivalAt("22:00");
        ctx.getConstraints().setDepartureAt("10:00");
        when(fixture.router.resolveAndVerifyDays(anyList(), anyMap(), any())).thenAnswer(call -> {
            TravelConstraints local = call.getArgument(2);
            assertEquals("未提供", local.getArrivalAt());
            assertEquals("未提供", local.getDepartureAt());
            List<Map<String,Object>> days = call.getArgument(0);
            var repaired = new LinkedHashMap<>(days.get(0));
            repaired.put("repairMarker", "returned-copy");
            return new ArrayList<>(List.of(repaired));
        });
        fixture.strategy.apply(ctx);
        assertEquals("returned-copy", PlanAdjustmentHelper.days(ctx.getDraftTrip()).get(1).get("repairMarker"));
    }

    @Test void requiredTargetsSurviveRepairDeletingSingleAlternativeWhileOnlyOldTimingChanges() {
        var fixture = fixture(true);
        var anchor = new LinkedHashMap<String,Object>(Map.of("id", "anchor", "venueId", "anchor", "name", "测试公园",
                "time", "10:00", "duration", "90分钟", "district", "中心区"));
        var d = day(1); d.put("stops", new ArrayList<>(List.of(anchor)));
        var ctx = context(fixture, "在测试公园之后接上测试广场和测试码头", 1, List.of(d));
        when(fixture.router.resolveAndVerifyDays(anyList(), anyMap(), any())).thenAnswer(call -> {
            List<Map<String,Object>> input = call.getArgument(0);
            var repaired = new LinkedHashMap<>(input.get(0));
            var preserved = new LinkedHashMap<>(PlanAdjustmentHelper.dayStops(input.get(0)).get(0));
            preserved.put("time", "10:45");
            repaired.put("stops", new ArrayList<>(List.of(preserved)));
            return new ArrayList<>(List.of(repaired));
        });
        fixture.strategy.apply(ctx);
        var single = stops(ctx.getOptionPlans().get("option-2"), 0);
        assertEquals(1, single.size());
        assertEquals("anchor", single.get(0).get("id"));
        assertEquals("10:45", single.get(0).get("time"), "Changing an old clock must not substitute for adding the target");
        assertEquals(List.of("day1-added-test-a"), ctx.getRequiredStopIdsByOption().get("option-2"));
        assertEquals(List.of("day1-added-test-a", "day1-added-test-b"), ctx.getRequiredStopIdsByOption().get("option-1"));
        assertEquals("10:00", anchor.get("time"));
        assertFalse(ctx.getOptionPlans().get("option-2").containsKey("requiredStopIdsByOption"));
    }

    private Fixture fixture(boolean mockRouter) {
        var a = Attraction.builder().id("test-a").location("116.30,39.90").name("测试广场").duration("90分钟").district("中心区").summary("开放广场").intro("介绍").build();
        var b = Attraction.builder().id("test-b").location("116.31,39.91").name("测试码头").duration("90分钟").district("中心区").summary("滨水步行").intro("介绍").build();
        var anchor = Attraction.builder().id("anchor").location("116.32,39.92").name("测试公园").duration("90分钟").district("中心区").build();
        var byId = new LinkedHashMap<String,Attraction>();
        var service = mock(AttractionService.class);
        when(service.list(null, null)).thenReturn(List.of(a, b, anchor));
        when(service.list(eq("测试城"), isNull(), isNull())).thenReturn(List.of(a, b, anchor));
        for (var v : List.of(a,b,anchor)) { byId.put(v.getId(),v); when(service.get(v.getId())).thenReturn(v); }
        var builder = mock(ItineraryBuilderPort.class);
        when(builder.createStop(any(), anyString(), nullable(String.class), anyString(), anyString(), nullable(String.class))).thenAnswer(call -> {
            Attraction v = call.getArgument(0);
            var stop = new LinkedHashMap<String,Object>();
            stop.put("id", call.getArgument(1));
            stop.put("venueId", v.getId());
            stop.put("name", v.getName());
            stop.put("time", call.getArgument(2));
            stop.put("duration", call.getArgument(3));
            stop.put("district", v.getDistrict());
            stop.put("location", v.getLocation());
            return stop;
        });
        var costs = mock(RouteCostProvider.class);
        when(costs.resolveFinalEdge(any(), any(), anyString())).thenReturn(new RouteCost(Duration.ofMinutes(37), 1000, 0,
                "TRANSIT", RouteCost.RouteDataStatus.ESTIMATED, "隔离测试"));
        var router = mockRouter ? mock(RouteAwarePlanner.class) : new RouteAwarePlanner(costs, new PlanVerifier(), com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer());
        var strategy = new AddStopAdjustmentStrategy(service, builder, router, new CandidateRankingEngine(costs),
                new ItineraryTimeEngine(), mock(DiningCategoryGrounder.class));
        return new Fixture(strategy, router, byId);
    }
    private AdjustmentContext context(Fixture f, String raw, int activeDay, List<Map<String,Object>> days) {
        var source = new LinkedHashMap<String,Object>(); source.put("days", new ArrayList<>(days)); source.put("city", "测试城");
        return new AdjustmentContext(null, PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).rawMessage(raw).dayNumber(activeDay).build(),
                PlanPageContext.builder().activeDay(activeDay).build(), source, mapper.convertValue(source, Map.class),
                new TravelConstraints(), f.byId, mapper);
    }
    private Map<String,Object> day(int n) {
        return new LinkedHashMap<>(Map.of("day", n, "date", "2026-10-0"+(6+n), "stops", new ArrayList<>()));
    }
    private List<Map<String,Object>> stops(Map<String,Object> trip, int dayIndex) {
        return PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.days(trip).get(dayIndex));
    }
    private record Fixture(AddStopAdjustmentStrategy strategy, RouteAwarePlanner router, Map<String,Attraction> byId) {}
}

package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlanningRouteRepairRegressionTest {
    private Map<String,Object> stop(String id,String time,int duration) {
        var s=new LinkedHashMap<String,Object>();s.put("id",id);s.put("venueId",id);s.put("name",id);
        s.put("time",time);s.put("duration",duration+"分钟");s.put("district","同一区");s.put("location","116.3,39.9");return s;
    }
    private Map<String,Object> day(int number,List<Map<String,Object>> stops) {
        var d=new LinkedHashMap<String,Object>();d.put("day",number);d.put("date","2026-10-07");d.put("stops",new ArrayList<>(stops));return d;
    }
    @Test void routeSecondsRoundUpAndRejectOverlap() {
        var times=new ItineraryTimeEngine();
        for(int seconds:new int[]{0,1,59,60,61,90}) {
            var a=stop("A","10:00",30);var b=stop("B","10:31",30);
            var cost=RouteCost.verifiedAmap(100,seconds,0,"WALKING");b.put("resolvedRouteCost",cost.asMap());
            assertEquals((int)Math.ceil(seconds/60.0),times.transferMinutes(a,b));
        }
        var a=stop("A","10:00",30);var b=stop("B","10:31",30);
        b.put("resolvedRouteCost",Map.of("durationMinutes",1,"durationSeconds",90,"routeDataStatus","VERIFIED_AMAP"));
        assertFalse(new PlanVerifier().verify(List.of(day(1,List.of(a,b))),new TravelConstraints()).feasible());
    }
    @Test void dynamicCoordinateEndpointReplacesOldIncomingEdge() {
        var costs=mock(RouteCostProvider.class);when(costs.resolveFinalEdge(any(),any(),any()))
                .thenReturn(RouteCost.verifiedAmap(1000,2700,100,"TRANSIT"));
        var taxonomy=com.ai.guide.testing.PlannerTaxonomyFixtures.taxonomy();
        var planner=new RouteAwarePlanner(costs,new PlanVerifier(taxonomy),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer(),new com.ai.guide.domain.planner.service.PlannerMetricsLogger(),null,taxonomy);
        var a=stop("amap-dining-A","10:00",30);var b=stop("B","10:31",30);
        b.put("resolvedRouteCost",RouteCost.estimated(100,1,0,"WALKING","old X-to-B").asMap());
        b.put("routeFromPrevious",Map.of("selected",Map.of("durationSeconds",60)));
        planner.backfillAdjacentEdgeCosts(List.of(day(1,List.of(a,b))),Map.of("B",Attraction.builder().id("B").location("116.5,40.0").build()),"TRANSIT");
        verify(costs).resolveFinalEdge(argThat(x -> x.getId().equals("amap-dining-A") && x.getLocation().equals("116.3,39.9")),any(),eq("TRANSIT"));
        assertEquals(45,new ItineraryTimeEngine().transferMinutes(a,b));
        assertNotEquals("old X-to-B",((Map<?,?>)b.get("resolvedRouteCost")).get("reason"));
        assertFalse(new PlanVerifier().verify(List.of(day(1,List.of(a,b))),new TravelConstraints()).feasible());
    }
    @Test void changingCoordinatesInvalidatesProviderCacheKey() {
        var provider=new RouteCostProvider(null);
        var a=Attraction.builder().id("A").location("116.3,39.9").build();
        var relocated=Attraction.builder().id("A").location("116.6,40.1").build();
        var b=Attraction.builder().id("B").location("116.5,40.0").build();
        assertNotEquals(provider.edgeKey(a,b,"TRANSIT"),provider.edgeKey(relocated,b,"TRANSIT"));
        assertNotEquals(provider.edgeKey(a,b,"TRANSIT"),provider.edgeKey(a,b,"WALKING"));
    }
    @Test void scopedBackfillPreservesUnchangedIncomingMetadata() {
        var costs=mock(RouteCostProvider.class);
        when(costs.resolveFinalEdge(any(),any(),any())).thenReturn(RouteCost.estimated(200,15,100,"TRANSIT","new edge"));
        var planner=new RouteAwarePlanner(costs,new PlanVerifier(),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer());
        var a=stop("A","10:00",30);var lunch=stop("lunch","12:00",60);var evening=stop("evening","20:00",30);
        lunch.put("resolvedRouteCost",RouteCost.unavailable("original evidence").asMap());
        lunch.put("walk","original untouched");
        var original=day(1,List.of(a,lunch,evening));
        var revisedLunch=new LinkedHashMap<>(lunch);var revisedEvening=new LinkedHashMap<>(evening);revisedEvening.put("venueId","new-night");
        var revised=day(1,List.of(new LinkedHashMap<>(a),revisedLunch,revisedEvening));
        planner.backfillAdjacentEdgeCosts(List.of(revised),Map.of(),"TRANSIT",List.of(original));
        assertEquals(lunch,revisedLunch);verify(costs,times(1)).resolveFinalEdge(any(),argThat(x -> x.getId().equals("new-night")),any());
        assertEquals("ESTIMATED",revisedEvening.get("routeDataStatus"));
    }
    @Test void routeRecalculationInvalidatesFallbackGeometryButKeepsAccessibility() {
        var costs=mock(RouteCostProvider.class);when(costs.resolveFinalEdge(any(),any(),any()))
                .thenReturn(RouteCost.estimated(100,15,0,"TRANSIT","new edge"));
        var planner=new RouteAwarePlanner(costs,new PlanVerifier(),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer());
        var a=stop("new-A","10:00",30);var b=stop("B","12:00",30);
        b.put("walkingInfo",Map.of("polyline",List.of(List.of(116.1,39.8),List.of(116.3,39.9)),"summary","old edge","accessibility","elevator"));
        b.put("mapContext",Map.of("polyline",List.of(List.of(116.1,39.8),List.of(116.3,39.9)),"coordinate","116.3,39.9"));
        planner.backfillAdjacentEdgeCosts(List.of(day(1,List.of(a,b))),Map.of(),"TRANSIT");
        assertFalse(((Map<?,?>)b.get("walkingInfo")).containsKey("polyline"));
        assertFalse(((Map<?,?>)b.get("mapContext")).containsKey("polyline"));
        assertEquals("elevator",((Map<?,?>)b.get("walkingInfo")).get("accessibility"));
        assertEquals("116.3,39.9",((Map<?,?>)b.get("mapContext")).get("coordinate"));
    }
    @Test void removingFirstStopClearsItsSuccessorsIncomingAssets() {
        var a=stop("A","10:00",30);var b=stop("B","12:00",30);
        b.put("routeFromPrevious",Map.of("summary","old-A-to-B","polyline",List.of(List.of(116.1,39.8),List.of(116.3,39.9))));
        b.put("walkingInfo",Map.of("polyline",List.of(List.of(116.1,39.8),List.of(116.3,39.9)),"accessibility","elevator"));
        b.put("resolvedRouteCost",RouteCost.estimated(1000,40,0,"TRANSIT","old-A-to-B").asMap());
        var trip=new LinkedHashMap<String,Object>();trip.put("days",new ArrayList<>(List.of(day(1,List.of(a,b)))));
        var planner=new RouteAwarePlanner(mock(RouteCostProvider.class),new PlanVerifier(),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer());
        var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).targetStopId("A").build();
        var ctx=new AdjustmentContext(null,intent,null,trip,trip,new TravelConstraints(),Map.of(),new ObjectMapper());
        new RemoveStopAdjustmentStrategy(planner).apply(ctx);
        var first=PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.days(trip).get(0)).get(0);
        assertFalse(first.containsKey("routeFromPrevious"));assertFalse(first.containsKey("resolvedRouteCost"));
        assertEquals("116.3,39.9",first.get("location"));
        assertEquals("elevator",((Map<?,?>)first.get("walkingInfo")).get("accessibility"));
    }
    @Test void relativeDateLongestTokenAndStructuredBoundariesWinOverPromptHistory() {
        var c=new TravelConstraints();c.setRawPrompt("大后天開始玩一天");
        assertEquals(java.time.LocalDate.now().plusDays(3).toString(),com.ai.guide.domain.planner.service.TravelDateResolver.resolveItineraryDate(c,1));
        c.setArrivalAt("明天");c.setRawPrompt("今天先确认安排，明天到重庆");
        assertEquals(java.time.LocalDate.now().plusDays(1).toString(),com.ai.guide.domain.planner.service.TravelDateResolver.resolveItineraryDate(c,1));
        c.setArrivalAt("未提供");c.setDepartureAt("2026-10-10上午");c.setDurationDays(3);
        assertEquals("2026-10-08",com.ai.guide.domain.planner.service.TravelDateResolver.resolveItineraryDate(c,1));
        assertEquals("2026-10-10",com.ai.guide.domain.planner.service.TravelDateResolver.resolveItineraryDate(c,3));
    }
    @Test void missingCoordinatesInvalidateOldRoute() {
        var costs=mock(RouteCostProvider.class);var planner=new RouteAwarePlanner(costs,new PlanVerifier(),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer());
        var a=stop("A","10:00",30);a.remove("location");var b=stop("B","11:00",30);
        b.put("resolvedRouteCost",RouteCost.estimated(100,1,0,"WALKING","old edge").asMap());
        b.put("routeFromPrevious",Map.of("selected",Map.of("durationSeconds",60)));
        planner.backfillAdjacentEdgeCosts(List.of(day(1,List.of(a,b))),Map.of(),"TRANSIT");
        verifyNoInteractions(costs);assertEquals("UNAVAILABLE",b.get("routeDataStatus"));
        assertFalse(b.containsKey("routeFromPrevious"));assertEquals(-1,new ItineraryTimeEngine().transferMinutes(a,b));
    }
    @Test void removeReattachesActualRepairedTree() {
        var costs=mock(RouteCostProvider.class);
        when(costs.resolveFinalEdge(any(),any(),any())).thenAnswer(x ->
                RouteCost.estimated(1000, x.getArgument(1,Attraction.class).getId().equals("C") ? 180 : 15,0,"TRANSIT","test"));
        var taxonomy=com.ai.guide.testing.PlannerTaxonomyFixtures.taxonomy();
        var planner=new RouteAwarePlanner(costs,new PlanVerifier(taxonomy),com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer(),new com.ai.guide.domain.planner.service.PlannerMetricsLogger(),null,taxonomy);
        var d=day(1,List.of(stop("A","08:00",30),stop("X","09:00",30),stop("B","13:00",30),stop("C","17:00",30)));
        var trip=new LinkedHashMap<String,Object>();trip.put("days",new ArrayList<>(List.of(d)));
        var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).targetStopId("X").build();
        var ctx=new AdjustmentContext(null,intent,null,trip,trip,new TravelConstraints(),Map.of(),new ObjectMapper());
        new RemoveStopAdjustmentStrategy(planner).apply(ctx);
        assertEquals(2,PlanAdjustmentHelper.dayStops(PlanAdjustmentHelper.days(trip).get(0)).size());
        assertTrue(new PlanVerifier().verify(PlanAdjustmentHelper.days(trip),new TravelConstraints()).feasible());
    }
    @Test void interiorDayRepairRetainsArrivalDepartureAndProtectedDurations() {
        var planner=mock(RouteAwarePlanner.class);
        when(planner.resolveAndVerifyDays(any(),any(),any())).thenAnswer(x -> x.getArgument(0));
        var trip=new LinkedHashMap<String,Object>();trip.put("days",new ArrayList<>(List.of(
                day(1,List.of(stop("first","09:00",30))),day(2,List.of(stop("outside","10:00",120),stop("replacement","18:00",120),stop("pinned","20:00",120))),day(3,List.of(stop("last","09:00",30))))));
        var constraints=new TravelConstraints();constraints.setArrivalAt("18:00");constraints.setDepartureAt("10:00");
        var intent=PlanAdjustmentIntent.builder().pinnedStopIds(List.of("pinned")).targetStartMinute(17*60).targetEndMinute(22*60).build();
        LocalAdjustmentRepair.repair(trip,2,Map.of(),constraints,intent,"replacement",planner);
        var capture=org.mockito.ArgumentCaptor.forClass(TravelConstraints.class);
        verify(planner).resolveAndVerifyDays(any(),any(),capture.capture());var local=capture.getValue();
        assertEquals("未提供",local.getArrivalAt());assertEquals("未提供",local.getDepartureAt());
        assertTrue(local.getMustVisit().containsAll(List.of("outside","replacement","pinned")));
        assertEquals("18:00",constraints.getArrivalAt());assertTrue(constraints.getMustVisit().isEmpty());
        var d=PlanAdjustmentHelper.days(trip).get(1);
        var violation=new PlanVerifier.PlanViolation("DAY_SCHEDULE_OVERFLOW",1,"replacement","replacement","test",true);
        var result=com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer().repair(List.of(d),new PlanVerifier.VerificationResult(false,List.of(violation),Map.of()),local,List.of());
        assertEquals(List.of("120分钟","120分钟","120分钟"),PlanAdjustmentHelper.dayStops(result.get(0)).stream().map(s -> s.get("duration")).toList());
    }
}

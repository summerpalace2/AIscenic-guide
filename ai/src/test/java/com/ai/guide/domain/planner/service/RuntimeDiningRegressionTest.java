package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeDiningRegressionTest {
    @Test
    void usesRestaurantRouteDurationAndKeepsProviderCategoryOnScenicStop() {
        Map<String,Object> trip = plan(600, 180, "中午12点出发");
        List<Map<String,Object>> stops = stops(trip);
        Map<String,Object> meal = stops.stream().filter(s -> "DINING".equals(s.get("type"))).findFirst().orElseThrow();
        assertEquals("12:10", meal.get("startTime"));
        assertEquals("约 50 分钟", meal.get("duration"));
        assertEquals(600, ((Map<?,?>) meal.get("routeFromPrevious")).get("durationSeconds"));
        assertEquals(900, ((Map<?,?>) meal.get("routeFromPrevious")).get("distanceMeters"));
        assertEquals("restaurant", meal.get("amapPoiId"));
        assertEquals("营业时间待核验", meal.get("bestTime"));
        assertTrue(stops.stream().anyMatch(s -> "140100".equals(s.get("typecode"))));
        assertEquals("VERIFIED_AMAP", trip.get("routeDataStatus"));
    }

    @Test
    void unverifiedRoutesRemainEstimatesInTheTripSummary() {
        Map<String,Object> trip = plan(null, 180, "中午12点出发");
        assertFalse(stops(trip).isEmpty());
        assertEquals("PARTIAL", trip.get("routeDataStatus"));
        assertEquals(true, trip.get("degraded"));
        assertEquals("待确认", ((Map<?,?>)trip.get("sourceStatus")).get("route"));
        Map<String,Object> meal = stops(trip).stream().filter(s -> "DINING".equals(s.get("type"))).findFirst().orElseThrow();
        assertFalse(((Map<?,?>) meal.get("routeFromPrevious")).containsKey("durationSeconds"));
    }

    @Test
    void excludesMealWhenTravelAndEatingCannotFitTheWindow() {
        Map<String,Object> trip = plan(2700, 120, "中午12:45出发");
        assertTrue(stops(trip).stream().noneMatch(s -> "DINING".equals(s.get("type"))));
    }

    private Map<String,Object> plan(Integer routeSeconds, int budget, String departure) {
        GeoRouteGatewayPort routes = mock(GeoRouteGatewayPort.class);
        when(routes.isConfigured()).thenReturn(true);
        var start = poi("start", "出发点", "141201", 116.40);
        var museum = poi("museum", "地方博物馆", "140100", 116.41);
        when(routes.searchPoi(anyString(), eq("北京市"))).thenReturn(new PoiOutcome(OutcomeStatus.SUCCESS,List.of(start),"",Instant.now()));
        when(routes.searchAround(anyString(), anyInt(), anyString(), anyString(), eq("北京市"), anyInt()))
                .thenReturn(new PoiOutcome(OutcomeStatus.SUCCESS,List.of(museum),"",Instant.now()));
        RouteOutcome segment = routeSeconds == null ? new RouteOutcome(OutcomeStatus.TIMEOUT,null,"测试超时","test",Instant.now())
                : new RouteOutcome(OutcomeStatus.SUCCESS,new AmapResponseNormalizer.RouteResult("WALKING",900,routeSeconds,900,1,List.of(),"",true),"","test",Instant.now());
        when(routes.routePair(anyString(),anyString(),eq("北京市"),anyString(),anyString(),anyString()))
                .thenReturn(new RoutePairOutcome(segment,segment,segment,"walking",false));
        DiningVenueService dining = mock(DiningVenueService.class);
        when(dining.resolveNearbyDining(any(),anyString(),any())).thenReturn(new AttractionDiningKnowledge.DiningOption(
                "amap-dining-restaurant","本地餐厅","特色菜","地方菜","待核验","约 50 分钟","待核验","本地餐饮","邻近出发点","gold","116.405,39.9"));
        var constraints = TravelConstraints.builder().destination("北京").startPlace("出发点").timeBudgetMinutes(budget)
                .dietPreference("地方菜").rawPrompt(departure).build();
        constraints.markOrigin("startPlace", ConstraintOrigin.PROMPT);
        return new RuntimeLocationPlanner(routes,new ObjectMapper(),6000,40,dining,new TaxonomyProvider()).plan(constraints,1);
    }

    private AmapResponseNormalizer.PoiCandidate poi(String id,String name,String code,double longitude) {
        return new AmapResponseNormalizer.PoiCandidate(id,name,new AmapResponseNormalizer.Coordinate(longitude,39.9),"北京市测试区",
                "140100".equals(code)?"科教文化服务;博物馆":"科教文化服务;学校",null,null,id,null,null,null,null,code,null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> stops(Map<String,Object> trip) {
        return (List<Map<String,Object>>) ((Map<?,?>)((List<?>)trip.get("days")).get(0)).get("stops");
    }
}

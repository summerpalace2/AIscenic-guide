package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.TopographyStrategyRouter;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CrossCityPlannerPolicyTest {
    @Test void missingUnknownAndUninstalledCitiesUseGenericPolicy() {
        var router=new TopographyStrategyRouter(List.of(new ChongqingTopographyStrategy()), new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        assertEquals("GENERIC",router.route(null).supportedCity());
        assertEquals("GENERIC",router.route("杭州").supportedCity());
        assertEquals("GENERIC",router.route("成都").supportedCity());
        assertEquals("GENERIC",router.getByCityCode("WHATEVER").supportedCity());
        assertEquals("CHONGQING",router.route("重庆市").supportedCity());
    }
    @Test void hangzhouCandidateIsNotRejectedAsRemoteChongqingDistrict() {
        var verifier=mock(PlanVerifier.class);
        when(verifier.verify(anyList(),any())).thenReturn(new PlanVerifier.VerificationResult(true,List.of(),Map.of()));
        var planner=new RouteAwarePlanner(new RouteCostProvider(null),verifier,mock(LocalPlanRepairer.class),
                new com.ai.guide.domain.planner.service.PlannerMetricsLogger(),null,
                new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        var builder=mock(ItineraryBuilderPort.class);
        when(builder.createStop(any(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(call -> {
            Attraction venue=call.getArgument(0);
            return new LinkedHashMap<>(Map.of("id",call.getArgument(1),"venueId",venue.getId(),"name",venue.getName(),
                    "time",call.getArgument(2),"duration",call.getArgument(3),"location",venue.getLocation()));
        });
        var venue=Attraction.builder().id("test-west-lake").name("西湖").district("西湖区").location("120.15,30.25")
                .category("自然").duration("约 60 分钟").summary("测试").intro("测试").build();
        var constraints=new TravelConstraints();constraints.setDestination("杭州");
        var days=planner.planDays(1,constraints,Map.of(venue.getId(),venue),List.of(),builder);
        assertFalse(((List<?>)days.get(0).get("stops")).isEmpty());
        assertFalse(String.valueOf(days.get(0).get("dateLabel")).contains("巴渝"));
    }
    @Test void invalidCoordinatesNeverGenerateZeroDurationAvailableRoute() {
        var costs=new RouteCostProvider(null);
        var first=Attraction.builder().id("a").location("NaN,NaN").build();
        var second=Attraction.builder().id("b").location("116.4,39.9").build();
        assertEquals(RouteCost.RouteDataStatus.UNAVAILABLE,costs.calculate(first,second,"walking").status());
        assertTrue(Double.isInfinite(costs.haversineDistanceKm(first.getLocation(),second.getLocation())));
    }
}

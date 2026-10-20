package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItineraryBuilderPoiIdentityTest {
    @Test
    void preGeocodeUsesTheSameExactCatalogAliasAndUniqueIdentityGuard() {
        GeoPoiCandidate main = poi("main", "杭州西湖风景名胜区", "杭州市", 120.12, 30.22);
        GeoPoiCandidate branch = poi("branch", "杭州西湖风景名胜区-断桥残雪", "杭州市", 120.15, 30.25);
        Attraction resolved = buildWithCandidates(List.of(main, branch));
        assertEquals(main.poiId(), resolved.getResolvedPoiId());
        assertEquals("120.12,30.22", resolved.getLocation());

        Attraction ambiguous = buildWithCandidates(List.of(main,
                poi("duplicate", "杭州西湖风景名胜区", "杭州市", 120.13, 30.23)));
        assertEquals(null, ambiguous.getResolvedPoiId());
        assertEquals("", ambiguous.getLocation());

        Attraction wrongCity = buildWithCandidates(List.of(
                poi("other-city", "杭州西湖风景名胜区", "南京市", 118.8, 32.0)));
        assertEquals(null, wrongCity.getResolvedPoiId());
        assertEquals("", wrongCity.getLocation());
    }

    private Attraction buildWithCandidates(List<GeoPoiCandidate> candidates) {
        var attraction = Attraction.builder().id("hz-west-lake").name("西湖风景名胜区")
                .displayName("西湖风景名胜区").city("杭州").location("")
                .amapQuery(Map.of("keywords", "西湖风景名胜区",
                        "matches", List.of("西湖风景名胜区", "杭州西湖风景名胜区"))).build();
        AttractionService attractionService = mock(AttractionService.class);
        when(attractionService.list(null, null)).thenReturn(List.of(attraction));
        var routePlanner = mock(RouteAwarePlanner.class);
        AtomicReference<Map<String, Attraction>> receivedCatalog = new AtomicReference<>();
        when(routePlanner.planDays(anyInt(), any(), anyMap(), anyList(), any()))
                .thenAnswer(call -> {
                    receivedCatalog.set(call.getArgument(2));
                    return new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day", 1, "stops", new ArrayList<>()))));
                });
        GeoRouteGatewayPort geo = mock(GeoRouteGatewayPort.class);
        when(geo.isConfigured()).thenReturn(true);
        when(geo.searchPoi("西湖风景名胜区", "杭州")).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS, candidates, "", Instant.now()));
        var verifier = new PlanVerifier();
        ItineraryBuilder builder = new ItineraryBuilder(attractionService, routePlanner, verifier,
                mock(LocalPlanRepairer.class),
                mock(PlanNarrativeService.class), mock(AttractionAliasMatcher.class));
        builder.setGeoRouteGateway(geo);

        builder.build(1, TravelConstraints.builder().destination("杭州").durationDays(1).build());

        return receivedCatalog.get().get(attraction.getId());
    }

    private GeoPoiCandidate poi(String id, String name, String city, double longitude, double latitude) {
        return new GeoPoiCandidate(id, name, new GeoCoordinate(longitude, latitude), "", "",
                null, null, null, null, null, null, null, null, null, city);
    }
}

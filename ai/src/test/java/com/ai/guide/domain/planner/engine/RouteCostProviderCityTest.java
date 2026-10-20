package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RouteCostProviderCityTest {
    @Test
    void sendsCatalogCityToProviderEvenWithoutCoordinatesOrQueryCity() {
        var gateway = mock(GeoRouteGatewayPort.class);
        when(gateway.isConfigured()).thenReturn(true);
        var provider = new RouteCostProvider(gateway);
        var origin = attraction("a", "北京", Map.of("city", "错误旧值"));
        var destination = attraction("b", "北京", Map.of());
        assertEquals(RouteCost.RouteDataStatus.UNAVAILABLE, provider.resolveFinalEdge(origin, destination, "walking").status());
        verify(gateway).routePair(eq(""), eq(""), eq("北京"), anyString(), anyString(), eq("walking"));
    }

    @Test
    void retainsLegacyQueryCityWhenCatalogHasNoCity() {
        var gateway = mock(GeoRouteGatewayPort.class);
        when(gateway.isConfigured()).thenReturn(true);
        new RouteCostProvider(gateway).resolveFinalEdge(attraction("a", "", Map.of("city", "杭州")), attraction("b", "", Map.of()), "walking");
        verify(gateway).routePair(eq(""), eq(""), eq("杭州"), anyString(), anyString(), eq("walking"));
    }

    @Test
    void missingCoordinatesCannotBecomeAUsableEstimatedRoute() {
        var cost = new RouteCostProvider(null).calculate(attraction("a", "西安", Map.of()), attraction("b", "西安", Map.of()), "walking");
        assertEquals(RouteCost.RouteDataStatus.UNAVAILABLE, cost.status());
    }

    private Attraction attraction(String id, String city, Map<String, Object> query) {
        return Attraction.builder().id(id).name(id).city(city).location("").amapQuery(query).build();
    }
}

package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AttractionMediaServiceTest {
    @Test
    void queriesMediaInConfiguredDefaultCity() {
        GeoRouteGatewayPort route = mock(GeoRouteGatewayPort.class);
        when(route.isConfigured()).thenReturn(true);
        when(route.searchPoi(anyString(), anyString())).thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "none", null));
        TaxonomyPort taxonomy = new TaxonomyPort() {
            @Override public MealWindow getMealWindow(String slot) { return new MealWindow(0, 1440, 0, ""); }
            @Override public CityCuisineProfile getCityProfile(String city) { return new CityCuisineProfile(city, "", List.of(), List.of()); }
            @Override public java.util.Set<String> getBrands() { return java.util.Set.of(); }
            @Override public List<String> getHints(String key) { return key.equals("defaultDestination") ? List.of("杭州") : List.of(); }
            @Override public List<FlavorAlias> getFlavorAliases() { return List.of(); }
            @Override public boolean isKnownBrand(String name) { return false; }
        };
        AttractionMediaService service = new AttractionMediaService(route, taxonomy);

        service.enrich(Attraction.builder().id("poi-1").name("某景点").build());

        verify(route).searchPoi(anyString(), eq("杭州"));
    }
}

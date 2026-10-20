package com.ai.guide.domain.attraction.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAttractionDetailServiceTest {
    @Test void projectsProviderCityForBothIdAndNameLookupWithoutUsingRequestedCity() {
        var gateway = org.mockito.Mockito.mock(com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort.class);
        org.mockito.Mockito.when(gateway.isConfigured()).thenReturn(true);
        var poi = new com.ai.guide.domain.geo.model.GeoPoiCandidate("poi", "探针馆", null, "地址", "博物馆", null, null, null, null, null, null, null, null, null, "杭州市");
        var outcome = new com.ai.guide.domain.geo.model.GeoPoiOutcome(com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS, java.util.List.of(poi), "", java.time.Instant.now());
        org.mockito.Mockito.when(gateway.poiDetail("poi")).thenReturn(outcome);
        org.mockito.Mockito.when(gateway.searchPoi("探针馆", "北京")).thenReturn(outcome);
        var service = new RuntimeAttractionDetailService(gateway);
        assertEquals("杭州市", service.toAttraction("amap-poi").getCity());
        assertEquals("杭州市", service.searchAndBuildAttraction("探针馆", "北京").getCity());
    }

    @Test
    void extractsAdministrativeDistrictAcrossCitiesWithoutLandmarkWhitelist() {
        var service = new RuntimeAttractionDetailService(null);
        assertEquals("东城区", service.extractDistrict("北京市东城区五四大街1号"));
        assertEquals("渝中区", service.extractDistrict("重庆市渝中区人民路236号"));
        assertEquals("西湖区", service.extractDistrict("浙江省杭州市西湖区北山街"));
        assertEquals("", service.extractDistrict("地址未提供"));
        assertFalse(service.generateAiGuide("北京美术馆", "美术馆", "北京市东城区", "东城区").contains("免费"));
    }
}

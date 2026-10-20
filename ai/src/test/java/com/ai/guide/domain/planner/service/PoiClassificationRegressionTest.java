package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.infrastructure.geo.amap.AmapResponseNormalizer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PoiClassificationRegressionTest {
    private final RuntimeLocationPlanner planner;

    PoiClassificationRegressionTest() {
        var gateway = mock(GeoRouteGatewayPort.class);
        var taxonomy = new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
        planner = new RuntimeLocationPlanner(gateway, new com.fasterxml.jackson.databind.ObjectMapper(), 6000, 40,
                new com.ai.guide.domain.dining.service.DiningVenueService(null, gateway, taxonomy), taxonomy);
    }

    @Test
    void admitsCulturalVenuesButRejectsTeachingBuildingsAndInteriorFacilities() {
        for (String code : new String[]{"140100", "140200", "140400", "140500", "140600", "140700"}) {
            assertFalse(planner.isNoisePoi(poi("未知文教场馆", "科教文化服务", code)), code);
        }
        assertTrue(planner.isNoisePoi(poi("蒙民伟理科馆", "科教文化服务;学校", "141201")));
        assertTrue(planner.isNoisePoi(poi("园区售票处", "风景名胜;公园内部设施", "110106")));
        assertTrue(planner.isNoisePoi(poi("文化展位", "科教文化服务;展览馆", "140201")));
        assertFalse(planner.isNoisePoi(poi("第三教学楼遗址", "风景名胜", "110202")));
    }

    @Test
    void keepsDiningOutOfScenicPoolWithoutCallingItInvalidForDining() {
        assertTrue(planner.isNoisePoi(poi("教学楼咖啡馆", "餐饮服务;咖啡厅", "050500")));
        PoiCategoryPolicyRegistry policies = com.ai.guide.testing.PlannerTaxonomyFixtures.categoryPolicies();
        assertEquals(PoiDomainType.DINING, policies.resolveCategory(Map.of("name", "教学楼咖啡馆", "typecode", "050500")));
        assertEquals(PoiDomainType.SCENIC, policies.resolveCategory(Map.of("name", "餐厅旧址公园", "typecode", "110101")));
        assertEquals(PoiDomainType.TRANSIT, policies.resolveCategory(Map.of("name", "客运码头", "typecode", "150301")));
        assertEquals(PoiDomainType.HOTEL, policies.resolveCategory(Map.of("name", "午餐主题酒店", "typecode", "100100")));
        assertFalse(policies.isDiningStop(Map.of("name", "午餐主题酒店", "typecode", "100100")));
        Map<String, Object> publicFacility = Map.of("name", "午餐餐厅公厕", "type", "DINING", "isDining", true, "typecode", "200300");
        assertFalse(policies.isDiningStop(publicFacility));
        assertNotEquals(PoiDomainType.DINING, policies.resolveCategory(publicFacility));
        assertFalse(policies.isDiningStop(Map.of("name", "餐厅商住楼", "typecode", "120201")));
        assertNotEquals(PoiDomainType.DINING, policies.resolveCategory(Map.of("name", "餐厅商住楼", "typecode", "120201")));
    }

    @Test
    void fallsBackToProviderCategoriesWhenCodeIsUnavailable() {
        assertFalse(planner.isNoisePoi(poi("格物楼展馆", "科教文化服务;博物馆", null)));
        assertTrue(planner.isNoisePoi(poi("不规则楼名", "科教文化服务;学校", null)));
        assertTrue(planner.isNoisePoi(poi("任意店名", "餐饮服务", null)));
    }

    private com.ai.guide.domain.geo.model.GeoPoiCandidate poi(String name, String type, String code) {
        return new com.ai.guide.domain.geo.model.GeoPoiCandidate("p", name, new com.ai.guide.domain.geo.model.GeoCoordinate(116.4,39.9),
                "北京市", type, null, null, "p", null, null, null, null, code, null);
    }
}

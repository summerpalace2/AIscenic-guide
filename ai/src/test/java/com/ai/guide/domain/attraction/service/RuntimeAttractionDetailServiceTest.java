package com.ai.guide.domain.attraction.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeAttractionDetailServiceTest {
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

package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AttractionPoiIdentityMatcherTest {
    @Test
    void verifiedSupplierNamesAreExternalExactAliasesForTheirCatalogEntries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Keep the fixture aligned with CityAttractionCatalogImporter: city assets are parsed
        // as Map rows so importer-only metadata (sourceUrl, verifiedAt) is valid and retained.
        List<Attraction> hangzhou = readCatalog(mapper, "taxonomy/cities/hangzhou.json");
        List<Attraction> xian = readCatalog(mapper, "taxonomy/cities/xian.json");
        Attraction westLake = hangzhou.stream().filter(a -> "hz-west-lake".equals(a.getId())).findFirst().orElseThrow();
        Attraction gushan = hangzhou.stream().filter(a -> "hz-zhejiang-museum-gushan".equals(a.getId())).findFirst().orElseThrow();
        Attraction cityWall = xian.stream().filter(a -> "xa-city-wall".equals(a.getId())).findFirst().orElseThrow();

        assertTrue(AttractionPoiIdentityMatcher.acceptableExactNames(westLake).contains("杭州西湖风景名胜区"));
        assertTrue(AttractionPoiIdentityMatcher.acceptableExactNames(gushan).contains("浙江省博物馆(孤山馆区)"));
        assertTrue(AttractionPoiIdentityMatcher.acceptableExactNames(cityWall).contains("西安城墙"));
        assertFalse(AttractionPoiIdentityMatcher.acceptableExactNames(gushan).contains("浙江省博物馆(之江馆区)"));
    }

    private List<Attraction> readCatalog(ObjectMapper mapper, String resource) throws Exception {
        List<Map<String, Object>> rows = mapper.readValue(
                new ClassPathResource(resource).getInputStream(),
                new TypeReference<List<Map<String, Object>>>() { });
        return rows.stream().map(row -> Attraction.builder()
                .id((String) row.get("id"))
                .name((String) row.get("name"))
                .city((String) row.get("city"))
                .amapQuery((Map<String, Object>) row.get("amapQuery"))
                .build()).toList();
    }
}

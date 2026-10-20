package com.ai.guide.infrastructure.geo.amap;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapResponseNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test void preservesProviderCityWithoutGuessingFromAddress() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[{"id":"known","cityname":"杭州市"},{"id":"unknown","address":"北京市某处"}]}
                """);
        var pois = AmapResponseNormalizer.normalizePois(response);
        assertEquals("杭州市", pois.get(0).cityName());
        assertNull(pois.get(1).cityName());
    }

    @Test
    void preservesV5CategoryAndNestedBusinessAreaWithV3Fallback() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[
                  {"id":"museum","typecode":"140600","business":{"business_area":"本地商圈"},"business_area":"旧字段"},
                  {"id":"legacy","typecode":"110101","business_area":"兼容商圈"}
                ]}
                """);
        var candidates = AmapResponseNormalizer.normalizePois(response);
        assertEquals("140600", candidates.get(0).typecode());
        assertEquals("本地商圈", candidates.get(0).businessArea());
        assertEquals("兼容商圈", candidates.get(1).businessArea());
    }

    @Test
    void preservesFirstUsablePoiPhotoAndTitle() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[{"id":"poi-1","name":"洪崖洞","location":"106.58,29.56","photos":[{"url":"","title":"空图"},{"src":"https://example.invalid/hongya.jpg","title":"夜景"}]}]}
                """);

        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates = AmapResponseNormalizer.normalizePois(response);

        assertEquals(1, candidates.size());
        assertEquals("https://example.invalid/hongya.jpg", candidates.get(0).photoUrl());
        assertEquals("夜景", candidates.get(0).photoTitle());
    }

    @Test
    void preservesProviderInfoAndInfocodeOnSemanticFailure() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"}
                """);

        var failure = assertThrows(AmapResponseNormalizer.AmapResponseException.class,
                () -> AmapResponseNormalizer.normalizePois(response));

        assertEquals("CUQPS_HAS_EXCEEDED_THE_LIMIT", failure.info());
        assertEquals("10021", failure.infocode());
    }

    @Test
    void leavesPhotoUnavailableWhenProviderReturnsNoUsablePhoto() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[{"id":"poi-1","name":"洪崖洞","location":"106.58,29.56","photos":[{"url":""}]}]}
                """);

        var candidate = AmapResponseNormalizer.normalizePois(response).get(0);

        assertNull(candidate.photoUrl());
        assertNull(candidate.photoTitle());
    }

    @Test
    void preservesRatingAndHeatTagsFromBusinessMetadata() throws Exception {
        var response = objectMapper.readTree("""
                {
                  "status":"1",
                  "pois":[{
                    "id":"poi-ciqikou",
                    "name":"磁器口古镇",
                    "location":"106.44,29.58",
                    "business":{
                      "rating":"4.7",
                      "rectag":"新巴渝十二景",
                      "keytag":"文物古迹",
                      "tag":"著名古镇"
                    }
                  }]
                }
                """);

        var candidate = AmapResponseNormalizer.normalizePois(response).get(0);

        assertEquals(4.7, candidate.rating());
        assertEquals("新巴渝十二景;文物古迹;著名古镇", candidate.heatTag());
    }

    @Test
    void normalizesV5NestedTransitCostWalkingDistanceAndPolyline() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","route":{"transits":[{
                  "distance":"4200","cost":{"duration":"2400"},
                  "segments":[
                    {"walking":{"distance":"120","steps":[{"polyline":"120.0,30.0;120.1,30.1","navi":{"walk_type":"10"}}]}},
                    {"bus":{"buslines":[{"polyline":"120.1,30.1;120.2,30.2"}]}},
                    {"walking":{"distance":"230","steps":[{"polyline":"120.2,30.2;120.3,30.3"}]}}
                  ]
                }]}}
                """);

        var route = AmapResponseNormalizer.normalizeTransit(response);

        assertEquals(2400, route.durationSeconds());
        assertEquals(350, route.walkingDistanceMeters());
        assertTrue(route.polyline().contains("120.0,30.0;120.1,30.1"));
        assertTrue(route.polyline().contains("120.1,30.1;120.2,30.2"));
        assertTrue(route.metricsComplete());
        var missingWalking = AmapResponseNormalizer.normalizeTransit(objectMapper.readTree("""
                {"status":"1","route":{"transits":[{"distance":"4200","cost":{"duration":"2400"},"segments":[{"bus":{}}]}]}}
                """));
        assertNull(missingWalking.walkingDistanceMeters());
    }

    @Test
    void normalizesV5WalkingDurationFromNestedCost() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","route":{"paths":[{"distance":"1200","cost":{"duration":"900"},
                  "steps":[{"polyline":"120.0,30.0;120.1,30.1"}]}]}}
                """);

        var route = AmapResponseNormalizer.normalizeWalking(response);

        assertEquals(900, route.durationSeconds());
        assertEquals(1200, route.distanceMeters());
        org.junit.jupiter.api.Assertions.assertTrue(route.polyline().contains("120.0,30.0;120.1,30.1"));
    }

}

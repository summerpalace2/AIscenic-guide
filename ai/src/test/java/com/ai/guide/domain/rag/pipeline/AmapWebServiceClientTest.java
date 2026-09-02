package com.ai.guide.domain.rag.pipeline;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapWebServiceClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsP0RequestsWithoutCallingTheNetwork() {
        AmapWebServiceClient client = new AmapWebServiceClient(
                "https://restapi.amap.com/", "test-key");

        URI poi = client.buildPoiTextRequest("洪崖洞", "重庆");
        URI walking = client.buildWalkingRequest("106.000,29.000", "106.010,29.010");
        URI transit = client.buildTransitRequest("106.000,29.000", "106.010,29.010", "重庆");

        assertEquals("/v5/place/text", poi.getPath());
        assertTrue(poi.getQuery().contains("key=test-key"));
        assertTrue(poi.getQuery().contains("show_fields=navi"));
        assertEquals("/v5/direction/walking", walking.getPath());
        assertTrue(walking.getQuery().contains("show_fields=cost,navi,polyline"));
        assertEquals("/v5/direction/transit/integrated", transit.getPath());
        assertTrue(transit.getQuery().contains("city=重庆"));
    }

    @Test
    void refusesToBuildRequestsWithoutRuntimeKey() {
        AmapWebServiceClient client = new AmapWebServiceClient(null, " ");

        assertThrows(IllegalStateException.class,
                () -> client.buildPoiTextRequest("洪崖洞", "重庆"));
    }

    @Test
    void normalizesPoiAndNavigationFields() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "status":"1",
                  "pois":[{
                    "id":"B000A1",
                    "name":"洪崖洞",
                    "location":"106.580,29.563",
                    "address":"重庆市渝中区",
                    "type":"风景名胜",
                    "navi_poiid":"B000A1-N",
                    "entr_location":"106.581,29.564",
                    "exit_location":"106.582,29.565"
                  }]
                }
                """);

        List<AmapResponseNormalizer.PoiCandidate> result = AmapResponseNormalizer.normalizePois(response);

        assertEquals(1, result.size());
        assertEquals("B000A1", result.get(0).poiId());
        assertEquals(106.581, result.get(0).navigationEntrance().longitude());
        assertEquals("B000A1-N", result.get(0).navigationPoiId());
    }

    @Test
    void normalizesGeocodeAndWalkingMetrics() throws Exception {
        JsonNode geocode = objectMapper.readTree("""
                {"status":"1","geocodes":[{"formatted_address":"洪崖洞","location":"106.580,29.563","adcode":"500103","level":"兴趣点"}]}
                """);
        JsonNode walking = objectMapper.readTree("""
                {
                  "status":"1",
                  "route":{"paths":[{"distance":"1260","duration":"900","polyline":"106.0,29.0;106.1,29.1","steps":[{"walk_type":"21"},{"walk_type":22}]}]}
                }
                """);

        AmapResponseNormalizer.GeocodeResult geocodeResult = AmapResponseNormalizer.normalizeGeocode(geocode);
        AmapResponseNormalizer.RouteResult routeResult = AmapResponseNormalizer.normalizeWalking(walking);

        assertTrue(geocodeResult.resolved());
        assertEquals(106.580, geocodeResult.location().longitude());
        assertEquals(1260, routeResult.distanceMeters());
        assertEquals(900, routeResult.durationSeconds());
        assertEquals(List.of(21, 22), routeResult.walkTypes());
        assertTrue(routeResult.metricsComplete());
    }

    @Test
    void keepsTransitWalkingMetricsOptionalAndRejectsProviderErrors() throws Exception {
        JsonNode transit = objectMapper.readTree("""
                {
                  "status":"1",
                  "route":{"transits":[{"distance":"3000","duration":"1800","walking_distance":"650","segments":[{"walking":{"steps":[{"walk_type":10}]}}]}]}
                }
                """);
        JsonNode error = objectMapper.readTree("{\"status\":\"0\",\"info\":\"KEY错误\",\"infocode\":\"10001\"}");

        AmapResponseNormalizer.RouteResult result = AmapResponseNormalizer.normalizeTransit(transit);

        assertEquals(3000, result.distanceMeters());
        assertEquals(650, result.walkingDistanceMeters());
        assertEquals(List.of(10), result.walkTypes());
        assertTrue(result.metricsComplete());
        assertThrows(AmapResponseNormalizer.AmapResponseException.class,
                () -> AmapResponseNormalizer.normalizePois(error));
        assertFalse(AmapResponseNormalizer.normalizeWalking(
                objectMapper.readTree("{\"status\":\"1\",\"route\":{}}"))
                .metricsComplete());
        assertNull(AmapResponseNormalizer.normalizeGeocode(
                objectMapper.readTree("{\"status\":\"1\",\"geocodes\":[]}"))
                .location());
    }
}

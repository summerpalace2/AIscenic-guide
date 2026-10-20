package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;

import com.ai.guide.infrastructure.planner.amap.AmapRequestCache;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;



import com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class AmapRouteServiceTest {

    @Test
    void resolvesNamedCityAdcodeBeforeWeatherAndCachesTheResult() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/geocode/geo")))
                .andRespond(withSuccess("""
                        {"status":"1","geocodes":[{"location":"120.15,30.28","adcode":"330100"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("city", "330100"))
                .andRespond(withSuccess("{\"status\":\"1\",\"forecasts\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(GeoOutcomeStatus.SUCCESS, service.weather("杭州市").status());
        assertEquals(GeoOutcomeStatus.SUCCESS, service.weather("杭州市").status());
        server.verify();
        service.close();
    }

    @Test
    void doesNotSubstituteAnotherCityWhenGeocodingFails() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/geocode/geo")))
                .andRespond(withSuccess("{\"status\":\"1\",\"geocodes\":[]}", MediaType.APPLICATION_JSON));
        assertEquals(GeoOutcomeStatus.INVALID, service.weather("未解析城市").status());
        server.verify();
        service.close();
    }

    @Test
    void successfulRouteAndWeatherResultsAreReused() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/direction/transit/integrated")))
                .andExpect(method(GET))
                .andExpect(request -> assertTrue(request.getURI().getQuery().contains("city=重庆市")))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"status":"1","forecasts":[{"reporttime":"2026-08-17 08:00:00","casts":[{"date":"2026-08-17","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"26"}]}]}
                        """, MediaType.APPLICATION_JSON));

        GeoRoutePairOutcome first = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        GeoRoutePairOutcome second = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        GeoWeatherOutcome firstWeather = service.weather("500000");
        GeoWeatherOutcome secondWeather = service.weather("500000");

        assertEquals(GeoOutcomeStatus.SUCCESS, first.selected().status());
        assertEquals(GeoOutcomeStatus.SUCCESS, second.selected().status());
        assertEquals(GeoOutcomeStatus.SUCCESS, firstWeather.status());
        assertEquals(GeoOutcomeStatus.SUCCESS, secondWeather.status());
        server.verify();
        service.close();
    }

    @Test
    void missingKeyReturnsJavaFallbackWithoutNetwork() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "", restTemplate));

        GeoRoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");

        assertEquals(GeoOutcomeStatus.UNAVAILABLE, result.walking().status());
        assertTrue(result.selected().reason().contains("未配置"));
        server.verify();
        service.close();
    }

    @Test
    void v5SemanticFailureDoesNotRetryThroughV3() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapWebServiceClient client = new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate);
        AmapRouteService service = new AmapRouteService(client);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"兼容错误\"}", MediaType.APPLICATION_JSON));

        GeoRoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "023", "", "", "transit");

        assertEquals(GeoOutcomeStatus.UNAVAILABLE, result.transit().status());
        assertEquals("v5/direction/transit/integrated", result.transit().endpoint());
        server.verify();
        service.close();
    }

    @Test
    void qpsSemanticFailureDoesNotRetryOrUseTransitV3Fallback() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"}
                        """, MediaType.APPLICATION_JSON));

        GeoRoutePairOutcome first = service.routePair(
                "106.580,29.563", "106.581,29.564", "023", "", "", "walking");
        GeoRoutePairOutcome second = service.routePair(
                "106.580,29.563", "106.581,29.564", "023", "", "", "walking");

        assertEquals(GeoOutcomeStatus.RATE_LIMITED, first.transit().status());
        assertTrue(first.transit().reason().contains("10021"));
        assertEquals(GeoOutcomeStatus.RATE_LIMITED, second.transit().status());
        assertEquals(GeoOutcomeStatus.SUCCESS, second.walking().status());
        server.verify();
        service.close();
    }

    @Test
    void poiQpsFailureActivatesCooldownWithoutSecondProviderCall() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"}
                        """, MediaType.APPLICATION_JSON));

        GeoPoiOutcome first = service.searchPoi("洪崖洞", "重庆市");
        GeoPoiOutcome second = service.searchPoi("解放碑", "重庆市");

        assertEquals(GeoOutcomeStatus.RATE_LIMITED, first.status());
        assertTrue(first.reason().contains("10021"));
        assertEquals(GeoOutcomeStatus.RATE_LIMITED, second.status());
        assertTrue(second.reason().contains("冷却"));
        server.verify();
        service.close();
    }

    @Test
    void weatherIsNormalizedAsDynamicEvidence() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"status":"1","forecasts":[{"reporttime":"2026-08-17 08:00:00","casts":[{"date":"2026-08-17","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"26"}]}]}
                        """, MediaType.APPLICATION_JSON));

        GeoWeatherOutcome result = service.weather("500000");

        assertEquals(GeoOutcomeStatus.SUCCESS, result.status());
        assertTrue(result.weather().available());
        assertEquals("晴", result.weather().casts().get(0).dayWeather());
        server.verify();
        service.close();
    }

    @Test
    void invalidCoordinatesAreRejectedBeforeProviderCall() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        GeoRoutePairOutcome result = service.routePair(
                "not-a-coordinate", "106.581,29.564", "重庆市", "", "", "transit");

        assertEquals(GeoOutcomeStatus.INVALID, result.selected().status());
        assertFalse(result.selected().reason().isBlank());
        server.verify();
        service.close();
    }

    @Test
    void semanticProviderFailureIsNotRetried() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"服务不可用\"}", MediaType.APPLICATION_JSON));

        GeoPoiOutcome result = service.searchPoi("洪崖洞", "重庆市");

        assertEquals(GeoOutcomeStatus.UNAVAILABLE, result.status());
        assertTrue(result.reason().contains("服务不可用"));
        server.verify();
        service.close();
    }

    @Test
    void transientTimeoutIsRetriedAndClassifiedAsTimeout() {
        AtomicInteger calls = new AtomicInteger();
        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> T getForObject(URI url, Class<T> responseType) {
                calls.incrementAndGet();
                throw new ResourceAccessException("read timed out", new SocketTimeoutException("read timed out"));
            }
        };
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        GeoPoiOutcome result = service.searchPoi("洪崖洞", "重庆市");

        assertEquals(GeoOutcomeStatus.TIMEOUT, result.status());
        assertEquals(2, calls.get());
        service.close();
    }

    @Test
    void routePairExecutesWalkingAndTransitConcurrently() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        RestTemplate restTemplate = new RestTemplate() {
            @Override
            public <T> T getForObject(URI url, Class<T> responseType) {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                bothEntered.countDown();
                try {
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                try {
                    String json = url.toString().contains("walking") ? walkingJson() : transitJson();
                    return (T) mapper.readTree(json);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        java.util.concurrent.CompletableFuture<GeoRoutePairOutcome> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> service.routePair(
                        "106.580,29.563", "106.581,29.564", "重庆市", "", "", "transit"));

        assertTrue(bothEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "步行和公交应同时发起调用");
        assertEquals(2, maxActive.get(), "最大并发调用应达到2");
        release.countDown();

        GeoRoutePairOutcome outcome = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(GeoOutcomeStatus.SUCCESS, outcome.walking().status());
        assertEquals(GeoOutcomeStatus.SUCCESS, outcome.transit().status());
        assertEquals(GeoOutcomeStatus.SUCCESS, outcome.selected().status());
        assertEquals("TRANSIT", outcome.selected().segment().mode());
        assertEquals("transit", outcome.preference());
        assertFalse(outcome.taxiUnsupported());
        service.close();
    }

    @Test
    void maxConcurrencyOneDoesNotDeadlock() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate),
                6000, 6500, 1, new AmapRequestCache(256), true, true, 60000, 10000, 60000, 2000);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/direction/transit/integrated")))
                .andExpect(request -> assertTrue(request.getURI().getQuery().contains("city=重庆市")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            GeoRoutePairOutcome result = service.routePair(
                    "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
            assertEquals(GeoOutcomeStatus.SUCCESS, result.walking().status());
            assertEquals(GeoOutcomeStatus.SUCCESS, result.transit().status());
            assertEquals(GeoOutcomeStatus.SUCCESS, result.selected().status());
            assertEquals("WALKING", result.selected().segment().mode());
        });

        server.verify();
        service.close();
    }

    private String walkingJson() {
        return """
                {"status":"1","route":{"paths":[{"distance":"1200","duration":"900","polyline":"106.580,29.563;106.581,29.564"}]}}
                """;
    }

    private String transitJson() {
        return """
                {"status":"1","route":{"transits":[{"distance":"1800","duration":"1200","walking_distance":"300","polyline":"106.580,29.563;106.581,29.564"}]}}
                """;
    }

    @Test
    void v5TransitUsesExplicitCitycodePairAndNormalizesProviderWireShape() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        expectV5Transit(server, "106.580,29.563", "106.581,29.564");
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        expectV5Transit(server, "106.582,29.565", "106.583,29.566");

        GeoRoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "023", "poi-origin", "poi-destination", "transit");
        GeoRoutePairOutcome cachedCityCode = service.routePair(
                "106.582,29.565", "106.583,29.566", "023", "poi-origin-2", "poi-destination-2", "transit");

        assertEquals(GeoOutcomeStatus.SUCCESS, result.transit().status(), result.transit().reason());
        assertEquals("v5/direction/transit/integrated", result.transit().endpoint());
        assertEquals(2400, result.transit().segment().durationSeconds());
        assertEquals(350, result.transit().segment().walkingDistanceMeters());
        assertTrue(result.transit().segment().polyline().contains("106.580,29.563;106.581,29.564"));
        assertEquals(GeoOutcomeStatus.SUCCESS, cachedCityCode.transit().status());
        server.verify();
        service.close();
    }

    private void expectV5Transit(MockRestServiceServer server, String origin, String destination) {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("city1", "023"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("city2", "023"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("originpoi",
                        org.hamcrest.Matchers.containsString("poi-")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam("destinationpoi",
                        org.hamcrest.Matchers.containsString("poi-")))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"transits":[{"distance":"4200","cost":{"duration":"2400"},
                          "segments":[
                            {"walking":{"distance":"120","steps":[{"polyline":"106.580,29.563;106.581,29.564"}]}},
                            {"bus":{"buslines":[{"polyline":"106.581,29.564;106.582,29.565"}]}},
                            {"walking":{"distance":"230","steps":[{"polyline":"106.582,29.565;106.583,29.566"}]}}
                          ]}]}}
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    void cityNameUsesV3CompatibilityWithoutCallingV5OrGeocoding() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/direction/transit/integrated")))
                .andExpect(request -> assertTrue(request.getURI().getQuery().contains("city=重庆市")))
                .andRespond(withSuccess("""
                        {"status":"1","route":{"transits":[{"distance":"2500","duration":"1200"}]}}
                        """, MediaType.APPLICATION_JSON));

        GeoRoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "transit");

        server.verify();
        assertEquals(GeoOutcomeStatus.SUCCESS, result.transit().status(), result.transit().reason());
        assertEquals("v3/direction/transit/integrated", result.transit().endpoint());
        assertTrue(result.transit().reason().contains("citycode"));
        server.verify();
        service.close();
    }

    @Test
    void numericAdcodeIsRejectedWithoutTransitProviderRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));

        GeoRoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "500103", "", "", "transit");

        assertEquals(GeoOutcomeStatus.INVALID, result.transit().status());
        assertTrue(result.transit().reason().contains("城市编码格式无效"));
        server.verify();
        service.close();
    }
}



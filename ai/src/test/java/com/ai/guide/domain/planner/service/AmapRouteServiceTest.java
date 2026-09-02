package com.ai.guide.domain.planner.service;



import com.ai.guide.domain.rag.pipeline.AmapWebServiceClient;
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
    void successfulRouteAndWeatherResultsAreReused() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"status":"1","forecasts":[{"reporttime":"2026-08-17 08:00:00","casts":[{"date":"2026-08-17","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"26"}]}]}
                        """, MediaType.APPLICATION_JSON));

        AmapRouteService.RoutePairOutcome first = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        AmapRouteService.RoutePairOutcome second = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        AmapRouteService.WeatherOutcome firstWeather = service.weather("500000");
        AmapRouteService.WeatherOutcome secondWeather = service.weather("500000");

        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, first.selected().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, second.selected().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, firstWeather.status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, secondWeather.status());
        server.verify();
        service.close();
    }

    @Test
    void missingKeyReturnsJavaFallbackWithoutNetwork() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService service = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "", restTemplate));

        AmapRouteService.RoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");

        assertEquals(AmapRouteService.OutcomeStatus.UNAVAILABLE, result.walking().status());
        assertTrue(result.selected().reason().contains("未配置"));
        server.verify();
        service.close();
    }

    @Test
    void transitFallsBackFromV5ToV3AndWalkingPreferenceSelectsWalking() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapWebServiceClient client = new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate);
        AmapRouteService service = new AmapRouteService(client);

        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"兼容错误\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v3/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));

        AmapRouteService.RoutePairOutcome result = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        AmapRouteService.RoutePairOutcome cached = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");

        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.walking().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.transit().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, cached.transit().status());
        assertEquals("TRANSIT", result.transit().segment().mode());
        assertEquals("WALKING", result.selected().segment().mode());
        assertTrue(result.transit().reason().contains("v5 transit fallback"));
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

        AmapRouteService.RoutePairOutcome first = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
        AmapRouteService.RoutePairOutcome second = service.routePair(
                "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");

        assertEquals(AmapRouteService.OutcomeStatus.RATE_LIMITED, first.transit().status());
        assertTrue(first.transit().reason().contains("10021"));
        assertEquals(AmapRouteService.OutcomeStatus.RATE_LIMITED, second.transit().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, second.walking().status());
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

        AmapRouteService.PoiOutcome first = service.searchPoi("洪崖洞", "重庆市");
        AmapRouteService.PoiOutcome second = service.searchPoi("解放碑", "重庆市");

        assertEquals(AmapRouteService.OutcomeStatus.RATE_LIMITED, first.status());
        assertTrue(first.reason().contains("10021"));
        assertEquals(AmapRouteService.OutcomeStatus.RATE_LIMITED, second.status());
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

        AmapRouteService.WeatherOutcome result = service.weather("500000");

        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.status());
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

        AmapRouteService.RoutePairOutcome result = service.routePair(
                "not-a-coordinate", "106.581,29.564", "重庆市", "", "", "transit");

        assertEquals(AmapRouteService.OutcomeStatus.INVALID, result.selected().status());
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

        AmapRouteService.PoiOutcome result = service.searchPoi("洪崖洞", "重庆市");

        assertEquals(AmapRouteService.OutcomeStatus.UNAVAILABLE, result.status());
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

        AmapRouteService.PoiOutcome result = service.searchPoi("洪崖洞", "重庆市");

        assertEquals(AmapRouteService.OutcomeStatus.TIMEOUT, result.status());
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

        java.util.concurrent.CompletableFuture<AmapRouteService.RoutePairOutcome> future =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> service.routePair(
                        "106.580,29.563", "106.581,29.564", "重庆市", "", "", "transit"));

        assertTrue(bothEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "步行和公交应同时发起调用");
        assertEquals(2, maxActive.get(), "最大并发调用应达到2");
        release.countDown();

        AmapRouteService.RoutePairOutcome outcome = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, outcome.walking().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, outcome.transit().status());
        assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, outcome.selected().status());
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
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            AmapRouteService.RoutePairOutcome result = service.routePair(
                    "106.580,29.563", "106.581,29.564", "重庆市", "", "", "walking");
            assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.walking().status());
            assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.transit().status());
            assertEquals(AmapRouteService.OutcomeStatus.SUCCESS, result.selected().status());
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
}

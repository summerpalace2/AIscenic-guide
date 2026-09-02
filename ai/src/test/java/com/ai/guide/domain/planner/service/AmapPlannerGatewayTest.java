package com.ai.guide.domain.planner.service;




import com.ai.guide.domain.rag.pipeline.AmapWebServiceClient;
import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapPlannerGatewayTest {

    @Test
    void routeBatchUsesDedicatedExecutorBoundary() {
        AtomicReference<String> executionThread = new AtomicReference<>();
        ExecutorService hydrationExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-amap-hydration");
            thread.setDaemon(true);
            return thread;
        });
        AmapRouteService routeService = new AmapRouteService(null) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public RoutePairOutcome routePair(String origin, String destination, String city,
                                              String originPoi, String destinationPoi, String preference) {
                executionThread.set(Thread.currentThread().getName());
                return new RoutePairOutcome(null, null, null, preference, false);
            }
        };

        try {
            AmapPlannerGateway gateway = new AmapPlannerGateway(
                    routeService, mock(ItineraryBuilderPort.class), 3, hydrationExecutor);
            Map<String, Object> first = stop("a", "106.580,29.563");
            Map<String, Object> second = stop("b", "106.581,29.564");
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", "旅行第1天");
            day.put("stops", new ArrayList<>(List.of(first, second)));
            Map<String, Object> trip = new LinkedHashMap<>();
            trip.put("days", new ArrayList<>(List.of(day)));

            gateway.hydrate(trip);

            assertEquals("test-amap-hydration", executionThread.get());
        } finally {
            hydrationExecutor.shutdownNow();
            routeService.close();
        }
    }

    @Test
    void successfulProviderResultsAreProjectedIntoPlannerTrip() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AmapWebServiceClient client = new AmapWebServiceClient(
                "https://restapi.amap.com", "test-key", restTemplate);
        AmapRouteService routeService = new AmapRouteService(client);
        ItineraryBuilder itineraryBuilder = mock(ItineraryBuilder.class);
        when(itineraryBuilder.attraction(anyString())).thenReturn(Attraction.builder()
                .amapQuery(Map.of("keywords", "洪崖洞"))
                .build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, itineraryBuilder);

        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess(poiJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v5/direction/transit/integrated")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> first = stop("a", "106.580,29.563");
        Map<String, Object> second = stop("b", "106.581,29.564");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-08-17");
        day.put("stops", new ArrayList<>(List.of(first, second)));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        AmapPlannerGateway.HydrationResult result = gateway.hydrate(trip);

        assertEquals(2, result.poiSuccess());
        assertEquals(1, result.routeSuccess());
        assertEquals(1, result.weatherSuccess());
        assertEquals("高德实时接口", trip.get("sourceMode"));
        assertEquals("poi-a", first.get("amapPoiId"));
        Map<?, ?> route = (Map<?, ?>) second.get("routeFromPrevious");
        assertEquals("动态", ((Map<?, ?>) route.get("selected")).get("status"));
        assertEquals("步行", route.get("selectedMode"));
        assertTrue(((Map<?, ?>) trip.get("sourceStatus")).get("routeResolved").equals(1));
        server.verify();
        routeService.close();
    }

    @Test
    void undatedItinerarySkipsWeatherProviderAndKeepsUnknownFact() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService routeService = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, mock(ItineraryBuilder.class));

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "旅行第1天");
        day.put("stops", new ArrayList<>());
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        AmapPlannerGateway.HydrationResult result = gateway.hydrate(trip);

        assertEquals(0, result.weatherSuccess());
        assertEquals("未知", ((Map<?, ?>) day.get("weather")).get("status"));
        assertEquals(0, ((Map<?, ?>) trip.get("sourceStatus")).get("weatherDaysResolved"));
        server.verify();
        routeService.close();
    }

    @Test
    void duplicateAndExcessPoiKeywordsAreBounded() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService routeService = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        ItineraryBuilder itineraryBuilder = mock(ItineraryBuilder.class);
        when(itineraryBuilder.attraction(anyString())).thenReturn(Attraction.builder()
                .amapQuery(Map.of("keywords", List.of("洪崖洞", "洪崖洞"),
                        "alternatives", List.of("备用关键词", "第三关键词")))
                .build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, itineraryBuilder, 2);

        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess(poiJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "旅行第1天");
        day.put("stops", new ArrayList<>(List.of(stop("a", "106.580,29.563"))));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        AmapPlannerGateway.HydrationResult result = gateway.hydrate(trip);

        assertEquals(1, result.poiSuccess());
        server.verify();
        routeService.close();
    }

    @Test
    void outOfWindowForecastReplacesStaleWeatherWithUnavailableFact() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService routeService = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, mock(ItineraryBuilder.class));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-08-18");
        day.put("weather", new LinkedHashMap<>(Map.of("value", "旧天气", "status", "动态")));
        day.put("stops", new ArrayList<>());
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        AmapPlannerGateway.HydrationResult result = gateway.hydrate(trip);

        assertEquals(0, result.weatherSuccess());
        Map<?, ?> weather = (Map<?, ?>) day.get("weather");
        assertEquals("不可用·待确认", weather.get("value"));
        assertEquals("未知", weather.get("status"));
        server.verify();
        routeService.close();
    }

    @Test
    void invalidPoiCandidateIsRejectedAndStaticProjectionRemains() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService routeService = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        ItineraryBuilder itineraryBuilder = mock(ItineraryBuilder.class);
        when(itineraryBuilder.attraction(anyString())).thenReturn(Attraction.builder()
                .amapQuery(Map.of("keywords", "洪崖洞"))
                .build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, itineraryBuilder);
        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"1\",\"pois\":[{\"id\":\"\",\"name\":\"\",\"location\":\"\"}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> stop = stop("a", "106.580,29.563");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "2026-08-17");
        day.put("stops", new ArrayList<>(List.of(stop)));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        AmapPlannerGateway.HydrationResult result = gateway.hydrate(trip);

        assertEquals(0, result.poiSuccess());
        assertEquals(1, result.weatherSuccess());
        assertTrue(!stop.containsKey("amapPoiId"));
        assertEquals(0, ((Map<?, ?>) trip.get("sourceStatus")).get("poiResolved"));
        server.verify();
        routeService.close();
    }

    private Map<String, Object> stop(String venueId, String location) {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("venueId", venueId);
        stop.put("displayName", venueId);
        stop.put("name", venueId);
        stop.put("location", location);
        stop.put("routePreference", "walking");
        stop.put("facts", new ArrayList<>());
        return stop;
    }

    private String poiJson() {
        return """
                {"status":"1","pois":[{"id":"poi-a","name":"洪崖洞","location":"106.5801,29.5631","address":"重庆市渝中区嘉陵江滨江路"}]}
                """;
    }

    private String weatherJson() {
        return """
                {"status":"1","forecasts":[{"reporttime":"2026-08-17 08:00:00","casts":[{"date":"2026-08-17","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"26"}]}]}
                """;
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

package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.HydrationResult;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.infrastructure.planner.amap.AmapPlannerGateway;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;




import com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void hydrationCannotReplacePreselectedPoiOrItsRouteCoordinate() {
        AmapRouteService routeService = mock(AmapRouteService.class);
        when(routeService.isConfigured()).thenReturn(true);
        when(routeService.searchPoi(anyString(), org.mockito.ArgumentMatchers.eq("北京市")))
                .thenReturn(new com.ai.guide.domain.geo.model.GeoPoiOutcome(
                        com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS,
                        List.of(new GeoPoiCandidate("other-poi", "故宫博物院", new GeoCoordinate(116.5, 39.9),
                                "", "", null, null, null, null, null, null, null, null, null, "北京市")),
                        "", java.time.Instant.now()));
        ItineraryBuilderPort builder = mock(ItineraryBuilderPort.class);
        when(builder.attraction("bj-palace-museum")).thenReturn(Attraction.builder().id("bj-palace-museum")
                .name("故宫博物院").city("北京").amapQuery(Map.of("keywords", "故宫博物院")).build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, builder);
        gateway.setPoiIdentityPolicy(() -> new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 20));
        Map<String, Object> stop = stop("bj-palace-museum", "116.4,39.9");
        stop.put("preselectedPoiId", "original-poi");
        stop.put("amapPoiId", "original-poi");
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("city", "北京市");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("date", "第1天");
        day.put("stops", new ArrayList<>(List.of(stop)));
        trip.put("days", new ArrayList<>(List.of(day)));
        HydrationResult result = gateway.hydrate(trip);
        assertEquals(0, result.poiSuccess());
        assertEquals("original-poi", stop.get("amapPoiId"));
        assertEquals("116.4,39.9", stop.get("location"));
        when(routeService.searchPoi(anyString(), org.mockito.ArgumentMatchers.eq("北京市")))
                .thenReturn(new com.ai.guide.domain.geo.model.GeoPoiOutcome(
                        com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS,
                        List.of(new GeoPoiCandidate("original-poi", "故宫博物院", new GeoCoordinate(116.6, 39.9),
                                "", "", null, null, null, null, null, null, null, null, null, "北京市")),
                        "", java.time.Instant.now()));
        assertEquals(0, gateway.hydrate(trip).poiSuccess());
        assertEquals("116.4,39.9", stop.get("location"));
        when(routeService.searchPoi(anyString(), org.mockito.ArgumentMatchers.eq("北京市")))
                .thenReturn(new com.ai.guide.domain.geo.model.GeoPoiOutcome(
                        com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS,
                        List.of(new GeoPoiCandidate("other-poi", "故宫博物院", new GeoCoordinate(116.5, 39.9),
                                        "", "", null, null, null, null, null, null, null, null, null, "北京市"),
                                new GeoPoiCandidate("original-poi", "故宫博物院", new GeoCoordinate(116.4, 39.9),
                                        "", "", null, null, null, null, null, null, null, null, null, "北京市")),
                        "", java.time.Instant.now()));
        assertEquals(1, gateway.hydrate(trip).poiSuccess(), "已预选的精确 POI 应在同名干扰项中获选");
        assertEquals("original-poi", stop.get("amapPoiId"));
        assertEquals("116.4,39.9", stop.get("location"));
    }

    @Test
    void unresolvedCityCatalogPoiMarksDraftDegradedWithoutBorrowingAnotherCityCoordinate() {
        AmapRouteService routeService = mock(AmapRouteService.class);
        when(routeService.isConfigured()).thenReturn(true);
        when(routeService.searchPoi(anyString(), org.mockito.ArgumentMatchers.eq("北京市")))
                .thenReturn(new com.ai.guide.domain.geo.model.GeoPoiOutcome(
                        com.ai.guide.domain.geo.model.GeoOutcomeStatus.UNAVAILABLE, List.of(),
                        "当前城市未找到可核验 POI", java.time.Instant.now()));
        ItineraryBuilderPort builder = mock(ItineraryBuilderPort.class);
        when(builder.attraction("bj-palace-museum")).thenReturn(Attraction.builder()
                .id("bj-palace-museum").name("故宫博物院").city("北京")
                .amapQuery(Map.of("keywords", "故宫博物院", "matches", List.of("故宫博物院"))).build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, builder);
        Map<String, Object> stop = stop("bj-palace-museum", "");
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("city", "北京市");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "旅行第1天");
        day.put("stops", new ArrayList<>(List.of(stop)));
        trip.put("days", new ArrayList<>(List.of(day)));

        gateway.hydrate(trip);

        assertEquals(true, trip.get("degraded"));
        assertTrue(((List<?>) trip.get("degradationReasons")).contains("POI_COORDINATES_UNVERIFIED"));
        assertEquals(true, ((Map<?, ?>) trip.get("sourceStatus")).get("coordinatesUnverified"));
        assertEquals("", stop.get("location"));
        org.mockito.Mockito.verify(routeService).searchPoi("故宫博物院", "北京市");
        org.mockito.Mockito.verify(routeService, org.mockito.Mockito.never())
                .searchPoi(anyString(), org.mockito.ArgumentMatchers.eq("重庆市"));

        stop.put("location", "116.397,39.916");
        gateway.hydrate(trip);
        assertTrue(!((List<?>) trip.get("degradationReasons")).contains("POI_COORDINATES_UNVERIFIED"));
        assertEquals(false, ((Map<?, ?>) trip.get("sourceStatus")).get("coordinatesUnverified"));
        assertEquals(true, trip.get("degraded"), "other supplier failures still require fallback");
    }

    @Test
    void poiIdentitySelectionUsesTheLatestPolicySnapshot() throws Exception {
        AmapPlannerGateway gateway = new AmapPlannerGateway(null, (com.ai.guide.domain.attraction.service.AttractionService) null, 3);
        AtomicReference<com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy> current =
                new AtomicReference<>(new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 20));
        gateway.setPoiIdentityPolicy(current::get);
        GeoPoiCandidate candidate = new GeoPoiCandidate("poi-1", "Target", new GeoCoordinate(1, 1),
                "", "", null, null, null, null, null, null, null, null, null, "北京市");
        Attraction attraction = Attraction.builder().amapQuery(Map.of("keywords", "Target")).build();
        Method select = AmapPlannerGateway.class.getDeclaredMethod("select", List.class, Attraction.class, String.class);
        select.setAccessible(true);

        assertEquals(candidate, select.invoke(gateway, List.of(candidate), attraction, "北京"));
        GeoPoiCandidate duplicate = new GeoPoiCandidate("poi-duplicate", "Target", new GeoCoordinate(2, 2),
                "", "", null, null, null, null, null, null, null, null, null, "北京");
        assertEquals(null, select.invoke(gateway, List.of(candidate, duplicate), attraction, "北京"));
        GeoPoiCandidate missingCity = new GeoPoiCandidate("poi-missing-city", "Target", new GeoCoordinate(1, 1),
                "", "", null, null, null, null, null);
        assertEquals(null, select.invoke(gateway, List.of(missingCity), attraction, "北京"));
        GeoPoiCandidate wrongCity = new GeoPoiCandidate("poi-2", "Target", new GeoCoordinate(1, 1),
                "", "", null, null, null, null, null, null, null, null, null, "重庆市");
        assertEquals(null, select.invoke(gateway, List.of(wrongCity), attraction, "北京"));
        current.set(new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 121));
        assertEquals(null, select.invoke(gateway, List.of(candidate), attraction, "北京"));
    }

    @Test
    void chineseProviderWireAcceptsOnlyCatalogExactAliasesAndRejectsAmbiguousIdentity() {
        var mainPoi = """
                {"status":"1","infocode":"10000","count":"3","pois":[
                  {"id":"branch","name":"浙江省博物馆(之江馆区)","cityname":"杭州市","location":"120.1,30.1"},
                  {"id":"gushan-main","name":"浙江省博物馆(孤山馆区)","cityname":"杭州市","location":"120.2,30.2"},
                  {"id":"wrong-city","name":"浙江省博物馆(孤山馆区)","cityname":"北京市","location":"116.4,39.9"}]}
                """;
        Map<String, Object> selected = hydrateFromPoiWire(mainPoi, List.of(
                "浙江省博物馆孤山馆区", "浙江省博物馆(孤山馆区)"));
        assertEquals("gushan-main", selected.get("amapPoiId"));
        assertEquals("120.2,30.2", selected.get("location"));

        var branchOnly = """
                {"status":"1","infocode":"10000","count":"1","pois":[
                  {"id":"branch","name":"浙江省博物馆(之江馆区)","cityname":"杭州市","location":"120.1,30.1"}]}
                """;
        Map<String, Object> rejectedBranch = hydrateFromPoiWire(branchOnly,
                List.of("浙江省博物馆孤山馆区", "浙江省博物馆(孤山馆区)"));
        assertNull(rejectedBranch.get("amapPoiId"));
        assertEquals("", rejectedBranch.get("location"));

        var ambiguous = """
                {"status":"1","infocode":"10000","count":"2","pois":[
                  {"id":"gushan-a","name":"浙江省博物馆(孤山馆区)","cityname":"杭州市","location":"120.2,30.2"},
                  {"id":"gushan-b","name":"浙江省博物馆(孤山馆区)","cityname":"杭州市","location":"120.3,30.3"}]}
                """;
        Map<String, Object> rejectedDuplicate = hydrateFromPoiWire(ambiguous,
                List.of("浙江省博物馆孤山馆区", "浙江省博物馆(孤山馆区)"));
        assertNull(rejectedDuplicate.get("amapPoiId"));
        assertEquals("", rejectedDuplicate.get("location"));
    }

    private Map<String, Object> hydrateFromPoiWire(String poiWire, List<String> exactNames) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AmapRouteService routeService = new AmapRouteService(
                new AmapWebServiceClient("https://restapi.amap.com", "test-key", restTemplate));
        ItineraryBuilderPort builder = mock(ItineraryBuilderPort.class);
        when(builder.attraction("hz-zhejiang-museum-gushan")).thenReturn(Attraction.builder()
                .id("hz-zhejiang-museum-gushan").name("浙江省博物馆孤山馆区").city("杭州")
                .amapQuery(Map.of("keywords", "浙江省博物馆孤山馆区", "matches", exactNames)).build());
        AmapPlannerGateway gateway = new AmapPlannerGateway(routeService, builder);
        gateway.setPoiIdentityPolicy(() -> new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 20));
        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess(poiWire, MediaType.APPLICATION_JSON));
        Map<String, Object> stop = stop("hz-zhejiang-museum-gushan", "");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "旅行第1天");
        day.put("stops", new ArrayList<>(List.of(stop)));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("city", "杭州市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        gateway.hydrate(trip);

        server.verify();
        routeService.close();
        return stop;
    }

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
            public GeoRoutePairOutcome routePair(String origin, String destination, String city,
                                              String originPoi, String destinationPoi, String preference) {
                executionThread.set(Thread.currentThread().getName());
                return new GeoRoutePairOutcome(null, null, null, preference, false);
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
            gateway.setPoiIdentityPolicy(() -> new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 20));

        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess(poiJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/geocode/geo")))
                .andRespond(withSuccess(cityGeocodeJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v5/direction/walking")))
                .andExpect(method(GET))
                .andRespond(withSuccess(walkingJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/direction/transit/integrated")))
                .andExpect(request -> assertTrue(request.getURI().getQuery().contains("city=重庆市")))
                .andExpect(method(GET))
                .andRespond(withSuccess(transitJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> first = stop("a", "106.580,29.563");
        Map<String, Object> second = stop("b", "106.581,29.564");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", LocalDate.now().plusDays(1).toString());
        day.put("stops", new ArrayList<>(List.of(first, second)));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("city", "重庆市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        HydrationResult result = gateway.hydrate(trip);

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
        trip.put("city", "重庆市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        HydrationResult result = gateway.hydrate(trip);

        assertEquals(0, result.weatherSuccess());
        assertEquals("未知", ((Map<?, ?>) day.get("weather")).get("status"));
        assertEquals(0, ((Map<?, ?>) trip.get("sourceStatus")).get("weatherDaysResolved"));
        assertEquals(0.0, ((Map<?, ?>) trip.get("sourceStatus")).get("dataCoverage"));
        assertEquals(true, ((Map<?, ?>) trip.get("sourceStatus")).get("fallback"));
        assertEquals("演示回退模式", trip.get("sourceMode"));
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
        gateway.setPoiIdentityPolicy(() -> new com.ai.guide.domain.geo.gateway.PoiIdentityPolicyPort.Policy(100, 60, 10, 10, 20));

        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v5/place/text")))
                .andExpect(method(GET))
                .andRespond(withSuccess(poiJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", "旅行第1天");
        day.put("stops", new ArrayList<>(List.of(stop("a", "106.580,29.563"))));
        Map<String, Object> catalogStop = (Map<String, Object>) ((List<?>) day.get("stops")).get(0);
        catalogStop.put("citations", List.of(Map.of("title", "景点简介官方来源", "status", "来源已核验",
                "url", "https://example.org/official", "verifiedFields", List.of("summary"))));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("city", "重庆市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        HydrationResult result = gateway.hydrate(trip);

        assertEquals(1, result.poiSuccess());
        assertTrue(((List<?>) catalogStop.get("citations")).stream().anyMatch(source ->
                "https://example.org/official".equals(((Map<?, ?>) source).get("url"))));
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
        server.expect(requestTo(containsString("/v3/geocode/geo")))
                .andRespond(withSuccess(cityGeocodeJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", LocalDate.now().plusDays(10).toString());
        day.put("weather", new LinkedHashMap<>(Map.of("value", "旧天气", "status", "动态")));
        day.put("stops", new ArrayList<>());
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("city", "重庆市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        HydrationResult result = gateway.hydrate(trip);

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
        server.expect(requestTo(containsString("/v3/geocode/geo")))
                .andRespond(withSuccess(cityGeocodeJson(), MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v3/weather/weatherInfo")))
                .andExpect(method(GET))
                .andRespond(withSuccess(weatherJson(), MediaType.APPLICATION_JSON));

        Map<String, Object> stop = stop("a", "106.580,29.563");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("date", LocalDate.now().plusDays(1).toString());
        day.put("stops", new ArrayList<>(List.of(stop)));
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("days", new ArrayList<>(List.of(day)));
        trip.put("city", "重庆市");
        trip.put("sourceStatus", new LinkedHashMap<>());
        trip.put("qualityMetrics", new LinkedHashMap<>());
        trip.put("retrieval", new LinkedHashMap<>());

        HydrationResult result = gateway.hydrate(trip);

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

    private String cityGeocodeJson() {
        return "{\"status\":\"1\",\"geocodes\":[{\"location\":\"106.58,29.56\",\"adcode\":\"500000\"}]}";
    }

    private String poiJson() {
        return """
                {"status":"1","pois":[{"id":"poi-a","name":"洪崖洞","cityname":"重庆市","location":"106.5801,29.5631","address":"重庆市渝中区嘉陵江滨江路"}]}
                """;
    }

    private String weatherJson() {
        String forecastDate = LocalDate.now().plusDays(1).toString();
        return """
                {"status":"1","forecasts":[{"reporttime":"%s 08:00:00","casts":[{"date":"%s","dayweather":"晴","nightweather":"多云","daytemp":"34","nighttemp":"26"}]}]}
                """.formatted(forecastDate, forecastDate);
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


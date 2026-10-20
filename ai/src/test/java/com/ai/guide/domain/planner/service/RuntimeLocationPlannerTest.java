package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;
import com.ai.guide.domain.dining.service.DiningVenueService;

import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.infrastructure.geo.amap.AmapResponseNormalizer;
import com.ai.guide.infrastructure.planner.amap.AmapPlannerGateway;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeLocationPlannerTest {

    @Test
    void buildsNearbyPlanFromResolvedStartWithoutCoreCatalogOrRag() {
        com.ai.guide.domain.geo.model.GeoCoordinate origin = new com.ai.guide.domain.geo.model.GeoCoordinate(106.33, 29.58);
        com.ai.guide.domain.geo.model.GeoCoordinate target = new com.ai.guide.domain.geo.model.GeoCoordinate(106.335, 29.585);
        com.ai.guide.domain.geo.model.GeoPoiCandidate start = poi("start", "测试起点", origin);
        com.ai.guide.domain.geo.model.GeoPoiCandidate nearby = poi("nearby", "动态周边景点", target);
        com.ai.guide.domain.geo.model.GeoRouteSegment segment = new com.ai.guide.domain.geo.model.GeoRouteSegment(
                "WALKING", 1000, 600, 1000, 1, List.of(), "106.33,29.58;106.335,29.585", true);
        GeoRouteOutcome route = new GeoRouteOutcome(
                GeoOutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public GeoPoiOutcome searchPoi(String keywords, String city) {
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(start), "", Instant.now());
            }

            @Override
            public GeoPoiOutcome searchAround(String location, int radiusMeters, String types,
                                           String keywords, String city) {
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(nearby), "", Instant.now());
            }

            @Override
            public GeoRoutePairOutcome routePair(String origin, String destination, String city,
                                              String originPoi, String destinationPoi, String preference) {
                return new GeoRoutePairOutcome(route, route, route, preference, false);
            }
        };

        TravelConstraints constraints = new TravelConstraintParser().parse("我在测试起点 上午8点出发 给我3小时的旅游规划");
        var trip = planner(provider).plan(constraints, 1);

        assertNotNull(trip);
        assertEquals("COMPLETED", ((java.util.Map<?, ?>) trip.get("spatialPlan")).get("status"));
        assertEquals("entity-gated-rag", ((java.util.Map<?, ?>) trip.get("retrieval")).get("mode"));
        assertEquals("动态周边景点", ((java.util.Map<?, ?>) ((java.util.List<?>) ((java.util.Map<?, ?>)
                ((java.util.List<?>) trip.get("days")).get(0)).get("stops")).get(0)).get("name"));
        // Verify date and dateLabel default to today
        java.util.Map<?, ?> firstDay = (java.util.Map<?, ?>) ((java.util.List<?>) trip.get("days")).get(0);
        assertEquals(java.time.LocalDate.now().toString(), firstDay.get("date"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(firstDay.get("dateLabel")).contains(java.time.LocalDate.now().toString()));
    }

    @Test
    void planFromLocationHydratesWeatherForToday() {
        com.ai.guide.domain.geo.model.GeoCoordinate origin = new com.ai.guide.domain.geo.model.GeoCoordinate(106.53, 29.50);
        com.ai.guide.domain.geo.model.GeoCoordinate target = new com.ai.guide.domain.geo.model.GeoCoordinate(106.535, 29.505);
        com.ai.guide.domain.geo.model.GeoPoiCandidate start = poi("cqu", "重庆交通大学", origin);
        com.ai.guide.domain.geo.model.GeoPoiCandidate nearby = poi("nanping", "南滨路", target);
        com.ai.guide.domain.geo.model.GeoRouteSegment segment = new com.ai.guide.domain.geo.model.GeoRouteSegment(
                "WALKING", 1200, 720, 1200, 1, List.of(), "106.53,29.50;106.535,29.505", true);
        GeoRouteOutcome route = new GeoRouteOutcome(
                GeoOutcomeStatus.SUCCESS, segment, "", "test", Instant.now());
        com.ai.guide.domain.geo.model.GeoWeatherCast todayCast = new com.ai.guide.domain.geo.model.GeoWeatherCast(
                java.time.LocalDate.now().toString(), "多云", "晴", "26", "20");
        com.ai.guide.domain.geo.model.GeoWeatherForecast weatherResult = new com.ai.guide.domain.geo.model.GeoWeatherForecast(
                java.time.LocalDate.now() + " 10:00:00", List.of(todayCast), true);

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String k, String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l, int r, String t, String k, String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(nearby), "", Instant.now()); }
            @Override public GeoRoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) { return new GeoRoutePairOutcome(route, route, route, p, false); }
            @Override public GeoWeatherOutcome weather(String city) { return new GeoWeatherOutcome(GeoOutcomeStatus.SUCCESS, weatherResult, "", Instant.now()); }
        };

        RuntimeLocationPlanner planner = planner(provider);
        AmapPlannerGateway gateway = new AmapPlannerGateway(provider, null, planner, null, 1);
        TravelConstraints constraints = new TravelConstraintParser().parse("我在重庆交通大学 为我规划一个3小时旅游方案");
        var trip = gateway.planFromLocation(constraints, 1);

        assertNotNull(trip);
        java.util.Map<?, ?> firstDay = (java.util.Map<?, ?>) ((java.util.List<?>) trip.get("days")).get(0);
        assertEquals(java.time.LocalDate.now().toString(), firstDay.get("date"));
        java.util.Map<?, ?> weather = (java.util.Map<?, ?>) firstDay.get("weather");
        assertEquals("动态", weather.get("status"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(weather.get("value")).contains("多云"));
        assertEquals(true, ((java.util.Map<?, ?>) trip.get("sourceStatus")).get("weatherAvailable"));
        assertEquals(1, ((java.util.Map<?, ?>) trip.get("sourceStatus")).get("weatherDaysResolved"));
    }

    @Test
    void hourlyPlanningIncludesDiningWhenRequestedOrSpanningMealTime() {
        com.ai.guide.domain.geo.model.GeoCoordinate origin = new com.ai.guide.domain.geo.model.GeoCoordinate(106.58, 29.56);
        com.ai.guide.domain.geo.model.GeoCoordinate target = new com.ai.guide.domain.geo.model.GeoCoordinate(106.585, 29.565);
        com.ai.guide.domain.geo.model.GeoPoiCandidate start = poi("jfb", "解放碑", origin);
        com.ai.guide.domain.geo.model.GeoPoiCandidate nearby = poi("hyd", "洪崖洞", target);
        com.ai.guide.domain.geo.model.GeoPoiCandidate restaurant = new com.ai.guide.domain.geo.model.GeoPoiCandidate(
                "restaurant", "附近火锅店", new com.ai.guide.domain.geo.model.GeoCoordinate(106.581, 29.561), "重庆市渝中区",
                "餐饮服务;中餐厅", null, null, "restaurant", null, null, 4.6, null, "050100", null);
        com.ai.guide.domain.geo.model.GeoRouteSegment segment = new com.ai.guide.domain.geo.model.GeoRouteSegment(
                "WALKING", 800, 600, 800, 1, List.of(), "106.58,29.56;106.585,29.565", true);
        GeoRouteOutcome route = new GeoRouteOutcome(
                GeoOutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String k, String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l, int r, String t, String k, String c) {
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(t.startsWith("05") ? restaurant : nearby), "", Instant.now());
            }
            @Override public GeoRoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) { return new GeoRoutePairOutcome(route, route, route, p, false); }
        };

        RuntimeLocationPlanner planner = planner(provider);
        TravelConstraints constraints = new TravelConstraintParser().parse("我在解放碑 中午12点出发 给我3小时的旅游规划 想吃火锅");
        var trip = planner.plan(constraints, 1);

        assertNotNull(trip);
        java.util.List<?> days = (java.util.List<?>) trip.get("days");
        java.util.Map<?, ?> firstDay = (java.util.Map<?, ?>) days.get(0);
        java.util.List<?> stops = (java.util.List<?>) firstDay.get("stops");
        org.junit.jupiter.api.Assertions.assertTrue(stops.stream().anyMatch(s -> "DINING".equals(((java.util.Map<?, ?>) s).get("type"))), "Should include dining stop");
    }

    @Test
    void enforcesStrictCategoryDiversityAndAtMostOneSquare() {
        com.ai.guide.domain.geo.model.GeoCoordinate origin = new com.ai.guide.domain.geo.model.GeoCoordinate(106.4678, 29.5637);
        com.ai.guide.domain.geo.model.GeoPoiCandidate start = new com.ai.guide.domain.geo.model.GeoPoiCandidate("cqu-a", "重庆大学A校园", origin,
                "沙坪坝区沙正街174号重庆大学", "科教文化服务;学校;高等院校", null, null, "cqu-a", null, null);

        com.ai.guide.domain.geo.model.GeoPoiCandidate plaza1 = poi("p1", "沙坪坝历史文化名人广场", new com.ai.guide.domain.geo.model.GeoCoordinate(106.469, 29.560));
        com.ai.guide.domain.geo.model.GeoPoiCandidate campusPlaza = new com.ai.guide.domain.geo.model.GeoPoiCandidate("p2", "重庆大学思群广场",
                new com.ai.guide.domain.geo.model.GeoCoordinate(106.468, 29.564), "重庆大学A区内", "风景名胜;公园广场;城市广场", null, null, "p2", null, null);
        com.ai.guide.domain.geo.model.GeoPoiCandidate noisePlaza = poi("p3", "沙坪坝奇峰广场", new com.ai.guide.domain.geo.model.GeoCoordinate(106.469, 29.559));
        com.ai.guide.domain.geo.model.GeoPoiCandidate park = poi("p4", "沙坪公园", new com.ai.guide.domain.geo.model.GeoCoordinate(106.460, 29.550));
        com.ai.guide.domain.geo.model.GeoPoiCandidate museum = poi("p5", "红岩魂陈列馆", new com.ai.guide.domain.geo.model.GeoCoordinate(106.440, 29.570));

        com.ai.guide.domain.geo.model.GeoRouteSegment segment = new com.ai.guide.domain.geo.model.GeoRouteSegment(
                "WALKING", 600, 480, 600, 1, List.of(), "106.4678,29.5637;106.469,29.560", true);
        GeoRouteOutcome route = new GeoRouteOutcome(
                GeoOutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public GeoPoiOutcome searchPoi(String k, String c) { return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public GeoPoiOutcome searchAround(String l, int r, String t, String k, String c, int pages) {
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(plaza1, campusPlaza, noisePlaza, park, museum), "", Instant.now());
            }
            @Override public GeoRoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) {
                return new GeoRoutePairOutcome(route, route, route, p, false);
            }
        };

        RuntimeLocationPlanner planner = planner(provider);
        TravelConstraints constraints = new TravelConstraintParser().parse("我在重庆大学A校园 上午8点出发 给我3小时的旅游规划");
        var trip = planner.plan(constraints, 1);

        assertNotNull(trip);
        java.util.List<?> days = (java.util.List<?>) trip.get("days");
        java.util.Map<?, ?> firstDay = (java.util.Map<?, ?>) days.get(0);
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> stops = (java.util.List<java.util.Map<String, Object>>) firstDay.get("stops");

        // 1. Must NOT include campus internal POI (思群广场)
        boolean hasCampusPoi = stops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("思群广场"));
        org.junit.jupiter.api.Assertions.assertFalse(hasCampusPoi, "Campus internal POI must be excluded from recommendations");

        // 2. Must NOT recommend commercial noise plaza (奇峰广场)
        boolean hasNoisePlaza = stops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("奇峰广场"));
        org.junit.jupiter.api.Assertions.assertFalse(hasNoisePlaza, "Commercial noise plaza must be filtered");

        // 3. Must NEVER recommend more than 1 plaza in the entire day
        long squareCount = stops.stream()
                .filter(s -> "景".equals(s.get("icon")) && String.valueOf(s.get("name")).contains("广场"))
                .count();
        org.junit.jupiter.api.Assertions.assertTrue(squareCount <= 1, "Plaza/square count must be at most 1, got: " + squareCount);

        // 4. Must include diverse categories (such as park and museum)
        boolean hasPark = stops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("沙坪公园"));
        boolean hasMuseum = stops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("红岩魂陈列馆"));
        org.junit.jupiter.api.Assertions.assertTrue(hasPark, "Should recommend park");
        org.junit.jupiter.api.Assertions.assertTrue(hasMuseum, "Should recommend museum/historic site");
    }

    @Test
    void detectCategoryCorrectlyClassifiesDiverseVenues() {
        assertEquals(RuntimeLocationPlanner.PoiCategory.SQUARE,
                RuntimeLocationPlanner.detectCategory("沙坪坝历史文化名人广场", "风景名胜;公园广场;城市广场", "", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.PARK_NATURE,
                RuntimeLocationPlanner.detectCategory("沙坪公园", "风景名胜;公园广场;综合公园", "", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.HISTORIC_CULTURE,
                RuntimeLocationPlanner.detectCategory("红岩魂陈列馆", "风景名胜;文博院馆;纪念馆", "", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.HISTORIC_CULTURE,
                RuntimeLocationPlanner.detectCategory("磁器口古镇", "风景名胜;古镇老街;著名古镇", "", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.LANDMARK_VIEW,
                RuntimeLocationPlanner.detectCategory("任意观景地标", "风景名胜;观景点", "110209", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.RELIGIOUS_TEMPLE,
                RuntimeLocationPlanner.detectCategory("罗汉寺", "风景名胜;宗教名胜;寺庙", "", new JsonTaxonomyAdapter()));
        assertEquals(RuntimeLocationPlanner.PoiCategory.COMMERCIAL_STREET,
                RuntimeLocationPlanner.detectCategory("观音桥步行街", "购物服务;商业街;商业街区", "", new JsonTaxonomyAdapter()));
    }

    private com.ai.guide.domain.geo.model.GeoPoiCandidate poi(String id, String name,
                                                      com.ai.guide.domain.geo.model.GeoCoordinate coordinate) {
        return new com.ai.guide.domain.geo.model.GeoPoiCandidate(id, name, coordinate, "重庆市测试区", "风景名胜",
                null, null, id, null, null);
    }

    private RuntimeLocationPlanner planner(AmapRouteService provider) {
        JsonTaxonomyAdapter taxonomy = new JsonTaxonomyAdapter();
        return new RuntimeLocationPlanner(provider, new ObjectMapper(), 6000, 40,
                new DiningVenueService(null, provider, taxonomy), taxonomy);
    }

    @Test
    void durationBudgetParsesHoursAndFallsBackOnlyForUnknownValues() throws Exception {
        RuntimeLocationPlanner planner = planner(null);
        var method = RuntimeLocationPlanner.class.getDeclaredMethod("durationMinutes", Object.class);
        method.setAccessible(true);

        assertEquals(120, method.invoke(planner, "约 2 小时"));
        assertEquals(90, method.invoke(planner, "1.5小时"));
        assertEquals(60, method.invoke(planner, "无明确时长"));
    }
}


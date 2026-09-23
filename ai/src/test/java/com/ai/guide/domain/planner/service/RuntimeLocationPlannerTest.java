package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeLocationPlannerTest {

    @Test
    void buildsNearbyPlanFromResolvedStartWithoutCoreCatalogOrRag() {
        AmapResponseNormalizer.Coordinate origin = new AmapResponseNormalizer.Coordinate(106.33, 29.58);
        AmapResponseNormalizer.Coordinate target = new AmapResponseNormalizer.Coordinate(106.335, 29.585);
        AmapResponseNormalizer.PoiCandidate start = poi("start", "测试起点", origin);
        AmapResponseNormalizer.PoiCandidate nearby = poi("nearby", "动态周边景点", target);
        AmapResponseNormalizer.RouteResult segment = new AmapResponseNormalizer.RouteResult(
                "WALKING", 1000, 600, 1000, 1, List.of(), "106.33,29.58;106.335,29.585", true);
        AmapRouteService.RouteOutcome route = new AmapRouteService.RouteOutcome(
                AmapRouteService.OutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public PoiOutcome searchPoi(String keywords, String city) {
                return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(start), "", Instant.now());
            }

            @Override
            public PoiOutcome searchAround(String location, int radiusMeters, String types,
                                           String keywords, String city) {
                return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(nearby), "", Instant.now());
            }

            @Override
            public RoutePairOutcome routePair(String origin, String destination, String city,
                                              String originPoi, String destinationPoi, String preference) {
                return new RoutePairOutcome(route, route, route, preference, false);
            }
        };

        TravelConstraints constraints = new TravelConstraintParser().parse("我在测试起点 给我3小时的旅游规划");
        var trip = new RuntimeLocationPlanner(provider).plan(constraints, 1);

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
        AmapResponseNormalizer.Coordinate origin = new AmapResponseNormalizer.Coordinate(106.53, 29.50);
        AmapResponseNormalizer.Coordinate target = new AmapResponseNormalizer.Coordinate(106.535, 29.505);
        AmapResponseNormalizer.PoiCandidate start = poi("cqu", "重庆交通大学", origin);
        AmapResponseNormalizer.PoiCandidate nearby = poi("nanping", "南滨路", target);
        AmapResponseNormalizer.RouteResult segment = new AmapResponseNormalizer.RouteResult(
                "WALKING", 1200, 720, 1200, 1, List.of(), "106.53,29.50;106.535,29.505", true);
        AmapRouteService.RouteOutcome route = new AmapRouteService.RouteOutcome(
                AmapRouteService.OutcomeStatus.SUCCESS, segment, "", "test", Instant.now());
        AmapResponseNormalizer.WeatherCast todayCast = new AmapResponseNormalizer.WeatherCast(
                java.time.LocalDate.now().toString(), "多云", "晴", "26", "20");
        AmapResponseNormalizer.WeatherResult weatherResult = new AmapResponseNormalizer.WeatherResult(
                java.time.LocalDate.now() + " 10:00:00", List.of(todayCast), true);

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public PoiOutcome searchPoi(String k, String c) { return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public PoiOutcome searchAround(String l, int r, String t, String k, String c) { return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(nearby), "", Instant.now()); }
            @Override public RoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) { return new RoutePairOutcome(route, route, route, p, false); }
            @Override public WeatherOutcome weather(String city) { return new WeatherOutcome(OutcomeStatus.SUCCESS, weatherResult, "", Instant.now()); }
        };

        RuntimeLocationPlanner planner = new RuntimeLocationPlanner(provider);
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
        AmapResponseNormalizer.Coordinate origin = new AmapResponseNormalizer.Coordinate(106.58, 29.56);
        AmapResponseNormalizer.Coordinate target = new AmapResponseNormalizer.Coordinate(106.585, 29.565);
        AmapResponseNormalizer.PoiCandidate start = poi("jfb", "解放碑", origin);
        AmapResponseNormalizer.PoiCandidate nearby = poi("hyd", "洪崖洞", target);
        AmapResponseNormalizer.RouteResult segment = new AmapResponseNormalizer.RouteResult(
                "WALKING", 800, 600, 800, 1, List.of(), "106.58,29.56;106.585,29.565", true);
        AmapRouteService.RouteOutcome route = new AmapRouteService.RouteOutcome(
                AmapRouteService.OutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public PoiOutcome searchPoi(String k, String c) { return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public PoiOutcome searchAround(String l, int r, String t, String k, String c) { return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(nearby), "", Instant.now()); }
            @Override public RoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) { return new RoutePairOutcome(route, route, route, p, false); }
        };

        RuntimeLocationPlanner planner = new RuntimeLocationPlanner(provider);
        TravelConstraints constraints = new TravelConstraintParser().parse("我在解放碑 给我3小时的旅游规划 想吃火锅");
        var trip = planner.plan(constraints, 1);

        assertNotNull(trip);
        java.util.List<?> days = (java.util.List<?>) trip.get("days");
        java.util.Map<?, ?> firstDay = (java.util.Map<?, ?>) days.get(0);
        java.util.List<?> stops = (java.util.List<?>) firstDay.get("stops");
        org.junit.jupiter.api.Assertions.assertTrue(stops.stream().anyMatch(s -> "DINING".equals(((java.util.Map<?, ?>) s).get("type"))), "Should include dining stop");
    }

    @Test
    void enforcesStrictCategoryDiversityAndAtMostOneSquare() {
        AmapResponseNormalizer.Coordinate origin = new AmapResponseNormalizer.Coordinate(106.4678, 29.5637);
        AmapResponseNormalizer.PoiCandidate start = new AmapResponseNormalizer.PoiCandidate("cqu-a", "重庆大学A校园", origin,
                "沙坪坝区沙正街174号重庆大学", "科教文化服务;学校;高等院校", null, null, "cqu-a", null, null);

        AmapResponseNormalizer.PoiCandidate plaza1 = poi("p1", "沙坪坝历史文化名人广场", new AmapResponseNormalizer.Coordinate(106.469, 29.560));
        AmapResponseNormalizer.PoiCandidate campusPlaza = new AmapResponseNormalizer.PoiCandidate("p2", "重庆大学思群广场",
                new AmapResponseNormalizer.Coordinate(106.468, 29.564), "重庆大学A区内", "风景名胜;公园广场;城市广场", null, null, "p2", null, null);
        AmapResponseNormalizer.PoiCandidate noisePlaza = poi("p3", "沙坪坝奇峰广场", new AmapResponseNormalizer.Coordinate(106.469, 29.559));
        AmapResponseNormalizer.PoiCandidate park = poi("p4", "沙坪公园", new AmapResponseNormalizer.Coordinate(106.460, 29.550));
        AmapResponseNormalizer.PoiCandidate museum = poi("p5", "红岩魂陈列馆", new AmapResponseNormalizer.Coordinate(106.440, 29.570));

        AmapResponseNormalizer.RouteResult segment = new AmapResponseNormalizer.RouteResult(
                "WALKING", 600, 480, 600, 1, List.of(), "106.4678,29.5637;106.469,29.560", true);
        AmapRouteService.RouteOutcome route = new AmapRouteService.RouteOutcome(
                AmapRouteService.OutcomeStatus.SUCCESS, segment, "", "test", Instant.now());

        AmapRouteService provider = new AmapRouteService(null) {
            @Override public boolean isConfigured() { return true; }
            @Override public PoiOutcome searchPoi(String k, String c) { return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(start), "", Instant.now()); }
            @Override public PoiOutcome searchAround(String l, int r, String t, String k, String c, int pages) {
                return new PoiOutcome(OutcomeStatus.SUCCESS, List.of(plaza1, campusPlaza, noisePlaza, park, museum), "", Instant.now());
            }
            @Override public RoutePairOutcome routePair(String o, String d, String c, String op, String dp, String p) {
                return new RoutePairOutcome(route, route, route, p, false);
            }
        };

        RuntimeLocationPlanner planner = new RuntimeLocationPlanner(provider);
        TravelConstraints constraints = new TravelConstraintParser().parse("我在重庆大学A校园 给我3小时的旅游规划");
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
                RuntimeLocationPlanner.detectCategory("沙坪坝历史文化名人广场", "风景名胜;公园广场;城市广场"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.PARK_NATURE,
                RuntimeLocationPlanner.detectCategory("沙坪公园", "风景名胜;公园广场;综合公园"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.HISTORIC_CULTURE,
                RuntimeLocationPlanner.detectCategory("红岩魂陈列馆", "风景名胜;文博院馆;纪念馆"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.HISTORIC_CULTURE,
                RuntimeLocationPlanner.detectCategory("磁器口古镇", "风景名胜;古镇老街;著名古镇"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.LANDMARK_VIEW,
                RuntimeLocationPlanner.detectCategory("洪崖洞民俗风貌区", "风景名胜;风景名胜;国家级景点"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.RELIGIOUS_TEMPLE,
                RuntimeLocationPlanner.detectCategory("罗汉寺", "风景名胜;宗教名胜;寺庙"));
        assertEquals(RuntimeLocationPlanner.PoiCategory.COMMERCIAL_STREET,
                RuntimeLocationPlanner.detectCategory("观音桥步行街", "购物服务;商业街;商业街区"));
    }

    private AmapResponseNormalizer.PoiCandidate poi(String id, String name,
                                                      AmapResponseNormalizer.Coordinate coordinate) {
        return new AmapResponseNormalizer.PoiCandidate(id, name, coordinate, "重庆市测试区", "风景名胜",
                null, null, id, null, null);
    }
}

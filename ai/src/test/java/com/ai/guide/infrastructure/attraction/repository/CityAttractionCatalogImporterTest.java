package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSeedDataInitializer;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.*;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.planner.service.ItineraryBuilder;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.infrastructure.planner.amap.AmapPlannerGateway;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CityAttractionCatalogImporterTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hundredEntryCatalogWithFullPlanDoesNotReportUnselectedCandidatesAsShortfall() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                Map<String, Object> row = row("full-" + i, "测试城", "完整景点" + i);
                row.put("duration", "约60分钟");
                row.put("summary", "测试景点");
                row.put("intro", "测试景点");
                rows.add(row);
            }
            write(rows, "many.json");
            new CityAttractionCatalogImporter(directory).refresh(jdbc, true);
            var catalog = new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper));
            var geo = mock(GeoRouteGatewayPort.class);
            when(geo.isConfigured()).thenReturn(true);
            when(geo.searchPoi(anyString(), eq("测试城"))).thenAnswer(call -> {
                String name = call.getArgument(0);
                int index = Integer.parseInt(name.replace("完整景点", ""));
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS,
                        List.of(new GeoPoiCandidate("poi-" + index, name,
                                new GeoCoordinate(116.40 + index * 0.001, 39.90 + index * 0.001),
                                "", "", null, null, null, null, null, null, null, null, null, "测试城市")),
                        "", Instant.now());
            });
            Map<String, Object> trip = builder(catalog, geo).build(1, TravelConstraints.builder()
                    .destination("测试城").durationDays(1).build());
            assertEquals(3, stops(trip).size(), "仅预解析前 12 条，也应填满 3 个景点槽位");
            assertFalse(((List<?>) trip.get("degradationReasons")).contains("CATALOG_CANDIDATES_UNRESOLVED"));
            verify(geo, times(12)).searchPoi(anyString(), eq("测试城"));
        }
    }

    @Test
    void postHydrationCannotHideCatalogShortfallFromFailedPreGeocoding() {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            new CityAttractionCatalogImporter(directory).refresh(jdbc, true);
            var catalog = new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper));
            var routeService = mock(AmapRouteService.class);
            when(routeService.isConfigured()).thenReturn(true);
            when(routeService.weather(anyString())).thenReturn(
                    new GeoWeatherOutcome(GeoOutcomeStatus.UNAVAILABLE, null, "天气未返回", Instant.now()));
            when(routeService.searchPoi(anyString(), eq("北京"))).thenReturn(
                    new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "暂时失败", Instant.now()));
            Map<String, Object> trip = builder(catalog, routeService).build(1, beijingConstraints());
            assertEquals(1, stops(trip).size());
            assertTrue(((List<?>) trip.get("degradationReasons")).contains("CATALOG_CANDIDATES_UNRESOLVED"));
            Map<String, Object> stop = stops(trip).get(0);
            String name = String.valueOf(stop.get("name"));
            when(routeService.searchPoi(anyString(), eq("北京"))).thenReturn(new GeoPoiOutcome(
                    GeoOutcomeStatus.SUCCESS, List.of(new GeoPoiCandidate("late-poi", name,
                    new GeoCoordinate(116.4, 39.9), "", "", null, null, null, null,
                    null, null, null, null, null, "北京市")), "", Instant.now()));
            var gateway = new AmapPlannerGateway(routeService, catalog, 3);
            gateway.setPoiIdentityPolicy(new JsonTaxonomyAdapter());
            gateway.hydrate(trip);
            assertEquals("116.4,39.9", stop.get("location"));
            assertTrue(((List<?>) trip.get("degradationReasons")).contains("CATALOG_CANDIDATES_UNRESOLVED"));
            assertEquals(true, trip.get("degraded"));
            assertEquals(true, ((Map<?, ?>) trip.get("sourceStatus")).get("fallback"));
        }
    }

    @Test
    void hundredEntryCatalogCapsLookupsAndPrioritizesRequestedStop() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String name = i == 99 ? "目标景点" : "普通景点" + i;
                Map<String, Object> row = row("bulk-" + i, "测试城", name);
                row.put("duration", "约60分钟");
                row.put("summary", "测试景点");
                row.put("intro", "测试景点");
                rows.add(row);
            }
            write(rows, "many.json");
            new CityAttractionCatalogImporter(directory).refresh(jdbc, true);
            var catalog = new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper));
            var geo = mock(GeoRouteGatewayPort.class);
            when(geo.isConfigured()).thenReturn(true);
            when(geo.searchPoi(anyString(), eq("测试城"))).thenReturn(
                    new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "未找到", Instant.now()));
            builder(catalog, geo).build(1, TravelConstraints.builder().destination("测试城")
                    .durationDays(1).mustVisit(List.of("目标景点")).build());
            verify(geo, times(12)).searchPoi(anyString(), eq("测试城"));
            verify(geo).searchPoi("目标景点", "测试城");
        }
    }

    @Test
    void importedRealBeijingCatalogBuildsMultiStopOnlyAfterExactCityGeocoding() {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            new CityAttractionCatalogImporter(directory).refresh(jdbc, true);
            var catalog = new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper));
            assertEquals(3, catalog.list("北京", null, null).size());
            assertTrue(catalog.list("北京", null, null).stream().allMatch(a -> a.getLocation().isBlank() && a.getIndoor() == null));
            var geo = mock(GeoRouteGatewayPort.class);
            when(geo.isConfigured()).thenReturn(true);
            when(geo.searchPoi(anyString(), eq("北京"))).thenAnswer(call -> {
                String name = call.getArgument(0);
                int index = switch (name) { case "故宫博物院" -> 0; case "中国国家博物馆" -> 1; default -> 2; };
                var poi = new GeoPoiCandidate("official-" + index, name,
                        new GeoCoordinate(116.39 + index * 0.01, 39.91 + index * 0.01), "", "", null,
                        null, null, null, null, null, null, null, null, "北京市");
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(poi), "", Instant.now());
            });
            Map<String, Object> trip = builder(catalog, geo).build(1, beijingConstraints());
            List<Map<String, Object>> stops = stops(trip);
            assertTrue(stops.size() >= 2, "真实目录经地理编码后应能排多站: " + trip);
            assertTrue(stops.stream().allMatch(stop -> String.valueOf(stop.get("venueId")).startsWith("bj-")));
            assertTrue(stops.stream().skip(1).noneMatch(stop -> "VERIFIED_AMAP".equals(stop.get("routeDataStatus"))),
                    "仅有坐标时不得把直线估算标为已核验路线");
            verify(geo, never()).searchPoi(anyString(), eq("重庆"));
            assertTrue(catalog.list("北京", null, null).stream().allMatch(a -> a.getLocation().isBlank()),
                    "临时坐标不得写回目录");
        }
    }

    @Test
    void wrongCityOrFailedGeocodingCannotInventVerifiedRoute() {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            new CityAttractionCatalogImporter(directory).refresh(jdbc, true);
            var catalog = new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper));
            var geo = mock(GeoRouteGatewayPort.class);
            when(geo.isConfigured()).thenReturn(true);
            when(geo.searchPoi(anyString(), eq("北京"))).thenAnswer(call ->
                    new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(new GeoPoiCandidate("wrong-city",
                            call.getArgument(0), new GeoCoordinate(116.4, 39.9), "", "", null, null,
                            null, null, null, null, null, null, null, "重庆市")), "", Instant.now()));
            Map<String, Object> trip = builder(catalog, geo).build(1, beijingConstraints());
            assertTrue(stops(trip).size() <= 1, "错误城市的坐标不能用于多站路线");
            assertEquals(true, trip.get("degraded"));
            assertTrue(((List<?>) trip.get("degradationReasons")).contains("POI_COORDINATES_UNVERIFIED"));
            assertTrue(stops(trip).stream().noneMatch(stop -> "VERIFIED_AMAP".equals(stop.get("routeDataStatus"))));
            var unavailable = mock(GeoRouteGatewayPort.class);
            when(unavailable.isConfigured()).thenReturn(true);
            when(unavailable.searchPoi(anyString(), eq("北京"))).thenReturn(
                    new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "供应商超时", Instant.now()));
            Map<String, Object> failedTrip = builder(catalog, unavailable).build(1, beijingConstraints());
            assertTrue(stops(failedTrip).size() <= 1);
            assertTrue(((List<?>) failedTrip.get("degradationReasons")).contains("POI_COORDINATES_UNVERIFIED"));
            assertTrue(stops(failedTrip).stream().noneMatch(stop -> "VERIFIED_AMAP".equals(stop.get("routeDataStatus"))));
            var ambiguous = mock(GeoRouteGatewayPort.class);
            when(ambiguous.isConfigured()).thenReturn(true);
            when(ambiguous.searchPoi(anyString(), eq("北京"))).thenAnswer(call -> {
                String name = call.getArgument(0);
                var first = new GeoPoiCandidate("one", name, new GeoCoordinate(116.4, 39.9),
                        "", "", null, null, null, null, null, null, null, null, null, "北京");
                var second = new GeoPoiCandidate("two", name, new GeoCoordinate(116.5, 39.9),
                        "", "", null, null, null, null, null, null, null, null, null, "北京");
                return new GeoPoiOutcome(GeoOutcomeStatus.SUCCESS, List.of(first, second), "", Instant.now());
            });
            Map<String, Object> ambiguousTrip = builder(catalog, ambiguous).build(1, beijingConstraints());
            assertTrue(stops(ambiguousTrip).size() <= 1, "重名 POI 的身份歧义不能被任意选择");
            assertTrue(((List<?>) ambiguousTrip.get("degradationReasons")).contains("POI_COORDINATES_UNVERIFIED"));
        }
    }

    private ItineraryBuilder builder(AttractionService catalog, GeoRouteGatewayPort geo) {
        var taxonomy = new JsonTaxonomyAdapter();
        var verifier = new PlanVerifier();
        var repairer = com.ai.guide.testing.PlannerTaxonomyFixtures.localPlanRepairer();
        var routes = new RouteAwarePlanner(new RouteCostProvider(geo, taxonomy), verifier, repairer,
                null, null, taxonomy);
        var builder = new ItineraryBuilder(catalog, routes, verifier, repairer,
                new PlanNarrativeService(), null);
        builder.setGeoRouteGateway(geo);
        return builder;
    }

    private TravelConstraints beijingConstraints() {
        return TravelConstraints.builder().destination("北京").durationDays(1)
                .arrivalAt("09:00").departureAt("20:00").timeBudgetMinutes(660)
                .interests(List.of("人文历史")).build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stops(Map<String, Object> trip) {
        return (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) trip.get("days")).get(0)).get("stops");
    }

    @Test
    void initializesUpdatesDeletesOnlyManagedEntriesAndPreservesLegacyCatalog() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            new KnowledgeSeedDataInitializer().initialize(jdbc, true);
            jdbc.update("INSERT INTO attraction (id,name,city) VALUES ('manual-row','人工条目','测试城')");
            var importer = new CityAttractionCatalogImporter(directory);
            write(List.of(row("test-one", "测试城", "原名称"), row("test-two", "另一城", "另一景点")), "two-files");
            importer.refresh(jdbc, true);
            assertEquals(41, jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city='重庆'", Integer.class));
            assertEquals("原名称", name(jdbc, "test-one"));
            assertEquals("另一城", jdbc.queryForObject("SELECT city FROM attraction WHERE id='test-two'", String.class));
            write(List.of(row("test-one", "测试城市", "新名称")), "city.json");
            Files.delete(directory.resolve("other.json"));
            importer.refresh(jdbc, true);
            assertEquals("新名称", name(jdbc, "test-one"));
            assertEquals(0, count(jdbc, "test-two"));
            assertEquals(1, count(jdbc, "manual-row"));
            assertEquals("https://example.org/catalog", jdbc.queryForObject("SELECT source_url FROM attraction_catalog_entry WHERE id='test-one'", String.class));
        }
    }

    @Test
    void invalidBatchDoesNotPartiallyApplyEditsOrDeletes() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var importer = new CityAttractionCatalogImporter(directory);
            write(List.of(row("test-one", "测试城", "原名称")), "city.json");
            importer.refresh(jdbc, true);
            write(List.of(row("test-one", "测试城", "错误批次")), "city.json");
            Files.writeString(directory.resolve("broken.json"), "{broken");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals("原名称", name(jdbc, "test-one"));
            Files.delete(directory.resolve("broken.json"));
            var invalid = row("test-two", "测试城", "非法坐标");
            invalid.put("location", "NaN,10");
            write(List.of(invalid), "invalid.json");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals("原名称", name(jdbc, "test-one"));
        }
    }

    @Test
    void rejectsDuplicateCrossCityAndUnmanagedIdCollisions() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var importer = new CityAttractionCatalogImporter(directory);
            write(List.of(row("test-one", "测试城", "原名称")), "city.json");
            importer.refresh(jdbc, true);
            write(List.of(row("test-one", "另一城", "重复ID")), "other.json");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            Files.delete(directory.resolve("other.json"));
            write(List.of(row("test-one", "另一城", "跨城移动")), "city.json");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals("原名称", name(jdbc, "test-one"));
            jdbc.update("INSERT INTO attraction (id,name,city) VALUES ('manual-row','人工条目','测试城')");
            write(List.of(row("manual-row", "测试城", "覆盖人工条目")), "city.json");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals("人工条目", name(jdbc, "manual-row"));
            assertEquals("原名称", name(jdbc, "test-one"));
        }
    }

    @Test
    void externalFileOverridesPackagedCityAndRemovingOverrideRestoresPackagedSnapshot() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var importer = new CityAttractionCatalogImporter(directory);
            importer.refresh(jdbc, true);
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city='北京'", Integer.class));
            write(List.of(row("test-beijing", "北京", "运维替换目录")), "beijing.json");
            importer.refresh(jdbc, true);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city='北京'", Integer.class));
            assertEquals("运维替换目录", name(jdbc, "test-beijing"));
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city='杭州'", Integer.class));
            Files.delete(directory.resolve("beijing.json"));
            importer.refresh(jdbc, true);
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE city='北京'", Integer.class));
            assertEquals(0, count(jdbc, "test-beijing"));
        }
    }

    @Test
    void databaseWriteFailureRollsBackTheEntireReplacement() throws Exception {
        try (var source = source()) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var importer = new CityAttractionCatalogImporter(directory);
            write(List.of(row("test-one", "测试城", "原名称")), "city.json");
            importer.refresh(jdbc, true);
            jdbc.execute("CREATE TRIGGER reject_test_two BEFORE INSERT ON attraction WHEN NEW.id='test-two' BEGIN SELECT RAISE(ABORT,'fixture'); END");
            write(List.of(row("test-one", "测试城", "新名称"), row("test-two", "测试城", "触发失败")), "city.json");
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals("原名称", name(jdbc, "test-one"));
            assertEquals(0, count(jdbc, "test-two"));
        }
    }

    private SingleConnectionDataSource source() { return new SingleConnectionDataSource("jdbc:sqlite::memory:", true); }
    private int count(JdbcTemplate jdbc, String id) { return jdbc.queryForObject("SELECT COUNT(*) FROM attraction WHERE id=?", Integer.class, id); }
    private String name(JdbcTemplate jdbc, String id) { return jdbc.queryForObject("SELECT name FROM attraction WHERE id=?", String.class, id); }
    private Map<String, Object> row(String id, String city, String name) {
        return new LinkedHashMap<>(Map.of("id", id, "city", city, "name", name, "category", "人文历史", "sourceUrl", "https://example.org/catalog", "verifiedAt", "2026-10-01", "location", ""));
    }
    private void write(List<Map<String, Object>> rows, String filename) throws Exception {
        if (filename.equals("two-files")) {
            write(rows.subList(0, 1), "city.json");
            write(rows.subList(1, 2), "other.json");
        } else mapper.writeValue(directory.resolve(filename).toFile(), rows);
    }
}

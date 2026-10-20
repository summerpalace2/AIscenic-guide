package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter;
import com.ai.guide.infrastructure.attraction.repository.JdbcAttractionCatalogAdapter;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real isolated PostgreSQL schema and independent JVMs; never points at application/default data. */
@EnabledIfEnvironmentVariable(named = "CP6_IT_ENABLE", matches = "true",
        disabledReason = "Requires explicitly enabled disposable CP6 PostgreSQL target")
class Cp6CityCatalogImporterIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Cp6InfrastructureTarget target;
    private String schema;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        target = new Cp6InfrastructureTarget(System.getenv());
        schema = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(target.adminDataSource());
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(target.dataSource(schema));
        new KnowledgeSchemaInitializer().initialize(jdbc, false);
    }

    @AfterEach
    void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void independentJvmsSerializeOneSnapshotAndPreserveReadersOnBadUpdate() throws Exception {
        Path source = Files.createTempDirectory("cp6-city-catalog-source-");
        Path catalog = source.resolve("probe-city.json");
        String id = "cp6-city-catalog-" + UUID.randomUUID();
        writeCatalog(catalog, row(id, "初始馆", "旧别名", List.of("name", "city")));
        new CityAttractionCatalogImporter(source).refresh(jdbc, false);
        long before = revision();

        var firstMatcher = matcher();
        var secondMatcher = matcher();
        assertEquals(id, firstMatcher.extractMentionedVenueId("我想去旧别名"));
        assertEquals(id, secondMatcher.extractMentionedVenueId("我想去旧别名"));

        writeCatalog(catalog, row(id, "更新馆", "新别名", List.of("name", "city", "summary")));
        Path run = Files.createTempDirectory("cp6-city-catalog-workers-");
        Path start = run.resolve("start");
        Path firstReady = run.resolve("first.ready");
        Path secondReady = run.resolve("second.ready");
        Path firstLog = run.resolve("first.log");
        Path secondLog = run.resolve("second.log");
        Process first = worker(source, firstReady, start, firstLog);
        Process second = worker(source, secondReady, start, secondLog);
        try {
            awaitFile(firstReady, first, firstLog);
            awaitFile(secondReady, second, secondLog);
            Files.createFile(start);
            awaitSuccess(first, firstLog);
            awaitSuccess(second, secondLog);
            assertEquals(before + 1, revision(), "two JVMs importing identical bytes must install one revision");

            assertEquals(id, firstMatcher.extractMentionedVenueId("我想去新别名"), "first consumer must reload on DB revision");
            assertNull(firstMatcher.extractMentionedVenueId("我想去旧别名"), "removed alias must disappear in first consumer");
            assertEquals(id, secondMatcher.extractMentionedVenueId("我想去新别名"), "second consumer must reload on DB revision");
            assertNull(secondMatcher.extractMentionedVenueId("我想去旧别名"), "removed alias must disappear in second consumer");
            assertEquals("更新馆", jdbc.queryForObject("SELECT name FROM attraction WHERE id=?", String.class, id));
            assertEquals("[\"name\",\"city\",\"summary\"]",
                    jdbc.queryForObject("SELECT verified_fields FROM attraction_catalog_entry WHERE id=?", String.class, id));

            long renamedRevision = revision();
            writeCatalog(catalog, row(id, "更新馆", "新别名", List.of("name", "city", "summary", "ticket")));
            new CityAttractionCatalogImporter(source).refresh(jdbc, false);
            assertEquals(renamedRevision + 1, revision(), "verifiedFields-only changes participate in the fingerprint");
            assertEquals("[\"name\",\"city\",\"summary\",\"ticket\"]",
                    jdbc.queryForObject("SELECT verified_fields FROM attraction_catalog_entry WHERE id=?", String.class, id));

            long installedRevision = revision();
            Files.writeString(catalog, "[{broken json");
            assertThrows(IllegalStateException.class, () -> new CityAttractionCatalogImporter(source).refresh(jdbc, false));
            assertEquals(installedRevision, revision(), "invalid source batch must not advance or replace the active snapshot");
            assertEquals("更新馆", jdbc.queryForObject("SELECT name FROM attraction WHERE id=?", String.class, id));
            assertEquals(id, firstMatcher.extractMentionedVenueId("新别名仍可用"));
            assertEquals(id, secondMatcher.extractMentionedVenueId("新别名仍可用"));

            writeCatalog(catalog, Map.of());
            new CityAttractionCatalogImporter(source).refresh(jdbc, false);
            assertEquals(installedRevision + 1, revision(), "deleting an owned source row advances one catalog revision");
            assertTrue(jdbc.queryForList("SELECT id FROM attraction WHERE id=?", String.class, id).isEmpty());
            assertNull(firstMatcher.extractMentionedVenueId("新别名已删除"));
            assertNull(secondMatcher.extractMentionedVenueId("新别名已删除"));
        } finally {
            stop(first);
            stop(second);
        }
    }

    @Test
    void blockedJvmReadsTheLatestSourceOnlyAfterRevisionLockAndAliasRefreshHandlesSplitRead() throws Exception {
        Path source = Files.createTempDirectory("cp6-city-catalog-lock-source-");
        Path catalog = source.resolve("probe-city.json");
        String id = "cp6-city-catalog-" + UUID.randomUUID();
        writeCatalog(catalog, row(id, "旧版馆", "旧版别名", List.of("name", "city")));
        new CityAttractionCatalogImporter(source).refresh(jdbc, false);
        long before = revision();

        Path run = Files.createTempDirectory("cp6-city-catalog-lock-worker-");
        Path start = run.resolve("start");
        Path ready = run.resolve("worker.ready");
        Path log = run.resolve("worker.log");
        Process process = null;
        try (var lockConnection = target.dataSource(schema).getConnection()) {
            lockConnection.setAutoCommit(false);
            try (var statement = lockConnection.createStatement()) {
                statement.executeQuery("SELECT revision FROM attraction_catalog_revision WHERE id=1 FOR UPDATE").close();
            }
            process = worker(source, ready, start, log);
            awaitFile(ready, process, log);
            Files.createFile(start);
            awaitCatalogLockWait(process, log);

            // A pre-lock reader would already hold the old parsed rows here and publish them after the lock releases.
            writeCatalog(catalog, row(id, "最新馆", "最新别名", List.of("name", "city", "summary")));
            lockConnection.commit();
            awaitSuccess(process, log);
        } finally {
            stop(process);
        }
        assertEquals(before + 1, revision());
        assertEquals("最新馆", jdbc.queryForObject("SELECT name FROM attraction WHERE id=?", String.class, id));

        var delegate = new JdbcAttractionCatalogAdapter(jdbc, mapper);
        AtomicBoolean updateBetweenRevisionAndList = new AtomicBoolean(true);
        AttractionCatalogPort racingPort = new AttractionCatalogPort() {
            @Override public List<com.ai.guide.domain.attraction.model.Attraction> find(String district, String category) {
                return delegate.find(district, category);
            }
            @Override public List<com.ai.guide.domain.attraction.model.Attraction> findByCity(String city, String district, String category) {
                return delegate.findByCity(city, district, category);
            }
            @Override public com.ai.guide.domain.attraction.model.Attraction findById(String venueId) {
                return delegate.findById(venueId);
            }
            @Override public long revision() {
                long observed = delegate.revision();
                if (updateBetweenRevisionAndList.compareAndSet(true, false)) {
                    try {
                        writeCatalog(catalog, row(id, "最终馆", "最终别名", List.of("name", "city", "summary", "ticket")));
                        new CityAttractionCatalogImporter(source).refresh(jdbc, false);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
                return observed;
            }
        };
        var splitReadMatcher = new AttractionAliasMatcher(new AttractionService(racingPort));
        assertEquals(id, splitReadMatcher.extractMentionedVenueId("去最终别名"));
        assertNull(splitReadMatcher.extractMentionedVenueId("去旧版别名"));
        assertNull(splitReadMatcher.extractMentionedVenueId("去最新别名"));
    }

    private AttractionAliasMatcher matcher() {
        return new AttractionAliasMatcher(new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper)));
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
    }

    private Map<String, Object> row(String id, String name, String alias, List<String> verifiedFields) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("displayName", name);
        row.put("city", "探针城");
        row.put("district", "测试区");
        row.put("category", "文化场馆");
        row.put("tags", List.of("探针"));
        row.put("amapQuery", Map.of("keywords", name, "matches", List.of(alias)));
        row.put("summary", "仅用于隔离测试");
        row.put("sourceUrl", "https://example.invalid/catalog-probe");
        row.put("verifiedAt", "2026-10-15");
        row.put("verifiedFields", verifiedFields);
        return row;
    }

    private void writeCatalog(Path path, Map<String, Object> row) throws Exception {
        Files.writeString(path, mapper.writeValueAsString(row.isEmpty() ? List.of() : List.of(row)));
    }

    private Process worker(Path source, Path ready, Path start, Path log) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, Cp6CityCatalogWorker.class.getName(), schema, source.toString(), ready.toString(), start.toString());
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("CP6_IT_") || Set.of("SystemRoot", "WINDIR", "TEMP", "TMP").contains(key))
                builder.environment().put(key, value);
        });
        return builder.start();
    }

    private void awaitFile(Path file, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive() && !Files.exists(file)) Thread.sleep(25);
        assertTrue(Files.exists(file) && process.isAlive(), "worker readiness failed; log=" + log + readLog(log));
    }

    private void awaitSuccess(Process process, Path log) throws Exception {
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "catalog worker timed out; log=" + log + readLog(log));
        assertEquals(0, process.exitValue(), "catalog worker failed; log=" + log + readLog(log));
        assertTrue(readLog(log).contains("CP6_CITY_CATALOG_IMPORTED"), "worker did not report install; log=" + log);
    }

    private void awaitCatalogLockWait(Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive()) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE datname=current_database() AND pid<>pg_backend_pid()
                      AND wait_event_type='Lock'
                      AND query ILIKE '%attraction_catalog_revision%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(25);
        }
        fail("worker did not block on the singleton revision row; log=" + log + readLog(log));
    }

    private String readLog(Path log) throws Exception {
        return Files.exists(log) ? "\n" + Files.readString(log) : " (log not created)";
    }

    private void stop(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }
}

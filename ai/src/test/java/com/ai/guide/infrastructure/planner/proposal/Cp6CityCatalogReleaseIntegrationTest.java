package com.ai.guide.infrastructure.planner.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL release anti-rollback checks across independent JVMs; opt-in disposable schema only. */
@EnabledIfEnvironmentVariable(named = "CP6_IT_ENABLE", matches = "true",
        disabledReason = "Requires explicitly enabled disposable CP6 PostgreSQL target")
class Cp6CityCatalogReleaseIntegrationTest {
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
    void releaseVersionFencingIsAtomicAndEnforcedAcrossJvmRefreshers() throws Exception {
        String id = "cp6-release-" + UUID.randomUUID();
        Path newer = releaseDirectory(2, id, "新版馆", "新版别名");
        Path older = releaseDirectory(1, id, "旧版馆", "旧版别名");
        Path sameVersionSameBytes = releaseDirectory(2, id, "新版馆", "新版别名");
        Path sameVersionDifferentBytes = releaseDirectory(2, id, "新版馆", "不同别名");
        Path unversioned = Files.createTempDirectory("cp6-city-catalog-unversioned-");
        writeRows(unversioned, id, "无版本馆", "无版本别名");

        long initialRevision = revision();
        Path run = Files.createTempDirectory("cp6-city-release-workers-");
        runWorker(newer, "", run.resolve("newer"));
        assertEquals(initialRevision + 1, revision(), "first versioned data snapshot advances data revision once");
        assertEquals(2L, releaseVersion());
        assertEquals("新版馆", name(id));
        long installedRevision = revision();
        String installedReleaseFingerprint = releaseFingerprint();

        runWorker(sameVersionSameBytes, "", run.resolve("same-version-same"));
        assertEquals(installedRevision, revision(), "same version and exact same bytes are idempotent");
        assertEquals(2L, releaseVersion());
        assertEquals(installedReleaseFingerprint, releaseFingerprint());

        assertWorkerRejected(older, "城市目录发布版本不得回退", run.resolve("older"));
        assertWorkerRejected(sameVersionDifferentBytes, "同一城市目录发布版本的内容不可变更", run.resolve("same-version-different"));
        assertWorkerRejected(unversioned, "城市目录已启用发布版本，拒绝无清单刷新", run.resolve("unversioned"));

        assertEquals(installedRevision, revision(), "rejected refreshes must preserve data revision");
        assertEquals(2L, releaseVersion(), "rejected refreshes must preserve release version");
        assertEquals(installedReleaseFingerprint, releaseFingerprint(), "rejected refreshes must preserve release identity");
        assertEquals("新版馆", name(id), "rejected refreshes must preserve installed rows");
    }

    @Test
    void newerReleaseWithUnchangedDataAdvancesReleaseIdentityWithoutDataRevision() throws Exception {
        String id = "cp6-release-" + UUID.randomUUID();
        Path first = releaseDirectory(3, id, "稳定馆", "稳定别名");
        Path second = releaseDirectory(4, id, "稳定馆", "稳定别名");
        new CityAttractionCatalogImporter(first).refresh(jdbc, false);
        long dataRevision = revision();
        String firstReleaseFingerprint = releaseFingerprint();

        new CityAttractionCatalogImporter(second).refresh(jdbc, false);

        assertEquals(dataRevision, revision(), "release metadata-only upgrade must not churn catalog data revision");
        assertEquals(4L, releaseVersion());
        assertEquals(firstReleaseFingerprint, releaseFingerprint(), "release fingerprint identifies file content and intentionally excludes version");
        assertEquals("稳定馆", name(id));
    }

    private Path releaseDirectory(long version, String id, String name, String alias) throws Exception {
        Path dir = Files.createTempDirectory("cp6-city-catalog-release-");
        Path city = dir.resolve("probe-city.json");
        writeRows(dir, id, name, alias);
        byte[] content = Files.readAllBytes(city);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", version);
        manifest.put("files", Map.of(city.getFileName().toString(), sha256(content)));
        Files.writeString(dir.resolve("catalog-release.json"), mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
        return dir;
    }

    private void writeRows(Path dir, String id, String name, String alias) throws Exception {
        Path city = dir.resolve("probe-city.json");
        Files.writeString(city, mapper.writeValueAsString(List.of(row(id, name, alias))), StandardCharsets.UTF_8);
    }

    private Map<String, Object> row(String id, String name, String alias) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("displayName", name);
        row.put("city", "探针城");
        row.put("district", "测试区");
        row.put("category", "文化场馆");
        row.put("tags", List.of("探针"));
        row.put("amapQuery", Map.of("keywords", name, "matches", List.of(alias)));
        row.put("summary", "仅用于隔离测试");
        row.put("sourceUrl", "https://example.invalid/catalog-release-probe");
        row.put("verifiedAt", "2026-10-15");
        row.put("verifiedFields", List.of("name", "city"));
        return row;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private long revision() {
        return jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
    }

    private long releaseVersion() {
        return jdbc.queryForObject("SELECT release_version FROM attraction_catalog_revision WHERE id=1", Long.class);
    }

    private String releaseFingerprint() {
        return jdbc.queryForObject("SELECT release_fingerprint FROM attraction_catalog_revision WHERE id=1", String.class);
    }

    private String name(String id) {
        return jdbc.queryForObject("SELECT name FROM attraction WHERE id=?", String.class, id);
    }

    private void runWorker(Path source, String expectedRejectionCause, Path run) throws Exception {
        Files.createDirectories(run);
        Path ready = run.resolve("ready");
        Path start = run.resolve("start");
        Path log = run.resolve("worker.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", classpath, Cp6CityCatalogReleaseWorker.class.getName(), schema, source.toString(),
                ready.toString(), start.toString(), expectedRejectionCause);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        System.getenv().forEach((key, value) -> {
            if (key.startsWith("CP6_IT_") || Set.of("SystemRoot", "WINDIR", "TEMP", "TMP").contains(key))
                builder.environment().put(key, value);
        });
        Process process = builder.start();
        try {
            awaitFile(ready, process, log);
            Files.createFile(start);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "release worker timed out; log=" + readLog(log));
            assertEquals(0, process.exitValue(), "release worker failed; log=" + readLog(log));
            String output = readLog(log);
            assertTrue(output.contains(expectedRejectionCause.isBlank()
                    ? "CP6_CITY_CATALOG_RELEASE_ACCEPTED"
                    : "CP6_CITY_CATALOG_RELEASE_REJECTED " + expectedRejectionCause), "unexpected release worker outcome; log=" + output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private void assertWorkerRejected(Path source, String expectedCause, Path run) throws Exception {
        assertDoesNotThrow(() -> runWorker(source, expectedCause, run));
    }

    private void awaitFile(Path file, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive() && !Files.exists(file)) Thread.sleep(25);
        assertTrue(Files.exists(file) && process.isAlive(), "release worker readiness failed; log=" + readLog(log));
    }

    private String readLog(Path log) throws Exception {
        return Files.exists(log) ? "\n" + Files.readString(log) : " (log not created)";
    }
}

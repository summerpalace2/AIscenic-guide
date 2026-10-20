package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CityCatalogReleaseVersionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void versionedDirectoryIsAuthoritativeAndRejectsRollbackOrSameVersionMutation() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            Path city = directory.resolve("beijing.json");
            try (var input = getClass().getResourceAsStream("/taxonomy/cities/beijing.json")) {
                assertNotNull(input);
                Files.write(city, input.readAllBytes());
            }
            writeManifest(1);
            var importer = new CityAttractionCatalogImporter(directory);
            assertEquals(3, importer.refresh(jdbc, true), "versioned directory must not mix bundled cities");
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM attraction_catalog_entry", Integer.class));
            assertEquals(1L, publishedVersion(jdbc));
            long revision = revision(jdbc);
            assertEquals(3, importer.refresh(jdbc, true));
            assertEquals(revision, revision(jdbc), "same version and bytes are idempotent");

            writeManifest(2);
            assertEquals(3, importer.refresh(jdbc, true));
            assertEquals(2L, publishedVersion(jdbc));
            assertEquals(revision, revision(jdbc), "new release of identical data must not advance data revision");

            writeManifest(1);
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
            assertEquals(2L, publishedVersion(jdbc));
            writeManifest(2);
            Files.writeString(city, Files.readString(city).replace("北京的历史文化博物馆", "北京的古建筑博物馆"));
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true), "file bytes must match manifest");
            writeManifest(2);
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true), "same-version changed bytes must be rejected even with valid hash");
            assertEquals(revision, revision(jdbc));
            assertEquals(2L, publishedVersion(jdbc));

            writeManifest(3);
            assertEquals(3, importer.refresh(jdbc, true));
            assertEquals(revision + 1, revision(jdbc));
            assertEquals(3L, publishedVersion(jdbc));
            Files.delete(directory.resolve("catalog-release.json"));
            assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true), "unversioned refresh must not downgrade a published DB");
            assertEquals(3L, publishedVersion(jdbc));
        }
    }

    @Test void manifestMustListExactlyTheFilesActuallyRead() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            try (var input = getClass().getResourceAsStream("/taxonomy/cities/beijing.json")) {
                assertNotNull(input);
                Files.write(directory.resolve("beijing.json"), input.readAllBytes());
            }
            writeManifest(0);
            assertThrows(IllegalStateException.class,
                    () -> new CityAttractionCatalogImporter(directory).refresh(jdbc, true), "zero version is invalid");
            writeManifest(1);
            Files.copy(directory.resolve("beijing.json"), directory.resolve("extra.json"));
            assertThrows(IllegalStateException.class,
                    () -> new CityAttractionCatalogImporter(directory).refresh(jdbc, true));
            Files.delete(directory.resolve("extra.json"));
            Files.delete(directory.resolve("beijing.json"));
            assertThrows(IllegalStateException.class,
                    () -> new CityAttractionCatalogImporter(directory).refresh(jdbc, true), "listed file is missing");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attraction_catalog_entry", Integer.class));
        }
    }

    @Test void oldRevisionTableGainsReleaseColumnsWithoutLosingItsData() {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE attraction_catalog_revision (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL, fingerprint VARCHAR(64) NOT NULL)");
            jdbc.update("INSERT INTO attraction_catalog_revision (id,revision,fingerprint) VALUES (1,7,'legacy')");
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            assertEquals(7L, revision(jdbc));
            assertEquals("legacy", jdbc.queryForObject("SELECT fingerprint FROM attraction_catalog_revision WHERE id=1", String.class));
            assertEquals(0L, publishedVersion(jdbc));
            assertEquals("", jdbc.queryForObject("SELECT release_fingerprint FROM attraction_catalog_revision WHERE id=1", String.class));
        }
    }

    private void writeManifest(long version) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(directory.resolve("beijing.json"))));
        Files.writeString(directory.resolve("catalog-release.json"), mapper.writeValueAsString(
                Map.of("version", version, "files", Map.of("beijing.json", hash))));
    }

    private long revision(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
    }

    private long publishedVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT release_version FROM attraction_catalog_revision WHERE id=1", Long.class);
    }
}

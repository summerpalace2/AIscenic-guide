package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AttractionCatalogIdentityAliasesTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void upgradesLegacyEntryTableIdempotentlyAndDefaultsExistingAliasesToEmpty() {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE attraction_catalog_entry (id VARCHAR(128) PRIMARY KEY, city VARCHAR(255) NOT NULL, source_url TEXT NOT NULL, verified_at VARCHAR(32) NOT NULL, verified_fields TEXT NOT NULL DEFAULT '[]')");
            jdbc.update("INSERT INTO attraction_catalog_entry (id,city,source_url,verified_at) VALUES ('legacy','测试城','https://example.org','2026-10-01')");
            var initializer = new KnowledgeSchemaInitializer();
            initializer.initialize(jdbc, true);
            initializer.initialize(jdbc, true);
            assertEquals("[]", jdbc.queryForObject("SELECT aliases FROM attraction_catalog_entry WHERE id='legacy'", String.class));
        }
    }

    @Test
    void importsReadsUpdatesRemovesAliasesAndPreservesLegacyRows() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            jdbc.update("INSERT INTO attraction (id,name,city) VALUES ('manual','人工馆','测试城')");
            var importer = new CityAttractionCatalogImporter(directory);
            var adapter = new JdbcAttractionCatalogAdapter(jdbc, mapper);
            Map<String, Object> row = row();
            row.put("aliases", List.of("一博", "第一馆"));
            row.put("verifiedFields", List.of("name", "aliases"));
            write(row);
            importer.refresh(jdbc, true);
            assertEquals(List.of("一博", "第一馆"), adapter.findById("identity-fixture").getAliases());
            assertEquals(List.of("一博", "第一馆"), adapter.findByCity("测试城市", null, null).stream()
                    .filter(a -> a.getId().equals("identity-fixture")).findFirst().orElseThrow().getAliases());
            assertTrue(adapter.findById("manual").getAliases().isEmpty());
            long revision = adapter.revision();
            row.put("aliases", List.of("新简称"));
            write(row);
            importer.refresh(jdbc, true);
            assertTrue(adapter.revision() > revision);
            assertEquals(List.of("新简称"), adapter.findById("identity-fixture").getAliases());
            row.remove("aliases");
            write(row);
            importer.refresh(jdbc, true);
            assertTrue(adapter.findById("identity-fixture").getAliases().isEmpty());
            assertEquals(List.of("国丝馆", "国丝"), adapter.findById("hz-silk-museum").getAliases());
        }
    }

    @Test
    void invalidOrUnverifiedAliasesCannotPartiallyReplacePriorCatalog() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var importer = new CityAttractionCatalogImporter(directory);
            var adapter = new JdbcAttractionCatalogAdapter(jdbc, mapper);
            Map<String, Object> row = row();
            write(row);
            importer.refresh(jdbc, true);
            long revision = adapter.revision();
            for (Object invalid : List.of("简称", List.of(3), List.of(" "), List.of("未核验简称"))) {
                row.put("aliases", invalid);
                write(row);
                assertThrows(IllegalStateException.class, () -> importer.refresh(jdbc, true));
                assertEquals(revision, adapter.revision());
                assertTrue(adapter.findById("identity-fixture").getAliases().isEmpty());
            }
        }
    }

    private Map<String, Object> row() {
        return new LinkedHashMap<>(Map.of("id", "identity-fixture", "name", "第一博物馆", "city", "测试城",
                "category", "人文", "sourceUrl", "https://example.org/identity", "verifiedAt", "2026-10-01"));
    }

    private void write(Map<String, Object> row) throws Exception {
        mapper.writeValue(directory.resolve("identity-test.json").toFile(), List.of(row));
    }
}

package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndependentCityCatalogReviewProbeTest {
    @TempDir Path directory;
    @Test void aliasIndexTracksHotRefreshAndRemovesStaleNames() throws Exception {
        try (var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true)) {
            var jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var mapper = new ObjectMapper();
            var importer = new CityAttractionCatalogImporter(directory);
            Path file = directory.resolve("probe.json");
            mapper.writeValue(file.toFile(), List.of(row("旧名探针馆")));
            importer.refresh(jdbc, true);
            var service = spy(new AttractionService(new JdbcAttractionCatalogAdapter(jdbc, mapper)));
            var matcher = new AttractionAliasMatcher(service);
            assertEquals("probe", matcher.extractMentionedVenueId("旧名探针馆"));
            mapper.writeValue(file.toFile(), List.of(row("新名探针馆")));
            importer.refresh(jdbc, true);
            assertEquals("新名探针馆", service.get("probe").getName());
            assertEquals("probe", matcher.extractMentionedVenueId("新名探针馆"));
            assertNull(matcher.extractMentionedVenueId("旧名探针馆"));
            long revision = service.revision();
            importer.refresh(jdbc, true);
            assertEquals(revision, service.revision(), "Unchanged resources must not advance the catalog revision");
            matcher.extractMentionedVenueId("新名探针馆");
            verify(service, times(2)).list(null, null);
            mapper.writeValue(file.toFile(), List.of());
            importer.refresh(jdbc, true);
            assertNull(matcher.extractMentionedVenueId("新名探针馆"));
            verify(service, times(3)).list(null, null);
        }
    }
    private Map<String,Object> row(String name) {
        return Map.of("id","probe","city","探针城","name",name,"category","人文","sourceUrl","https://example.org/catalog","verifiedAt","2026-10-01");
    }
}

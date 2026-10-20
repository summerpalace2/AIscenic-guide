package com.ai.guide.common.config;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSeedDataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSeedDataInitializerTest {
    @Test
    void packagedKnowledgeDocumentsDeclareTheirOwningEntity() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("knowledge_documents.jsonl")) {
            assertNotNull(input);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String line : new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank()) continue;
                var document = mapper.readTree(line);
                assertTrue(document.hasNonNull("entityName"));
                assertFalse(document.path("entityName").asText().isBlank());
            }
        }
    }

    @Test
    void failedDatasetSeedRollsBackItsPartialRowsAndFailsInitialization() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            jdbc.execute("CREATE TRIGGER fail_after_first_attraction BEFORE INSERT ON attraction " +
                    "WHEN (SELECT COUNT(*) FROM attraction) > 0 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");

            assertThrows(IllegalStateException.class,
                    () -> new KnowledgeSeedDataInitializer().initialize(jdbc, true));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attraction", Integer.class));
        } finally {
            source.destroy();
        }
    }
}

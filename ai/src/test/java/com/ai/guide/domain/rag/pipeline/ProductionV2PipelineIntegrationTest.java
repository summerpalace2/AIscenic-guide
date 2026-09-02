package com.ai.guide.domain.rag.pipeline;




import com.ai.guide.domain.rag.service.ProductionV2FactQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden Demo for the nine-entity Production V2 core. */
class ProductionV2PipelineIntegrationTest {

    private static final Path SOURCE = Path.of("..", "work", "production-v2-source");
    private static final Path DB = Path.of("target", "production-v2-golden.db");
    private static final Path REPORT = Path.of("target", "production-v2-test-reports", "golden-demo-report.json");

    private JdbcTemplate jdbcTemplate;
    private ProductionV2FactQueryService factQuery;
    private ProductionV2QdrantImporter qdrantImporter;

    @BeforeEach
    void setUp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE), "Skipping: external production-v2-source not found");
        Files.deleteIfExists(DB);
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + DB.toAbsolutePath()));
        new ProductionV2DataPipelineService(jdbcTemplate).importProductionV2(SOURCE, false);
        factQuery = new ProductionV2FactQueryService(jdbcTemplate);
        qdrantImporter = new ProductionV2QdrantImporter(
                new ProductionV2ChunkService(), null, null,
                new ObjectMapper().findAndRegisterModules(),
                ProductionV2QdrantImporter.DEFAULT_COLLECTION);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(DB);
    }

    @Test
    void verifiesCoreStructuredFactsAndSemanticProvenance() throws Exception {
        Map<String, String> coreFactFields = new LinkedHashMap<>();
        coreFactFields.put("hongyadong", "official_name");
        coreFactFields.put("liziba_viewpoint", "official_name");
        coreFactFields.put("liziba_station", "official_name");
        coreFactFields.put("eling_testbed2", "official_name");
        coreFactFields.put("shancheng_trail", "official_name");
        coreFactFields.put("huguang_guild_hall", "ticket_price");
        coreFactFields.put("jiefangbei", "official_name");
        coreFactFields.put("yangtze_cableway", "opening_hours");
        coreFactFields.put("three_gorges_museum", "official_name");

        int citedCoreFacts = 0;
        for (Map.Entry<String, String> entry : coreFactFields.entrySet()) {
            ProductionV2FactQueryService.FactResult result = factQuery
                    .first(entry.getKey(), entry.getValue())
                    .orElseThrow(() -> new AssertionError("missing core fact: " + entry.getKey()));
            assertNotNull(result.citation());
            assertTrue(result.citation().sourceUrl().startsWith("http"));
            citedCoreFacts++;
        }
        assertEquals(9, citedCoreFacts);

        ProductionV2FactQueryService.FactResult unknown = factQuery
                .first("eling_testbed2", "ticket_price").orElseThrow();
        assertNull(unknown.value());
        assertEquals("UNKNOWN", unknown.factStatus());

        ProductionV2FactQueryService.FactResult huguang = factQuery
                .first("huguang_guild_hall", "ticket_price").orElseThrow();
        assertEquals("CLM-HGG-003A", huguang.claimId());
        assertEquals(25, huguang.value().asInt());

        ProductionV2FactQueryService.FactResult stale = factQuery
                .first("yangtze_cableway", "opening_hours").orElseThrow();
        assertEquals("STALE", stale.factStatus());
        assertFalse(stale.currentFactEligible());

        List<ProductionV2QdrantImporter.ProvenancePayload> payloads = qdrantImporter.buildPayloads(SOURCE);
        assertFalse(payloads.isEmpty());
        assertTrue(payloads.stream().anyMatch(p -> "hongyadong".equals(p.entityId())));
        for (ProductionV2QdrantImporter.ProvenancePayload payload : payloads) {
            assertNotNull(payload.entityId());
            assertNotNull(payload.knowledgeDocumentId());
            assertNotNull(payload.sourceId());
            assertTrue(payload.sourceUrl().startsWith("http"));
            assertFalse(payload.content().isBlank());
        }
        ProductionV2QdrantImporter.ImportReport qdrantDryRun = qdrantImporter.importData(SOURCE, true, REPORT);
        assertTrue(qdrantDryRun.dryRun());
        assertFalse(qdrantDryRun.legacyCollectionTouched());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASS");
        report.put("structuredCoreCoverage", citedCoreFacts);
        report.put("semanticPayloadCount", payloads.size());
        report.put("semanticProvenanceComplete", qdrantDryRun.provenancePayloadComplete());
        report.put("qdrantCollection", qdrantDryRun.collection());
        report.put("qdrantDryRun", qdrantDryRun.dryRun());
        report.put("externalQdrantUpsert", "NOT_ATTEMPTED");
        report.put("unknownElingTicketPrice", null);
        report.put("huguangVerifiedPrice", 25);
        report.put("yangtzeStaleNotCurrent", !stale.currentFactEligible());
        Files.createDirectories(REPORT.getParent());
        new ObjectMapper().writeValue(REPORT.toFile(), report);
        assertTrue(Files.size(REPORT) > 0);
    }
}

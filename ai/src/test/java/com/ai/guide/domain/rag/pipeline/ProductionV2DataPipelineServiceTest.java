package com.ai.guide.domain.rag.pipeline;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionV2DataPipelineServiceTest {

    private static final Path SOURCE = Path.of("..", "work", "production-v2-source");
    private static final Path DB = Path.of("target", "production-v2-test.db");
    private static final Path REPORT = Path.of("target", "production-v2-test-reports", "postgres-import-report.json");

    private JdbcTemplate jdbcTemplate;
    private ProductionV2DataPipelineService service;

    @BeforeEach
    void setUp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE), "Skipping: external production-v2-source not found");
        Files.deleteIfExists(DB);
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + DB.toAbsolutePath());
        jdbcTemplate = new JdbcTemplate(dataSource);
        service = new ProductionV2DataPipelineService(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(DB);
    }

    @Test
    void dryRunThenIdempotentImportPreservesProductionStatusesAndCitations() throws Exception {
        ProductionV2DataPipelineService.ImportReport dryRun = service.importProductionV2(SOURCE, true);
        assertTrue(dryRun.dryRun());
        assertEquals(17, dryRun.counts().get("entities"));
        assertEquals(36, dryRun.counts().get("facts"));
        assertEquals(0, dryRun.writes());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_v2_entity", Integer.class));

        ProductionV2DataPipelineService.ImportReport first = service.importProductionV2(SOURCE, false);
        ProductionV2DataPipelineService.ImportReport second = service.importProductionV2(SOURCE, false);
        assertEquals(17, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_v2_entity", Integer.class));
        assertEquals(18, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_v2_source_document", Integer.class));
        assertEquals(36, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_v2_fact_claim", Integer.class));
        assertEquals(6, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_v2_qa_issue", Integer.class));
        assertEquals(first.counts(), second.counts());
        assertTrue(first.unknownNullPreserved());
        assertFalse(first.legacyDataTouched());
        assertFalse(first.destructiveOperation());

        Map<String, Object> unknown = jdbcTemplate.queryForMap("SELECT value_json, fact_status, verification_result FROM prod_v2_fact_claim WHERE claim_id = ?", "CLM-EL2-003");
        assertNull(unknown.get("value_json"));
        assertEquals("UNKNOWN", unknown.get("fact_status"));
        assertEquals("UNVERIFIED", unknown.get("verification_result"));

        Map<String, Object> verified = jdbcTemplate.queryForMap("SELECT value_json, source_id, source_url FROM prod_v2_fact_claim WHERE claim_id = ?", "CLM-HGG-003A");
        assertEquals("25", verified.get("value_json"));
        assertEquals("SRC-GOV-012A", verified.get("source_id"));
        assertTrue(String.valueOf(verified.get("source_url")).startsWith("http"));

        Files.createDirectories(REPORT.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASS");
        report.put("dryRunWrites", dryRun.writes());
        report.put("entityCount", first.counts().get("entities"));
        report.put("sourceCount", first.counts().get("sources"));
        report.put("factCount", first.counts().get("facts"));
        report.put("qaIssueCount", first.counts().get("qaIssues"));
        report.put("idempotentSecondRun", second.counts().equals(first.counts()));
        report.put("unknownNullPreserved", first.unknownNullPreserved());
        report.put("legacyDataTouched", first.legacyDataTouched());
        report.put("destructiveOperation", first.destructiveOperation());
        new ObjectMapper().writeValue(REPORT.toFile(), report);
        assertTrue(Files.size(REPORT) > 0);
    }
}

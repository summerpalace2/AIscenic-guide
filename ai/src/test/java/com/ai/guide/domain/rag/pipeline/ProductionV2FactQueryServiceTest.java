package com.ai.guide.domain.rag.pipeline;


import com.ai.guide.domain.rag.service.ProductionV2FactQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionV2FactQueryServiceTest {

    private static final Path SOURCE = Path.of("..", "work", "production-v2-source");
    private static final Path DB = Path.of("target", "production-v2-fact-query.db");

    private JdbcTemplate jdbcTemplate;
    private ProductionV2FactQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE), "Skipping: external production-v2-source not found");
        Files.deleteIfExists(DB);
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + DB.toAbsolutePath()));
        new ProductionV2DataPipelineService(jdbcTemplate).importProductionV2(SOURCE, false);
        service = new ProductionV2FactQueryService(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(DB);
    }

    @Test
    void returnsVerifiedFactWithResolvedSourceCitation() {
        ProductionV2FactQueryService.FactResult result = service
                .first("huguang_guild_hall", "ticket_price")
                .orElseThrow();

        assertEquals("CLM-HGG-003A", result.claimId());
        assertEquals(25, result.value().asInt());
        assertEquals("KNOWN", result.factStatus());
        assertEquals("VERIFIED", result.verificationResult());
        assertEquals("FRESH", result.freshnessStatus());
        assertTrue(result.currentFactEligible());
        assertEquals("SRC-GOV-012A", result.citation().sourceId());
        assertTrue(result.citation().sourceUrl().startsWith("http"));
        assertNotNull(result.citation().sourceTitle());
    }

    @Test
    void preservesUnknownNullAndStaleSemantics() {
        ProductionV2FactQueryService.FactResult unknown = service
                .first("eling_testbed2", "ticket_price")
                .orElseThrow();
        assertNull(unknown.value());
        assertEquals("UNKNOWN", unknown.factStatus());
        assertEquals("UNVERIFIED", unknown.verificationResult());
        assertFalse(unknown.currentFactEligible());

        ProductionV2FactQueryService.FactResult stale = service
                .first("yangtze_cableway", "opening_hours")
                .orElseThrow();
        assertEquals("STALE", stale.factStatus());
        assertEquals("CONFLICT", stale.verificationResult());
        assertEquals("STALE", stale.freshnessStatus());
        assertFalse(stale.currentFactEligible());
        assertTrue(stale.citation().sourceUrl().startsWith("http"));
    }
}

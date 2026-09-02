package com.ai.guide.domain.rag.pipeline;


import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetV21ValidatorTest {

    private static final Path DATASET = Path.of("src", "test", "resources", "golden-dataset-v2-1");

    @Test
    void validatesGoldenDatasetV21WithoutSideEffects() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(DATASET.resolve("golden_route_graph_p0.json")), "Skipping: golden_route_graph_p0.json not present");
        GoldenDatasetV21Validator.ValidationReport report = new GoldenDatasetV21Validator().validate(DATASET);

        assertEquals("PASS", report.status());
        assertEquals("GOLDEN_DATASET_CANDIDATE_V2_1", report.revisionId());
        assertEquals(17, report.totalEntityCount());
        assertEquals(9, report.goldenEntityCount());
        assertEquals(21, report.mapRequiredFieldCount());
        assertEquals(15, report.candidateFactCount());
        assertEquals(14, report.routeEdgeCount());
        assertEquals(9, report.routeNodeCount());
        assertTrue(report.routeHasNoIsolatedNodes());
        assertTrue(report.readOnly());
        assertFalse(report.databaseWritten());
        assertFalse(report.mapApiCalled());
    }
}

package com.ai.guide.domain.rag.pipeline;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionV2ChunkServiceTest {

    private static final Path SOURCE = Path.of("..", "work", "production-v2-source");
    private static final Path REPORT = Path.of("target", "production-v2-test-reports", "chunking-report.json");

    @Test
    void chunksOnlyEligibleKnowledgeDocumentsWithStableProvenance() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE), "Skipping: external production-v2-source not found");
        ProductionV2ChunkService service = new ProductionV2ChunkService();
        List<ProductionV2ChunkService.ProductionV2Chunk> chunks = service.chunk(SOURCE);

        assertFalse(chunks.isEmpty());
        Set<String> documentIds = chunks.stream()
                .map(ProductionV2ChunkService.ProductionV2Chunk::knowledgeDocumentId)
                .collect(Collectors.toSet());
        assertEquals(10, documentIds.size());
        assertTrue(chunks.stream().allMatch(chunk -> nonBlank(chunk.chunkId())
                && nonBlank(chunk.entityId())
                && nonBlank(chunk.sourceId())
                && nonBlank(chunk.sourceDocumentId())
                && nonBlank(chunk.sourceUrl())
                && nonBlank(chunk.publisher())
                && nonBlank(chunk.verificationResult())
                && chunk.ragEligible()
                && nonBlank(chunk.version())
                && nonBlank(chunk.provenance())
                && nonBlank(chunk.content())));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("CLM-")
                || chunk.content().contains("ISSUE-")));

        List<ProductionV2ChunkService.ProductionV2Chunk> secondRun = service.chunk(SOURCE);
        assertEquals(chunks.stream().map(ProductionV2ChunkService.ProductionV2Chunk::chunkId).toList(),
                secondRun.stream().map(ProductionV2ChunkService.ProductionV2Chunk::chunkId).toList());

        Files.createDirectories(REPORT.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", "PASS");
        report.put("ragEligibleDocuments", documentIds.size());
        report.put("chunkCount", chunks.size());
        report.put("provenanceComplete", true);
        report.put("factClaimsIncluded", false);
        report.put("qaIssuesIncluded", false);
        new ObjectMapper().writeValue(REPORT.toFile(), report);
        assertNotNull(Files.readString(REPORT));
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}

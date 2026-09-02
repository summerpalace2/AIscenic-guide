package com.ai.guide.domain.rag.pipeline;



import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionV2QdrantImporterTest {

    private static final Path SOURCE_DIR = Path.of("..", "work", "production-v2-source");
    private static final Path REPORT = Path.of("target", "production-v2-test-reports", "qdrant-import-report.json");

    @Test
    void dryRunProducesStableProvenancePayloadsWithoutTouchingLegacyCollection() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE_DIR), "Skipping: external production-v2-source not found");
        Files.deleteIfExists(REPORT);
        ProductionV2QdrantImporter importer = new ProductionV2QdrantImporter(
                new ProductionV2ChunkService(),
                null,
                null,
                new ObjectMapper().findAndRegisterModules(),
                ProductionV2QdrantImporter.DEFAULT_COLLECTION);

        List<ProductionV2QdrantImporter.ProvenancePayload> payloads = importer.buildPayloads(SOURCE_DIR);
        assertFalse(payloads.isEmpty());
        Set<String> chunkIds = new HashSet<>();
        Set<String> pointIds = new HashSet<>();
        for (ProductionV2QdrantImporter.ProvenancePayload payload : payloads) {
            assertTrue(chunkIds.add(payload.chunkId()));
            assertTrue(pointIds.add(importer.deterministicPointId(payload)));
            assertNotNull(payload.entityId());
            assertNotNull(payload.knowledgeDocumentId());
            assertNotNull(payload.sourceId());
            assertNotNull(payload.sourceDocumentId());
            assertNotNull(payload.publisher());
            assertTrue(payload.sourceUrl().startsWith("http"));
            assertFalse(payload.content().isBlank());
            assertTrue(payload.ragEligible());
            assertFalse(payload.version().isBlank());
            assertTrue(payload.provenance().contains(payload.sourceDocumentId()));
        }

        List<ProductionV2QdrantImporter.ProvenancePayload> secondPayloads = importer.buildPayloads(SOURCE_DIR);
        assertEquals(pointIds,
                secondPayloads.stream().map(importer::deterministicPointId).collect(java.util.stream.Collectors.toSet()));

        ProductionV2QdrantImporter.ImportReport report = importer.importData(SOURCE_DIR, true, REPORT);
        assertEquals("PASS", report.status());
        assertTrue(report.dryRun());
        assertEquals(importer.collectionName(), report.collection());
        assertEquals(payloads.size(), report.chunkCount());
        assertEquals(0, report.embeddedCount());
        assertEquals(0, report.upsertedCount());
        assertEquals("NOT_RUN", report.verificationStatus());
        assertFalse(report.legacyCollectionTouched());
        assertTrue(report.stablePointIds());
        assertTrue(report.provenancePayloadComplete());
        assertEquals(10, report.documentsEligible());
        assertEquals(payloads.size(), report.expectedPoints());
        assertEquals(0, report.embeddingFailures());
        assertTrue(Files.exists(REPORT));
    }

    @Test
    void shrinkingSnapshotProducesStalePointIdsForReconciliation() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(SOURCE_DIR), "Skipping: external production-v2-source not found");
        Path temp = Files.createTempDirectory("production-v2-shrink");
        Files.copy(SOURCE_DIR.resolve("source_documents.json"), temp.resolve("source_documents.json"));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.fasterxml.jackson.databind.node.ArrayNode documents = (com.fasterxml.jackson.databind.node.ArrayNode)
                mapper.readTree(Files.readString(SOURCE_DIR.resolve("knowledge_documents.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) documents.get(0)).put("content", "洪崖洞 ".repeat(300));
        mapper.writeValue(temp.resolve("knowledge_documents.json").toFile(), documents);

        ProductionV2QdrantImporter importer = new ProductionV2QdrantImporter(
                new ProductionV2ChunkService(), null, null, mapper,
                ProductionV2QdrantImporter.DEFAULT_COLLECTION);
        Set<String> oldPointIds = importer.expectedPointIds(importer.buildPayloads(temp));
        assertTrue(oldPointIds.size() > 10);

        ((com.fasterxml.jackson.databind.node.ObjectNode) documents.get(0)).put("content", "洪崖洞的官方介绍与交通信息。");
        mapper.writeValue(temp.resolve("knowledge_documents.json").toFile(), documents);
        Set<String> newPointIds = importer.expectedPointIds(importer.buildPayloads(temp));

        assertEquals(10, newPointIds.size());
        assertNotEquals(oldPointIds, newPointIds);
        assertEquals(oldPointIds, importer.stalePointIds(oldPointIds, newPointIds));
    }

    @Test
    void rejectsLegacyCollectionName() {
        assertThrows(IllegalArgumentException.class, () -> new ProductionV2QdrantImporter(
                new ProductionV2ChunkService(), null, null,
                new ObjectMapper(), ProductionV2QdrantImporter.LEGACY_COLLECTION));
    }
}

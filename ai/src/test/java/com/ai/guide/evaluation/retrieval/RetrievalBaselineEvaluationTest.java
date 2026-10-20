package com.ai.guide.evaluation.retrieval;

import com.ai.guide.domain.rag.gateway.*;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.service.*;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter;
import com.ai.guide.testing.KnowledgeTestPorts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Opt-in measurement, not a pass-rate assertion. Uses production JDBC search,
 * ranking, query expansion, fallback coordinator and evidence gate. Only the
 * unavailable semantic supplier is stubbed; no embedding/model/network call.
 */
@EnabledIfSystemProperty(named = "ai.evaluation.enabled", matches = "true")
class RetrievalBaselineEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporaryDirectory;

    @Test void measureControlledDocumentRetrievalAndUnknownGate() throws Exception {
        byte[] caseBytes = resource("retrieval-cases.json");
        byte[] corpusBytes = resource("retrieval-corpus.json");
        // Freeze source identity before running any query; gold never depends on results.
        String datasetHash = sha256(caseBytes);
        String corpusHash = sha256(corpusBytes);
        JsonNode dataset = JSON.readTree(caseBytes);
        JsonNode corpus = JSON.readTree(corpusBytes);
        assertEquals(100, dataset.path("cases").size());
        assertEquals(30, corpus.path("documents").size());
        var documentsById = new LinkedHashMap<String, JsonNode>();
        for (JsonNode d : corpus.path("documents")) assertNull(documentsById.put(d.path("id").asText(), d));
        var ids = new HashSet<String>();
        for (JsonNode c : dataset.path("cases")) {
            assertTrue(ids.add(c.path("id").asText()), "duplicate case ID");
            assertFalse(c.path("query").asText().isBlank());
            assertEquals(c.path("relevantDocumentIds").isEmpty(), c.path("expectedStatus").asText().equals("UNKNOWN"));
            for (JsonNode id : c.path("relevantDocumentIds")) {
                assertTrue(documentsById.containsKey(id.asText()), "gold document missing: " + id);
                assertEquals(c.path("city").asText(), documentsById.get(id.asText()).path("city").asText());
            }
        }
        var source = new SingleConnectionDataSource("jdbc:sqlite:" + temporaryDirectory.resolve("controlled-retrieval.sqlite"), true);
        var jdbc = new JdbcTemplate(source);
        var results = new ArrayList<Map<String, Object>>();
        try {
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            var store = new JdbcKnowledgeDocumentStoreAdapter(jdbc);
            for (JsonNode fixture : corpus.path("documents")) {
                var document = new KnowledgeDocument();
                document.setId(fixture.path("id").asText());
                document.setTitle(fixture.path("title").asText());
                document.setContent(fixture.path("content").asText());
                document.setCategory("controlled-evaluation");
                document.setTags(JSON.writeValueAsString(fixture.path("tags")));
                document.setScope(fixture.path("scope").asText());
                document.setCity(fixture.path("city").asText());
                document.setSourceName(document.getId());
                document.setCreatedBy("isolated-evaluation");
                document.setCreatedAt(LocalDateTime.of(2026, 10, 17, 0, 0));
                document.setUpdatedAt(document.getCreatedAt());
                store.insert(document);
            }
            var taxonomy = new JsonTaxonomyAdapter();
            var policy = mock(KnowledgeRuntimePolicyPort.class);
            when(policy.retrievalMaxCandidates()).thenReturn(200);
            when(policy.retrievalExcerptChars()).thenReturn(4096);
            var service = new RecordingLocalService(store, policy, taxonomy);
            var semanticUnavailable = mock(ScenicKnowledgePort.class);
            when(semanticUnavailable.collectionName()).thenReturn("NOT_RUN-controlled-local-fallback");
            when(semanticUnavailable.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                    .thenThrow(new IllegalStateException("Controlled evaluation: semantic supplier deliberately unavailable"));
            var retrieval = new RagRetrievalService(semanticUnavailable, service, new RagQueryExpander(taxonomy));
            for (JsonNode c : dataset.path("cases")) {
                service.lastCandidates = List.of();
                service.lastQuery = "";
                long start = System.nanoTime();
                Map<String, Object> response = retrieval.retrieve(c.path("query").asText(), c.path("city").asText(), Map.of(), false);
                double latency = (System.nanoTime() - start) / 1_000_000.0;
                assertEquals(true, response.get("ok"), "Infrastructure/harness error must not be scored as quality");
                assertTrue(List.of("LOCAL_FALLBACK", "QUERY_GATE").contains(response.get("retrievalSource")));
                List<String> retrieved = service.lastCandidates.stream().map(d -> String.valueOf(d.get("id"))).toList();
                Set<String> gold = new LinkedHashSet<>();
                c.path("relevantDocumentIds").forEach(d -> gold.add(d.asText()));
                boolean answerable = !gold.isEmpty();
                boolean unknown = "UNKNOWN".equals(response.get("retrievalStatus"));
                boolean leak = service.lastCandidates.stream().anyMatch(d ->
                        !new KnowledgeScope("CITY", c.path("city").asText()).permits(
                                new KnowledgeScope(String.valueOf(d.get("scope")), String.valueOf(d.get("city")))));
                var metrics = new LinkedHashMap<String, Object>();
                metrics.put("answerable", answerable);
                metrics.put("recallAt3", answerable ? recall(gold, retrieved, 3) : null);
                metrics.put("recallAt5", answerable ? recall(gold, retrieved, 5) : null);
                metrics.put("mrr", answerable ? reciprocalRank(gold, retrieved) : null);
                metrics.put("top1Relevant", answerable ? !retrieved.isEmpty() && gold.contains(retrieved.get(0)) : null);
                metrics.put("rejectedAsUnknown", unknown);
                metrics.put("crossCityLeak", leak);
                var actual = new LinkedHashMap<String, Object>();
                actual.put("retrievedDocumentIds", retrieved);
                actual.put("candidates", service.lastCandidates.stream().map(d -> Map.of(
                        "documentId", d.get("id"), "city", d.get("city"), "scope", d.get("scope"),
                        "score", d.get("retrievalScore"))).toList());
                actual.put("expandedQuery", service.lastQuery);
                actual.put("retrievalSource", response.get("retrievalSource"));
                actual.put("retrievalStatus", response.get("retrievalStatus"));
                actual.put("gateDecision", Map.of("isUnknown", unknown, "reasonCode", response.get("reasonCode")));
                actual.put("factsReturnedCount", ((List<?>) response.get("facts")).size());
                var row = new LinkedHashMap<String, Object>();
                row.put("id", c.path("id").asText()); row.put("query", c.path("query").asText());
                row.put("city", c.path("city").asText()); row.put("category", c.path("category").asText());
                row.put("familyId", c.path("familyId").asText());
                row.put("expected", Map.of("relevantDocumentIds", gold, "retrievalStatus", c.path("expectedStatus").asText()));
                row.put("actual", actual); row.put("metrics", metrics); row.put("latencyMs", latency);
                boolean passed = !leak && (answerable ? !unknown && recall(gold, retrieved, 5) == 1.0 : unknown);
                row.put("status", passed ? "PASS" : "QUALITY_FAILURE");
                results.add(row);
            }
            long retrievalRequests = results.stream().filter(r -> !"QUERY_GATE".equals(
                    ((Map<?, ?>) r.get("actual")).get("retrievalSource"))).count();
            verify(semanticUnavailable, times(Math.toIntExact(retrievalRequests)))
                    .searchScoped(anyString(), anyInt(), anyInt(), eq(false), any());
        } finally { source.destroy(); }
        var output = new LinkedHashMap<String, Object>();
        output.put("schemaVersion", "ai-evaluation-v1"); output.put("suite", "retrieval");
        output.put("protocolVersion", "v2-query-gate-before-retrieval");
        output.put("runId", System.getProperty("ai.evaluation.runId", "unspecified-local-run"));
        output.put("generatedAt", Instant.now().toString());
        output.put("executionMode", "LOCAL_RETRIEVAL_WITH_UNAVAILABLE_SEMANTIC_GATE_FAIL_CLOSED_NOT_SEMANTIC_QUALITY");
        output.put("datasetSha256", datasetHash); output.put("corpusSha256", corpusHash);
        output.put("sourcePolicy", corpus.path("sourcePolicy").asText());
        output.put("annotationPolicy", dataset.path("annotationPolicy").asText());
        output.put("familyCount", dataset.path("familyCount").asInt());
        output.put("measurementBoundary", "Document IDs before gate; actual production fallback coordinator and gate. No answer generation, faithfulness or real-world accuracy measured. 200 candidate budget, 4096 excerpt chars, limit 5; classpath taxonomy.");
        output.put("strategies", Map.of("localFallback", "EXECUTED", "dense", "NOT_RUN", "hybrid", "NOT_RUN", "bm25", "NOT_RUN"));
        output.put("cases", results); output.put("summary", summarize(results));
        Path out = Path.of("target", "ai-evaluation", "retrieval-results.json");
        Files.createDirectories(out.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), output);
        // Assert the measurement contract, never tune away or fail merely on a low baseline.
        assertEquals(100, results.size());
        assertEquals(75L, results.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("answerable").equals(true)).count());
        assertEquals(datasetHash, sha256(resource("retrieval-cases.json")), "Gold changed while measuring");
        assertEquals(corpusHash, sha256(resource("retrieval-corpus.json")), "Corpus changed while measuring");
    }

    private static final class RecordingLocalService extends KnowledgeDocumentService {
        List<Map<String, Object>> lastCandidates = List.of();
        String lastQuery = "";
        RecordingLocalService(KnowledgeDocumentStorePort store, KnowledgeRuntimePolicyPort policy, JsonTaxonomyAdapter taxonomy) {
            super(store, KnowledgeTestPorts.noIndex(), mock(KnowledgeFileStoragePort.class), Runnable::run, () -> {}, policy, taxonomy);
        }
        @Override public List<Map<String, Object>> searchLocalKnowledge(String query, int limit, KnowledgeScope scope) {
            lastQuery = query;
            lastCandidates = super.searchLocalKnowledge(query, limit, scope);
            return lastCandidates;
        }
    }
    private static byte[] resource(String name) throws Exception {
        try (var input = RetrievalBaselineEvaluationTest.class.getResourceAsStream("/ai-evaluation/" + name)) {
            assertNotNull(input, name); return input.readAllBytes();
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static double recall(Set<String> gold, List<String> retrieved, int k) {
        return retrieved.stream().limit(k).distinct().filter(gold::contains).count() / (double) gold.size();
    }
    private static double reciprocalRank(Set<String> gold, List<String> retrieved) {
        for (int i = 0; i < retrieved.size(); i++) if (gold.contains(retrieved.get(i))) return 1.0 / (i + 1);
        return 0.0;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> summarize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> answerable = rows.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("answerable").equals(true)).toList();
        List<Map<String, Object>> unknown = rows.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("answerable").equals(false)).toList();
        var summary = new LinkedHashMap<String, Object>();
        summary.put("executed", rows.size()); summary.put("answerableDenominator", answerable.size());
        summary.put("unknownDenominator", unknown.size());
        for (String name : List.of("recallAt3", "recallAt5", "mrr")) summary.put(name,
                answerable.stream().map(r -> (Map<String, Object>) r.get("metrics")).mapToDouble(m -> ((Number) m.get(name)).doubleValue()).average().orElse(0));
        long falseAnswer = unknown.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("rejectedAsUnknown").equals(false)).count();
        long missedAnswer = answerable.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("rejectedAsUnknown").equals(true)).count();
        summary.put("unknownFalseAnswerCount", falseAnswer); summary.put("unknownFalseAnswerRate", falseAnswer / (double) unknown.size());
        summary.put("answerableRejectedCount", missedAnswer); summary.put("answerableRejectedRate", missedAnswer / (double) answerable.size());
        summary.put("crossCityLeakCaseCount", rows.stream().filter(r -> ((Map<?, ?>) r.get("metrics")).get("crossCityLeak").equals(true)).count());
        summary.put("qualityFailureCount", rows.stream().filter(r -> r.get("status").equals("QUALITY_FAILURE")).count());
        summary.put("latencyMeanMs", rows.stream().mapToDouble(r -> ((Number) r.get("latencyMs")).doubleValue()).average().orElse(0));
        double[] sorted = rows.stream().mapToDouble(r -> ((Number) r.get("latencyMs")).doubleValue()).sorted().toArray();
        summary.put("latencyP95Ms", sorted[(int) Math.ceil(sorted.length * .95) - 1]);
        return summary;
    }
}

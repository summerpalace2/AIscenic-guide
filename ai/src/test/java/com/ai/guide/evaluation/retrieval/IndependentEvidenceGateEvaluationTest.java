package com.ai.guide.evaluation.retrieval;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.KnowledgeFragment;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Frozen independent gate cases, with supplied evidence; this does not measure retrieval recall. */
@EnabledIfSystemProperty(named="gate.unseen.enabled", matches="true")
class IndependentEvidenceGateEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void evaluateFrozenQuestionsWithoutChangingGoldOrProduction() throws Exception {
        Path input = Path.of(Objects.requireNonNull(System.getProperty("gate.unseen.input")));
        Path output = Path.of(Objects.requireNonNull(System.getProperty("gate.unseen.output")));
        byte[] bytes = Files.readAllBytes(input);
        String inputHash = hash(bytes);
        assertEquals(System.getProperty("gate.unseen.sha256"), inputHash, "Author freeze must match before reading cases");
        JsonNode dataset = JSON.readTree(bytes);
        assertTrue(dataset.path("cases").size() >= 20 && dataset.path("cases").size() <= 30);
        Path sources = Path.of(System.getProperty("gate.unseen.project"));
        var before = sourceHashes(sources);
        Files.createDirectories(output);
        write(output.resolve("freeze-before.json"), Map.of("datasetSha256", inputHash,
                "productionHashes", before, "source", input.toString(), "protocol", "PRESELECTED_EVIDENCE_GATE_ONLY"));
        var rows = new ArrayList<Map<String,Object>>();
        var seen = new HashSet<String>();
        for (JsonNode c : dataset.path("cases")) {
            String id = c.path("id").asText();
            assertTrue(!id.isBlank() && seen.add(id));
            assertTrue(List.of("RELEVANT", "UNKNOWN").contains(c.path("expectedStatus").asText()));
            assertFalse(c.path("query").asText().isBlank());
            String city = c.path("city").asText();
            assertFalse(city.isBlank());
            assertTrue(c.path("evidence").isArray());
            var evidence = new ArrayList<Map<String,Object>>();
            var fragments = new ArrayList<KnowledgeFragment>();
            for (JsonNode e : c.path("evidence")) {
                String title = e.path("title").asText(), content = e.path("content").asText();
                evidence.add(Map.of("id", e.path("id").asText(), "title", title, "content", content));
                fragments.add(new KnowledgeFragment(e.path("id").asText(), "INDEPENDENT_SYNTHETIC_FIXTURE",
                        title + "\n" + content, new KnowledgeScope("CITY", city)));
            }
            var row = new LinkedHashMap<String,Object>();
            row.put("id", id); row.put("category", c.path("category").asText());
            row.put("query", c.path("query").asText()); row.put("city", city);
            row.put("expectedStatus", c.path("expectedStatus").asText());
            row.put("rationale", c.path("rationale").asText()); row.put("evidence", evidence);
            for (boolean fallback : List.of(false, true)) {
                var semantic = mock(ScenicKnowledgePort.class);
                var local = mock(KnowledgeDocumentService.class);
                when(semantic.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                        .thenReturn(fallback ? List.of() : fragments);
                when(local.searchLocalKnowledge(anyString(), anyInt(), any(KnowledgeScope.class))).thenReturn(evidence);
                long start = System.nanoTime();
                var actual = new RagRetrievalService(semantic, local).retrieve(c.path("query").asText(), city, Map.of(), false);
                assertEquals(true, actual.get("ok"), "Harness failure is not a quality score");
                var result = new LinkedHashMap<String,Object>();
                result.put("response", actual);
                result.put("matched", c.path("expectedStatus").asText().equals(actual.get("retrievalStatus")));
                result.put("latencyMs", (System.nanoTime()-start)/1_000_000.0);
                row.put(fallback ? "local" : "semantic", result);
            }
            rows.add(row);
        }
        assertEquals(inputHash, hash(Files.readAllBytes(input)), "Dataset changed during evaluation");
        assertEquals(before, sourceHashes(sources), "Production changed during holdout measurement");
        var summary = new LinkedHashMap<String,Object>();
        summary.put("schemaVersion", "independent-gate-evaluation-v1");
        summary.put("datasetSha256", inputHash); summary.put("caseCount", rows.size());
        summary.put("provenance", dataset.path("provenance"));
        summary.put("productionHashes", before);
        summary.put("boundary", "Historical synthetic dataset; model unavailable, fail-closed service branches. Not a live semantic quality evaluation; no real embedding/rerank, HTTP or model answers");
        for (String branch : List.of("semantic", "local")) {
            long unknown = rows.stream().filter(r -> "UNKNOWN".equals(r.get("expectedStatus"))).count();
            long allowed = rows.size() - unknown;
            long falseAllow = rows.stream().filter(r -> "UNKNOWN".equals(r.get("expectedStatus")))
                    .filter(r -> !Boolean.TRUE.equals(((Map<?,?>)r.get(branch)).get("matched"))).count();
            long falseReject = rows.stream().filter(r -> "RELEVANT".equals(r.get("expectedStatus")))
                    .filter(r -> !Boolean.TRUE.equals(((Map<?,?>)r.get(branch)).get("matched"))).count();
            summary.put(branch, Map.of("unknownDenominator", unknown, "falseAllow", falseAllow,
                    "answerableDenominator", allowed, "falseReject", falseReject));
        }
        write(output.resolve("results.json"), Map.of("summary", summary, "cases", rows));
        write(output.resolve("summary.json"), summary);
    }

    private static Map<String,String> sourceHashes(Path root) throws Exception {
        var result = new LinkedHashMap<String,String>();
        for(String name : List.of("RagRetrievalService.java")) {
            Path path = root.resolve("ai/src/main/java/com/ai/guide/domain/rag/service").resolve(name);
            result.put(name, hash(Files.readAllBytes(path)));
        }
        return result;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardOpenOption.CREATE_NEW);
    }
}

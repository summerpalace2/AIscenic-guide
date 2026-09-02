package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选知识数据集无副作用只读校验器
 *
 * 所属领域：domain.rag.pipeline（知识与数据管线层）
 */
public class GoldenDatasetV21Validator {

    public static final String REVISION_ID = "GOLDEN_DATASET_CANDIDATE_V2_1";
    public static final String ROUTE_GRAPH_TYPE = "GOLDEN_ROUTE_GRAPH_P0";
    public static final List<String> GOLDEN_ENTITIES = List.of(
            "hongyadong",
            "liziba_viewpoint",
            "liziba_station",
            "eling_testbed2",
            "shancheng_trail",
            "huguang_guild_hall",
            "jiefangbei",
            "yangtze_cableway",
            "three_gorges_museum"
    );

    private static final Set<String> ALLOWED_FACT_FIELDS = Set.of(
            "official_name", "official_address", "ticket_price", "opening_hours"
    );
    private static final Set<String> ALLOWED_TRAVEL_MODES = Set.of("WALKING", "TRANSIT");

    private final ObjectMapper objectMapper;

    public GoldenDatasetV21Validator() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public GoldenDatasetV21Validator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationReport validate(Path sourceDirectory) throws IOException {
        if (sourceDirectory == null || !Files.isDirectory(sourceDirectory)) {
            throw new IllegalArgumentException("Golden Dataset directory does not exist: " + sourceDirectory);
        }

        JsonNode manifest = readObject(sourceDirectory, "dataset_revision_manifest.json");
        JsonNode mapRequirements = readArray(sourceDirectory, "map_api_required_all_9.json");
        JsonNode candidates = readArray(sourceDirectory, "verified_candidates_corrected.json");
        JsonNode routeGraph = readObject(sourceDirectory, "golden_route_graph_p0.json");

        validateManifest(manifest);
        MapSummary mapSummary = validateMapRequirements(mapRequirements);
        CandidateSummary candidateSummary = validateCandidates(candidates);
        RouteSummary routeSummary = validateRouteGraph(routeGraph);

        return new ValidationReport(
                "PASS",
                REVISION_ID,
                manifest.path("entity_count").asInt(),
                manifest.path("priority_golden_entity_count").asInt(),
                mapSummary.entityCount(),
                mapSummary.requiredFieldCount(),
                candidateSummary.candidateCount(),
                candidateSummary.entityCount(),
                routeSummary.edgeCount(),
                routeSummary.nodeCount(),
                routeSummary.coveredNodeCount(),
                routeSummary.noIsolatedNodes(),
                true,
                false,
                false
        );
    }

    private void validateManifest(JsonNode manifest) {
        requireEquals(REVISION_ID, text(manifest, "revision_id"), "manifest.revision_id");
        requireEquals(17, manifest.path("entity_count").asInt(), "manifest.entity_count");
        requireEquals(GOLDEN_ENTITIES.size(), manifest.path("priority_golden_entity_count").asInt(),
                "manifest.priority_golden_entity_count");
        requireEquals(18, manifest.path("source_count").asInt(), "manifest.source_count");
        requireEquals(36, manifest.path("fact_count").asInt(), "manifest.fact_count");
        requireEquals(10, manifest.path("knowledge_document_count").asInt(),
                "manifest.knowledge_document_count");
        requireEquals(6, manifest.path("qa_issue_count").asInt(), "manifest.qa_issue_count");
        requireEquals(14, manifest.path("p0_route_edges_count").asInt(), "manifest.p0_route_edges_count");
    }

    private MapSummary validateMapRequirements(JsonNode rows) {
        Set<String> entities = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        int requiredFieldCount = 0;
        for (JsonNode row : rows) {
            String entityId = requiredText(row, "entity_id");
            requireCoreEntity(entityId, "map requirement entity_id");
            requireEquals("AMAP_GEOCODING_AND_POI", text(row, "api_action"),
                    "map requirement api_action for " + entityId);
            JsonNode requiredFields = row.get("required_fields");
            if (requiredFields == null || !requiredFields.isArray() || requiredFields.isEmpty()) {
                throw new IllegalArgumentException("required_fields must be a non-empty array for " + entityId);
            }
            entities.add(entityId);
            requiredFieldCount += requiredFields.size();
            requiredFields.forEach(field -> fields.add(field.asText()));
        }
        requireSetEquals(new HashSet<>(GOLDEN_ENTITIES), entities, "map requirement entity coverage");
        requireEquals(9, rows.size(), "map requirement count");
        requireEquals(21, requiredFieldCount, "map required field count");
        return new MapSummary(entities.size(), requiredFieldCount);
    }

    private CandidateSummary validateCandidates(JsonNode rows) {
        Set<String> candidateIds = new HashSet<>();
        Set<String> entities = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String candidateId = requiredText(row, "candidate_id");
            if (!candidateIds.add(candidateId)) {
                throw new IllegalArgumentException("duplicate candidate_id: " + candidateId);
            }
            String entityId = requiredText(row, "entity_id");
            requireCoreEntity(entityId, "candidate entity_id");
            String field = requiredText(row, "field");
            if (!ALLOWED_FACT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unsupported candidate field: " + field);
            }
            requireHttpUrl(row, "source_url");
            requireNonBlank(row, "organization");
            requireNonBlank(row, "evidence_excerpt");
            requireEquals("VERIFIABLE", text(row, "audit_verdict"),
                    "candidate audit_verdict for " + candidateId);
            entities.add(entityId);
        }
        requireEquals(15, rows.size(), "verified candidate count");
        requireSetEquals(new HashSet<>(GOLDEN_ENTITIES), entities, "candidate entity coverage");
        return new CandidateSummary(rows.size(), entities.size());
    }

    private RouteSummary validateRouteGraph(JsonNode graph) {
        requireEquals(ROUTE_GRAPH_TYPE, text(graph, "graph_type"), "route graph type");
        requireEquals("v2.1", text(graph, "version"), "route graph version");
        requireEquals(9, graph.path("target_nodes_count").asInt(), "route graph target_nodes_count");
        requireEquals(14, graph.path("p0_edges_count").asInt(), "route graph p0_edges_count");
        JsonNode edges = graph.get("edges");
        if (edges == null || !edges.isArray()) {
            throw new IllegalArgumentException("route graph edges must be an array");
        }

        Set<String> edgeIds = new HashSet<>();
        Set<String> nodes = new LinkedHashSet<>();
        for (JsonNode edge : edges) {
            String edgeId = requiredText(edge, "edge_id");
            if (!edgeIds.add(edgeId)) throw new IllegalArgumentException("duplicate route edge: " + edgeId);
            requireEquals("P0", text(edge, "priority"), "route edge priority for " + edgeId);
            String from = requiredText(edge, "from_entity_id");
            String to = requiredText(edge, "to_entity_id");
            requireCoreEntity(from, "route edge from_entity_id");
            requireCoreEntity(to, "route edge to_entity_id");
            if (from.equals(to)) throw new IllegalArgumentException("route edge cannot be self-loop: " + edgeId);
            if (!ALLOWED_TRAVEL_MODES.contains(text(edge, "travel_mode"))) {
                throw new IllegalArgumentException("unsupported travel mode for " + edgeId);
            }
            nodes.add(from);
            nodes.add(to);
        }
        requireEquals(14, edges.size(), "route graph edge count");
        requireEquals(9, nodes.size(), "route graph node count");
        requireSetEquals(new HashSet<>(GOLDEN_ENTITIES), nodes, "route graph node coverage");
        return new RouteSummary(edges.size(), nodes.size(), nodes.size(), true);
    }

    private JsonNode readObject(Path directory, String name) throws IOException {
        JsonNode node = read(directory, name);
        if (!node.isObject()) throw new IOException(name + " must contain an object");
        return node;
    }

    private JsonNode readArray(Path directory, String name) throws IOException {
        JsonNode node = read(directory, name);
        if (!node.isArray()) throw new IOException(name + " must contain an array");
        return node;
    }

    private JsonNode read(Path directory, String name) throws IOException {
        return objectMapper.readTree(Files.readString(directory.resolve(name), StandardCharsets.UTF_8));
    }

    private void requireCoreEntity(String entityId, String label) {
        if (!GOLDEN_ENTITIES.contains(entityId)) throw new IllegalArgumentException(label + ": " + entityId);
    }

    private void requireHttpUrl(JsonNode row, String field) {
        String value = requiredText(row, field);
        try {
            URI uri = URI.create(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("not an HTTP URL: " + value);
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " must be a valid HTTP URL", error);
        }
    }

    private void requireNonBlank(JsonNode row, String field) {
        if (requiredText(row, field).isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private String requiredText(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void requireEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) throw new IllegalArgumentException(label + " expected " + expected + " but was " + actual);
    }

    private void requireSetEquals(Set<String> expected, Set<String> actual, String label) {
        if (!expected.equals(actual)) throw new IllegalArgumentException(label + " expected " + expected + " but was " + actual);
    }

    private record MapSummary(int entityCount, int requiredFieldCount) {}

    private record CandidateSummary(int candidateCount, int entityCount) {}

    private record RouteSummary(int edgeCount, int nodeCount, int coveredNodeCount, boolean noIsolatedNodes) {}

    public record ValidationReport(
            String status,
            String revisionId,
            int totalEntityCount,
            int goldenEntityCount,
            int mapRequirementEntityCount,
            int mapRequiredFieldCount,
            int candidateFactCount,
            int candidateEntityCount,
            int routeEdgeCount,
            int routeNodeCount,
            int routeCoveredNodeCount,
            boolean routeHasNoIsolatedNodes,
            boolean readOnly,
            boolean databaseWritten,
            boolean mapApiCalled
    ) {}
}

package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 候选地标路线拓扑构建器
 *
 * 所属领域：domain.rag.pipeline（知识与数据管线层）
 */
public class GoldenDatasetV21RouteTaskBuilder {

    public static final String REVISION_ID = GoldenDatasetV21Validator.REVISION_ID;

    private final ObjectMapper objectMapper;

    public GoldenDatasetV21RouteTaskBuilder() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public GoldenDatasetV21RouteTaskBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RouteMeasurementTask> build(Path routeGraphFile) throws IOException {
        if (routeGraphFile == null || !Files.isRegularFile(routeGraphFile)) {
            throw new IllegalArgumentException("Route graph does not exist: " + routeGraphFile);
        }
        JsonNode graph = objectMapper.readTree(Files.readString(routeGraphFile, StandardCharsets.UTF_8));
        if (!GoldenDatasetV21Validator.ROUTE_GRAPH_TYPE.equals(text(graph, "graph_type"))) {
            throw new IllegalArgumentException("unsupported route graph type");
        }
        if (!"v2.1".equals(text(graph, "version"))) {
            throw new IllegalArgumentException("unsupported route graph version");
        }

        JsonNode edges = graph.get("edges");
        if (edges == null || !edges.isArray()) throw new IOException("route graph edges must be an array");
        List<RouteMeasurementTask> tasks = new ArrayList<>();
        for (JsonNode edge : edges) {
            String edgeId = requiredText(edge, "edge_id");
            String fromEntityId = requiredText(edge, "from_entity_id");
            String toEntityId = requiredText(edge, "to_entity_id");
            String fromNode = requiredText(edge, "from_node");
            String toNode = requiredText(edge, "to_node");
            String travelMode = requiredText(edge, "travel_mode");
            String endpoint = switch (travelMode) {
                case "WALKING" -> "/v5/direction/walking";
                case "TRANSIT" -> "/v5/direction/transit/integrated";
                default -> throw new IllegalArgumentException("unsupported travel mode: " + travelMode);
            };
            tasks.add(new RouteMeasurementTask(
                    "ROUTE-" + edgeId,
                    REVISION_ID,
                    edgeId,
                    "P0",
                    fromEntityId,
                    toEntityId,
                    fromNode,
                    toNode,
                    travelMode,
                    "重庆 " + fromNode,
                    "重庆 " + toNode,
                    endpoint,
                    true,
                    "PENDING",
                    null,
                    null,
                    null,
                    null,
                    null
            ));
        }
        return List.copyOf(tasks);
    }

    private static String requiredText(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record RouteMeasurementTask(
            String taskId,
            String revisionId,
            String edgeId,
            String priority,
            String fromEntityId,
            String toEntityId,
            String fromNode,
            String toNode,
            String travelMode,
            String originQuery,
            String destinationQuery,
            String endpoint,
            boolean requiresCoordinateResolution,
            String status,
            String requestTime,
            String rawResponseRef,
            Integer distanceMeters,
            Integer durationSeconds,
            Integer walkingDistanceMeters
    ) {
    }
}

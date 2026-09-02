package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选高德地图数据采集任务构建器
 *
 * 所属领域：domain.rag.pipeline（知识与数据管线层）
 */
public class GoldenDatasetV21MapTaskBuilder {

    public static final String REVISION_ID = GoldenDatasetV21Validator.REVISION_ID;
    private static final String PROVIDER = "AMAP";
    private static final String PENDING = "PENDING";
    private static final Map<String, String> FIELD_LABELS = fieldLabels();
    private static final List<String> ENDPOINT_CANDIDATES = List.of(
            "/v5/place/text",
            "/v5/place/detail",
            "/v3/geocode/geo"
    );

    private final ObjectMapper objectMapper;

    public GoldenDatasetV21MapTaskBuilder() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public GoldenDatasetV21MapTaskBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MapCollectionTask> build(Path contractFile) throws IOException {
        if (contractFile == null || !Files.isRegularFile(contractFile)) {
            throw new IllegalArgumentException("Map collection contract does not exist: " + contractFile);
        }
        JsonNode rows = objectMapper.readTree(Files.readString(contractFile, StandardCharsets.UTF_8));
        if (!rows.isArray()) throw new IOException("map_api_required_all_9.json must contain an array");

        List<MapCollectionTask> tasks = new ArrayList<>();
        Set<String> taskIds = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            String entityId = requiredText(row, "entity_id");
            String canonicalName = requiredText(row, "canonical_name");
            if (!GoldenDatasetV21Validator.GOLDEN_ENTITIES.contains(entityId)) {
                throw new IllegalArgumentException("unknown Golden Entity: " + entityId);
            }
            if (!"AMAP_GEOCODING_AND_POI".equals(requiredText(row, "api_action"))) {
                throw new IllegalArgumentException("unsupported map api action for " + entityId);
            }

            JsonNode fields = row.get("required_fields");
            if (fields == null || !fields.isArray() || fields.isEmpty()) {
                throw new IllegalArgumentException("required_fields must be a non-empty array for " + entityId);
            }
            for (JsonNode fieldNode : fields) {
                String field = fieldNode.asText();
                String taskId = "MAP-" + entityId + "-" + field;
                if (!taskIds.add(taskId)) throw new IllegalArgumentException("duplicate map task: " + taskId);
                tasks.add(new MapCollectionTask(
                        taskId,
                        REVISION_ID,
                        entityId,
                        canonicalName,
                        field,
                        "AMAP_GEOCODING_AND_POI",
                        PROVIDER,
                        buildQuery(canonicalName, field),
                        ENDPOINT_CANDIDATES,
                        PENDING,
                        null,
                        null,
                        null,
                        text(row, "reason_for_collection")
                ));
            }
        }
        return List.copyOf(tasks);
    }

    private String buildQuery(String canonicalName, String field) {
        String label = FIELD_LABELS.get(field);
        return label == null || label.isBlank() ? canonicalName : canonicalName + " " + label;
    }

    private static Map<String, String> fieldLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("coordinates_gps", "中心点");
        labels.put("entrance_1f_gps", "1F入口");
        labels.put("entrance_11f_gps", "11F入口");
        labels.put("plaza_anchor_gps", "观景平台");
        labels.put("station_exit_1_gps", "1号出入口");
        labels.put("station_exit_2_gps", "2号出入口");
        labels.put("east_gate_gps", "东门");
        labels.put("entrance_top_zhongxing_rd_gps", "中兴路上口");
        labels.put("entrance_bottom_nanqu_rd_gps", "南区路下口");
        labels.put("main_gate_changbin_rd_gps", "长滨路正门");
        labels.put("monument_center_gps", "纪念碑中心");
        labels.put("coordinates_north_station_gps", "北站");
        labels.put("coordinates_south_station_gps", "南站");
        labels.put("museum_main_entrance_gps", "主入口");
        return Map.copyOf(labels);
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

    public record MapCollectionTask(
            String taskId,
            String revisionId,
            String entityId,
            String canonicalName,
            String field,
            String apiAction,
            String provider,
            String query,
            List<String> endpointCandidates,
            String status,
            String requestTime,
            String rawResponseRef,
            String normalizedValue,
            String reasonForCollection
    ) {
    }
}

package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仅将 Production V2 语义分块导入隔离的 Qdrant 集合。
 *
 * 此适配器会主动拒绝专用 Production V2 目标之外的所有集合，避免配置错误
 * 导致 reconciliation 误操作已经受到污染的旧 scenic_guide 集合。
 */
@Service
public class ProductionV2QdrantImporter {

    public static final String LEGACY_COLLECTION = "scenic_guide";
    public static final String DEFAULT_COLLECTION = "scenic_guide_production_v3_rebuild";
    public static final String ACTIVATION_NAMESPACE = "production-v2";

    private final ProductionV2ChunkService chunkService;
    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final String collectionName;

    @Autowired
    public ProductionV2QdrantImporter(
            ProductionV2ChunkService chunkService,
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            ObjectMapper objectMapper,
            @Value("${qdrant.production-v2.collection:" + DEFAULT_COLLECTION + "}") String collectionName) {
        this.chunkService = chunkService;
        this.qdrantClient = qdrantClient;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.collectionName = validateCollectionName(collectionName);
    }

    public String collectionName() {
        return collectionName;
    }

    /**
     * 不连接 Qdrant，构建结果稳定且可用于引用追踪的 payload。
     */
    public List<ProvenancePayload> buildPayloads(Path sourceDirectory) throws IOException {
        return chunkService.chunk(sourceDirectory).stream()
                .map(chunk -> new ProvenancePayload(
                        chunk.chunkId(),
                        chunk.entityId(),
                        chunk.knowledgeDocumentId(),
                        chunk.sourceDocumentId(),
                        chunk.sourceUrl(),
                        chunk.publisher(),
                        chunk.sourceTitle(),
                        chunk.title(),
                        chunk.content(),
                        chunk.trustLevel(),
                        chunk.verificationResult(),
                        chunk.ragEligible(),
                        chunk.version(),
                        chunk.provenance(),
                        chunk.sourceUpdatedAt(),
                        chunk.accessedAt(),
                        chunk.chunkIndex(),
                        chunk.chunkCount()))
                .toList();
    }

    /**
     * 返回某个 payload snapshot 预期的完整 point 标识集合。
     * 这是纯函数，也用于本地测试在不连接远端服务的情况下验证幂等性。
     */
    Set<String> expectedPointIds(List<ProvenancePayload> payloads) {
        Set<String> ids = new LinkedHashSet<>();
        for (ProvenancePayload payload : payloads) {
            ids.add(deterministicPointId(payload));
        }
        return ids;
    }

    String deterministicPointId(ProvenancePayload payload) {
        return pointIdKey(pointId(payload));
    }

    Set<String> stalePointIds(Set<String> existingIds, Set<String> expectedIds) {
        Set<String> stale = new LinkedHashSet<>(existingIds);
        stale.removeAll(expectedIds);
        return stale;
    }

    /**
     * 执行 dry-run，或在隔离集合中进行真实 Embedding 与 Upsert。
     * 所有远端删除操作都必须经过命名空间过滤，并且限定在目标集合内。
     */
    public ImportReport importData(Path sourceDirectory, boolean dryRun, Path reportPath) throws IOException {
        List<ProvenancePayload> payloads = buildPayloads(sourceDirectory);
        Set<String> documentIds = new HashSet<>();
        for (ProvenancePayload payload : payloads) {
            documentIds.add(payload.knowledgeDocumentId());
        }
        String snapshotVersion = payloads.isEmpty() ? "" : payloads.get(0).version();
        int expectedPoints = payloads.size();
        ImportReport report;
        if (dryRun) {
            report = new ImportReport(
                    "PASS", true, collectionName, expectedPoints, 0, 0,
                    "NOT_RUN", false, true, true,
                    snapshotVersion, documentIds.size(), documentIds.size(), expectedPoints,
                    0, 0, expectedPoints, 0, 0, 0, 0, false, 0);
        } else {
            if (embeddingModel == null || qdrantClient == null) {
                throw new IllegalStateException("Qdrant client and embedding model are required for non-dry-run import");
            }

            List<Points.PointStruct> points = new ArrayList<>();
            int vectorDimension = 0;
            int embeddingFailures = 0;
            for (ProvenancePayload payload : payloads) {
                final float[] vector;
                try {
                    vector = embeddingModel.embed(payload.content());
                    if (vector == null || vector.length == 0) {
                        throw new IllegalStateException("Embedding returned an empty vector");
                    }
                } catch (RuntimeException e) {
                    embeddingFailures++;
                    throw new IOException("Embedding failed for " + payload.knowledgeDocumentId(), e);
                }
                if (vectorDimension == 0) {
                    vectorDimension = vector.length;
                } else if (vectorDimension != vector.length) {
                    throw new IOException("Embedding dimension changed within one snapshot");
                }
                points.add(toPoint(payload, vector));
            }

            boolean collectionCreated = ensureCollection(vectorDimension);
            try {
                if (!points.isEmpty()) {
                    qdrantClient.upsertAsync(collectionName, points).get();
                }
                int stalePoints = reconcileStalePoints(expectedPointIds(payloads));
                long storedCount = qdrantClient.countAsync(collectionName).get();
                int successfulPoints = points.size();
                int failedPoints = expectedPoints - successfulPoints;
                String verificationStatus = storedCount == expectedPoints ? "PASS" : "FAIL";
                report = new ImportReport(
                        verificationStatus.equals("PASS") ? "PASS" : "FAIL",
                        false, collectionName, expectedPoints, points.size(), successfulPoints,
                        verificationStatus, false, true, embeddingFailures == 0,
                        snapshotVersion, documentIds.size(), documentIds.size(), expectedPoints,
                        points.size(), embeddingFailures, expectedPoints, points.size(), successfulPoints,
                        failedPoints, stalePoints, collectionCreated, vectorDimension);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Qdrant import interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IOException("Qdrant import failed", e.getCause());
            }
        }
        if (reportPath != null) {
            Files.createDirectories(reportPath.toAbsolutePath().getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        }
        return report;
    }

    private boolean ensureCollection(int vectorDimension) throws IOException {
        if (vectorDimension <= 0) {
            throw new IOException("Cannot create an empty-vector collection");
        }
        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
            if (!exists) {
                Collections.VectorParams params = Collections.VectorParams.newBuilder()
                        .setSize(vectorDimension)
                        .setDistance(Collections.Distance.Cosine)
                        .build();
                qdrantClient.createCollectionAsync(collectionName, params).get();
                return true;
            }

            Collections.CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
            Collections.VectorsConfig vectors = info.getConfig().getParams().getVectorsConfig();
            if (vectors.getConfigCase() != Collections.VectorsConfig.ConfigCase.PARAMS
                    || vectors.getParams().getSize() != vectorDimension
                    || vectors.getParams().getDistance() != Collections.Distance.Cosine) {
                throw new IOException("Existing Production V2 collection contract does not match the current embedding contract");
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Qdrant collection inspection interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("Qdrant collection inspection failed", e.getCause());
        }
    }

    private int reconcileStalePoints(Set<String> expectedIds) throws IOException, InterruptedException, java.util.concurrent.ExecutionException {
        Map<String, Points.PointId> existingIds = new LinkedHashMap<>();
        Points.ScrollPoints.Builder builder = Points.ScrollPoints.newBuilder()
                .setCollectionName(collectionName)
                .setLimit(1000)
                .setFilter(Points.Filter.newBuilder()
                        .addMust(ConditionFactory.matchKeyword("activation_namespace", ACTIVATION_NAMESPACE))
                        .build());
        Points.PointId offset = null;
        while (true) {
            if (offset != null) {
                builder.setOffset(offset);
            }
            Points.ScrollResponse response = qdrantClient.scrollAsync(builder.build()).get();
            if (response.getResultList().isEmpty()) {
                break;
            }
            for (Points.RetrievedPoint point : response.getResultList()) {
                existingIds.put(pointIdKey(point.getId()), point.getId());
            }
            offset = response.getNextPageOffset();
            if (offset == null || (!offset.hasNum() && !offset.hasUuid())) {
                break;
            }
        }
        Set<String> staleIds = stalePointIds(existingIds.keySet(), expectedIds);
        if (!staleIds.isEmpty()) {
            List<Points.PointId> stalePointIds = staleIds.stream().map(existingIds::get).toList();
            qdrantClient.deleteAsync(collectionName, stalePointIds, Duration.ofSeconds(30)).get();
        }
        return staleIds.size();
    }

    private Points.PointStruct toPoint(ProvenancePayload payload, float[] vector) {
        Map<String, JsonWithInt.Value> pointPayload = new HashMap<>();
        pointPayload.put("activation_namespace", ValueFactory.value(ACTIVATION_NAMESPACE));
        pointPayload.put("knowledge_document_id", ValueFactory.value(payload.knowledgeDocumentId()));
        pointPayload.put("entity_id", ValueFactory.value(payload.entityId()));
        pointPayload.put("source_document_id", ValueFactory.value(payload.sourceDocumentId()));
        pointPayload.put("source_id", ValueFactory.value(payload.sourceDocumentId()));
        pointPayload.put("source_url", ValueFactory.value(payload.sourceUrl()));
        pointPayload.put("publisher", ValueFactory.value(payload.publisher()));
        pointPayload.put("source_title", ValueFactory.value(payload.sourceTitle()));
        pointPayload.put("title", ValueFactory.value(payload.title()));
        pointPayload.put("content", ValueFactory.value(payload.content()));
        pointPayload.put("trust_level", ValueFactory.value(payload.trustLevel()));
        pointPayload.put("verification_result", ValueFactory.value(payload.verificationResult()));
        pointPayload.put("rag_eligible", ValueFactory.value(payload.ragEligible()));
        pointPayload.put("version", ValueFactory.value(payload.version()));
        pointPayload.put("provenance", ValueFactory.value(payload.provenance()));
        pointPayload.put("source_updated_at", ValueFactory.value(payload.sourceUpdatedAt()));
        pointPayload.put("accessed_at", ValueFactory.value(payload.accessedAt()));
        pointPayload.put("chunk_index", ValueFactory.value(payload.chunkIndex()));
        pointPayload.put("chunk_count", ValueFactory.value(payload.chunkCount()));

        return Points.PointStruct.newBuilder()
                .setId(pointId(payload))
                .setVectors(VectorsFactory.vectors(toFloatList(vector)))
                .putAllPayload(pointPayload)
                .build();
    }

    private Points.PointId pointId(ProvenancePayload payload) {
        String identity = ACTIVATION_NAMESPACE + ":" + payload.version() + ":" + payload.chunkId();
        UUID stableUuid = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return PointIdFactory.id(stableUuid);
    }

    private String pointIdKey(Points.PointId id) {
        if (id.hasUuid()) {
            return "uuid:" + id.getUuid();
        }
        if (id.hasNum()) {
            return "num:" + id.getNum();
        }
        return id.toString();
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) values.add(value);
        return values;
    }

    private String validateCollectionName(String requested) {
        String value = requested == null ? "" : requested.trim();
        if (value.isBlank() || LEGACY_COLLECTION.equals(value)) {
            throw new IllegalArgumentException("Production V2 requires the dedicated isolated Qdrant collection");
        }
        return value;
    }

    public record ProvenancePayload(
            String chunkId,
            String entityId,
            String knowledgeDocumentId,
            String sourceDocumentId,
            String sourceUrl,
            String publisher,
            String sourceTitle,
            String title,
            String content,
            String trustLevel,
            String verificationResult,
            boolean ragEligible,
            String version,
            String provenance,
            String sourceUpdatedAt,
            String accessedAt,
            int chunkIndex,
            int chunkCount) {

        /** 为仍使用旧 source_id 名称的调用方保留的向后兼容别名。 */
        public String sourceId() {
            return sourceDocumentId;
        }
    }

    public record ImportReport(
            String status,
            boolean dryRun,
            String collection,
            int chunkCount,
            int embeddedCount,
            int upsertedCount,
            String verificationStatus,
            boolean legacyCollectionTouched,
            boolean stablePointIds,
            boolean provenancePayloadComplete,
            String snapshotVersion,
            int documentsRead,
            int documentsEligible,
            int chunksGenerated,
            int embeddingsGenerated,
            int embeddingFailures,
            int expectedPoints,
            int attemptedPoints,
            int successfulPoints,
            int failedPoints,
            int stalePointsReconciled,
            boolean collectionCreated,
            int vectorDimension) {
    }
}

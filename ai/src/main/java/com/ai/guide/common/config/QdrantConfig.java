package com.ai.guide.common.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Map;

/**
 * Qdrant 向量数据库配置类
 * 核心职责：初始化 QdrantClient Bean + 维度检查 + Payload 索引自动创建
 *
 * 维度说明：
 * - text-embedding-v2（DashScope）：支持 dimension=1536
 * - Qdrant 集合已在 Cloud 控制台预创建为 1536 维 Cosine 距离
 * - 若维度不匹配，需到 Qdrant Cloud 控制台手动删除重建
 *
 * Payload 索引说明：
 * - source: 按文件来源去重/删除时使用（keyword 索引）
 * - scenic_id: 按景点 ID 精确过滤时使用（keyword 索引）
 * - 使用 createIndexIfNotExists 幂等方法，重复启动不会报错
 */
@Slf4j
@Configuration
public class QdrantConfig {

    private static final String LEGACY_COLLECTION_NAME = "scenic_guide";
    private static final String DEFAULT_COLLECTION_NAME = "scenic_guide_production_v3_rebuild";
    /** 向量维度，与 text-embedding-v2 输出一致 */
    private static final long VECTOR_DIMENSION = 1536;

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int port;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Value("${qdrant.use-tls:}")
    private String useTlsProperty;

    @Value("${qdrant.production-v2.collection:scenic_guide_production_v3_rebuild}")
    private String collectionName = DEFAULT_COLLECTION_NAME;

    @Value("${qdrant.production-v2.auto-create-indexes:false}")
    private boolean autoCreateIndexes;

    @Bean
    public QdrantClient qdrantClient() {
        ensureProductionV2Collection();
        boolean useTls = "true".equalsIgnoreCase(useTlsProperty)
                || (useTlsProperty.isBlank() && host != null && (host.contains("cloud.qdrant.io") || host.contains("qdrant.tech")));
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port, useTls)
                .withApiKey(apiKey)
                .build();
        QdrantClient client = new QdrantClient(grpcClient);
        // 启动时检查集合维度；集合不可用时保持既有降级边界。
        if (checkCollection(client)) {
            // rag_eligible 是 Production V2 语义检索的必要索引，幂等确保一次。
            ensureRagEligibleIndex(client);
        }
        // 其他历史索引仍由显式开关控制，避免扩大远端 mutation 范围。
        if (autoCreateIndexes) {
            createIndexes(client);
        }
        return client;
    }

    private void ensureProductionV2Collection() {
        if (collectionName == null || collectionName.isBlank()
                || LEGACY_COLLECTION_NAME.equals(collectionName.trim())) {
            throw new IllegalStateException("Qdrant Chat/RAG requires an isolated Production V2 collection");
        }
        collectionName = collectionName.trim();
    }

    /**
     * 检查集合维度是否正确
     * 维度不匹配时打印警告（需手动删除重建）
     */
    private boolean checkCollection(QdrantClient client) {
        try {
            var response = client.getCollectionInfoAsync(collectionName).get();
            long existingDim = response.getConfig().getParams().getVectorsConfig().getParams().getSize();
            long pointCount = response.getPointsCount();
            if (existingDim != VECTOR_DIMENSION) {
                log.warn("[Qdrant RAG 启动检查] actualCollection={} 维度不匹配！pointCount={} vectorDimension={} 期望维度={}",
                        collectionName, pointCount, existingDim, VECTOR_DIMENSION);
            } else {
                log.info("[Qdrant RAG 启动检查] actualCollection={}, pointCount={}, vectorDimension={}, embeddingModel={}, retrievalSource={}",
                        collectionName, pointCount, existingDim, "Alibaba DashScope text-embedding-v2 (1536维)", "Java Core Backend / Qdrant");
            }
            return true;
        } catch (Exception e) {
            log.warn("[Qdrant RAG 启动检查] actualCollection={} 不存在或不可用；保持 Java RAG 明确降级（期望 {} 维）", collectionName, VECTOR_DIMENSION);
            return false;
        }
    }

    /**
     * 确保 Production V2 语义检索所需的 rag_eligible bool 索引。
     * 只检查当前配置集合；已存在且类型正确时不执行 mutation。
     */
    PayloadIndexAction ensureRagEligibleIndex(QdrantClient client) {
        try {
            Collections.CollectionInfo info = client.getCollectionInfoAsync(collectionName).get();
            Map<String, Collections.PayloadSchemaInfo> schema = info.getPayloadSchemaMap();
            Collections.PayloadSchemaInfo existing = schema.get("rag_eligible");
            if (existing != null) {
                if (existing.getDataType() == Collections.PayloadSchemaType.Bool) {
                    log.info("[Qdrant] Payload 索引已存在且类型正确: rag_eligible (Bool)");
                    if (verifyRagEligiblePayloadTypes(client)) {
                        log.info("[Qdrant] 已有 Point 的 rag_eligible payload 类型校验通过: Bool");
                    }
                    return PayloadIndexAction.ALREADY_PRESENT;
                }
                log.error("[Qdrant] Payload 索引类型错误: rag_eligible ({})，保持安全降级，不删除或替换现有索引。",
                        existing.getDataType());
                return PayloadIndexAction.WRONG_TYPE;
            }

            client.createPayloadIndexAsync(
                    collectionName,
                    "rag_eligible",
                    Collections.PayloadSchemaType.Bool,
                    null,
                    true,
                    null,
                    null
            ).get();
            log.info("[Qdrant] Payload 索引已创建: rag_eligible (Bool)");
            if (verifyRagEligiblePayloadTypes(client)) {
                log.info("[Qdrant] 已有 Point 的 rag_eligible payload 类型校验通过: Bool");
            }
            return PayloadIndexAction.CREATED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Qdrant] rag_eligible 索引检查被中断，Java RAG 将使用明确降级: {}", e.getMessage());
            return PayloadIndexAction.UNAVAILABLE;
        } catch (Exception e) {
            log.warn("[Qdrant] rag_eligible 索引检查/创建不可用，Java RAG 将使用明确降级: {}", e.getMessage());
            return PayloadIndexAction.UNAVAILABLE;
        }
    }

    /** 验证已有点的 rag_eligible payload 是 protobuf bool，而不是字符串。 */
    private boolean verifyRagEligiblePayloadTypes(QdrantClient client) {
        try {
            Points.ScrollPoints request = Points.ScrollPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setLimit(100)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            Points.ScrollResponse response = client.scrollAsync(request).get();
            for (Points.RetrievedPoint point : response.getResultList()) {
                JsonWithInt.Value value = point.getPayloadMap().get("rag_eligible");
                if (value != null && value.getKindCase() != JsonWithInt.Value.KindCase.BOOL_VALUE) {
                    log.error("[Qdrant] Point {} 的 rag_eligible 不是 bool，而是 {}；禁止伪装检索成功。",
                            point.getId(), value.getKindCase());
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Qdrant] rag_eligible payload 类型验证被中断: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("[Qdrant] rag_eligible payload 类型暂无法验证，保持检索降级边界: {}", e.getMessage());
            return false;
        }
    }

    enum PayloadIndexAction {
        CREATED,
        ALREADY_PRESENT,
        WRONG_TYPE,
        UNAVAILABLE
    }

    /**
     * 创建所有可选的 Payload 索引（幂等方法）
     * - source: 文件来源，用于去重删除
     * - scenic_id: 景点 ID，用于精确过滤
     */
    private void createIndexes(QdrantClient client) {
        createIndexIfNotExists(client, "source", Collections.PayloadSchemaType.Keyword);
        createIndexIfNotExists(client, "scenic_id", Collections.PayloadSchemaType.Keyword);
    }

    /**
     * 创建单个 Payload 索引（幂等）
     * 索引已存在时 Qdrant 会返回异常，此处捕获并忽略
     *
     * @param client    Qdrant 客户端
     * @param fieldName 索引字段名
     * @param type      索引类型（Keyword = 精确匹配）
     */
    private void createIndexIfNotExists(QdrantClient client, String fieldName,
                                        Collections.PayloadSchemaType type) {
        try {
            client.createPayloadIndexAsync(
                    collectionName,
                    fieldName,
                    type,
                    null,   // params: 不指定，使用默认
                    true,   // wait: true，等待索引创建完成
                    null,   // writeOrdering: 不指定写入顺序
                    null    // timeout: 不指定超时
            ).get();
            log.info("[Qdrant] Payload 索引已确保: {} ({})", fieldName, type);
        } catch (Exception e) {
            // 索引已存在时会抛异常，属于正常情况，忽略即可
            log.info("[Qdrant] Payload 索引已存在或创建失败（可忽略）: {} - {}", fieldName, e.getMessage());
        }
    }
}

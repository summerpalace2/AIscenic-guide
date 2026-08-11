package com.ai.guide.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    private static final String DEFAULT_COLLECTION_NAME = "scenic_guide_production_v2";
    /** 向量维度，与 text-embedding-v2 输出一致 */
    private static final long VECTOR_DIMENSION = 1536;

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int port;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Value("${qdrant.production-v2.collection:scenic_guide_production_v2}")
    private String collectionName = DEFAULT_COLLECTION_NAME;

    @Value("${qdrant.production-v2.auto-create-indexes:false}")
    private boolean autoCreateIndexes;

    @Bean
    public QdrantClient qdrantClient() {
        ensureProductionV2Collection();
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port)
                .withApiKey(apiKey)
                .build();
        QdrantClient client = new QdrantClient(grpcClient);
        // 启动时检查集合维度
        checkCollection(client);
        // 索引创建采用显式开关；本次运行未授权执行远端 mutation。
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
    private void checkCollection(QdrantClient client) {
        try {
            var response = client.getCollectionInfoAsync(collectionName).get();
            long existingDim = response.getConfig().getParams().getVectorsConfig().getParams().getSize();
            if (existingDim != VECTOR_DIMENSION) {
                log.warn("[Qdrant] 集合维度不匹配！现有={} 期望={}。请在授权后重新导入 Production V2 数据。", existingDim, VECTOR_DIMENSION);
            } else {
                log.info("[Qdrant] 集合 {} 维度正确: {} 维", collectionName, existingDim);
            }
        } catch (Exception e) {
            log.warn("[Qdrant] 集合 {} 不存在；Production V2 激活仍需授权（期望 {} 维）", collectionName, VECTOR_DIMENSION);
        }
    }

    /**
     * 创建所有必需的 Payload 索引（幂等方法）
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

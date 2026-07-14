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
 * 初始化 QdrantClient Bean + 自动检测集合维度
 *
 * 维度说明：
 * - text-embedding-v2（DashScope）：支持 dimension=1536
 * - Qdrant 集合已在 Cloud 控制台预创建为 1536 维 Cosine 距离
 * - 若维度不匹配，需到 Qdrant Cloud 控制台手动删除重建
 */
@Slf4j
@Configuration
public class QdrantConfig {

    private static final String COLLECTION_NAME = "scenic_guide";
    /** 向量维度，与 text-embedding-v2 输出一致 */
    private static final long VECTOR_DIMENSION = 1536;

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int port;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Bean
    public QdrantClient qdrantClient() {
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port)
                .withApiKey(apiKey)
                .build();
        QdrantClient client = new QdrantClient(grpcClient);
        // 启动时打印集合维度信息（用于确认配置正确）
        checkCollection(client);
        return client;
    }

    /**
     * 检查集合维度是否正确，不匹配则打印警告
     */
    private void checkCollection(QdrantClient client) {
        try {
            var response = client.getCollectionInfoAsync(COLLECTION_NAME).get();
            long existingDim = response.getConfig().getParams().getVectorsConfig().getParams().getSize();
            if (existingDim != VECTOR_DIMENSION) {
                log.warn("[Qdrant] ⚠ 集合维度不匹配！现有={} 期望={}。请删除集合后重新导入数据。", existingDim, VECTOR_DIMENSION);
            } else {
                log.info("[Qdrant] 集合 {} 维度正确: {} 维", COLLECTION_NAME, existingDim);
            }
        } catch (Exception e) {
            log.warn("[Qdrant] 集合 {} 不存在，请在 Qdrant Cloud 控制台手动创建 {} 维集合", COLLECTION_NAME, VECTOR_DIMENSION);
        }
    }
}
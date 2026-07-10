package com.ai.guide.config;

import io.qdrant.client.QdrantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Qdrant 连接诊断配置
 * 启动后列出当前可用集合，验证与 Qdrant Cloud 的连通性。
 */
@Configuration
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);

    private final QdrantClient qdrantClient;

    public QdrantConfig(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @PostConstruct
    public void checkConnection() {
        try {
            List<String> collections = qdrantClient.listCollectionsAsync().get(10, TimeUnit.SECONDS);
            log.info("！！！[连接验证]：Qdrant 连接成功，当前集合列表：{}", collections);
        } catch (Exception e) {
            log.error("！！！[连接验证]：无法连接 Qdrant：{}", e.getMessage());
        }
    }
}

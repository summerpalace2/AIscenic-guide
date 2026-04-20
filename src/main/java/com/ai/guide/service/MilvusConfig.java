package com.ai.guide.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.beans.factory.annotation.Value; // 导入这个
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class MilvusConfig {

    // 从环境变量/YAML中读取
    @Value("${zilliz.token}")
    private String token;

    @Value("${zilliz.endpoint}")
    private String endpoint;

    @Bean
    @Primary
    public MilvusServiceClient milvusClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withUri(endpoint)
                .withToken(token)
                .build();

        return new MilvusServiceClient(connectParam);
    }
}
package com.ai.guide.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.ShowCollectionsParam;
import org.springframework.beans.factory.annotation.Value;
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

        MilvusServiceClient client = new MilvusServiceClient(connectParam);

        // 诊断日志
        try {
            R<ShowCollectionsResponse> res = client.showCollections(ShowCollectionsParam.newBuilder().build());
            System.out.println("！！！[连接验证]：当前连接能看到的表：" + res.getData().getCollectionNamesList());
        } catch (Exception e) {
            System.err.println("！！！[连接验证]：获取表失败");
        }

        return client;
    }
}
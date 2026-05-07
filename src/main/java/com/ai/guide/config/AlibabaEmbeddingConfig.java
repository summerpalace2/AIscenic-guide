package com.ai.guide.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 阿里 DashScope Embedding 模型独立配置
 * 聊天模型已切换到 DeepSeek，但向量化仍需阿里的 text-embedding-v2（1536 维）。
 * Marked as @Primary 以确保 Milvus 相关操作使用此配置。
 * 注意：Milvus 中所有向量均为 1536 维，换模型会导致维度不匹配。
 */
@Configuration
public class AlibabaEmbeddingConfig {

    @Value("${alibabacloud.dashscope.api-key}")
    private String dashscopeApiKey;

    @Bean
    @Primary
    public EmbeddingModel alibabaEmbeddingModel() {
        OpenAiApi dashscopeApi = new OpenAiApi(
                "https://dashscope.aliyuncs.com/compatible-mode",
                dashscopeApiKey
        );
        return new OpenAiEmbeddingModel(
                dashscopeApi,
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .withModel("text-embedding-v2")
                        .build()
        );
    }
}

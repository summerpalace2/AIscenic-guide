package com.ai.guide.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 阿里云 DashScope Embedding 配置类
 * 模型：text-embedding-v2（1536 维）
 *
 * 声明 Spring AI EmbeddingModel Bean 覆盖 OpenAI 自动配置
 * 避免 SpringAIRetryAutoConfiguration 调用 DeepSeek /v1/embeddings 返回 404
 */
@Component
public class AlibabaEmbeddingConfig {

    @Value("${alibabacloud.dashscope.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EMBEDDING_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";

    @Bean
    public EmbeddingModel embeddingModel() {
        return new DashScopeEmbeddingModel(apiKey, restTemplate, objectMapper);
    }

    /** 单文本向量化（供项目内部使用） */
    public float[] embed(String text) {
        return embeddingModel().embed(text);
    }

    /** Spring AI EmbeddingModel 实现 */
    private class DashScopeEmbeddingModel extends AbstractEmbeddingModel {
        private final String apiKey;
        private final RestTemplate restTemplate;
        private final ObjectMapper objectMapper;

        DashScopeEmbeddingModel(String apiKey, RestTemplate restTemplate, ObjectMapper objectMapper) {
            this.apiKey = apiKey;
            this.restTemplate = restTemplate;
            this.objectMapper = objectMapper;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            int index = 0;
            for (String text : request.getInstructions()) {
                embeddings.add(new Embedding(embedText(text), index++));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document.getContent().toString());
        }

        @Override
        public int dimensions() {
            return 1536;
        }

        private float[] embedText(String text) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            Map<String, Object> body = new HashMap<>();
            body.put("model", "text-embedding-v2");
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("dimension", 1536);
            body.put("parameters", parameters);
            Map<String, Object> input = new HashMap<>();
            input.put("texts", List.of(text));
            body.put("input", input);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            try {
                String response = restTemplate.postForObject(EMBEDDING_URL, request, String.class);
                if (response == null) return new float[1536];
                return parseResponse(response);
            } catch (Exception e) {
                System.err.println("[Embedding] 调用失败: " + e.getMessage());
                return new float[1536];
            }
        }

        private float[] parseResponse(String response) {
            try {
                JsonNode root = objectMapper.readTree(response);
                JsonNode embeddings = root.path("output").path("embeddings");
                if (embeddings.isArray() && embeddings.size() > 0) {
                    JsonNode vectorNode = embeddings.get(0).path("embedding");
                    float[] vector = new float[vectorNode.size()];
                    for (int i = 0; i < vectorNode.size(); i++) {
                        vector[i] = (float) vectorNode.get(i).asDouble();
                    }
                    return vector;
                }
            } catch (Exception e) {
                System.err.println("[Embedding] 解析响应失败: " + e.getMessage());
            }
            return new float[1536];
        }
    }
}
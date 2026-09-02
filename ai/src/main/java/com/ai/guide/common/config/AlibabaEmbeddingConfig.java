package com.ai.guide.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 阿里云 DashScope Embedding 配置类
 * 模型：text-embedding-v2（1536 维）
 *
 * 声明 Spring AI EmbeddingModel Bean 覆盖 OpenAI 自动配置
 * 避免 SpringAIRetryAutoConfiguration 调用 DeepSeek /v1/embeddings 返回 404
 */
@Component
public class AlibabaEmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(AlibabaEmbeddingConfig.class);

    @Value("${alibabacloud.dashscope.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;

    {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        restTemplate = new RestTemplate(factory);
    }
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
                return parseResponse(response);
            } catch (Exception e) {
                log.error("[Embedding] 调用失败: {}", e.getMessage());
                throw new IllegalStateException("Embedding provider unavailable", e);
            }
        }

        private float[] parseResponse(String response) {
            try {
                if (response == null || response.isBlank()) {
                    throw new IllegalStateException("Embedding provider returned an empty response");
                }
                JsonNode root = objectMapper.readTree(response);
                JsonNode embeddings = root.path("output").path("embeddings");
                if (!embeddings.isArray() || embeddings.isEmpty()) {
                    throw new IllegalStateException("Embedding provider returned no embeddings");
                }
                JsonNode vectorNode = embeddings.get(0).path("embedding");
                if (!vectorNode.isArray() || vectorNode.size() != 1536) {
                    throw new IllegalStateException("Embedding provider returned an invalid dimension");
                }
                float[] vector = new float[vectorNode.size()];
                double normSquared = 0d;
                for (int i = 0; i < vectorNode.size(); i++) {
                    double value = vectorNode.get(i).asDouble(Double.NaN);
                    if (!Double.isFinite(value)) {
                        throw new IllegalStateException("Embedding provider returned a non-finite value");
                    }
                    vector[i] = (float) value;
                    normSquared += value * value;
                }
                if (!(normSquared > 0d)) {
                    throw new IllegalStateException("Embedding provider returned a zero vector");
                }
                return vector;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.error("[Embedding] 解析响应失败: {}", e.getMessage());
                throw new IllegalStateException("Embedding provider returned malformed data", e);
            }
        }
    }
}
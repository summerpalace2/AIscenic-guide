package com.ai.guide.infrastructure.rag.rerank;

import com.ai.guide.domain.rag.gateway.RerankProviderPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Bailian HTTP protocol adapter for the rerank provider. */
@Component
public class BailianRerankAdapter implements RerankProviderPort {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String rerankUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public BailianRerankAdapter(ObjectMapper objectMapper,
                                @Value("${alibabacloud.dashscope.api-key:}") String apiKey,
                                @Value("${bailian.rerank-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank}") String rerankUrl,
                                @Value("${bailian.connect-timeout-ms:3000}") int connectTimeoutMs,
                                @Value("${bailian.read-timeout-ms:3000}") int readTimeoutMs) {
        this(createRestTemplate(connectTimeoutMs, readTimeoutMs), objectMapper, apiKey, rerankUrl);
    }

    private static RestTemplate createRestTemplate(int connectTimeoutMs, int readTimeoutMs) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    BailianRerankAdapter(RestTemplate restTemplate, ObjectMapper objectMapper, String apiKey, String rerankUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.rerankUrl = rerankUrl;
    }

    @Override
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (apiKey.isBlank()) throw new IllegalStateException("Rerank API key is not configured");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> input = Map.of("query", query, "documents", documents);
        Map<String, Object> parameters = Map.of("top_n", topN, "return_documents", true);
        Map<String, Object> requestBody = Map.of("model", "gte-rerank-v2", "input", input, "parameters", parameters);
        String response = restTemplate.postForObject(rerankUrl, new HttpEntity<>(requestBody, headers), String.class);
        if (response == null) throw new IllegalStateException("Rerank provider returned an empty response");
        try {
            JsonNode results = objectMapper.readTree(response).path("output").path("results");
            if (!results.isArray() || results.isEmpty()) throw new IllegalStateException("Rerank response has no results");
            LinkedHashSet<Integer> indices = new LinkedHashSet<>();
            for (JsonNode result : results) {
                JsonNode index = result.path("index");
                if (!index.isIntegralNumber() || index.asInt() < 0 || index.asInt() >= documents.size())
                    throw new IllegalStateException("Rerank response contains an invalid document index");
                indices.add(index.asInt());
                if (indices.size() >= topN) break;
            }
            if (indices.isEmpty()) throw new IllegalStateException("Rerank provider returned no usable indices");
            return new ArrayList<>(indices);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse rerank provider response", e);
        }
    }
}

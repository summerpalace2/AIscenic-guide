package com.ai.guide.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 阿里百炼 gte-rerank-v2 重排序服务
 *
 * 从向量检索返回的 TOP30 候选中，调用阿里百炼重排序接口，
 * 根据查询相关性精选 TOP10 最相关文档。
 *
 * 调用链：用户提问 → 向量检索 TOP30 → gte-rerank-v2 → 精选 TOP10
 */
@Slf4j
@Service
public class RerankService {

    @Value("${alibabacloud.bailian.api-key}")
    private String apiKey;

    @Value("${alibabacloud.bailian.rerank-url}")
    private String rerankUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 调用阿里百炼重排序 API，将候选文档按相关性重新排序并返回 TOP N */
    public List<String> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            log.warn("【Rerank 调试】候选文档为空，跳过重排");
            return Collections.emptyList();
        }

        // 1. 请求头配置
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        // 2. 请求体构造 (根据官方 cURL 调整)
        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", documents);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", topN);
        parameters.put("return_documents", true); // 设为 true 方便解析

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gte-rerank-v2"); // 建议使用 v2
        body.put("input", input);
        body.put("parameters", parameters);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("【Rerank 调试】请求体序列化失败: ", e);
            return documents.subList(0, Math.min(documents.size(), topN));
        }
        log.info("【Rerank 调试】正在发送请求至: {}", rerankUrl);
        log.debug("【Rerank 调试】请求 Body: {}", jsonBody);

        try {
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(rerankUrl, entity, String.class);

            log.info("【Rerank 调试】收到响应，状态码: {}", response.getStatusCode());
            log.debug("【Rerank 调试】响应原文: {}", response.getBody());

            JsonNode resultJson = objectMapper.readTree(response.getBody());
            // 阿里原生接口返回的层级较深，增加空指针保护
            JsonNode output = resultJson.get("output");
            if (output == null || output.isNull()) {
                log.error("【Rerank 调试】响应中缺失 output 字段: {}", response.getBody());
                return documents.subList(0, Math.min(documents.size(), topN));
            }

            JsonNode results = output.get("results");
            List<String> rerankedDocs = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                int index = results.get(i).get("index").asInt();
                rerankedDocs.add(documents.get(index));
            }
            return rerankedDocs;

        } catch (HttpStatusCodeException e) {
            // 捕获 HTTP 4xx/5xx 错误，打印阿里云返回的报错详情 JSON
            log.error("【Rerank 调试】接口返回 HTTP 错误: {}，错误详情: {}", e.getStatusCode(), e.getResponseBodyAsString());
            return documents.subList(0, Math.min(documents.size(), topN));
        } catch (Exception e) {
            log.error("【Rerank 调试】重排过程发生未知异常: ", e);
            return documents.subList(0, Math.min(documents.size(), topN));
        }
    }
}
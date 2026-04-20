package com.ai.guide.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class RerankService {

    @Value("${alibabacloud.bailian.api-key}")
    private String apiKey;

    @Value("${alibabacloud.bailian.rerank-url}")
    private String rerankUrl;

    private final RestTemplate restTemplate = new RestTemplate();

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

        String jsonBody = JSON.toJSONString(body);
        log.info("【Rerank 调试】正在发送请求至: {}", rerankUrl);
        log.debug("【Rerank 调试】请求 Body: {}", jsonBody);

        try {
            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(rerankUrl, entity, String.class);

            log.info("【Rerank 调试】收到响应，状态码: {}", response.getStatusCode());
            log.debug("【Rerank 调试】响应原文: {}", response.getBody());

            JSONObject resultJson = JSON.parseObject(response.getBody());
            // 阿里原生接口返回的层级较深，增加空指针保护
            JSONObject output = resultJson.getJSONObject("output");
            if (output == null) {
                log.error("【Rerank 调试】响应中缺失 output 字段: {}", response.getBody());
                return documents.subList(0, Math.min(documents.size(), topN));
            }

            JSONArray results = output.getJSONArray("results");
            List<String> rerankedDocs = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                int index = results.getJSONObject(i).getInteger("index");
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
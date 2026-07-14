package com.ai.guide.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 查询分解 Service（Agentic RAG 核心）
 * LLM 意图分析 + 子问题拆解（1-4 个子问题）
 */
@Service
public class QueryDecompositionService {

    private static final String DECOMPOSE_PROMPT = """
            你是问题分析专家。判断用户输入是否需要查询景区知识库，如果需要则拆解为子问题。

            【判断规则】
            1. 闲聊/问候/感谢（"你好""谢谢""再见"）→ 不需要检索
            2. 简单确认（"好的""嗯""明白了"）→ 不需要检索
            3. 询问景点/路线/门票/美食/住宿/文化/交通等 → 需要检索

            【拆解规则】（仅当需要检索时执行）
            - 将复合问题拆为 1-4 个子问题
            - 每个子问题聚焦一个明确维度
            - 子问题应简洁、可直接用于知识检索

            【输出格式】严格输出 JSON，不添加任何其他文字：
            {"needSearch": true/false, "subQueries": ["子问题1", "子问题2", ...]}
            """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public QueryDecompositionService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 分析用户问题：是否需要搜索 + 拆解子问题
     */
    public DecomposeResult decompose(String query) {
        if (query == null || query.isBlank()) {
            return new DecomposeResult(false, Collections.emptyList());
        }

        try {
            System.out.println("[Deep] Step1 LLM 拆解中... query=\"" + query + "\"");
            long t0 = System.currentTimeMillis();

            String response = chatClient.prompt()
                    .messages(new SystemMessage(DECOMPOSE_PROMPT), new UserMessage(query))
                    .call()
                    .content();

            long llmTime = System.currentTimeMillis() - t0;
            System.out.println("[Deep] Step1 LLM 完成, 耗时=" + llmTime + "ms, 原始响应=" + response.substring(0, Math.min(120, response.length())));

            DecomposeResult result = parseResponse(response);
            System.out.println("[Deep] Step1 解析: needSearch=" + result.needSearch() + ", 子问题=" + result.subQueries());
            return result;
        } catch (Exception e) {
            System.err.println("[Deep] ❌ LLM 拆解失败: " + e.getMessage() + " →  fallback 为直接检索");
            return new DecomposeResult(true, List.of(query));
        }
    }

    private DecomposeResult parseResponse(String response) {
        if (response == null || response.isBlank()) {
            return new DecomposeResult(true, Collections.emptyList());
        }
        String json = extractJson(response);
        if (json == null) {
            return new DecomposeResult(true, Collections.emptyList());
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            boolean needSearch = root.path("needSearch").asBoolean(true);
            List<String> subQueries = new ArrayList<>();
            JsonNode arr = root.path("subQueries");
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    String q = node.asText().trim();
                    if (!q.isEmpty() && q.length() <= 50) {
                        subQueries.add(q);
                    }
                }
            }
            if (subQueries.size() > 4) {
                subQueries = subQueries.subList(0, 4);
            }
            return new DecomposeResult(needSearch, subQueries);
        } catch (Exception e) {
            System.err.println("[Deep] JSON 解析失败: " + json);
            return new DecomposeResult(true, Collections.emptyList());
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 分解结果
     */
    public record DecomposeResult(boolean needSearch, List<String> subQueries) {
        public boolean isEmpty() {
            return subQueries == null || subQueries.isEmpty();
        }
    }
}
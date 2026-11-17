package com.ai.guide.application.agent.tools;

import com.ai.guide.application.chat.ChatRetrievalCoordinator;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅游知识库 RAG 检索工具：复用现有 Qdrant + 百炼 Rerank + 事实核验门控体系。
 */
@Component
public class SearchTravelKnowledgeTool implements AgentToolPort {

    public static final String TOOL_NAME = "search_travel_knowledge";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "需要核实或检索的具体文旅问题或景点攻略关键词"
                },
                "city": {
                  "type": "string",
                  "description": "目标城市，默认使用上下文城市"
                },
                "deep": {
                  "type": "boolean",
                  "description": "是否开启深度检索重排"
                }
              },
              "required": ["query"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "检索文旅知识库中的景点历史文化、门票预约、开放闭馆政策、交通指南与官方公告，并返回核验状态及引用来源。",
            SCHEMA,
            false
    );

    private final ChatRetrievalCoordinator retrievalCoordinator;
    private final ObjectMapper objectMapper;

    public SearchTravelKnowledgeTool(ChatRetrievalCoordinator retrievalCoordinator, ObjectMapper objectMapper) {
        this.retrievalCoordinator = retrievalCoordinator;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String query = AgentToolJsonSupport.text(args, "query", "");
        if (query.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_QUERY", "请提供需要检索的问题或关键词 query");
        }
        String city = AgentToolJsonSupport.text(args, "city", context.activeCity());
        boolean deep = args.has("deep") ? args.get("deep").asBoolean(context.deepMode()) : context.deepMode();

        ChatRetrievalCoordinator.ChatRetrieval retrieval = retrievalCoordinator.retrieveChat(query, deep, city);
        if (retrieval == null) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "RETRIEVAL_UNAVAILABLE", "知识库检索返回空结果");
        }
        List<Object> citations = retrieval.citations() == null ? List.of() : new ArrayList<>(retrieval.citations());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", !"不可用".equals(retrieval.status()));
        payload.put("status", retrieval.status());
        payload.put("verified", retrieval.verified());
        payload.put("source", retrieval.source());
        payload.put("reason", retrieval.reason());
        payload.put("context", retrieval.context());
        payload.put("citationCount", citations.size());

        String summary = "知识库状态：" + retrieval.status() + "（引用 " + citations.size() + " 条）";
        if ("不可用".equals(retrieval.status())) {
            return AgentToolResult.failure(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload), summary);
        }
        return AgentToolResult.successWithCitations(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload),
                summary, citations);
    }
}

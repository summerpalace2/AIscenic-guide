package com.ai.guide.domain.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文旅知识 RAG 检索增强与事实核验服务
 *
 * 所属领域：domain.rag.service（文旅知识与检索增强服务层）
 * 架构职责：作为文旅事实知识检索的唯一统一网关，屏蔽 Qdrant 向量检索、DashScope Embedding、百炼三级重排缓存及 SQLite 本地知识降级细节，向调用方提供稳定的 Facts 与 Citations 契约。
 *
 * 核心方法与职责：
 * 1. retrieve：执行多路语义检索与重排核验
 *    - 参数：query（查询 Prompt）、city（目标城市）、constraints（用户偏好约束字典）、deep（是否启用深度多跳语义召回）
 *    - 返回值：包含 Facts 事实列表、引文溯源与核验状态的 Map
 * 2. status：查询当前 RAG 各组件（Embedding 模型、Qdrant 向量库、Rerank 缓存）的健康度与工作模式
 */
@Slf4j
@Service
public class RagRetrievalService {

    private static final int QDRANT_LIMIT = 25;
    private static final int RERANK_TOP_N = 12;
    private static final int LOCAL_LIMIT = 5;

    private static final Pattern FUTURE_CERTAINTY_PATTERN = Pattern.compile(
            "(?:一定不会|绝对不|肯定不|保证不|必定不|肯定没有|绝对没有).*(?:排队|拥堵|堵车|客满|下雨|停运|拥挤)|(?:排队|堵车|下雨).*(?:一定不会|绝对不|肯定不|保证不)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TEMPORARY_EVENT_QUERY_PATTERN = Pattern.compile(
            "(?:今晚|今天|目前|此时|实时).*(?:有没有|是否有|确定|安排|临时).*(?:无人机|无人机表演|烟花|快闪|临时演出|明星见面)|(?:无人机表演|烟花秀).*(?:今晚|今天|确定安排)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern EXACT_MEASUREMENT_OR_FARE_QUERY_PATTERN = Pattern.compile(
            "(?:具体|准确|精确|精准).*(?:多少米|几米|多高|多长|多宽|多重|多大尺寸|多大厘米)|最大的.*(?:具体|到底|究竟).*(?:多少米|几米|多长)|(?:从.+)?(?:打车|网约车|出租车).*(?:到|去).*(?:准确|精确|精准).*(?:车费|多少钱|价格|运价)|(?:准确|精确|精准)车费是多少",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TAXI_FARE_QUERY_PATTERN = Pattern.compile(
            "(?:打车|网约车|出租车).*(?:车费|多少钱|运价)|车费是多少",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PET_POLICY_QUERY_PATTERN = Pattern.compile(
            "(?:带宠物|携宠|带狗|带猫|导盲犬).*(?:进入|进|入内|展厅|展馆|馆内)|(?:宠物|猫狗).*(?:准入|通行|入内|所有展厅)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DISTANCE_QUERY_PATTERN = Pattern.compile(
            "(?:距离|离|步行|走|地铁站|入口|多远)"
    );

    private final ScenicDataImportService scenicDataImportService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    public RagRetrievalService(ScenicDataImportService scenicDataImportService,
                               KnowledgeDocumentService knowledgeDocumentService) {
        this.scenicDataImportService = scenicDataImportService;
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    public record GateDecision(boolean isUnknown, String reasonCode) {
        public static GateDecision relevant() {
            return new GateDecision(false, "EVIDENCE_SUFFICIENT");
        }
        public static GateDecision unknown(String reasonCode) {
            return new GateDecision(true, reasonCode);
        }
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) return false;
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean hasMeasurementEvidence(String query, String corpus) {
        if (DISTANCE_QUERY_PATTERN.matcher(query).find()) {
            return Pattern.compile("(?:\\d+(?:\\.\\d+)?\\s*(?:米|公里|千米|km|m))|平路|步道").matcher(corpus).find();
        }
        if (Pattern.compile("(?:恐龙|化石|骨架|标本)").matcher(query).find()) {
            return Pattern.compile("(?:恐龙|化石|骨架|标本).*(?:\\d+(?:\\.\\d+)?\\s*(?:米|m|厘米|cm)|长达|高达|体长)").matcher(corpus).find();
        }
        if (Pattern.compile("(?:大桥|天桥|索道|建筑|高楼|大礼堂)").matcher(query).find()) {
            return Pattern.compile("(?:\\d+(?:\\.\\d+)?\\s*(?:米|m|厘米|cm|层)|高差|垂直|跨度)").matcher(corpus).find();
        }
        return Pattern.compile("(?:\\d+(?:\\.\\d+)?\\s*(?:米|厘米|公分|m|cm|高度|长达|高达|长度))").matcher(corpus).find();
    }

    public static GateDecision evaluateEvidenceGate(String queryText, List<String> retrievedTexts, String retrievalSource) {
        if (queryText == null || queryText.isBlank()) {
            return GateDecision.unknown("INSUFFICIENT_EVIDENCE");
        }
        if (retrievedTexts == null || retrievedTexts.isEmpty()) {
            return GateDecision.unknown("INSUFFICIENT_EVIDENCE");
        }

        String q = queryText.toLowerCase();
        String combinedCorpus = String.join(" ", retrievedTexts).toLowerCase();

        // 1. 查询级前置拒答：静态知识库天然无法保证的确定性未来预测
        if (FUTURE_CERTAINTY_PATTERN.matcher(queryText).find()) {
            return GateDecision.unknown("FUTURE_CERTAINTY_UNSUPPORTED");
        }

        // 2. 实时临时活动 / 演艺判定：
        if (TEMPORARY_EVENT_QUERY_PATTERN.matcher(queryText).find()) {
            boolean hasEventProof = containsAny(combinedCorpus, "无人机", "烟花", "快闪", "演出安排", "演艺节目");
            if (!hasEventProof) {
                return GateDecision.unknown("REALTIME_DATA_REQUIRED");
            }
        }

        // 3. 精确数值与动态运价检查：
        if (EXACT_MEASUREMENT_OR_FARE_QUERY_PATTERN.matcher(queryText).find()) {
            boolean isAskingTaxi = TAXI_FARE_QUERY_PATTERN.matcher(queryText).find();
            if (isAskingTaxi) {
                boolean hasTaxiPrice = containsAny(combinedCorpus, "打车费约", "出租车费约", "打车约", "出租车约") &&
                        containsAny(combinedCorpus, "元", "块");
                if (!hasTaxiPrice) {
                    return GateDecision.unknown("EXACT_DYNAMIC_VALUE_UNSUPPORTED");
                }
            } else {
                boolean hasMeasurement = hasMeasurementEvidence(q, combinedCorpus);
                if (!hasMeasurement) {
                    return GateDecision.unknown("EXACT_DYNAMIC_VALUE_UNSUPPORTED");
                }
            }
        }

        // 4. 特殊政策检查：
        if (PET_POLICY_QUERY_PATTERN.matcher(queryText).find()) {
            boolean hasPetPolicy = containsAny(combinedCorpus, "宠物", "携宠", "导盲犬", "猫狗", "动物准入");
            if (!hasPetPolicy) {
                return GateDecision.unknown("POLICY_NOT_IN_CORPUS");
            }
        }

        return GateDecision.relevant();
    }

    /**
     * Run semantic retrieval first and use the Java knowledge database as the
     * only degraded/local fallback. A failed Qdrant or rerank call is therefore
     * represented as a normal structured response rather than leaking provider
     * details to the Node BFF.
     */
    public Map<String, Object> retrieve(String query,
                                        String city,
                                        Map<String, Object> constraints,
                                        boolean deep) {
        String queryText = buildQuery(query, city, constraints);
        if (queryText.isBlank()) {
            return failure("检索问题不能为空");
        }

        log.info("[RAG 检索请求] mode={}, targetCollection={}, queryLength={}, city={}",
                deep ? "DEEP" : "STANDARD", safeCollectionName(), queryText.length(), city);

        boolean qdrantAvailable = true;
        List<String> fragments = null;
        try {
            fragments = deep
                    ? scenicDataImportService.searchFragmentsDeep(queryText, QDRANT_LIMIT, RERANK_TOP_N)
                    : scenicDataImportService.searchFragments(queryText, QDRANT_LIMIT, RERANK_TOP_N);

            if (fragments != null && !fragments.isEmpty()) {
                GateDecision gateDecision = evaluateEvidenceGate(queryText, fragments, "QDRANT");
                if (gateDecision.isUnknown()) {
                    log.info("[RAG 证据门禁拦截-Qdrant] reasonCode={}", gateDecision.reasonCode());
                    return unknownResult(gateDecision.reasonCode(), true);
                }
                return semanticResult(fragments, deep);
            }
        } catch (Exception e) {
            qdrantAvailable = false;
            log.warn("[RAG 检索降级] 语义检索异常，转入本地知识库回退: {}", e.getMessage());
            // The local fallback below is the contract for all provider failures.
        }

        // Local Fallback 路径
        try {
            List<Map<String, Object>> documents =
                    knowledgeDocumentService.searchLocalKnowledge(queryText, LOCAL_LIMIT);
            if (documents != null && !documents.isEmpty()) {
                List<String> localTexts = new ArrayList<>();
                for (Map<String, Object> doc : documents) {
                    localTexts.add(stringValue(doc.get("title"), "") + "\n" + stringValue(doc.get("content"), ""));
                }
                GateDecision gateDecision = evaluateEvidenceGate(queryText, localTexts, "LOCAL_FALLBACK");
                if (gateDecision.isUnknown()) {
                    log.info("[RAG 证据门禁拦截-LocalFallback] reasonCode={}", gateDecision.reasonCode());
                    return unknownResult(gateDecision.reasonCode(), false);
                }
                return localResult(documents);
            }
        } catch (Exception e) {
            log.error("[RAG 检索失败] 本地知识库检索异常: {}", e.getMessage());
            return failure("Java 本地知识检索不可用");
        }

        return unknownResult("INSUFFICIENT_EVIDENCE", qdrantAvailable);
    }

    /** Status is deliberately provider-neutral at the BFF boundary. */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", true);
        status.put("provider", "Java Core Backend");
        status.put("owner", "Java");
        status.put("embedding", Map.of(
                "provider", "Java EmbeddingModel",
                "directFromNode", false
        ));
        Map<String, Object> vectorStore = new LinkedHashMap<>();
        vectorStore.put("provider", "Java-managed Qdrant");
        vectorStore.put("collection", safeCollectionName());
        vectorStore.put("configured", true);
        vectorStore.put("directFromNode", false);
        vectorStore.put("verified", false);
        vectorStore.put("indexedDocuments", null);
        status.put("vectorStore", vectorStore);
        status.put("rerank", Map.of(
                "provider", "Java RerankService",
                "cacheOwner", "Java",
                "directFromNode", false
        ));
        status.put("fallback", Map.of(
                "provider", "Java kb_document",
                "mode", "关键词检索",
                "directFromNode", false
        ));
        status.put("contract", Map.of(
                "request", "{ query, city, constraints, deep? }",
                "response", "{ ok, mode, facts, citations, vectorSearch, vectorStore }",
                "verified", true
        ));
        return status;
    }

    private Map<String, Object> semanticResult(List<String> fragments, boolean deep) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (int index = 0; index < fragments.size(); index++) {
            String text = String.valueOf(fragments.get(index));
            if (text.isBlank()) continue;
            facts.add(fact(
                    extractTitle(text, index),
                    text,
                    "已核验",
                    deep ? "Java 深度 RAG 语义命中。" : "Java Qdrant 语义命中。"
            ));
        }

        if (facts.isEmpty()) return failure("Java RAG 返回了空知识片段");

        Map<String, Object> result = baseResult();
        result.put("ok", true);
        result.put("retrievalStatus", "RELEVANT");
        result.put("reasonCode", "EVIDENCE_SUFFICIENT");
        result.put("retrievalSource", "QDRANT");
        result.put("evidenceCount", facts.size());
        result.put("message", "检索完成");
        result.put("mode", deep ? "Java 深度 RAG" : "Java Qdrant 语义检索");
        result.put("source", "Java Core Backend / Qdrant");
        result.put("verified", true);
        result.put("reason", "");
        result.put("vectorSearch", Map.of(
                "provider", "Java Qdrant",
                "collection", safeCollectionName(),
                "resultCount", facts.size(),
                "rerankProvider", "Java RerankService",
                "retrievalStatus", "RELEVANT",
                "reasonCode", "EVIDENCE_SUFFICIENT"
        ));
        result.put("facts", facts);
        result.put("citations", List.of(citation(
                "Java Core RAG",
                "语义检索结果由 Java Core Backend 统一生成"
        )));
        return result;
    }

    private Map<String, Object> localResult(List<Map<String, Object>> documents) {
        List<Map<String, Object>> facts = new ArrayList<>();
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            String title = stringValue(document.get("title"), "Java 本地知识文档");
            String content = stringValue(document.get("content"), "");
            if (content.isBlank()) continue;
            facts.add(fact(title, content, "已核验", "Java kb_document 关键词检索回退。"));
            citations.add(citation(title, "本地知识文档由 Java kb_document 提供"));
        }

        if (facts.isEmpty()) return failure("Java 本地知识库没有可用正文");
        Map<String, Object> result = baseResult();
        result.put("ok", true);
        result.put("retrievalStatus", "RELEVANT");
        result.put("reasonCode", "LOCAL_EVIDENCE_SUFFICIENT");
        result.put("retrievalSource", "LOCAL_FALLBACK");
        result.put("evidenceCount", facts.size());
        result.put("message", "本地知识库检索完成");
        result.put("mode", "Java 本地知识回退（关键词检索）");
        result.put("source", "Java kb_document");
        result.put("verified", true);
        result.put("reason", "语义检索无可用结果，已降级到 Java 本地知识库。");
        result.put("facts", facts);
        result.put("citations", citations);
        result.put("vectorSearch", Map.of(
                "provider", "Java Local",
                "collection", safeCollectionName(),
                "resultCount", facts.size(),
                "fallback", true,
                "retrievalStatus", "RELEVANT",
                "reasonCode", "LOCAL_EVIDENCE_SUFFICIENT"
        ));
        return result;
    }

    private Map<String, Object> unknownResult(String reasonCode, boolean fromQdrant) {
        Map<String, Object> result = baseResult();
        result.put("ok", true);
        result.put("retrievalStatus", "UNKNOWN");
        result.put("reasonCode", reasonCode);
        result.put("retrievalSource", fromQdrant ? "QDRANT" : "LOCAL_FALLBACK");
        result.put("evidenceCount", 0);
        result.put("message", "当前知识库没有足够证据确认该问题，建议查询景区官方渠道或实时地图服务。");
        result.put("mode", fromQdrant ? "Java Qdrant 语义检索" : "Java 本地知识回退（关键词检索）");
        result.put("source", fromQdrant ? "Java Core Backend / Qdrant" : "Java kb_document");
        result.put("verified", false);
        result.put("reason", "当前知识库没有足够证据确认该问题，建议查询景区官方渠道或实时地图服务。");
        result.put("facts", Collections.emptyList());
        result.put("citations", Collections.emptyList());
        result.put("vectorSearch", Map.of(
                "provider", fromQdrant ? "Java Qdrant" : "Java Local",
                "collection", safeCollectionName(),
                "resultCount", 0,
                "retrievalStatus", "UNKNOWN",
                "reasonCode", reasonCode
        ));
        return result;
    }

    private Map<String, Object> failure(String reason) {
        Map<String, Object> result = baseResult();
        result.put("ok", false);
        result.put("retrievalStatus", "UNAVAILABLE");
        result.put("reasonCode", "SYSTEM_UNAVAILABLE");
        result.put("mode", "Java Core RAG");
        result.put("source", "Java Core Backend");
        result.put("verified", false);
        result.put("reason", reason);
        result.put("facts", Collections.emptyList());
        result.put("citations", Collections.emptyList());
        return result;
    }

    private Map<String, Object> baseResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "Java Core Backend");
        result.put("embedding", Map.of("directFromNode", false));
        result.put("vectorStore", status().get("vectorStore"));
        result.put("fallbackOwner", "Java");
        return result;
    }

    private Map<String, Object> fact(String label, String value, String status, String note) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("label", label);
        fact.put("value", value);
        fact.put("status", status);
        fact.put("note", note);
        fact.put("citations", List.of(citation(label, note)));
        return fact;
    }

    private Map<String, Object> citation(String title, String note) {
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("title", title);
        citation.put("publisher", "Java Core Backend");
        citation.put("endpoint", "/ai/rag/retrieve");
        citation.put("url", "/ai/rag/retrieve");
        citation.put("updatedAt", java.time.Instant.now().toString());
        citation.put("status", "已核验");
        citation.put("note", note);
        return citation;
    }

    private String buildQuery(String query, String city, Map<String, Object> constraints) {
        StringBuilder builder = new StringBuilder(String.valueOf(query == null ? "" : query).trim());
        if (city != null && !city.isBlank()) builder.append("\n城市：").append(city.trim());
        if (constraints != null && !constraints.isEmpty()) builder.append("\n约束：").append(constraints);
        return RagQueryExpander.profile(builder.toString()).expandedQuery().trim();
    }

    private String extractTitle(String text, int index) {
        String firstLine = text.replace('\r', '\n').split("\n", 2)[0].trim();
        return firstLine.length() > 80 ? firstLine.substring(0, 80) : (firstLine.isBlank() ? "知识片段 " + (index + 1) : firstLine);
    }

    private String stringValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private String safeCollectionName() {
        try {
            String value = scenicDataImportService.collectionName();
            return value == null || value.isBlank()
                    ? "Java-managed Production V2 collection"
                    : value;
        } catch (Exception e) {
            return "Java-managed Production V2 collection";
        }
    }
}

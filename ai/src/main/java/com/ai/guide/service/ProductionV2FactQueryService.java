package com.ai.guide.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Production V2 关系型数据边界的只读精确事实适配器。 */
@Service
public class ProductionV2FactQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionV2FactQueryService(
            @Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ProductionV2FactQueryService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
    }

    /**
     * 返回标准实体和字段对应的全部事实声明，并保留来源状态语义。
     */
    public List<FactResult> query(String entityId, String field) {
        if (entityId == null || entityId.isBlank() || field == null || field.isBlank()) {
            return List.of();
        }
        String sql = "SELECT f.claim_id, f.entity_id, f.field, f.value_json, f.unit, " +
                "f.fact_status, f.verification_result, f.freshness_status, f.validity_type, " +
                "f.valid_from, f.valid_to, f.source_id, f.source_url, f.evidence_excerpt, " +
                "f.reasoning_note, s.organization, s.source_title, s.source_url AS joined_source_url, " +
                "s.trust_level AS source_trust_level " +
                "FROM prod_v2_fact_claim f LEFT JOIN prod_v2_source_document s ON s.source_id = f.source_id " +
                "WHERE f.entity_id = ? AND f.field = ? ORDER BY f.claim_id";
        return jdbcTemplate.query(sql, new Object[]{entityId, field}, (rs, rowNum) -> new FactResult(
                rs.getString("claim_id"),
                rs.getString("entity_id"),
                rs.getString("field"),
                parseValue(rs.getString("value_json")),
                rs.getString("unit"),
                rs.getString("fact_status"),
                rs.getString("verification_result"),
                rs.getString("freshness_status"),
                rs.getString("validity_type"),
                rs.getString("valid_from"),
                rs.getString("valid_to"),
                new Citation(
                        rs.getString("source_id"),
                        firstNonBlank(rs.getString("joined_source_url"), rs.getString("source_url")),
                        rs.getString("source_title"),
                        rs.getString("organization"),
                        rs.getString("source_trust_level")),
                rs.getString("evidence_excerpt"),
                rs.getString("reasoning_note")));
    }

    public Optional<FactResult> first(String entityId, String field) {
        return query(entityId, field).stream().findFirst();
    }

    /**
     * detail - 根据 Production V2 事实声明构建只读景点详情投影。
     *
     * @param entityId 标准 Production V2 实体标识符
     * @return 事实/引用投影；本地适配器没有事实声明时返回 null
     */
    public Map<String, Object> detail(String entityId) {
        if (entityId == null || entityId.isBlank()) return null;
        try {
            List<FactResult> claims = jdbcTemplate.query(
                    "SELECT f.claim_id, f.entity_id, f.field, f.value_json, f.unit, " +
                            "f.fact_status, f.verification_result, f.freshness_status, f.validity_type, " +
                            "f.valid_from, f.valid_to, f.source_id, f.source_url, f.evidence_excerpt, " +
                            "f.reasoning_note, s.organization, s.source_title, s.source_url AS joined_source_url, " +
                            "s.trust_level AS source_trust_level " +
                            "FROM prod_v2_fact_claim f LEFT JOIN prod_v2_source_document s ON s.source_id = f.source_id " +
                            "WHERE f.entity_id = ? ORDER BY f.field, f.claim_id",
                    new Object[]{entityId},
                    (rs, rowNum) -> new FactResult(
                            rs.getString("claim_id"),
                            rs.getString("entity_id"),
                            rs.getString("field"),
                            parseValue(rs.getString("value_json")),
                            rs.getString("unit"),
                            rs.getString("fact_status"),
                            rs.getString("verification_result"),
                            rs.getString("freshness_status"),
                            rs.getString("validity_type"),
                            rs.getString("valid_from"),
                            rs.getString("valid_to"),
                            new Citation(
                                    rs.getString("source_id"),
                                    firstNonBlank(rs.getString("joined_source_url"), rs.getString("source_url")),
                                    rs.getString("source_title"),
                                    rs.getString("organization"),
                                    rs.getString("source_trust_level")),
                            rs.getString("evidence_excerpt"),
                            rs.getString("reasoning_note")));
            if (claims.isEmpty()) return null;

            List<Map<String, Object>> facts = new ArrayList<>();
            Map<String, Map<String, Object>> citationIndex = new LinkedHashMap<>();
            for (FactResult claim : claims) {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("claimId", claim.claimId());
                fact.put("field", claim.field());
                fact.put("value", claim.value());
                fact.put("unit", claim.unit());
                fact.put("factStatus", claim.factStatus());
                fact.put("verificationResult", claim.verificationResult());
                fact.put("freshnessStatus", claim.freshnessStatus());
                fact.put("validityType", claim.validityType());
                fact.put("evidenceExcerpt", claim.evidenceExcerpt());
                fact.put("citation", claim.citation());
                facts.add(fact);
                if (claim.citation() != null && claim.citation().sourceId() != null) {
                    Map<String, Object> citation = new LinkedHashMap<>();
                    citation.put("sourceId", claim.citation().sourceId());
                    citation.put("sourceUrl", claim.citation().sourceUrl());
                    citation.put("title", claim.citation().sourceTitle());
                    citation.put("organization", claim.citation().organization());
                    citation.put("trustLevel", claim.citation().trustLevel());
                    citationIndex.putIfAbsent(claim.citation().sourceId(), citation);
                }
            }

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("entityId", entityId);
            detail.put("dataStatus", "VERIFIED");
            detail.put("facts", facts);
            detail.put("citations", new ArrayList<>(citationIndex.values()));
            return detail;
        } catch (DataAccessException ignored) {
            // 本地适配器可能尚未接收到 Production V2 导入数据。
            return null;
        }
    }

    private JsonNode parseValue(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid Production V2 value_json", e);
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    public record FactResult(
            String claimId,
            String entityId,
            String field,
            JsonNode value,
            String unit,
            String factStatus,
            String verificationResult,
            String freshnessStatus,
            String validityType,
            String validFrom,
            String validTo,
            Citation citation,
            String evidenceExcerpt,
            String reasoningNote) {

        /** 为明确要求当前已验证事实的调用方提供保守门禁。 */
        public boolean currentFactEligible() {
            return "KNOWN".equals(factStatus)
                    && "VERIFIED".equals(verificationResult)
                    && "FRESH".equals(freshnessStatus)
                    && value != null;
        }
    }

    public record Citation(
            String sourceId,
            String sourceUrl,
            String sourceTitle,
            String organization,
            String trustLevel) {
    }
}

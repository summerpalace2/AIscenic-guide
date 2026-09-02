package com.ai.guide.domain.rag.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 面向现有 SQLite/PostgreSQL 边界的幂等 Production V2 关系型数据导入器。 */
@Service
public class ProductionV2DataPipelineService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionV2DataPipelineService(
            @org.springframework.beans.factory.annotation.Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public ProductionV2DataPipelineService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new ObjectMapper().findAndRegisterModules());
    }

    /** 仅执行增量 schema 操作；不清空旧表或任何 Qdrant 集合。 */
    public void ensureSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS prod_v2_entity (" +
                "entity_id VARCHAR(128) PRIMARY KEY," +
                "canonical_name VARCHAR(255) NOT NULL," +
                "aliases TEXT," +
                "entity_type VARCHAR(128)," +
                "district VARCHAR(128)," +
                "city VARCHAR(128)," +
                "parent_entity_id VARCHAR(128)," +
                "related_entity_ids TEXT," +
                "coverage_scope VARCHAR(32) NOT NULL DEFAULT 'INDEX_ONLY')");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS prod_v2_source_document (" +
                "source_id VARCHAR(128) PRIMARY KEY," +
                "organization TEXT," +
                "source_title TEXT," +
                "source_url TEXT NOT NULL," +
                "source_type VARCHAR(128)," +
                "trust_level VARCHAR(32)," +
                "published_at VARCHAR(32)," +
                "updated_at VARCHAR(32)," +
                "accessed_at VARCHAR(32)," +
                "applies_to_entity_ids TEXT," +
                "content_summary TEXT," +
                "evidence_excerpt TEXT," +
                "status VARCHAR(32))");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS prod_v2_fact_claim (" +
                "claim_id VARCHAR(128) PRIMARY KEY," +
                "entity_id VARCHAR(128) NOT NULL," +
                "field VARCHAR(128) NOT NULL," +
                "value_json TEXT," +
                "unit VARCHAR(64)," +
                "fact_status VARCHAR(64) NOT NULL," +
                "verification_result VARCHAR(64)," +
                "freshness_status VARCHAR(64)," +
                "validity_type VARCHAR(64)," +
                "valid_from VARCHAR(32)," +
                "valid_to VARCHAR(32)," +
                "source_id VARCHAR(128) NOT NULL," +
                "source_url TEXT NOT NULL," +
                "evidence_excerpt TEXT," +
                "reasoning_note TEXT)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS prod_v2_qa_issue (" +
                "issue_id VARCHAR(128) PRIMARY KEY," +
                "entity_id VARCHAR(128)," +
                "field VARCHAR(128)," +
                "issue_type VARCHAR(128)," +
                "candidate_value TEXT," +
                "verified_value TEXT," +
                "severity VARCHAR(32)," +
                "description TEXT," +
                "source_ids TEXT," +
                "recommended_action TEXT)");
    }

    /** 校验并导入五个 Production V2 文件。 */
    public ImportReport importProductionV2(Path sourceDirectory, boolean dryRun) throws IOException {
        ensureSchema();
        JsonNode entities = readArray(sourceDirectory, "entity_index.json");
        JsonNode sources = readArray(sourceDirectory, "source_documents.json");
        JsonNode facts = readArray(sourceDirectory, "fact_claims.json");
        JsonNode knowledge = readArray(sourceDirectory, "knowledge_documents.json");
        JsonNode qaIssues = readArray(sourceDirectory, "qa_issues.json");

        List<String> core = List.of("hongyadong", "liziba_viewpoint", "liziba_station", "eling_testbed2",
                "shancheng_trail", "huguang_guild_hall", "jiefangbei", "yangtze_cableway", "three_gorges_museum");
        int unknownWithValue = 0;
        for (JsonNode fact : facts) {
            if ("UNKNOWN".equals(text(fact, "fact_status")) && nonNull(fact, "value")) unknownWithValue++;
        }
        if (unknownWithValue > 0) throw new IllegalArgumentException("UNKNOWN facts must retain null value");

        int writes = 0;
        if (!dryRun) {
            for (JsonNode entity : entities) writes += upsertEntity(entity, core.contains(text(entity, "entity_id")));
            for (JsonNode source : sources) writes += upsertSource(source);
            for (JsonNode fact : facts) writes += upsertFact(fact);
            for (JsonNode issue : qaIssues) writes += upsertQaIssue(issue);
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("entities", entities.size());
        counts.put("sources", sources.size());
        counts.put("facts", facts.size());
        counts.put("knowledgeDocuments", knowledge.size());
        counts.put("ragEligibleKnowledgeDocuments", countRagEligible(knowledge));
        counts.put("qaIssues", qaIssues.size());
        return new ImportReport("PASS", dryRun, counts, writes, unknownWithValue == 0, false, false);
    }

    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    private int upsertEntity(JsonNode row, boolean core) {
        String id = text(row, "entity_id");
        int updated = jdbcTemplate.update("UPDATE prod_v2_entity SET canonical_name=?, aliases=?, entity_type=?, district=?, city=?, parent_entity_id=?, related_entity_ids=?, coverage_scope=? WHERE entity_id=?",
                text(row, "canonical_name"), json(row, "aliases"), text(row, "entity_type"), text(row, "district"), text(row, "city"),
                text(row, "parent_entity_id"), json(row, "related_entity_ids"), core ? "CORE" : "INDEX_ONLY", id);
        if (updated > 0) return updated;
        return jdbcTemplate.update("INSERT INTO prod_v2_entity (entity_id, canonical_name, aliases, entity_type, district, city, parent_entity_id, related_entity_ids, coverage_scope) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, text(row, "canonical_name"), json(row, "aliases"), text(row, "entity_type"), text(row, "district"), text(row, "city"),
                text(row, "parent_entity_id"), json(row, "related_entity_ids"), core ? "CORE" : "INDEX_ONLY");
    }

    private int upsertSource(JsonNode row) {
        String id = text(row, "source_id");
        int updated = jdbcTemplate.update("UPDATE prod_v2_source_document SET organization=?, source_title=?, source_url=?, source_type=?, trust_level=?, published_at=?, updated_at=?, accessed_at=?, applies_to_entity_ids=?, content_summary=?, evidence_excerpt=?, status=? WHERE source_id=?",
                text(row, "organization"), text(row, "source_title"), text(row, "source_url"), text(row, "source_type"), text(row, "trust_level"),
                text(row, "published_at"), text(row, "updated_at"), text(row, "accessed_at"), json(row, "applies_to_entity_ids"),
                text(row, "content_summary"), text(row, "evidence_excerpt"), text(row, "status"), id);
        if (updated > 0) return updated;
        return jdbcTemplate.update("INSERT INTO prod_v2_source_document (source_id, organization, source_title, source_url, source_type, trust_level, published_at, updated_at, accessed_at, applies_to_entity_ids, content_summary, evidence_excerpt, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, text(row, "organization"), text(row, "source_title"), text(row, "source_url"), text(row, "source_type"), text(row, "trust_level"),
                text(row, "published_at"), text(row, "updated_at"), text(row, "accessed_at"), json(row, "applies_to_entity_ids"),
                text(row, "content_summary"), text(row, "evidence_excerpt"), text(row, "status"));
    }

    private int upsertFact(JsonNode row) {
        String id = text(row, "claim_id");
        String value = json(row, "value");
        int updated = jdbcTemplate.update("UPDATE prod_v2_fact_claim SET entity_id=?, field=?, value_json=?, unit=?, fact_status=?, verification_result=?, freshness_status=?, validity_type=?, valid_from=?, valid_to=?, source_id=?, source_url=?, evidence_excerpt=?, reasoning_note=? WHERE claim_id=?",
                text(row, "entity_id"), text(row, "field"), value, text(row, "unit"), text(row, "fact_status"), text(row, "verification_result"),
                text(row, "freshness_status"), text(row, "validity_type"), text(row, "valid_from"), text(row, "valid_to"), text(row, "source_id"),
                text(row, "source_url"), text(row, "evidence_excerpt"), text(row, "reasoning_note"), id);
        if (updated > 0) return updated;
        return jdbcTemplate.update("INSERT INTO prod_v2_fact_claim (claim_id, entity_id, field, value_json, unit, fact_status, verification_result, freshness_status, validity_type, valid_from, valid_to, source_id, source_url, evidence_excerpt, reasoning_note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, text(row, "entity_id"), text(row, "field"), value, text(row, "unit"), text(row, "fact_status"), text(row, "verification_result"),
                text(row, "freshness_status"), text(row, "validity_type"), text(row, "valid_from"), text(row, "valid_to"), text(row, "source_id"),
                text(row, "source_url"), text(row, "evidence_excerpt"), text(row, "reasoning_note"));
    }

    private int upsertQaIssue(JsonNode row) {
        String id = text(row, "issue_id");
        int updated = jdbcTemplate.update("UPDATE prod_v2_qa_issue SET entity_id=?, field=?, issue_type=?, candidate_value=?, verified_value=?, severity=?, description=?, source_ids=?, recommended_action=? WHERE issue_id=?",
                text(row, "entity_id"), text(row, "field"), text(row, "issue_type"), text(row, "candidate_value"), text(row, "verified_value"),
                text(row, "severity"), text(row, "description"), json(row, "source_ids"), text(row, "recommended_action"), id);
        if (updated > 0) return updated;
        return jdbcTemplate.update("INSERT INTO prod_v2_qa_issue (issue_id, entity_id, field, issue_type, candidate_value, verified_value, severity, description, source_ids, recommended_action) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, text(row, "entity_id"), text(row, "field"), text(row, "issue_type"), text(row, "candidate_value"), text(row, "verified_value"),
                text(row, "severity"), text(row, "description"), json(row, "source_ids"), text(row, "recommended_action"));
    }

    private JsonNode readArray(Path directory, String name) throws IOException {
        JsonNode node = objectMapper.readTree(Files.readString(directory.resolve(name), StandardCharsets.UTF_8));
        if (!node.isArray()) throw new IOException(name + " must contain an array");
        return node;
    }

    private int countRagEligible(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) if (row.path("rag_eligible").asBoolean(false)) count++;
        return count;
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private boolean nonNull(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value != null && !value.isNull();
    }

    private String json(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.toString();
    }

    public record ImportReport(String status, boolean dryRun, Map<String, Integer> counts, int writes,
                               boolean unknownNullPreserved, boolean legacyDataTouched, boolean destructiveOperation) {
    }
}

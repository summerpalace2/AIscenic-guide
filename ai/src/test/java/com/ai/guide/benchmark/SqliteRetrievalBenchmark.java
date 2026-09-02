package com.ai.guide.benchmark;

import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot isolated benchmark runner for the real Java kb_document fallback.
 * It creates only a benchmark SQLite file, imports the checked-in corpus, and
 * calls KnowledgeDocumentService.searchLocalKnowledge without copying ranking
 * logic into the runner.
 */
public final class SqliteRetrievalBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] QUERIES = {
            "洪崖洞晚上适合去吗？",
            "带父母去重庆有哪些少走路的景点？",
            "三峡博物馆有什么值得看？",
            "重庆三日游有哪些夜景地点？",
            "不想爬坡，在重庆适合去哪？",
            "情侣晚上去哪里拍照比较好？",
            "下雨天带孩子去重庆哪里比较合适？",
            "夏天怕热，想多安排室内景点，有哪些选择？",
            "我不喜欢特别商业化的景点，想看重庆历史文化，去哪比较好？",
            "想安排一个步行少、夜景好、又适合老人的晚上行程。"
    };

    private SqliteRetrievalBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <corpus.jsonl> <benchmark.db> <qdrant-doc-ids.json> <output.json>");
        }

        Path corpusPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path databasePath = Path.of(args[1]).toAbsolutePath().normalize();
        Path qdrantIdsPath = Path.of(args[2]).toAbsolutePath().normalize();
        Path outputPath = Path.of(args[3]).toAbsolutePath().normalize();

        if (Files.exists(databasePath)) {
            throw new IllegalStateException("benchmark database already exists; use a new isolated path: " + databasePath);
        }
        Files.createDirectories(databasePath.getParent());
        Files.createDirectories(outputPath.getParent());

        List<JsonNode> documents = readDocuments(corpusPath);
        Set<String> sqliteIds = new LinkedHashSet<>();
        JdbcTemplate jdbcTemplate = createDatabase(databasePath);
        importDocuments(jdbcTemplate, documents, sqliteIds);

        Set<String> qdrantIds = readIds(qdrantIdsPath);
        int sqliteCount = countDocuments(jdbcTemplate);
        if (sqliteCount != documents.size() || sqliteIds.size() != documents.size()) {
            throw new IllegalStateException("SQLite corpus count mismatch: expected=" + documents.size()
                    + ", rows=" + sqliteCount + ", uniqueIds=" + sqliteIds.size());
        }
        if (!qdrantIds.equals(sqliteIds)) {
            Set<String> missingFromSqlite = new LinkedHashSet<>(qdrantIds);
            missingFromSqlite.removeAll(sqliteIds);
            Set<String> missingFromQdrant = new LinkedHashSet<>(sqliteIds);
            missingFromQdrant.removeAll(qdrantIds);
            throw new IllegalStateException("DOCUMENT_ID_SET_MATCH=NO; missingFromSqlite="
                    + missingFromSqlite + "; missingFromQdrant=" + missingFromQdrant);
        }

        KnowledgeDocumentService service = new KnowledgeDocumentService(jdbcTemplate, null);
        List<Map<String, Object>> queryResults = new ArrayList<>();
        for (String query : QUERIES) {
            List<Map<String, Object>> rows = service.searchLocalKnowledge(query, 5);
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (int index = 0; index < rows.size(); index++) {
                Map<String, Object> row = rows.get(index);
                String docId = stringValue(row.get("id"));
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rank", index + 1);
                result.put("docId", docId);
                result.put("entity", stringValue(row.get("title")));
                result.put("topic", categoryFor(jdbcTemplate, docId));
                result.put("score", row.get("retrievalScore"));
                result.put("matchedTerms", "KnowledgeDocumentService retrievalScore");
                result.put("summary", shortSummary(row.get("content")));
                result.put("sourceName", stringValue(row.get("source_name")));
                normalized.add(result);
            }
            Map<String, Object> queryResult = new LinkedHashMap<>();
            queryResult.put("query", query);
            queryResult.put("results", normalized);
            queryResults.add(queryResult);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("retrievalOwner", "Java KnowledgeDocumentService.searchLocalKnowledge");
        output.put("database", databasePath.toString());
        output.put("corpus", corpusPath.toString());
        output.put("qdrantDocumentIdSource", qdrantIdsPath.toString());
        output.put("qdrantDocumentCount", qdrantIds.size());
        output.put("sqliteDocumentCount", sqliteCount);
        output.put("documentIdSetMatch", true);
        output.put("queryCount", QUERIES.length);
        output.put("topK", 5);
        output.put("results", queryResults);
        output.put("manualReviewStatus", "PENDING_POST_RETRIEVAL_MANUAL_REVIEW");
        output.put("winnerCalculation", "NOT_PERFORMED_IN_JAVA_RETRIEVAL_RUNNER");
        Files.writeString(outputPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "ok", true,
                "qdrantDocumentCount", qdrantIds.size(),
                "sqliteDocumentCount", sqliteCount,
                "documentIdSetMatch", true,
                "queryCount", QUERIES.length,
                "output", outputPath.toString()
        )));
    }

    private static JdbcTemplate createDatabase(Path databasePath) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE kb_document ("
                + "id VARCHAR(128) PRIMARY KEY,"
                + "title VARCHAR(255) NOT NULL,"
                + "category VARCHAR(255) NOT NULL DEFAULT '',"
                + "content TEXT DEFAULT '',"
                + "tags TEXT DEFAULT '[]',"
                + "file_url TEXT DEFAULT '',"
                + "file_md5 VARCHAR(64) DEFAULT '',"
                + "source_name VARCHAR(255) DEFAULT '',"
                + "status VARCHAR(255) NOT NULL DEFAULT 'active',"
                + "vector_status VARCHAR(255) NOT NULL DEFAULT 'pending',"
                + "chunk_count INTEGER DEFAULT 0,"
                + "created_by VARCHAR(255) DEFAULT '',"
                + "created_at VARCHAR(32) NOT NULL,"
                + "updated_at VARCHAR(32) NOT NULL"
                + ")");
        return jdbcTemplate;
    }

    private static void importDocuments(JdbcTemplate jdbcTemplate, List<JsonNode> documents, Set<String> ids) throws IOException {
        String now = LocalDateTime.of(2026, 8, 19, 0, 0).format(DT_FMT);
        String sql = "INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, source_name, status, vector_status, chunk_count, created_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, '', '', ?, 'active', 'benchmark', 0, ?, ?, ?)";
        for (JsonNode document : documents) {
            String docId = required(document, "docId");
            if (!ids.add(docId)) throw new IllegalStateException("duplicate corpus docId: " + docId);
            String title = required(document, "title");
            String category = firstNonBlank(document.path("topic").asText(""), document.path("dataType").asText(""));
            String content = required(document, "content");
            String tags = MAPPER.writeValueAsString(document.path("keywords"));
            String sourceName = document.path("sourceIds").isArray() && document.path("sourceIds").size() > 0
                    ? document.path("sourceIds").get(0).asText() : docId;
            jdbcTemplate.update(sql, docId, title, category, content, tags, sourceName,
                    "retrieval-benchmark", now, now);
        }
    }

    private static List<JsonNode> readDocuments(Path corpusPath) throws IOException {
        List<JsonNode> documents = new ArrayList<>();
        for (String line : Files.readAllLines(corpusPath)) {
            if (!line.isBlank()) documents.add(MAPPER.readTree(line));
        }
        return documents;
    }

    private static Set<String> readIds(Path idsPath) throws IOException {
        JsonNode root = MAPPER.readTree(Files.readString(idsPath));
        JsonNode values = root.isArray() ? root : root.path("docIds");
        if (!values.isArray()) throw new IllegalArgumentException("Qdrant doc ID file must be an array or {docIds: [...]}");
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode value : values) ids.add(value.asText());
        return ids;
    }

    private static int countDocuments(JdbcTemplate jdbcTemplate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE (status IS NULL OR status <> 'archived')", Integer.class);
        return count == null ? 0 : count;
    }

    private static String categoryFor(JdbcTemplate jdbcTemplate, String docId) {
        String category = jdbcTemplate.queryForObject("SELECT category FROM kb_document WHERE id = ?", String.class, docId);
        return category == null ? "" : category;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) throw new IllegalArgumentException("corpus field is empty: " + field);
        return value;
    }

    private static String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String shortSummary(Object value) {
        String text = stringValue(value).replaceAll("\\s+", " ").trim();
        return text.length() <= 140 ? text : text.substring(0, 137) + "...";
    }
}

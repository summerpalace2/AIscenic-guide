package com.ai.guide.infrastructure.persistence.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads packaged reference datasets after the target schema has been initialized. */
@Component
public class KnowledgeSeedDataInitializer {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeedDataInitializer.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter cityImporter;

    public KnowledgeSeedDataInitializer() { this(new com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter()); }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeSeedDataInitializer(com.ai.guide.infrastructure.attraction.repository.CityAttractionCatalogImporter cityImporter) {
        this.cityImporter = cityImporter;
    }

    public void initialize(JdbcTemplate jdbcTemplate, boolean sqlite) {
        var source = java.util.Objects.requireNonNull(jdbcTemplate.getDataSource(), "Knowledge datasource is required");
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> initAttractions(jdbcTemplate, sqlite));
        cityImporter.refresh(jdbcTemplate, sqlite);
        transaction.executeWithoutResult(status -> initDiningVenues(jdbcTemplate, sqlite));
        transaction.executeWithoutResult(status -> initKnowledgeDocuments(jdbcTemplate));
    }
    private void initAttractions(JdbcTemplate jdbcTemplate, boolean sqlite) {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("attractions.json")) {
                if (is == null) {
                    throw new IllegalStateException("未找到必需的 attractions.json 种子资源");
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, Object>> list = mapper.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                String sql = "INSERT INTO attraction (id, name, display_name, city, district, category, tags, icon, tone, location, summary, walk, duration, indoor, walk_difficulty, ticket, best_time, amap_query, intro, fit, accessibility, environment, recommended_visit_minutes, companion_tags, feature_tags, photo_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET city=excluded.city";
                int inserted = 0;
                for (Map<String, Object> map : list) {
                    boolean indoor = Boolean.TRUE.equals(map.get("indoor"));
                    // JDBC 将 Integer 1/0 直接写入 PostgreSQL BOOLEAN 会被拒绝；
                    // SQLite 则以整数布尔值保存，因此在数据边界按数据库方言转换。
                    Object indoorValue = sqlite ? (indoor ? 1 : 0) : indoor;
                    String photoUrl = String.valueOf(map.getOrDefault("photoUrl", ""));
                    inserted += jdbcTemplate.update(sql,
                            map.get("id"),
                            map.get("name"),
                            map.getOrDefault("displayName", ""),
                            map.getOrDefault("city", ""),
                            map.getOrDefault("district", ""),
                            map.getOrDefault("category", ""),
                            mapper.writeValueAsString(map.getOrDefault("tags", Collections.emptyList())),
                            map.getOrDefault("icon", ""),
                            map.getOrDefault("tone", ""),
                            map.getOrDefault("location", ""),
                            map.getOrDefault("summary", ""),
                            map.getOrDefault("walk", ""),
                            map.getOrDefault("duration", ""),
                            indoorValue,
                            map.getOrDefault("walkDifficulty", ""),
                            map.getOrDefault("ticket", ""),
                            map.getOrDefault("bestTime", ""),
                            mapper.writeValueAsString(map.getOrDefault("amapQuery", Collections.emptyMap())),
                            map.getOrDefault("intro", ""),
                            map.getOrDefault("fit", ""),
                            map.getOrDefault("accessibility", ""),
                            map.getOrDefault("environment", ""),
                            map.get("recommendedVisitMinutes"),
                            mapper.writeValueAsString(map.getOrDefault("companionTags", Collections.emptyList())),
                            mapper.writeValueAsString(map.getOrDefault("featureTags", Collections.emptyList())),
                            photoUrl
                    );
                    if (photoUrl != null && !photoUrl.isBlank() && !"null".equals(photoUrl)) {
                        jdbcTemplate.update("UPDATE attraction SET photo_url = ? WHERE id = ?", photoUrl, map.get("id"));
                    }
                }
                log.info("[KnowledgeDB] 景点目录已核对：资源 {} 条，本次新增 {} 条", list.size(), inserted);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化景点种子数据失败", e);
        }
    }

    private void initDiningVenues(JdbcTemplate jdbcTemplate, boolean sqlite) {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("dining_venues.json")) {
                if (is == null) {
                    throw new IllegalStateException("未找到必需的 dining_venues.json 种子资源");
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, Object>> list = mapper.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                String sql = "INSERT INTO dining_venue (id, name, district, category, meal_types, location, specialty_dish, average_cost, duration, distance_desc, summary, recommendation_reason, spiciness_level, elder_friendly, tone, tags) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT(id) DO UPDATE SET name=excluded.name, district=excluded.district, category=excluded.category, " +
                        "meal_types=excluded.meal_types, location=excluded.location, specialty_dish=excluded.specialty_dish, " +
                        "average_cost=excluded.average_cost, duration=excluded.duration, distance_desc=excluded.distance_desc, " +
                        "summary=excluded.summary, recommendation_reason=excluded.recommendation_reason, " +
                        "spiciness_level=excluded.spiciness_level, elder_friendly=excluded.elder_friendly, tone=excluded.tone, tags=excluded.tags";
                int inserted = 0;
                for (Map<String, Object> map : list) {
                    boolean elder = !Boolean.FALSE.equals(map.get("elderFriendly"));
                    Object elderValue = sqlite ? (elder ? 1 : 0) : elder;
                    inserted += jdbcTemplate.update(sql,
                            map.get("id"),
                            map.get("name"),
                            map.getOrDefault("district", ""),
                            map.getOrDefault("category", ""),
                            mapper.writeValueAsString(map.getOrDefault("mealTypes", Collections.emptyList())),
                            map.getOrDefault("location", ""),
                            map.getOrDefault("specialtyDish", ""),
                            map.getOrDefault("averageCost", ""),
                            map.getOrDefault("duration", ""),
                            map.getOrDefault("distanceDesc", ""),
                            map.getOrDefault("summary", ""),
                            map.getOrDefault("recommendationReason", ""),
                            map.getOrDefault("spicinessLevel", "MILD"),
                            elderValue,
                            map.getOrDefault("tone", "gold"),
                            mapper.writeValueAsString(map.getOrDefault("tags", Collections.emptyList()))
                    );
                }
                log.info("[KnowledgeDB] 美食餐厅目录已核对：资源 {} 条，本次新增 {} 条", list.size(), inserted);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化餐饮种子数据失败", e);
        }
    }

    private void initKnowledgeDocuments(JdbcTemplate jdbcTemplate) {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("knowledge_documents.jsonl")) {
                if (is == null) {
                    throw new IllegalStateException("未找到必需的 knowledge_documents.jsonl 种子资源");
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                String now = LocalDateTime.now().format(DT_FMT);
                String sql = "INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, source_name, source_url, scope, city, status, vector_status, chunk_count, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, '', '', ?, ?, ?, ?, 'active', 'pending', 0, 'system-init', ?, ?) ON CONFLICT(id) DO NOTHING";
                int inserted = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Map<String, Object> doc = mapper.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    String id = String.valueOf(doc.getOrDefault("docId", java.util.UUID.randomUUID().toString()));
                    String title = String.valueOf(doc.getOrDefault("title", ""));
                    String category = String.valueOf(doc.getOrDefault("topic", "culture"));
                    String content = String.valueOf(doc.getOrDefault("content", ""));
                    List<?> keywords = (List<?>) doc.get("keywords");
                    String tags = mapper.writeValueAsString(keywords != null ? keywords : List.of());
                    String sourceName = requiredText(doc, "entityName");
                    String sourceUrl = safeSourceUrl(doc.get("sourceUrl"));
                    var scope = new com.ai.guide.domain.rag.model.KnowledgeScope(String.valueOf(doc.getOrDefault("scope", "UNSPECIFIED")), String.valueOf(doc.getOrDefault("city", "")));
                    inserted += jdbcTemplate.update(sql, id, title, category, content, tags, sourceName, sourceUrl, scope.scope(), scope.city(), now, now);
                    if (!sourceUrl.isBlank()) jdbcTemplate.update("UPDATE kb_document SET source_url = ? WHERE id = ? AND created_by = 'system-init' AND content = ? AND title = ? AND (source_url IS NULL OR source_url = '')", sourceUrl, id, content, title);
                }
                log.info("[KnowledgeDB] 知识文档已核对：本次新增 {} 条记录", inserted);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化知识文档种子数据失败", e);
        }
    }

    private String requiredText(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null || String.valueOf(value).isBlank())
            throw new IllegalArgumentException("知识文档种子缺少必填字段: " + key);
        return String.valueOf(value).trim();
    }

    private String safeSourceUrl(Object raw) {
        try {
            var uri = java.net.URI.create(String.valueOf(raw == null ? "" : raw).trim());
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isBlank() && uri.getUserInfo() == null ? uri.toString() : "";
        } catch (RuntimeException ignored) { return ""; }
    }

}

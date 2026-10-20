package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** JDBC adapter for the curated attraction catalog. */
@Repository
public class JdbcAttractionCatalogAdapter implements AttractionCatalogPort {
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcAttractionCatalogAdapter(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                        ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Attraction> find(String district, String category) {
        return query(null, district, category);
    }

    @Override
    public List<Attraction> findByCity(String city, String district, String category) {
        return query(normalizeCity(city), district, category);
    }

    private List<Attraction> query(String city, String district, String category) {
        StringBuilder sql = new StringBuilder("SELECT a.*, e.source_url, e.verified_at, e.verified_fields, e.aliases FROM attraction a LEFT JOIN attraction_catalog_entry e ON e.id = a.id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (city != null && !city.isBlank()) { sql.append(" AND a.city = ?"); params.add(city); }
        if (district != null && !district.isBlank()) { sql.append(" AND a.district = ?"); params.add(district.trim()); }
        if (category != null && !category.isBlank()) { sql.append(" AND a.category = ?"); params.add(category.trim()); }
        sql.append(" ORDER BY a.id ASC");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
    }

    private String normalizeCity(String city) {
        if (city == null) return "";
        return city.trim().replaceAll("\\s+", "").replaceFirst("市$", "");
    }

    @Override
    public long revision() {
        return jdbcTemplate.queryForObject("SELECT revision FROM attraction_catalog_revision WHERE id=1", Long.class);
    }

    @Override
    public Attraction findById(String id) {
        try {
            return jdbcTemplate.queryForObject("SELECT a.*, e.source_url, e.verified_at, e.verified_fields, e.aliases FROM attraction a LEFT JOIN attraction_catalog_entry e ON e.id = a.id WHERE a.id = ?", (rs, rowNum) -> mapRow(rs), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Attraction mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> tags = readTags(rs.getString("tags"));
        Map<String, Object> amapQuery = Collections.emptyMap();
        try {
            String json = rs.getString("amap_query");
            if (json != null && !json.isBlank()) amapQuery = objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ignored) { }
        String photoUrl;
        try { photoUrl = rs.getString("photo_url"); } catch (Exception ignored) { photoUrl = ""; }
        if (photoUrl == null || "null".equalsIgnoreCase(photoUrl)) photoUrl = "";
        return Attraction.builder()
                .id(rs.getString("id")).name(rs.getString("name")).displayName(rs.getString("display_name"))
                .aliases(readTags(rs.getString("aliases")))
                .city(rs.getString("city")).district(rs.getString("district")).category(rs.getString("category")).tags(tags)
                .icon(rs.getString("icon")).tone(rs.getString("tone")).location(rs.getString("location"))
                .summary(rs.getString("summary")).walk(rs.getString("walk")).duration(rs.getString("duration"))
                .indoor((Boolean) (rs.getObject("indoor") == null ? null : rs.getBoolean("indoor"))).walkDifficulty(rs.getString("walk_difficulty"))
                .ticket(rs.getString("ticket")).bestTime(rs.getString("best_time")).amapQuery(amapQuery)
                .intro(rs.getString("intro")).fit(rs.getString("fit")).accessibility(rs.getString("accessibility"))
                .environment(rs.getString("environment")).recommendedVisitMinutes((Integer) rs.getObject("recommended_visit_minutes"))
                .companionTags(readTags(rs.getString("companion_tags"))).featureTags(readTags(rs.getString("feature_tags")))
                .photoUrl(photoUrl).image(photoUrl)
                .sourceUrl(rs.getString("source_url")).verifiedAt(rs.getString("verified_at"))
                .verifiedFields(readTags(rs.getString("verified_fields"))).build();
    }

    private List<String> readTags(String json) {
        try { return json == null || json.isBlank() ? Collections.emptyList() : objectMapper.readValue(json, TAGS_TYPE); }
        catch (Exception ignored) { return Collections.emptyList(); }
    }
}

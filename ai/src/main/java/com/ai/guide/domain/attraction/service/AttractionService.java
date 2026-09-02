package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 受控文旅核心地标数据服务
 *
 * 所属领域：domain.attraction.service（文旅地标与百科服务层）
 * 架构职责：负责 24 大文旅地标的元数据持久化、带分类与区县的多维度查询、内存快速查找以及开闭园/建议时长等排程所需属性的获取。
 *
 * 核心方法与职责：
 * 1. list：按所属区县与类别筛选景点列表
 * 2. get：按景点唯一 venueId 查询景点实体
 */
@Service
public class AttractionService {

    private static final Logger log = LoggerFactory.getLogger(AttractionService.class);
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AttractionService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private Attraction mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        List<String> tags = Collections.emptyList();
        try {
            String tagsJson = rs.getString("tags");
            if (tagsJson != null && !tagsJson.isBlank()) {
                tags = objectMapper.readValue(tagsJson, TAGS_TYPE);
            }
        } catch (Exception e) {
            log.error("Failed to parse tags JSON: {}", e.getMessage());
        }

        Map<String, Object> amapQuery = Collections.emptyMap();
        try {
            String amapQueryJson = rs.getString("amap_query");
            if (amapQueryJson != null && !amapQueryJson.isBlank()) {
                amapQuery = objectMapper.readValue(amapQueryJson, MAP_TYPE);
            }
        } catch (Exception e) {
            log.error("Failed to parse amapQuery JSON: {}", e.getMessage());
        }

        List<String> companionTags = readTags(rs.getString("companion_tags"));
        List<String> featureTags = readTags(rs.getString("feature_tags"));

        return Attraction.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .displayName(rs.getString("display_name"))
                .district(rs.getString("district"))
                .category(rs.getString("category"))
                .tags(tags)
                .icon(rs.getString("icon"))
                .tone(rs.getString("tone"))
                .location(rs.getString("location"))
                .summary(rs.getString("summary"))
                .walk(rs.getString("walk"))
                .duration(rs.getString("duration"))
                .indoor(rs.getBoolean("indoor"))
                .walkDifficulty(rs.getString("walk_difficulty"))
                .ticket(rs.getString("ticket"))
                .bestTime(rs.getString("best_time"))
                .amapQuery(amapQuery)
                .intro(rs.getString("intro"))
                .fit(rs.getString("fit"))
                .accessibility(rs.getString("accessibility"))
                .environment(rs.getString("environment"))
                .recommendedVisitMinutes((Integer) rs.getObject("recommended_visit_minutes"))
                .companionTags(companionTags)
                .featureTags(featureTags)
                .build();
    }

    private List<String> readTags(String json) {
        try {
            return json == null || json.isBlank() ? Collections.emptyList() : objectMapper.readValue(json, TAGS_TYPE);
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public List<Attraction> list(String district, String category) {
        StringBuilder sql = new StringBuilder("SELECT * FROM attraction WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (district != null && !district.isBlank()) {
            sql.append(" AND district = ?");
            params.add(district.trim());
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category.trim());
        }

        sql.append(" ORDER BY id ASC");
        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    public Attraction get(String id) {
        try {
            return jdbcTemplate.queryForObject("SELECT * FROM attraction WHERE id = ?", this::mapRow, id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }
}

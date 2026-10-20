package com.ai.guide.infrastructure.dining.repository;

import com.ai.guide.domain.dining.gateway.DiningVenueCatalogPort;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** JDBC implementation of the curated dining catalog port. */
@Repository
public class JdbcDiningVenueCatalogAdapter implements DiningVenueCatalogPort {
    private static final Logger log = LoggerFactory.getLogger(JdbcDiningVenueCatalogAdapter.class);
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final List<DiningVenue> seededFallback;

    public JdbcDiningVenueCatalogAdapter(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                         ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.seededFallback = new PackagedDiningVenueCatalogAdapter(objectMapper).findAll();
    }

    @Override
    public List<DiningVenue> findAll() {
        try {
            List<DiningVenue> records = jdbcTemplate.queryForList("SELECT * FROM dining_venue").stream()
                    .map(row -> mapRow(row, objectMapper))
                    .toList();
            return records.isEmpty() ? seededFallback : records;
        } catch (RuntimeException unavailable) {
            log.warn("Dining catalog unavailable; using packaged seed asset: {}", unavailable.getMessage());
            return seededFallback;
        }
    }

    static DiningVenue mapRow(Map<String, Object> row, ObjectMapper objectMapper) {
        List<String> mealTypes = strings(row.get("meal_types"), row.get("mealTypes"), objectMapper);
        List<String> tags = strings(row.get("tags"), null, objectMapper);
        Object elderValue = first(row, "elder_friendly", "elderFriendly");
        boolean elderFriendly = elderValue == null || Boolean.TRUE.equals(elderValue)
                || elderValue instanceof Number n && n.intValue() == 1;

        return DiningVenue.builder()
                .id(text(row, "id", ""))
                .name(text(row, "name", ""))
                .district(text(row, "district", ""))
                .category(text(row, "category", ""))
                .mealTypes(mealTypes)
                .location(text(row, "location", ""))
                .specialtyDish(text(row, "specialty_dish", "specialtyDish", ""))
                .averageCost(text(row, "average_cost", "averageCost", ""))
                .duration(text(row, "duration", ""))
                .distanceDesc(text(row, "distance_desc", "distanceDesc", "距离待确认"))
                .summary(text(row, "summary", ""))
                .recommendationReason(text(row, "recommendation_reason", "recommendationReason", ""))
                .spicinessLevel(text(row, "spiciness_level", "spicinessLevel", "MILD"))
                .elderFriendly(elderFriendly)
                .tone(text(row, "tone", "gold"))
                .tags(tags)
                .build();
    }

    private static Object first(Map<String, Object> row, String key, String alternate) {
        Object value = row.get(key);
        return value == null ? row.get(alternate) : value;
    }

    private static String text(Map<String, Object> row, String key, String fallback) {
        Object value = row.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String text(Map<String, Object> row, String key, String alternate, String fallback) {
        Object value = first(row, key, alternate);
        return value == null ? fallback : String.valueOf(value);
    }

    private static List<String> strings(Object primary, Object alternate, ObjectMapper objectMapper) {
        Object value = primary == null ? alternate : primary;
        if (value instanceof List<?> items) return items.stream().map(String::valueOf).toList();
        if (value instanceof String text && !text.isBlank()) {
            if (text.startsWith("[")) {
                try {
                    return objectMapper.readValue(text, new TypeReference<List<String>>() {});
                } catch (Exception ignored) {
                    return List.of();
                }
            }
            return new ArrayList<>(Arrays.asList(text.split(",")));
        }
        return List.of();
    }
}

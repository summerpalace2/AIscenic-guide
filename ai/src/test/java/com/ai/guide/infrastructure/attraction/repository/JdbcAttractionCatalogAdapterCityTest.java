package com.ai.guide.infrastructure.attraction.repository;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSeedDataInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcAttractionCatalogAdapterCityTest {
    @Test
    void filtersCatalogByExplicitCityAndMapsCityField() {
        var source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            new KnowledgeSchemaInitializer().initialize(jdbc, true);
            new KnowledgeSeedDataInitializer().initialize(jdbc, true);
            jdbc.update("UPDATE attraction SET city = '' WHERE id = ?", "cq-hongyadong");
            new KnowledgeSeedDataInitializer().initialize(jdbc, true);
            var adapter = new JdbcAttractionCatalogAdapter(jdbc, new ObjectMapper());

            var chongqing = adapter.findByCity("重庆市", "", "");
            assertTrue(chongqing.stream().anyMatch(item -> "cq-hongyadong".equals(item.getId())));
            assertTrue(chongqing.stream().allMatch(item -> "重庆".equals(item.getCity())));
            for (String unsupportedCity : new String[]{"北京", "杭州", "西安", "武汉"}) {
                assertEquals(3, adapter.findByCity(unsupportedCity, "", "").size(), unsupportedCity);
                assertTrue(adapter.findByCity(unsupportedCity, "", "").stream().allMatch(item -> unsupportedCity.equals(item.getCity())), unsupportedCity);
                assertTrue(adapter.findByCity(unsupportedCity, "", "").stream().allMatch(item ->
                        item.getSourceUrl() != null && item.getSourceUrl().startsWith("https://")
                                && item.getVerifiedFields().contains("summary")), unsupportedCity);
            }
            assertEquals(41, chongqing.size());
            assertEquals(adapter.find("", "").size(), jdbc.queryForObject("SELECT COUNT(*) FROM attraction", Integer.class));
        } finally {
            source.destroy();
        }
    }
}

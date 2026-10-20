package com.ai.guide.infrastructure.dining.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcDiningVenueCatalogAdapterTest {
    @Test
    void mapsCatalogRowsWithoutLeakingJdbcTypesIntoDomain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList("SELECT * FROM dining_venue")).thenReturn(java.util.List.of(Map.of(
                "id", "venue-1",
                "name", "示例餐厅",
                "meal_types", "[\"LUNCH\",\"DINNER\"]",
                "tags", "[\"清淡\",\"无障碍\"]",
                "elder_friendly", 1,
                "specialty_dish", "时令菜"
        )));

        var result = new JdbcDiningVenueCatalogAdapter(jdbc, new ObjectMapper()).findAll();

        assertEquals(1, result.size());
        assertEquals("venue-1", result.get(0).getId());
        assertEquals(java.util.List.of("LUNCH", "DINNER"), result.get(0).getMealTypes());
        assertEquals(java.util.List.of("清淡", "无障碍"), result.get(0).getTags());
        assertTrue(result.get(0).getElderFriendly());
        verify(jdbc).queryForList("SELECT * FROM dining_venue");
    }
}

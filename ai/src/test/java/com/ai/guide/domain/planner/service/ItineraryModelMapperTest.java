package com.ai.guide.domain.planner.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItineraryModelMapperTest {
    @Test
    void missingLocationFieldsStayUnknownInsteadOfInventingChongqingDefaults() {
        var mapper = new ItineraryModelMapper();
        var trip = mapper.fromMap(Map.of("days", List.of(Map.of("stops", List.of(Map.of("name", "未知站点"))))));

        assertEquals("", trip.destinationCity());
        assertEquals("", trip.days().get(0).stops().get(0).district());
        assertNull(trip.days().get(0).stops().get(0).startTime());
        assertNull(trip.days().get(0).stops().get(0).duration());
        Map<?, ?> mapped = (Map<?, ?>) ((List<?>) mapper.toMap(trip).get("days")).get(0);
        Map<?, ?> mappedStop = (Map<?, ?>) ((List<?>) mapped.get("stops")).get(0);
        assertEquals("待安排", mappedStop.get("time"));
        assertEquals("待安排", mappedStop.get("duration"));
    }
}

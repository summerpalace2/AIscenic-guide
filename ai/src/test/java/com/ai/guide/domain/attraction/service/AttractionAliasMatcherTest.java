package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AttractionAliasMatcherTest {
    @Test
    void externalAliasesCannotIntroduceEntitiesAbsentFromActiveCatalog() {
        Attraction place = Attraction.builder().id("beijing-museum").name("本地展馆")
                .amapQuery(Map.of("matches", List.of("地方文博馆"))).build();
        AttractionService catalog = new AttractionService(null, null) {
            @Override public List<Attraction> list(String district, String category) { return List.of(place); }
        };
        AttractionAliasMatcher matcher = new AttractionAliasMatcher(catalog);
        assertEquals("beijing-museum", matcher.extractMentionedVenueId("去地方文博馆"));
        assertNull(matcher.extractMentionedVenueId("洪崖洞"));
        assertNull(matcher.extractMentionedVenueId("参观博物馆"));
        assertTrue(matcher.containsAnotherVenue("去地方文博馆", null));
    }

    @Test
    void preservesCuratedColloquialAliasForAnExistingEntity() {
        AttractionAliasMatcher matcher = new AttractionAliasMatcher();
        assertEquals("cq-liziba", matcher.extractMentionedVenueId("想看单轨穿楼"));
        assertFalse(matcher.containsAnotherVenue("去李子坝", "day1-liziba"));
    }
}

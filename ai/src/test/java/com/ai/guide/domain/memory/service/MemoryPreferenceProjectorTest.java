package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPreferenceProjectorTest {
    private final MemoryPreferenceProjector projector = new MemoryPreferenceProjector(new TaxonomyProvider());
    private TravelMemory memory(String content) {
        return new TravelMemory("id", "DIET", content, "CHAT_CONFIRMED", "原始对话", "CONFIRMED", 1, 1, 2);
    }

    @Test
    void equivalentDessertAndSweetPreferencesHaveOneKeyWithoutLosingTheirSource() {
        for (String text : new String[]{"偏好甜品糖水", "偏好甜品糖水与特色甜点", "喜好甜口", "喜欢甜食"}) {
            TravelMemory projected = projector.project(memory(text));
            assertEquals("SWEET", projected.preferenceKey());
            assertEquals("喜欢甜口、甜品与糖水", projected.preferenceSummary());
            assertEquals(text, projected.content());
            assertEquals("原始对话", projected.sourceRef());
            assertEquals("id", projected.id());
        }
    }

    @Test
    void spicyAndSweetAreIndependentFacetsOfTheSameDietCategory() {
        TravelMemory spicy = projector.project(memory("饮食偏好辣味，喜欢吃辣"));
        TravelMemory sweet = projector.project(memory("偏好甜品糖水"));
        assertEquals("DIET", spicy.category());
        assertEquals(sweet.category(), spicy.category());
        assertEquals("SPICY", spicy.preferenceKey());
        assertNotEquals(sweet.preferenceKey(), spicy.preferenceKey());
    }

    @Test
    void qualifiersNegationsAndUnknownFoodsNeverCollapseIntoPositiveGenericPreference() {
        for (String text : new String[]{"不喜欢甜食", "只吃无糖甜品", "喜欢甜品，但对花生过敏", "偏好少辣", "喜欢驴肉火烧", "饮食偏好麻辣、地道重庆火锅与江湖菜风味", "偏好甜口和辣口"}) {
            TravelMemory projected = projector.project(memory(text));
            assertEquals("TEXT:" + text, projected.preferenceKey());
            assertEquals(text, projected.preferenceSummary());
        }
    }
}

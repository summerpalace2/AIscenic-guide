package com.ai.guide.domain.planner.narrative;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitySpecificInterestIsolationTest {
    @Test
    void chongqingTerrainPreferenceIsOnlyAppliedForItsConfiguredCityStrategy() {
        var service = new PlanNarrativeService(new JsonTaxonomyAdapter());
        var attraction = Attraction.builder().id("transit").name("轨道交通观景点")
                .category("交通").tags(List.of("单轨")).summary("城市交通体验").build();

        var beijing = TravelConstraints.builder().destination("北京").interests(List.of("8D魔幻")).build();
        var chongqing = TravelConstraints.builder().destination("重庆").interests(List.of("8D魔幻")).build();

        assertFalse(service.buildStopReason(attraction, null, null, beijing).contains("当地地形特色"));
        assertTrue(service.buildStopReason(attraction, null, null, chongqing).contains("当地地形特色"));
    }
}

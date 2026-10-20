package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChongqingTopographyKnowledgeTest {
    private final ChongqingTopographyStrategy strategy = new ChongqingTopographyStrategy(new JsonTaxonomyAdapter());

    @Test void configuredElevationTokensClassifyMountainRidgeLowlandAndGenericStops() {
        assertEquals(ElevationTier.HIGH_ALTITUDE, strategy.resolveElevationTier(stop("南山一棵树观景台", "南岸区")));
        assertEquals(ElevationTier.UPPER_PLATEAU, strategy.resolveElevationTier(stop("鹅岭二厂文创公园", "渝中区")));
        assertEquals(ElevationTier.LOWLAND_VALLEY, strategy.resolveElevationTier(stop("洪崖洞民俗风貌区", "渝中区")));
        assertEquals(ElevationTier.STANDARD_PLAIN, strategy.resolveElevationTier(stop("三峡广场", "沙坪坝区")));
    }

    @Test void configuredRiverDistrictGroupsDetectCrossingsInBothDirections() {
        Attraction south = stop("南岸景点", "渝中区");
        Attraction north = stop("江北景点", "江北区");
        Attraction southBank = stop("南岸景点", "南岸区");
        assertEquals(BarrierCrossingType.MAJOR_RIVER_PRIMARY, strategy.detectBarrierCrossing(south, north));
        assertEquals(BarrierCrossingType.MAJOR_RIVER_PRIMARY, strategy.detectBarrierCrossing(north, south));
        assertEquals(BarrierCrossingType.MAJOR_RIVER_SECONDARY, strategy.detectBarrierCrossing(south, southBank));
        assertEquals(BarrierCrossingType.CONFLUENCE_BOTH, strategy.detectBarrierCrossing(north, southBank));
    }

    @Test void configuredRoutePairsAndPenaltiesDriveFlowAndHints() {
        Attraction mountain = stop("鹅岭二厂文创公园", "渝中区");
        Attraction river = stop("李子坝单轨穿楼", "渝中区");
        assertEquals(ElevationFlow.DOWNHILL, strategy.detectElevationFlow(mountain, river));
        assertEquals(ElevationFlow.UPHILL, strategy.detectElevationFlow(river, mountain));
        Attraction high = stop("南山一棵树观景台", "南岸区");
        Attraction low = stop("洪崖洞民俗风貌区", "渝中区");
        assertTrue(strategy.calculateZigZagPenalty(List.of(high, low), high) > 0);
        String hint = strategy.generateTransitCharacteristicHint(mountain, river, "walking");
        assertFalse(hint.isBlank(), "configured route rule should produce a navigation caveat; got: " + hint
                + "; configured rules=" + new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter()
                .getHints("chongqingTopoTransitHintRules"));
    }

    private static Attraction stop(String name, String district) {
        return Attraction.builder().id("fixture-" + name).name(name).district(district).build();
    }
}

package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.planner.amap.AmapPlannerGateway;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartialHydrationMetadataTest {
    @Test void fullyAvailableHydrationCannotCompleteAnEarlierPartialPlan() throws Exception {
        var partial = snapshot();
        partial.put("degraded",true);
        partial.put("degradationReasons",List.of("PLANNING_DEADLINE_EXCEEDED"));
        partial.put("spatialPlan",Map.of("status","PARTIAL_TIMEOUT"));
        partial.put("sourceStatus",Map.of("planning","部分完成：请求超时","fallback",true));
        refreshWithAllDynamicFieldsAvailable(partial);
        assertEquals(true,((Map<?,?>)partial.get("sourceStatus")).get("fallback"));
        assertEquals(true,partial.get("degraded"));
        assertEquals("部分完成：请求超时",((Map<?,?>)partial.get("sourceStatus")).get("planning"));
        assertEquals("PARTIAL_TIMEOUT",((Map<?,?>)partial.get("spatialPlan")).get("status"));
    }
    @Test void freshCompletePlanKeepsNormalHydrationSemantics() throws Exception {
        var complete = snapshot();
        complete.put("degraded",false);
        complete.put("degradationReasons",List.of());
        complete.put("spatialPlan",Map.of("status","COMPLETED"));
        refreshWithAllDynamicFieldsAvailable(complete);
        assertEquals(false,((Map<?,?>)complete.get("sourceStatus")).get("fallback"));
        assertEquals(false,complete.get("degraded"));
    }
    @Test void catalogCandidateShortfallSurvivesOtherwiseCompleteHydration() throws Exception {
        var incomplete = snapshot();
        incomplete.put("degraded", true);
        incomplete.put("degradationReasons", List.of("CATALOG_CANDIDATES_UNRESOLVED"));
        refreshWithAllDynamicFieldsAvailable(incomplete);
        assertEquals(true, ((Map<?, ?>) incomplete.get("sourceStatus")).get("fallback"));
        assertEquals(true, incomplete.get("degraded"));
        assertTrue(((List<?>) incomplete.get("degradationReasons")).contains("CATALOG_CANDIDATES_UNRESOLVED"));
    }
    private static Map<String,Object> snapshot() {
        return new LinkedHashMap<>(Map.of("days",List.of(Map.of("day",1,"stops",List.of(Map.of(
                "id","a","name","测试馆","location","116.4,39.9"))))));
    }
    private static void refreshWithAllDynamicFieldsAvailable(Map<String,Object> trip) throws Exception {
        var provider = mock(AmapRouteService.class); when(provider.isConfigured()).thenReturn(true);
        var gateway = new AmapPlannerGateway(provider,(com.ai.guide.domain.attraction.service.AttractionService)null,3);
        // Exercise the actual hydration projection with all one-stop POI/weather data available.
        var refresh = AmapPlannerGateway.class.getDeclaredMethod("refreshStatus",Map.class,int.class,int.class,int.class);
        refresh.setAccessible(true); refresh.invoke(gateway,trip,1,0,1);
    }
}

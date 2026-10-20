package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AddStopCityIsolationTest {
    @Test void runtimeIdAndNameUseTheSameProviderCityGuard() {
        for (boolean byId : List.of(true, false)) {
            for (String providerCity : List.of("北京市", "杭州市", "")) {
                var service = mock(AttractionService.class);
                when(service.list(null, null)).thenReturn(List.of());
                var runtime = mock(RuntimeAttractionDetailService.class);
                var poi = Attraction.builder().id("amap-poi").name("探针馆").city(providerCity).duration("60分钟").summary("").intro("").district("").build();
                when(runtime.toAttraction("amap-poi")).thenReturn(poi);
                when(runtime.searchAndBuildAttraction("探针馆", "北京")).thenReturn(poi);
                var builder = mock(ItineraryBuilderPort.class);
                when(builder.createStop(any(), anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call ->
                        new LinkedHashMap<>(Map.of("id", call.getArgument(1), "venueId", "amap-poi", "name", "探针馆", "duration", "60分钟", "time", call.getArgument(2))));
                var strategy = new AddStopAdjustmentStrategy(service, builder, mock(RouteAwarePlanner.class), mock(CandidateRankingEngine.class), new ItineraryTimeEngine(), mock(DiningCategoryGrounder.class));
                strategy.setRuntimeAttractionDetailService(runtime);
                Map<String, Object> day = new LinkedHashMap<>(Map.of("day", 1, "stops", new ArrayList<>()));
                Map<String, Object> trip = new LinkedHashMap<>(Map.of("city", "北京", "days", new ArrayList<>(List.of(day))));
                var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).rawMessage("添加探针馆").dayNumber(1)
                        .targetAttractionId(byId ? "amap-poi" : null).requestedVenueName(byId ? null : "探针馆").build();
                var context = new AdjustmentContext(null, intent, null, trip, trip, new TravelConstraints(), new LinkedHashMap<>(), new ObjectMapper());
                strategy.apply(context);
                if (providerCity.equals("北京市")) {
                    assertNull(context.getFatalError());
                    assertTrue(context.getOptionPlans().containsKey("option-1"));
                    assertEquals(1, ((List<?>) day.get("stops")).size());
                } else {
                    assertEquals("TARGET_CITY_MISMATCH", context.getFatalError().body().get("reasonCode"));
                    assertTrue(((List<?>) day.get("stops")).isEmpty());
                    verifyNoInteractions(builder);
                }
            }
        }
    }

    @Test void rejectsForeignSingleIdWithoutCreatingAStop() {
        check(PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).rawMessage("添加景点")
                .targetAttractionId("foreign").build());
    }

    @Test void rejectsMixedCityMultiStopRequestWithoutPartialAddition() {
        check(PlanAdjustmentIntent.builder().type(ConversationIntentType.ADD_STOP).rawMessage("依次加入本地探针馆和异地探针馆")
                .build());
    }

    private void check(PlanAdjustmentIntent intent) {
        var local = Attraction.builder().id("local").name("本地探针馆").city("北京").build();
        var foreign = Attraction.builder().id("foreign").name("异地探针馆").city("杭州").build();
        var service = mock(AttractionService.class);
        when(service.list(null, null)).thenReturn(List.of(local, foreign));
        when(service.list("北京", null, null)).thenReturn(List.of(local));
        when(service.get("foreign")).thenReturn(foreign);
        var builder = mock(ItineraryBuilderPort.class);
        var strategy = new AddStopAdjustmentStrategy(service, builder, mock(RouteAwarePlanner.class),
                mock(CandidateRankingEngine.class), new ItineraryTimeEngine(), mock(DiningCategoryGrounder.class));
        Map<String, Object> trip = new LinkedHashMap<>(Map.of("city", "北京", "days", new ArrayList<>()));
        var context = new AdjustmentContext(null, intent, null, trip, new LinkedHashMap<>(trip), new TravelConstraints(),
                new LinkedHashMap<>(Map.of("local", local, "foreign", foreign)), new ObjectMapper());
        strategy.apply(context);
        assertNotNull(context.getFatalError());
        assertEquals("TARGET_CITY_MISMATCH", context.getFatalError().body().get("reasonCode"));
        assertTrue(context.getOptionPlans().isEmpty());
        assertEquals(List.of(), trip.get("days"));
        verifyNoInteractions(builder);
    }
}

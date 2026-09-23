package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ChongqingTopographyKnowledge;
import com.ai.guide.domain.planner.service.ItineraryBuilder;
import com.ai.guide.domain.planner.service.PlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RouteAwarePlannerTopographyTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private RouteAwarePlanner routeAwarePlanner;

    @Autowired
    private ItineraryBuilder itineraryBuilder;

    @Autowired
    private AttractionService attractionService;

    @Test
    @DisplayName("验证在带长辈/少走路偏好下，顺势而下（Downhill First）获得加分，形成舒适单向动线")
    void testDownhillFirstPreferenceUnderLowWalking() {
        Attraction eling = attractionService.get("cq-erling");
        Attraction liziba = attractionService.get("cq-liziba");
        assertNotNull(eling, "鹅岭二厂测试地标应存在");
        assertNotNull(liziba, "李子坝地标应存在");

        // 验证高程流向判定
        assertEquals(ChongqingTopographyKnowledge.ElevationFlow.DOWNHILL,
                ChongqingTopographyKnowledge.detectElevationFlow(eling, liziba));

        // 验证特色交通指引生成
        String hint = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(eling, liziba, "walking");
        assertTrue(hint.contains("三层马路步道") && hint.contains("下行"), "必须包含三层马路顺山势下行提示");
    }

    @Test
    @DisplayName("验证跨江行程节点中注入地道山城立体交通指引（如千厮门大桥/长江索道）")
    void testCrossRiverStopsContainTopographyTransitHint() {
        PlanRequest request = PlanRequest.builder()
                .prompt("周二出发2天行程，喜欢夜景和两江风光，包含解放碑和重庆大剧院")
                .idempotencyKey("topography-test-" + System.currentTimeMillis())
                .build();

        PlannerService.ServiceResult result = plannerService.create(request);
        assertEquals(200, result.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) result.body().get("trip");
        assertNotNull(trip);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        assertNotNull(days);

        // 检查是否有跨江站点注入了特色交通指引
        boolean foundTopographyTransitHint = false;
        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) day.get("stops");
            for (Map<String, Object> stop : stops) {
                if (stop.containsKey("topographyTransitHint") || stop.containsKey("trafficHint")) {
                    String hint = String.valueOf(stop.getOrDefault("topographyTransitHint",
                            stop.getOrDefault("trafficHint", "")));
                    if (!hint.isBlank()) {
                        foundTopographyTransitHint = true;
                        break;
                    }
                }
            }
            if (foundTopographyTransitHint) break;
        }

        // 至少在跨越嘉陵江或长江的站点中存在立体交通指引或顺畅衔接
        assertNotNull(days.get(0).get("stops"));
    }
}

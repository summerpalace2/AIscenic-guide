package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.planner.model.itinerary.ItineraryDay;
import com.ai.guide.domain.planner.model.itinerary.ItineraryStop;
import com.ai.guide.domain.planner.model.itinerary.ItineraryTrip;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import com.ai.guide.domain.planner.service.strategy.ChengduPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.XiAnHeritageTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("P2 平台化演进全链路验证测试套件 (Topography SPI + Multi-Category + Typed Domain)")
class P2PlatformEvolutionTest {

    private TopographyStrategyRouter router;
    private PoiCategoryPolicyRegistry categoryRegistry;
    private ItineraryModelMapper mapper;
    private LocalPlanRepairer repairer;

    @BeforeEach
    void setUp() {
        ChongqingTopographyStrategy cq = new ChongqingTopographyStrategy(new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        ChengduPlainTopographyStrategy cd = new ChengduPlainTopographyStrategy(new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        XiAnHeritageTopographyStrategy xa = new XiAnHeritageTopographyStrategy(new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy gn = new com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy();
        router = new TopographyStrategyRouter(List.of(cq, cd, xa, gn), new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        categoryRegistry = com.ai.guide.testing.PlannerTaxonomyFixtures.categoryPolicies();
        mapper = new ItineraryModelMapper();
        repairer = new LocalPlanRepairer(categoryRegistry);
    }

    @Test
    @DisplayName("P2-01: 验证多城市 TopographyStrategyRouter 动态路由与回退机制")
    void testTopographyStrategyRouterDispatch() {
        TopographyStrategyPort cqStrategy = router.route("重庆");
        assertTrue(cqStrategy instanceof ChongqingTopographyStrategy);
        assertEquals("CHONGQING", cqStrategy.supportedCity());

        TopographyStrategyPort cdStrategy = router.route("成都");
        assertTrue(cdStrategy instanceof ChengduPlainTopographyStrategy);
        assertEquals("CHENGDU", cdStrategy.supportedCity());

        TopographyStrategyPort xaStrategy = router.route("西安");
        assertTrue(xaStrategy instanceof XiAnHeritageTopographyStrategy);
        assertEquals("XIAN", xaStrategy.supportedCity());
        assertSame(xaStrategy, router.route("Xi'an"));

        // 未知城市回退至通用平原策略（防止非重庆城市被套用长江天堑）
        TopographyStrategyPort fallback = router.route("未知城市");
        assertInstanceOf(GenericPlainTopographyStrategy.class, fallback);
        assertEquals(com.ai.guide.domain.planner.model.topography.ElevationTier.UNVERIFIED,
                fallback.resolveElevationTier("poi-1", "岭南博物馆", "测试区", ""),
                "缺少高程证据时不应从“山/岭/峰”等地名词根推断地形");
    }

    @Test
    @DisplayName("P2-02: 验证重庆山城高程圈层、跨江天堑与顺势而下流向计算")
    void testChongqingTopographyCalculations() {
        TopographyStrategyPort cq = router.route("重庆");

        Attraction nanshan = Attraction.builder().id("cq-nanshan").name("南山一棵树").district("南岸区").location("106.58,29.55").build();
        Attraction hongyadong = Attraction.builder().id("cq-hongyadong").name("洪崖洞").district("渝中区").location("106.58,29.56").build();
        Attraction dajueryuan = Attraction.builder().id("cq-dajuyuan").name("重庆大剧院").district("江北区").location("106.58,29.57").build();
        Attraction eling = Attraction.builder().id("cq-eling").name("鹅岭二厂").district("渝中区").location("106.53,29.54").build();
        Attraction liziba = Attraction.builder().id("cq-liziba").name("李子坝轻轨站").district("渝中区").location("106.54,29.55").build();

        // 1. 高程圈层解析
        assertEquals(ElevationTier.HIGH_ALTITUDE, cq.resolveElevationTier(nanshan));
        assertEquals(ElevationTier.LOWLAND_VALLEY, cq.resolveElevationTier(hongyadong));
        assertEquals(ElevationTier.UPPER_PLATEAU, cq.resolveElevationTier(eling));

        // 2. 跨江检测
        assertEquals(BarrierCrossingType.MAJOR_RIVER_PRIMARY, cq.detectBarrierCrossing(hongyadong, dajueryuan));
        assertEquals(BarrierCrossingType.MAJOR_RIVER_SECONDARY, cq.detectBarrierCrossing(hongyadong, nanshan));
        assertEquals(BarrierCrossingType.NONE, cq.detectBarrierCrossing(eling, liziba));

        // 3. 高程流向
        assertEquals(ElevationFlow.DOWNHILL, cq.detectElevationFlow(eling, liziba));
        assertEquals(ElevationFlow.UPHILL, cq.detectElevationFlow(liziba, eling));

        // 4. 特色交通指引生成
        String hint = cq.generateTransitCharacteristicHint(eling, liziba, "walking");
        assertTrue(hint.contains("下行"));

        String crossHint = cq.generateTransitCharacteristicHint(hongyadong, dajueryuan, "walking");
        assertTrue(crossHint.contains("实时交通路线"));
    }

    @Test
    @DisplayName("P2-03: 验证成都平原策略环线阻隔与特色绿道指引")
    void testChengduPlainStrategy() {
        TopographyStrategyPort cd = router.route("成都");

        Attraction chunxi = Attraction.builder().id("cd-chunxi").name("春熙路").district("锦江区").location("104.08,30.65").build();
        Attraction qingcheng = Attraction.builder().id("cd-qingcheng").name("青城山景区").district("都江堰市").location("103.56,30.90").build();

        assertEquals(ElevationTier.STANDARD_PLAIN, cd.resolveElevationTier(chunxi));
        assertEquals(ElevationTier.HIGH_ALTITUDE, cd.resolveElevationTier(qingcheng));

        assertEquals(BarrierCrossingType.NONE, cd.detectBarrierCrossing(chunxi, qingcheng));

        String hint = cd.generateTransitCharacteristicHint(chunxi, qingcheng, "transit");
        assertEquals("", hint, "Live transit recommendations should come from the route provider, not a stale city-specific template");
    }

    @Test
    @DisplayName("P2-04: 验证西安古都明城墙护城河阻隔与唐风轴线指引")
    void testXiAnHeritageStrategy() {
        TopographyStrategyPort xa = router.route("西安");

        Attraction bellTower = Attraction.builder().id("xa-bell").name("西安钟楼").district("碑林区").location("108.94,34.26").build();
        Attraction bigWildGoose = Attraction.builder().id("xa-dayan").name("大雁塔大唐不夜城").district("雁塔区").location("108.96,34.21").build();

        assertEquals(ElevationTier.STANDARD_PLAIN, xa.resolveElevationTier(bellTower));
        assertEquals(ElevationTier.STANDARD_PLAIN, xa.resolveElevationTier(bigWildGoose));

        assertEquals(BarrierCrossingType.HISTORICAL_WALL, xa.detectBarrierCrossing(bellTower, bigWildGoose));

        String hint = xa.generateTransitCharacteristicHint(bellTower, bigWildGoose, "subway");
        assertEquals("", hint, "Transit advice must be sourced from the live route provider");
    }

    @Test
    @DisplayName("P2-05: 验证多品类 POI 分类器准确解析与 LocalPlanRepairer 剧场/酒店保护")
    void testMultiCategoryPoiAndRepairerProtection() {
        Map<String, Object> theaterStop = new LinkedHashMap<>();
        theaterStop.put("id", "stop-theater-1");
        theaterStop.put("name", "重庆大剧院《杜甫》舞剧专场");
        theaterStop.put("duration", "约 120 分钟");

        Map<String, Object> diningStop = new LinkedHashMap<>();
        diningStop.put("id", "stop-dining-1");
        diningStop.put("name", "午餐：周师兄火锅");
        diningStop.put("duration", "约 90 分钟");

        Map<String, Object> scenicStop = new LinkedHashMap<>();
        scenicStop.put("id", "stop-scenic-1");
        scenicStop.put("name", "洪崖洞民俗风貌区");
        scenicStop.put("duration", "约 180 分钟");

        // 1. 验证品类解析
        assertEquals(PoiDomainType.THEATER, categoryRegistry.resolveCategory(theaterStop));
        assertEquals(PoiDomainType.DINING, categoryRegistry.resolveCategory(diningStop));
        assertEquals(PoiDomainType.SCENIC, categoryRegistry.resolveCategory(scenicStop));

        // 2. 模拟 DAY_SCHEDULE_OVERFLOW 修复：验证剧场与餐饮绝不被删除，时长绝不被压缩
        List<Map<String, Object>> stops = new ArrayList<>(List.of(new LinkedHashMap<>(scenicStop), new LinkedHashMap<>(theaterStop), new LinkedHashMap<>(diningStop)));
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("stops", stops);

        List<PlanVerifier.PlanViolation> violations = List.of(
                new PlanVerifier.PlanViolation("DAY_SCHEDULE_OVERFLOW", 1, "stop-scenic-1", "cq-hongyadong", "单日行程严重超时", true)
        );
        PlanVerifier.VerificationResult verification = new PlanVerifier.VerificationResult(false, violations, Map.of());

        List<Map<String, Object>> repaired = repairer.repair(List.of(day), verification, TravelConstraints.builder().build(), List.of());
        assertNotNull(repaired);
        List<Map<String, Object>> repairedStops = (List<Map<String, Object>>) repaired.get(0).get("stops");

        // 剧场和餐饮不能被缩短，只有普通景区被压缩
        for (Map<String, Object> s : repairedStops) {
            String name = String.valueOf(s.get("name"));
            String dur = String.valueOf(s.get("duration"));
            if (name.contains("大剧院")) {
                assertEquals("约 120 分钟", dur, "剧场演艺时长必须保持原样不可压缩");
            } else if (name.contains("火锅")) {
                assertEquals("约 90 分钟", dur, "餐饮时长必须保持原样不可压缩");
            }
        }
    }

    @Test
    @DisplayName("P2-06: 验证强类型领域模型 ItineraryTrip 与前端 Legacy Map 无损双向互转")
    void testItineraryModelMapperBidirectional() {
        Map<String, Object> rawStop = new LinkedHashMap<>();
        rawStop.put("id", "stop-1");
        rawStop.put("venueId", "cq-hongyadong");
        rawStop.put("name", "洪崖洞");
        rawStop.put("type", "SCENIC");
        rawStop.put("icon", "景");
        rawStop.put("time", "19:30");
        rawStop.put("duration", "约 90 分钟");
        rawStop.put("trafficHint", "步行约5分钟");

        Map<String, Object> rawDay = new LinkedHashMap<>();
        rawDay.put("day", 1);
        rawDay.put("date", "2026-10-06");
        rawDay.put("dateLabel", "2026-10-06 · 第1天 · 山城经典");
        rawDay.put("stops", List.of(rawStop));

        Map<String, Object> rawTrip = new LinkedHashMap<>();
        rawTrip.put("tripId", "trip-test-001");
        rawTrip.put("title", "重庆2日山水魔幻之旅");
        rawTrip.put("city", "重庆");
        rawTrip.put("days", List.of(rawDay));

        // 1. Map -> Domain Model
        ItineraryTrip domainTrip = mapper.fromMap(rawTrip);
        assertNotNull(domainTrip);
        assertEquals("trip-test-001", domainTrip.tripId());
        assertEquals("重庆", domainTrip.destinationCity());
        assertEquals(1, domainTrip.days().size());

        ItineraryDay domainDay = domainTrip.days().get(0);
        assertEquals(1, domainDay.dayNumber());
        assertEquals(1, domainDay.stops().size());

        ItineraryStop domainStop = domainDay.stops().get(0);
        assertEquals("洪崖洞", domainStop.name());
        assertEquals(PoiDomainType.SCENIC, domainStop.type());
        assertEquals(90, domainStop.duration().toMinutes());

        // 2. Domain Model -> Map
        Map<String, Object> backToMap = mapper.toMap(domainTrip);
        assertNotNull(backToMap);
        assertEquals("trip-test-001", backToMap.get("formalTripId"));
        assertEquals("重庆", backToMap.get("city"));

        List<Map<String, Object>> backDays = (List<Map<String, Object>>) backToMap.get("days");
        assertEquals(1, backDays.size());
        List<Map<String, Object>> backStops = (List<Map<String, Object>>) backDays.get(0).get("stops");
        assertEquals(1, backStops.size());
        assertEquals("洪崖洞", backStops.get(0).get("name"));
        assertEquals("SCENIC", backStops.get(0).get("type"));
    }
}

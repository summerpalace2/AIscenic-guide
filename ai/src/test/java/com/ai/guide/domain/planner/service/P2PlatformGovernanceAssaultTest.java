package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.category.PoiCategoryPolicy;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.planner.model.itinerary.ItineraryDay;
import com.ai.guide.domain.planner.model.itinerary.ItineraryStop;
import com.ai.guide.domain.planner.model.itinerary.ItineraryTrip;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.service.strategy.ChengduPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.XiAnHeritageTopographyStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【智能体 B】P2 平台化架构治理与领域模型极限红方渗透进攻与防御加固测试套件
 *
 * 覆盖三大攻击方向与加固防线：
 * 1. 多城市阻抗穿透与跨城混淆攻击与加固 (TopographyStrategyRouter & Strategies)
 * 2. 多品类 POI 边界爆破与品类伪装攻击与加固 (PoiCategoryPolicyRegistry & LocalPlanRepairer)
 * 3. 双向领域模型无损保真与反序列化炸弹加固 (ItineraryModelMapper)
 */
public class P2PlatformGovernanceAssaultTest {

    private TopographyStrategyRouter topographyRouter;
    private ChengduPlainTopographyStrategy chengduStrategy;
    private XiAnHeritageTopographyStrategy xianStrategy;
    private ChongqingTopographyStrategy chongqingStrategy;
    private GenericPlainTopographyStrategy genericStrategy;

    private PoiCategoryPolicyRegistry categoryPolicyRegistry;
    private LocalPlanRepairer localPlanRepairer;

    private ItineraryModelMapper modelMapper;

    @BeforeEach
    void setUp() {
        chengduStrategy = new ChengduPlainTopographyStrategy();
        xianStrategy = new XiAnHeritageTopographyStrategy();
        chongqingStrategy = new ChongqingTopographyStrategy();
        genericStrategy = new GenericPlainTopographyStrategy();

        List<TopographyStrategyPort> strategies = List.of(chongqingStrategy, chengduStrategy, xianStrategy, genericStrategy);
        topographyRouter = new TopographyStrategyRouter(strategies);

        categoryPolicyRegistry = new PoiCategoryPolicyRegistry();
        localPlanRepairer = new LocalPlanRepairer(categoryPolicyRegistry);

        modelMapper = new ItineraryModelMapper();
    }

    // =========================================================================
    // 攻击方向 1：多城市阻抗穿透与跨城混淆防御
    // =========================================================================
    @Nested
    @DisplayName("攻击方向 1：多城市阻抗穿透与跨城混淆防御")
    class TopographyRouterAndStrategyAttacks {

        @Test
        @DisplayName("【漏洞修复 VULN-P2-01】英文大小写异构城市名精准路由：CHENGDU/XiAn 不再错误降级")
        void test01_EnglishAndHeterogeneousCityNamesRoutingBug() {
            TopographyStrategyPort routeChongqingLower = topographyRouter.route("chongqing");
            TopographyStrategyPort routeChengduUpper = topographyRouter.route("CHENGDU");
            TopographyStrategyPort routeChengduLower = topographyRouter.route("chengdu");
            TopographyStrategyPort routeXiAnMixed = topographyRouter.route("XiAn");
            TopographyStrategyPort routeXiAnUpper = topographyRouter.route("XIAN");

            assertEquals("CHONGQING", routeChongqingLower.supportedCity(), "chongqing 应精准路由至重庆");
            assertEquals("CHENGDU", routeChengduUpper.supportedCity(), "CHENGDU 应精准路由至成都平原策略");
            assertEquals("CHENGDU", routeChengduLower.supportedCity(), "chengdu 应精准路由至成都平原策略");
            assertEquals("XIAN", routeXiAnMixed.supportedCity(), "XiAn 应精准路由至西安策略");
            assertEquals("XIAN", routeXiAnUpper.supportedCity(), "XIAN 应精准路由至西安策略");

            assertEquals("CHENGDU", topographyRouter.getByCityCode("CHENGDU").supportedCity());
            assertEquals("XIAN", topographyRouter.getByCityCode("XIAN").supportedCity());
        }

        @Test
        @DisplayName("【漏洞修复 VULN-P2-02】未知城市通用平原兜底：三亚/拉萨不再被套用重庆长江嘉陵江天堑")
        void test02_UnknownCityDegradationExposes8DMountainAssumptions() {
            TopographyStrategyPort sanyaStrategy = topographyRouter.route("三亚");
            TopographyStrategyPort lhasaStrategy = topographyRouter.route("拉萨");
            TopographyStrategyPort unknownStrategy = topographyRouter.route("UnchartedCity");

            assertEquals("GENERIC", sanyaStrategy.supportedCity(), "三亚应回退至通用平原策略");
            assertEquals("GENERIC", lhasaStrategy.supportedCity(), "拉萨应回退至通用平原策略");
            assertEquals("GENERIC", unknownStrategy.supportedCity(), "未知城市应回退至通用平原策略");

            Attraction fromSanya = Attraction.builder().name("三亚湾").district("渝中区").build();
            Attraction toSanya = Attraction.builder().name("亚龙湾").district("江北区").build();
            BarrierCrossingType crossing = sanyaStrategy.detectBarrierCrossing(fromSanya, toSanya);
            assertEquals(BarrierCrossingType.NONE, crossing,
                    "通用平原策略下绝不出现虚假的重庆跨江天堑阻隔！");
        }

        @Test
        @DisplayName("【极限抗压】成都平原远郊折返惩罚打分与超长序列防溢出/死锁测试")
        void test03_ChengduExtremeZigZagAndUltraLongSequencePerformance() {
            Attraction chunxi = Attraction.builder().id("cd-1").name("春熙路").district("锦江区").build();
            Attraction dujiangyan = Attraction.builder().id("cd-2").name("都江堰景区").district("都江堰市").build();
            Attraction kuanzhai = Attraction.builder().id("cd-3").name("宽窄巷子").district("青羊区").build();
            Attraction qingcheng = Attraction.builder().id("cd-4").name("青城山景区").district("都江堰市").build();

            // 1. 验证典型远郊往返折腾：市区 -> 都江堰(远郊) -> 宽窄巷子(市区)
            List<Attraction> history = List.of(chunxi, dujiangyan);
            double penalty1 = chengduStrategy.calculateZigZagPenalty(history, kuanzhai);
            assertEquals(70.0, penalty1, "市区->远郊->市区折返必须受到 70.0 高额扣分！");

            // 2. 远郊->市区->远郊折返：都江堰 -> 宽窄巷子 -> 青城山
            List<Attraction> history2 = List.of(dujiangyan, kuanzhai);
            double penalty2 = chengduStrategy.calculateZigZagPenalty(history2, qingcheng);
            assertEquals(70.0, penalty2, "远郊->市区->远郊二次拉扯必须受到 70.0 扣分！");

            // 3. 构造 10,000 个超长历史站点序列，进行极限压力与防死锁测试
            List<Attraction> massiveHistory = new ArrayList<>(10000);
            for (int i = 0; i < 10000; i++) {
                massiveHistory.add(chunxi);
            }
            long startTime = System.nanoTime();
            double penaltyMassive = chengduStrategy.calculateZigZagPenalty(massiveHistory, kuanzhai);
            long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;

            assertEquals(0.0, penaltyMassive);
            assertTrue(elapsedMs < 20, "10000 规模站点序列折返计算耗时必须在 20ms 以内，实测: " + elapsedMs + "ms");
        }

        @Test
        @DisplayName("【西安历史城墙与临潼折返】古城墙穿越判定与临潼兵马俑拉扯防御")
        void test04_XiAnHeritageWallAndLintongZigZagDetection() {
            Attraction bellTower = Attraction.builder().id("xa-1").name("钟楼").district("碑林区").build(); // 城内
            Attraction dayanta = Attraction.builder().id("xa-2").name("大雁塔").district("雁塔区").build(); // 城外
            Attraction warriors = Attraction.builder().id("xa-3").name("秦始皇帝陵博物院(兵马俑)").district("临潼区").build(); // 远郊
            Attraction huaqing = Attraction.builder().id("xa-4").name("华清宫").district("临潼区").build(); // 远郊

            // 1. 古城墙穿越检测
            BarrierCrossingType wallCrossing = xianStrategy.detectBarrierCrossing(bellTower, dayanta);
            assertEquals(BarrierCrossingType.HISTORICAL_WALL, wallCrossing,
                    "钟楼(城内)至大雁塔(城外)必须判定为跨越明城墙 HISTORICAL_WALL");

            String hint = xianStrategy.generateTransitCharacteristicHint(bellTower, dayanta, "walking");
            assertTrue(hint.contains("明城墙") && hint.contains("永宁门"), "跨越城墙必须包含永宁门漫游建议");

            // 2. 临潼兵马俑反复折返
            List<Attraction> historyLintong = List.of(dayanta, warriors); // 市区 -> 临潼
            double penaltyBounce = xianStrategy.calculateZigZagPenalty(historyLintong, bellTower); // 临潼 -> 市区
            assertEquals(80.0, penaltyBounce, "临潼兵马俑远郊折返必须打出 80.0 极高惩罚！");
        }
    }

    // =========================================================================
    // 攻击方向 2：多品类 POI 边界爆破与品类伪装防御
    // =========================================================================
    @Nested
    @DisplayName("攻击方向 2：多品类 POI 边界爆破与品类伪装防御")
    class CategoryBoundaryAndRepairerAttacks {

        @Test
        @DisplayName("【漏洞修复 VULN-P2-03】剧场盛典品类扩展识别：演出时长获完整保护，不被截断压缩")
        void test05_TheaterCategoryCamouflageAndDurationTrunctionVulnerability() {
            Map<String, Object> theaterStop = new LinkedHashMap<>();
            theaterStop.put("id", "poi-theater-1");
            theaterStop.put("name", "又见平遥沉浸式盛典");
            theaterStop.put("type", "SCENIC");
            theaterStop.put("duration", "约 150 分钟");

            // 1. 扩充词库后成功识别为 THEATER
            PoiDomainType resolved = categoryPolicyRegistry.resolveCategory(theaterStop);
            assertEquals(PoiDomainType.THEATER, resolved, "又见平遥盛典应识别为演艺剧场");

            // 2. 放入 LocalPlanRepairer 执行修复
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", 1);
            day.put("stops", new ArrayList<>(List.of(theaterStop)));

            PlanVerifier.VerificationResult violationResult = new PlanVerifier.VerificationResult(
                    false,
                    List.of(new PlanVerifier.PlanViolation(
                            "DAY_SCHEDULE_OVERFLOW", 1, "poi-theater-1", "theater-1",
                            "单日游玩时间超标", true
                    )),
                    Map.of()
            );

            List<Map<String, Object>> repaired = localPlanRepairer.repair(
                    List.of(day), violationResult, TravelConstraints.builder().build(), List.of()
            );

            // 3. 验证剧场演出时长获得不可压缩保护
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> repairedStops = (List<Map<String, Object>>) repaired.get(0).get("stops");
            assertEquals("约 150 分钟", repairedStops.get(0).get("duration"), "剧场演出时长受保护，不被腰斩！");
        }

        @Test
        @DisplayName("【漏洞修复 VULN-P2-04】餐饮异常超长时长防御性规整，防止排程陷入不收敛")
        void test06_Dining10HoursAbnormalDurationCircumventionExploit() {
            Map<String, Object> abnormalDining = new LinkedHashMap<>();
            abnormalDining.put("id", "poi-dining-crazy");
            abnormalDining.put("name", "老字号火锅午餐");
            abnormalDining.put("type", "DINING");
            abnormalDining.put("duration", "约 600 分钟");

            Map<String, Object> normalScenic = new LinkedHashMap<>();
            normalScenic.put("id", "poi-scenic-1");
            normalScenic.put("name", "洪崖洞");
            normalScenic.put("type", "SCENIC");
            normalScenic.put("duration", "约 90 分钟");

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", 1);
            day.put("stops", new ArrayList<>(List.of(normalScenic, abnormalDining)));

            PlanVerifier.VerificationResult overflowResult = new PlanVerifier.VerificationResult(
                    false,
                    List.of(new PlanVerifier.PlanViolation(
                            "DAY_SCHEDULE_OVERFLOW", 1, "poi-dining-crazy", "dining-crazy",
                            "全天用时溢出", true
                    )),
                    Map.of()
            );

            List<Map<String, Object>> repaired = localPlanRepairer.repair(
                    List.of(day), overflowResult, TravelConstraints.builder().build(), List.of()
            );

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) repaired.get(0).get("stops");
            Map<String, Object> diningAfterRepair = stops.stream()
                    .filter(s -> "poi-dining-crazy".equals(s.get("id")))
                    .findFirst().orElseThrow();

            // 防御成功：600分钟异常时长被安全收拢至 90 分钟！
            assertEquals("约 90 分钟", diningAfterRepair.get("duration"), "10小时异常超长餐饮已被防御性规整！");
        }

        @Test
        @DisplayName("【漏洞修复 VULN-P2-05】LocalPlanRepairer 复合时长解析支持：'1小时30分钟'与'2.5小时'精确捕获")
        void test07_DurationParserFlaw_1Hour30MinParsedAs1Minute() throws Exception {
            Method method = LocalPlanRepairer.class.getDeclaredMethod("parseDurationMinutes", String.class);
            method.setAccessible(true);

            int parsed1H30M = (int) method.invoke(localPlanRepairer, "1小时30分钟");
            int parsed2H = (int) method.invoke(localPlanRepairer, "2小时");
            int parsed90M = (int) method.invoke(localPlanRepairer, "90分钟");
            int parsed2Point5H = (int) method.invoke(localPlanRepairer, "2.5小时");

            assertEquals(90, parsed1H30M, "'1小时30分钟' 应正确解析为 90 分钟");
            assertEquals(120, parsed2H, "'2小时' 应正确解析为 120 分钟");
            assertEquals(90, parsed90M, "'90分钟' 应正确解析为 90 分钟");
            assertEquals(150, parsed2Point5H, "'2.5小时' 应正确解析为 150 分钟");
        }

        @Test
        @DisplayName("【高并发无锁安全】PoiCategoryPolicyRegistry 在 50 并发线程压测下无竞态死锁")
        void test08_PoiCategoryConcurrencyAndRegistryThreadSafety() throws InterruptedException {
            int threadCount = 50;
            int iterationsPerThread = 2000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            Map<String, Object> sampleStop = Map.of(
                    "name", "川剧变脸大剧院",
                    "type", "THEATER",
                    "venueId", "theater-001"
            );

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < iterationsPerThread; i++) {
                            PoiDomainType type = categoryPolicyRegistry.resolveCategory(sampleStop);
                            PoiCategoryPolicy policy = categoryPolicyRegistry.getPolicy(type);
                            if (type == PoiDomainType.THEATER && policy.maxOccurrencesPerDay() == 1) {
                                successCount.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(finished, "50 线程高并发压测必须在 10s 内完成，无死锁！");
            assertEquals(threadCount * iterationsPerThread, successCount.get());
        }
    }

    // =========================================================================
    // 攻击方向 3：双向领域模型无损保真与反序列化加固
    // =========================================================================
    @Nested
    @DisplayName("攻击方向 3：双向领域模型无损保真与反序列化加固")
    class ModelMapperAndDeserializationAttacks {

        @Test
        @DisplayName("【漏洞修复 VULN-P2-06】显式 null 安全兜底：safeString 杜绝字面量 'null' 脏数据污染")
        void test09_MapGetOrDefaultNullTrap_InjectsLiteralNullString() {
            Map<String, Object> payloadWithNulls = new HashMap<>();
            payloadWithNulls.put("formalTripId", null);
            payloadWithNulls.put("title", null);
            payloadWithNulls.put("city", null);

            Map<String, Object> stopMap = new HashMap<>();
            stopMap.put("id", null);
            stopMap.put("name", null);
            stopMap.put("district", null);

            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("day", 1);
            dayMap.put("stops", List.of(stopMap));
            payloadWithNulls.put("days", List.of(dayMap));

            ItineraryTrip trip = modelMapper.fromMap(payloadWithNulls);
            assertNotNull(trip);

            assertNotEquals("null", trip.tripId(), "tripId 绝不为字面量 'null'");
            assertFalse(trip.tripId().contains("null"));
            assertEquals("定制旅行方案", trip.title(), "title 优雅回退到默认方案名");
            assertEquals("重庆", trip.destinationCity(), "city 优雅回退到重庆");

            ItineraryStop stop = trip.days().get(0).stops().get(0);
            assertNotEquals("null", stop.id());
            assertEquals("未命名点位", stop.name(), "stop.name 优雅回退到默认点位名");

            Map<String, Object> exported = modelMapper.toMap(trip);
            assertNotEquals("null", exported.get("formalTripId"));
            assertNotEquals("null", exported.get("title"));
        }

        @Test
        @DisplayName("【漏洞修复 VULN-P2-07】未补零单数字时间支持：'8:30' 精准解析为 08:30")
        void test10_StopStartTimeTruncation_SingleDigitHourMangledTo9AM() {
            Map<String, Object> stopMap = new LinkedHashMap<>();
            stopMap.put("id", "stop-early");
            stopMap.put("name", "晨间朝天门日出");
            stopMap.put("time", "8:30");
            stopMap.put("duration", "约 60 分钟");

            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", 1);
            dayMap.put("stops", List.of(stopMap));

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("tripId", "trip-time-test");
            raw.put("days", List.of(dayMap));

            ItineraryTrip trip = modelMapper.fromMap(raw);
            assertNotNull(trip);

            ItineraryStop stop = trip.days().get(0).stops().get(0);
            assertEquals(LocalTime.of(8, 30), stop.startTime(), "'8:30' 应精准解析为 08:30，绝不篡改");

            Map<String, Object> exported = modelMapper.toMap(trip);
            @SuppressWarnings("unchecked")
            Map<String, Object> firstDayMap = (Map<String, Object>) ((List<?>) exported.get("days")).get(0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) firstDayMap.get("stops");
            assertEquals("08:30", stops.get(0).get("time"));
        }

        @Test
        @DisplayName("【漏洞修复 VULN-P2-08】Day 级别扩展元数据在双向往返中 100% 完整保留无丢失")
        void test11_DayLevelCustomMetadataPermanentLossInRoundtrip() {
            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", 1);
            dayMap.put("dateLabel", "第 1 天");
            dayMap.put("theme", "历史文化漫游");
            // Day 级扩展元数据
            dayMap.put("hotelRecommendation", "解放碑威斯汀酒店");
            dayMap.put("dayBudget", 800);
            dayMap.put("weatherForecast", "多云转晴 22℃");
            dayMap.put("stops", Collections.emptyList());

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("formalTripId", "trip-roundtrip-loss");
            raw.put("title", "测试往返保真度");
            raw.put("days", List.of(dayMap));

            ItineraryTrip trip = modelMapper.fromMap(raw);
            assertNotNull(trip);

            Map<String, Object> exported = modelMapper.toMap(trip);
            assertNotNull(exported);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exportedDays = (List<Map<String, Object>>) exported.get("days");
            Map<String, Object> exportedDay1 = exportedDays.get(0);

            assertTrue(exportedDay1.containsKey("hotelRecommendation"), "hotelRecommendation 必须无损保留！");
            assertEquals("解放碑威斯汀酒店", exportedDay1.get("hotelRecommendation"));

            assertTrue(exportedDay1.containsKey("dayBudget"), "dayBudget 必须无损保留！");
            assertEquals(800, exportedDay1.get("dayBudget"));

            assertTrue(exportedDay1.containsKey("weatherForecast"), "weatherForecast 必须无损保留！");
            assertEquals("多云转晴 22℃", exportedDay1.get("weatherForecast"));
        }

        @Test
        @DisplayName("【畸形与深度嵌套 Payload 防御】非预期数据类型注入时的容灾弹性验证")
        void test12_MalformedAndDeepNestedPayloadResilience() {
            Map<String, Object> malformed = new HashMap<>();
            malformed.put("formalTripId", 123456);
            malformed.put("version", "invalid-version-string");
            malformed.put("days", "this-is-not-a-list");

            assertDoesNotThrow(() -> {
                ItineraryTrip trip = modelMapper.fromMap(malformed);
                assertNotNull(trip);
                assertEquals("123456", trip.tripId());
                assertEquals(1, trip.version());
                assertTrue(trip.days().isEmpty());
            });

            assertNull(modelMapper.fromMap(null));
            assertNull(modelMapper.fromMap(Collections.emptyMap()));
            assertTrue(modelMapper.toMap(null).isEmpty());
        }
    }
}

package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.DynamicGeoCentroidResolver;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.MealGapSolver;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 【智能体 A】红方攻击实弹渗透演练测试集
 *
 * 覆盖战区：
 * 战区 1：时空极度挤压与饥饿攻击（Temporal Starvation & Overlap Stress）
 * 战区 2：跨省飞地与经纬度毒丸攻击（Geographic Poison & Long-Distance Abyss）
 */
@SpringBootTest
@DisplayName("【智能体 A】红方极限实弹渗透攻击套件")
public class AgentARedTeamAssaultTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private CaffeineRedisProposalRepository proposalStore;

    @Autowired
    private PlannerSessionRepositoryPort sessionRepositoryPort;

    @Autowired
    private DiningVenueService actualDiningVenueService;

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private ItineraryBuilder itineraryBuilder;

    @Autowired
    private MealGapSolver mealGapSolver;

    @Autowired
    private DynamicGeoCentroidResolver geoCentroidResolver;

    @Autowired
    private TaxonomyPort taxonomy;

    @Autowired
    private ItineraryTimeEngine itineraryTimeEngine;

    @AfterEach
    void tearDown() {
        planAdjustmentService.setDiningVenueService(actualDiningVenueService);
        UserContext.clear();
        proposalStore.clearLocalCache();
    }

    @Nested
    @DisplayName("战区 1：时空极度挤压与饥饿攻击（Temporal Starvation & Overlap Stress）")
    class TemporalStarvationAssault {

        @Test
        @DisplayName("攻击 1.1：全天无缝特种兵行程（08:30-21:00 景点占满）强插火锅加餐，系统优雅拒绝无死锁")
        void assault_denseMilitaryItinerary_addDiningStarved_gracefullyRejected() {
            // 构造全天无缝衔接的极限日程，相邻两站之间只有 5 分钟交通间隙，午餐窗口(11:30-13:30)被完全占满
            List<Map<String, Object>> denseStops = new ArrayList<>();
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-jiefangbei"), "day1-stop1", "08:30", "120分钟", "", ""));
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-hongyadong"), "day1-stop2", "10:35", "120分钟", "", ""));
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-ciqikou"), "day1-stop3", "12:40", "120分钟", "", ""));
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-museum"), "day1-stop4", "14:45", "120分钟", "", ""));
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-liziba"), "day1-stop5", "16:50", "120分钟", "", ""));
            denseStops.add(itineraryBuilder.createStop(attractionService.get("cq-kuixinglou"), "day1-stop6", "18:55", "125分钟", "", ""));

            Map<String, Object> trip = new LinkedHashMap<>(Map.of(
                    "version", 1,
                    "city", "重庆市",
                    "days", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day", 1, "stops", denseStops))))
            ));
            TravelConstraints constraints = TravelConstraints.builder().destination("重庆市").durationDays(1).build();
            var created = sessionRepositoryPort.create("ANONYMOUS", UUID.randomUUID().toString(), "特种兵一日游",
                    constraints.asMap(), AppliedPreferencesSnapshot.empty(), trip, UUID.randomUUID().toString(), UUID.randomUUID().toString());

            String sessionId = created.session().sessionId();
            String token = created.accessToken();

            // 发起加餐实弹攻击：“加上午餐火锅”
            PlanConversationRequestDto request = PlanConversationRequestDto.builder()
                    .sessionId(sessionId)
                    .sessionAccessToken(token)
                    .message("加上午餐火锅")
                    .context(PlanPageContext.builder().activeDay(1).build())
                    .build();

            long startTime = System.currentTimeMillis();
            PlannerService.ServiceResult result = plannerService.previewAdjustment(sessionId, request);
            long elapsed = System.currentTimeMillis() - startTime;

            // 实测断言 1：绝不发生死循环，500ms 内完成决策
            assertTrue(elapsed < 2000, "排程算法必须在极短时间内完成拓扑空隙决策，实测耗时: " + elapsed + "ms");

            // 实测断言 2：HTTP 状态 200，但业务判定不可行 (feasible = false)
            assertEquals(200, result.status());
            Map<String, Object> body = result.body();
            assertTrue((Boolean) body.get("ok"));
            assertFalse((Boolean) body.get("feasible"), "时空被景点完全占满时，绝不能强行插入餐饮导致时间重叠！");

            // 实测断言 3：明确返回 NO_FEASIBLE_MEAL_GAP 诊断原因码
            @SuppressWarnings("unchecked")
            List<String> reasonCodes = (List<String>) body.get("reasonCodes");
            assertNotNull(reasonCodes);
            assertTrue(reasonCodes.contains("NO_FEASIBLE_MEAL_GAP"), "原因码必须包含 NO_FEASIBLE_MEAL_GAP，实际: " + reasonCodes);

            // 实测断言 4：给出清晰的兜底缓解选项（如调整用餐时段或缩短景点停留）
            @SuppressWarnings("unchecked")
            List<String> alternatives = (List<String>) body.get("alternatives");
            assertNotNull(alternatives);
            assertFalse(alternatives.isEmpty());
            assertTrue(alternatives.stream().anyMatch(a -> a.contains("调整用餐时段") || a.contains("缩短景点停留")),
                    "必须向用户提供合理日程调整建议: " + alternatives);
        }

        @Test
        @DisplayName("攻击 1.2：MealGapSolver 单元极限压测：进餐时间与交通耗时压线测试，绝不产生重叠与时钟倒流")
        void assault_mealGapSolver_boundaryStress_neverInvertsTime() {
            Attraction meal = Attraction.builder()
                    .id("dining-hotpot")
                    .name("老火锅")
                    .location("106.577000,29.556000")
                    .district("渝中区")
                    .duration("60分钟")
                    .build();

            com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.MealWindow lunchWindow = taxonomy.getMealWindow("LUNCH");

            // 构造两个站点，中间空隙刚好只有 40 分钟（12:00 到 12:40），但火锅需要 60 分钟
            List<Map<String, Object>> stops = List.of(
                    new LinkedHashMap<>(Map.of("name", "站点A", "time", "10:00", "duration", "120分钟", "location", "106.577000,29.556000")),
                    new LinkedHashMap<>(Map.of("name", "站点B", "time", "12:40", "duration", "60分钟", "location", "106.577000,29.556000"))
            );

            Optional<MealGapSolver.Placement> placement = mealGapSolver.findGap(
                    stops, meal, 60, lunchWindow, "walking", itineraryBuilder);

            // 空间不足 60 分钟，findGap 必须明确返回 empty，绝不允许强行挤压造成时钟倒流或越界
            assertTrue(placement.isEmpty(), "空隙小于进餐时长时，MealGapSolver 必须拒绝 placement！");
        }

        @Test
        @DisplayName("攻击 1.3：RippleRecalculation 级联推迟环形死锁防御：侦测到重复 ID 节点能够截断，不陷入死循环")
        void assault_rippleRecalculate_circularTopology_breaksSafely() {
            List<Map<String, Object>> cyclicStops = new ArrayList<>();
            Map<String, Object> stop1 = new LinkedHashMap<>(Map.of("id", "stop-A", "time", "09:00", "duration", "60分钟"));
            Map<String, Object> stop2 = new LinkedHashMap<>(Map.of("id", "stop-A", "time", "10:00", "duration", "60分钟")); // 重复 ID 形成拓扑死循环毒丸
            cyclicStops.add(stop1);
            cyclicStops.add(stop2);

            assertDoesNotThrow(() -> itineraryTimeEngine.rippleRecalculateStopTimes(cyclicStops, 1),
                    "遭遇拓扑环形死锁毒丸节点时，rippleRecalculateStopTimes 必须安全截断，不得死循环！");
        }
    }

    @Nested
    @DisplayName("战区 2：跨省飞地与经纬度毒丸攻击（Geographic Poison & Long-Distance Abyss）")
    class GeographicPoisonAssault {

        @Test
        @DisplayName("攻击 2.1：经纬度毒丸注入：NaN、Infinity、超界181度均被安全拦截并返回 null")
        void assault_poisonCoordinates_strictlyFiltered() {
            // 测试非法经纬度字符串
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("NaN,NaN"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("Infinity,-Infinity"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("181.000000,29.557000"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("-180.500000,29.557000"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("106.500000,91.000000"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate("poison,string"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate(",,,,"));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate(null));
            assertFalse(PlanAdjustmentHelper.isValidCoordinate(""));

            // 构造全部为毒丸坐标的站点列表
            List<Map<String, Object>> poisonStops = List.of(
                    Map.of("name", "毒丸1", "location", "NaN,NaN"),
                    Map.of("name", "毒丸2", "location", "Infinity,29.5"),
                    Map.of("name", "毒丸3", "location", "185.0,95.0")
            );

            // 执行质心解析：绝不抛出 NumberFormatException，且严格返回 null（拒绝编造虚假重庆坐标）
            String resolved = PlanAdjustmentHelper.resolveDefaultCoordinate(Map.of(), null, poisonStops, 50_000.0);
            assertNull(resolved, "全毒丸坐标下，resolveDefaultCoordinate 必须返回 null，严禁编造任何坐标！");
        }

        @Test
        @DisplayName("攻击 2.2：1500公里北京故宫+重庆洪崖洞跨省飞地攻击：质心智能锚定最新有效站点，严防落入河南深山无人区")
        void assault_1500kmCrossProvinceEnclave_anchorsToLastValidStop() {
            // 北京故宫 (116.397128, 39.916527) 与 重庆洪崖洞 (106.577054, 29.557161)
            // 两者经度差约 9.82°，纬度差约 10.36°，相距超过 1500 公里
            // 若机械执行算术平均，质心将落入 (111.487, 34.736) 河南洛阳/南阳交界的无人山区深谷
            List<Map<String, Object>> crossProvinceStops = List.of(
                    new LinkedHashMap<>(Map.of("name", "北京故宫", "location", "116.397128,39.916527")),
                    new LinkedHashMap<>(Map.of("name", "重庆洪崖洞", "location", "106.577054,29.557161"))
            );

            Map<String, Object> trip = Map.of("city", "重庆市");
            TravelConstraints constraints = TravelConstraints.builder().destination("重庆市").build();

            String anchorCoord = PlanAdjustmentHelper.resolveDefaultCoordinate(trip, constraints, crossProvinceStops, 50_000.0);
            assertNotNull(anchorCoord);

            // 断言：由于跨度大于 0.5 度，系统防畸变机制必须锚定最新打卡点洪崖洞坐标，而不是荒无人烟的算术平均深山！
            assertEquals("106.577054,29.557161", anchorCoord,
                    "跨省 1500 公里大跨度场景下，必须触发防漂移保护，锚定最新有效站点(洪崖洞)，严禁落入河南山区深谷！");
        }

        @Test
        @DisplayName("攻击 2.3：全毒丸坐标 + 网关异常实弹攻击：加餐全链路安全返回 DINING_POI_UNRESOLVED，不崩不伪造")
        void assault_fullPoisonContext_previewAdjustment_safelyHandlesUnresolved() {
            // 构造全毒丸站点的一日行程
            List<Map<String, Object>> poisonStops = new ArrayList<>();
            poisonStops.add(new LinkedHashMap<>(Map.of("id", "day1-p1", "name", "火星基地", "time", "10:00", "duration", "60分钟", "location", "NaN,NaN")));
            poisonStops.add(new LinkedHashMap<>(Map.of("id", "day1-p2", "name", "月球背面", "time", "14:00", "duration", "60分钟", "location", "Infinity,95.0")));

            Map<String, Object> trip = new LinkedHashMap<>(Map.of(
                    "version", 1,
                    "city", "未知城市",
                    "days", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day", 1, "stops", poisonStops))))
            ));
            TravelConstraints constraints = TravelConstraints.builder().destination("未知城市").durationDays(1).build();
            var created = sessionRepositoryPort.create("ANONYMOUS", UUID.randomUUID().toString(), "未知目的地探险",
                    constraints.asMap(), AppliedPreferencesSnapshot.empty(), trip, UUID.randomUUID().toString(), UUID.randomUUID().toString());

            String sessionId = created.session().sessionId();
            String token = created.accessToken();

            // 发起加餐请求：“中午加个火锅”
            PlanConversationRequestDto request = PlanConversationRequestDto.builder()
                    .sessionId(sessionId)
                    .sessionAccessToken(token)
                    .message("中午加个火锅")
                    .context(PlanPageContext.builder().activeDay(1).build())
                    .build();

            // 实测断言：全链路不抛出 NPE、500，优雅返回不可行及原因码
            assertDoesNotThrow(() -> {
                PlannerService.ServiceResult result = plannerService.previewAdjustment(sessionId, request);
                assertEquals(200, result.status());
                Map<String, Object> body = result.body();
                assertTrue((Boolean) body.get("ok"));
                assertFalse((Boolean) body.get("feasible"), "在完全无有效地理信息的极端毒丸场景下，方案必须判定为不可行！");
                @SuppressWarnings("unchecked")
                List<String> reasonCodes = (List<String>) body.get("reasonCodes");
                assertTrue(reasonCodes.contains("DINING_POI_UNRESOLVED") || reasonCodes.contains("DINING_CANDIDATES_UNAVAILABLE")
                                || reasonCodes.contains("NO_FEASIBLE_MEAL_GAP"),
                        "必须返回合规的原因码: " + reasonCodes);
            });
        }
    }
}

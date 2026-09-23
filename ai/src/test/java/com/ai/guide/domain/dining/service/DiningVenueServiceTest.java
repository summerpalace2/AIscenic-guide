package com.ai.guide.domain.dining.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge.DiningOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DiningVenueServiceTest {

    private DiningVenueService diningVenueService;

    @BeforeEach
    void setUp() {
        diningVenueService = new DiningVenueService();
    }

    @Test
    @DisplayName("验证全市12个核心文旅区县均能秒级命中本地标杆餐饮")
    void testAllTwelveDistrictsCoverage() {
        Map<String, String> sampleLocations = Map.ofEntries(
                Map.entry("渝中区", "106.577054,29.557161"), // 解放碑
                Map.entry("江北区", "106.533215,29.576842"), // 观音桥
                Map.entry("南岸区", "106.592518,29.548962"), // 南山一棵树
                Map.entry("沙坪坝区", "106.448564,29.582947"), // 磁器口
                Map.entry("九龙坡区", "106.543500,29.497500"), // 交通茶馆/涂鸦街
                Map.entry("大渡口区", "106.486400,29.481400"), // 建川博物馆
                Map.entry("北碚区", "106.423700,29.834400"), // 重庆自然博物馆
                Map.entry("武隆区", "107.801215,29.428912"), // 武隆天生三桥
                Map.entry("大足区", "105.789124,29.748912"), // 大足石刻
                Map.entry("江津区", "106.012500,29.072200"), // 白沙古镇
                Map.entry("涪陵区", "107.391215,29.708912"), // 白鹤梁水下博物馆
                Map.entry("巴南区", "106.598912,29.421215")  // 南温泉风景区
        );

        for (Map.Entry<String, String> entry : sampleLocations.entrySet()) {
            String district = entry.getKey();
            String location = entry.getValue();

            Attraction mockAttraction = Attraction.builder()
                    .id("mock-" + district)
                    .name("测试景点-" + district)
                    .district(district)
                    .location(location)
                    .build();

            DiningOption option = diningVenueService.resolveNearbyDining(mockAttraction, "LUNCH", null);
            assertNotNull(option, "区县 " + district + " 餐饮推荐不能为 null");
            assertNotEquals("dining-fallback-default", option.id(),
                    "区县 " + district + " 应命中本地标杆餐厅，而非降级兜底");
            assertFalse(option.name().isBlank());
            assertFalse(option.specialtyDish().isBlank());
        }
    }

    @Test
    @DisplayName("验证严格距离截断生效：武隆区景点绝不会错误匹配上百公里外的市区餐厅")
    void testDistanceCutoffPreventsCrossDistrictLeakage() {
        Attraction wulongAttraction = Attraction.builder()
                .id("cq-wulong-tiankeng")
                .name("武隆天生三桥")
                .district("武隆区")
                .location("107.801215,29.428912")
                .build();

        DiningOption option = diningVenueService.resolveNearbyDining(wulongAttraction, "LUNCH", null);
        assertNotNull(option);
        // 必须为武隆本地特色餐厅（如碗碗羊肉、乌江鱼等），不可为渝中或沙坪坝餐厅
        assertTrue(option.name().contains("武隆") || option.name().contains("羊肉") || option.name().contains("乌江") || option.name().contains("仙女山"),
                "武隆景点推荐结果必须为武隆本地名店，实际获得: " + option.name());
    }

    @Test
    @DisplayName("验证超远冷门区域无本地店铺时，距离截断生效并平滑触发安全兜底")
    void testExtremeDistanceWithoutLocalVenueFallback() {
        // 城口县（距离主城区约 350 公里，无预置 RAG 店铺）
        Attraction chengkouAttraction = Attraction.builder()
                .id("mock-chengkou")
                .name("城口亢谷景区")
                .district("城口县")
                .location("108.665000,31.942000")
                .build();

        DiningOption option = diningVenueService.resolveNearbyDining(chengkouAttraction, "LUNCH", null);
        assertNotNull(option);
        // 因为距离主城区与各分店均超过 15km 甚至 300km，本地候选被严格距离截断
        // 未配置高德真实密钥或未命中时，平滑转入 fallbackOption，绝不出现向城口推荐解放碑火锅的荒谬情况
        assertEquals("dining-fallback-default", option.id());
        assertTrue(option.summary().contains("周边精选"));
    }

    @Test
    @DisplayName("验证带父母适老需求下，系统优先挑选软烂温和无辣菜品")
    void testElderFriendlyPreference() {
        Attraction jiulongpoAttraction = Attraction.builder()
                .id("cq-chongqing-zoo")
                .name("重庆动物园")
                .district("九龙坡区")
                .location("106.502300,29.511800")
                .build();

        TravelConstraints constraints = new TravelConstraints();
        constraints.setCompanions("带父母");
        constraints.setDietPreference("清淡不辣");

        DiningOption option = diningVenueService.resolveNearbyDining(jiulongpoAttraction, "LUNCH", constraints);
        assertNotNull(option);
        // 九龙坡清淡适老餐厅胡记蹄花汤应以最高分胜出
        assertTrue(option.name().contains("胡记蹄花") || option.specialtyDish().contains("蹄花") || option.summary().contains("软烂"),
                "带长辈清淡偏好应优先推荐蹄花汤或软烂菜品，实际获得: " + option.name());
    }

    @Test
    @DisplayName("验证局部替换候选列表排重与风味筛选")
    void testResolveDiningReplacementCandidates() {
        Attraction guanyinqiao = Attraction.builder()
                .id("cq-guanyinqiao")
                .name("观音桥步行街")
                .district("江北区")
                .location("106.533215,29.576842")
                .build();

        List<DiningOption> candidates = diningVenueService.resolveDiningReplacementCandidates(
                "江北区",
                "【午餐推荐】珮姐老火锅（江北北城天街店）",
                "重庆小面",
                "BREAKFAST",
                guanyinqiao,
                null
        );

        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        // 候选列表中不能包含当前要替换的珮姐老火锅
        for (DiningOption opt : candidates) {
            assertFalse(opt.name().contains("珮姐老火锅"));
        }
        // 且由于指定了重庆小面，第一顺位应为小面馆
        assertTrue(candidates.get(0).name().contains("面") || candidates.get(0).diningType().contains("面"));
    }

    @Test
    @DisplayName("验证10小时同城游与短途规划控制在2-3km商圈半径，绝不跨区推荐超出2.8km远距离餐厅")
    void testShortDurationProximityPriorityRejectsDistantRAG() {
        // 重庆大学A区思群广场 (沙坪坝)
        Attraction cqu = Attraction.builder()
                .id("amap-cqu-001") // amap- 前缀代表运行时微动线规划
                .name("思群广场")
                .district("沙坪坝区")
                .location("106.471000,29.565000")
                .build();

        TravelConstraints shortConstraints = new TravelConstraints();
        shortConstraints.setTimeBudgetMinutes(600); // 10小时同城游
        shortConstraints.setRawPrompt("重大思群广场与三峡广场10小时游，就近吃地道江湖菜");

        DiningOption option = diningVenueService.resolveNearbyDining(cqu, "LUNCH", shortConstraints);
        assertNotNull(option);

        // 绝不可跨区推荐远在6公里外的李子坝梁山鸡或11公里外的解放碑
        assertFalse(option.name().contains("李子坝"),
                "10小时规划下，绝不可推荐远在6公里外的李子坝，实际获得: " + option.name());
        assertFalse(option.name().contains("解放碑"),
                "10小时规划下，绝不可推荐远在11公里外的解放碑，实际获得: " + option.name());

        // 应命中 2-3km 内沙坪坝核心特色餐饮（如磁器口古法毛血旺约 2.6km）
        assertTrue(option.name().contains("毛血旺") || option.name().contains("磁器口") || option.name().contains("沙坪坝"),
                "应就近命中2-3km相邻核心商圈名店，实际获得: " + option.name());
    }

    @Test
    @DisplayName("验证距离衰减机制：同风味下强力优先命中 < 600米极近餐厅")
    void testProximityPriorityFavorsUnder600Meters() {
        // 解放碑步行街 (106.577054,29.557161)
        Attraction jiefangbei = Attraction.builder()
                .id("cq-jiefangbei")
                .name("解放碑步行街")
                .district("渝中区")
                .location("106.577054,29.557161")
                .build();

        TravelConstraints constraints = new TravelConstraints();
        constraints.setTimeBudgetMinutes(360); // 6小时限时游
        constraints.setDietPreference("清淡不辣");

        DiningOption option = diningVenueService.resolveNearbyDining(jiefangbei, "LUNCH", constraints);
        assertNotNull(option);

        // 渝中区有两个清淡候选：
        // 1. 临江门传统老鸭清汤煲 (距离约 330 米，< 600米，加60分)
        // 2. 邱二馆养生雪豆炖鸡汤 (距离约 310 米，< 600米，加60分)
        // 3. 人和街传统老字号清汤蹄花煲 (距离约 2.3 公里，在2-3km范围内，加15分)
        // 系统必须优先命中 < 600米极近餐厅，绝不舍近求远
        assertTrue(option.name().contains("临江门") || option.name().contains("邱二馆"),
                "必须优先推荐 < 600米极近步行清淡名店，实际获得: " + option.name());
        assertFalse(option.name().contains("人和街"),
                "不应跳过极近名店去推荐2公里外的人和街，实际获得: " + option.name());
    }
}

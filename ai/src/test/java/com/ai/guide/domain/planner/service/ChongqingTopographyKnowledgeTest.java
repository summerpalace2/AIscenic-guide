package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChongqingTopographyKnowledgeTest {

    @Test
    @DisplayName("验证高程圈层划分：高山、上半城、下半城、常规浅丘")
    void testElevationTierResolution() {
        Attraction nanshan = Attraction.builder().id("cq-nanshan-tree").name("南山一棵树观景台").district("南岸区").build();
        Attraction huangjueya = Attraction.builder().id("cq-huangjueya").name("黄桷垭老街").district("南岸区").build();
        Attraction eling = Attraction.builder().id("cq-eling").name("鹅岭二厂文创公园").district("渝中区").build();
        Attraction jiefangbei = Attraction.builder().id("cq-jiefangbei").name("解放碑步行街").district("渝中区").build();
        Attraction hongyadong = Attraction.builder().id("cq-hongyadong").name("洪崖洞民俗风貌区").district("渝中区").build();
        Attraction liziba = Attraction.builder().id("cq-liziba").name("李子坝单轨穿楼").district("渝中区").build();
        Attraction nanbin = Attraction.builder().id("cq-nanbin").name("南滨路江滩公园").district("南岸区").build();
        Attraction sanxia = Attraction.builder().id("cq-sanxia-square").name("三峡广场").district("沙坪坝区").build();

        assertEquals(ChongqingTopographyKnowledge.ElevationTier.HIGH_MOUNTAIN, ChongqingTopographyKnowledge.resolveElevationTier(nanshan));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.HIGH_MOUNTAIN, ChongqingTopographyKnowledge.resolveElevationTier(huangjueya));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.UPPER_RIDGE, ChongqingTopographyKnowledge.resolveElevationTier(eling));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.UPPER_RIDGE, ChongqingTopographyKnowledge.resolveElevationTier(jiefangbei));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.LOWER_RIVER, ChongqingTopographyKnowledge.resolveElevationTier(hongyadong));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.LOWER_RIVER, ChongqingTopographyKnowledge.resolveElevationTier(liziba));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.LOWER_RIVER, ChongqingTopographyKnowledge.resolveElevationTier(nanbin));
        assertEquals(ChongqingTopographyKnowledge.ElevationTier.STANDARD_URBAN, ChongqingTopographyKnowledge.resolveElevationTier(sanxia));
    }

    @Test
    @DisplayName("验证长江与嘉陵江天然分界跨江识别")
    void testRiverCrossingDetection() {
        Attraction hongyadong = Attraction.builder().id("cq-hongyadong").name("洪崖洞").district("渝中区").build();
        Attraction grandTheatre = Attraction.builder().id("cq-grand-theatre").name("重庆大剧院").district("江北区").build();
        Attraction jiefangbei = Attraction.builder().id("cq-jiefangbei").name("解放碑步行街").district("渝中区").build();
        Attraction longmenhao = Attraction.builder().id("cq-longmenhao").name("龙门浩老街").district("南岸区").build();

        // 洪崖洞 (渝中) ↔ 大剧院 (江北) -> 跨嘉陵江
        assertEquals(ChongqingTopographyKnowledge.RiverCrossingType.JIALING_RIVER,
                ChongqingTopographyKnowledge.detectRiverCrossing(hongyadong, grandTheatre));
        assertEquals(ChongqingTopographyKnowledge.RiverCrossingType.JIALING_RIVER,
                ChongqingTopographyKnowledge.detectRiverCrossing(grandTheatre, hongyadong));

        // 解放碑 (渝中) ↔ 龙门浩 (南岸) -> 跨长江
        assertEquals(ChongqingTopographyKnowledge.RiverCrossingType.YANGTZE_RIVER,
                ChongqingTopographyKnowledge.detectRiverCrossing(jiefangbei, longmenhao));

        // 大剧院 (江北) ↔ 龙门浩 (南岸) -> 贯穿两江
        assertEquals(ChongqingTopographyKnowledge.RiverCrossingType.BOTH_RIVERS,
                ChongqingTopographyKnowledge.detectRiverCrossing(grandTheatre, longmenhao));

        // 解放碑 (渝中) ↔ 洪崖洞 (渝中) -> 无需跨江
        assertEquals(ChongqingTopographyKnowledge.RiverCrossingType.NONE,
                ChongqingTopographyKnowledge.detectRiverCrossing(jiefangbei, hongyadong));
    }

    @Test
    @DisplayName("验证山城特色下行坡度流向（顺势而下 vs 逆势登山）")
    void testElevationFlowDetection() {
        Attraction eling = Attraction.builder().id("cq-eling").name("鹅岭公园").district("渝中区").build();
        Attraction liziba = Attraction.builder().id("cq-liziba").name("李子坝单轨穿楼").district("渝中区").build();
        Attraction jiefangbei = Attraction.builder().id("cq-jiefangbei").name("解放碑步行街").district("渝中区").build();
        Attraction shibati = Attraction.builder().id("cq-shibati").name("十八梯传统风貌区").district("渝中区").build();

        // 鹅岭 (高) -> 李子坝 (低) -> DOWNHILL (舒适下行)
        assertEquals(ChongqingTopographyKnowledge.ElevationFlow.DOWNHILL,
                ChongqingTopographyKnowledge.detectElevationFlow(eling, liziba));

        // 李子坝 (低) -> 鹅岭 (高) -> UPHILL (爬坡吃力)
        assertEquals(ChongqingTopographyKnowledge.ElevationFlow.UPHILL,
                ChongqingTopographyKnowledge.detectElevationFlow(liziba, eling));

        // 解放碑 (高) -> 十八梯 (低) -> DOWNHILL
        assertEquals(ChongqingTopographyKnowledge.ElevationFlow.DOWNHILL,
                ChongqingTopographyKnowledge.detectElevationFlow(jiefangbei, shibati));
    }

    @Test
    @DisplayName("验证日内折返惩罚（Zig-zag Penalty）：杜绝山水反复横跳与频繁来回跨江")
    void testZigZagPenalty() {
        Attraction nanshan1 = Attraction.builder().id("cq-nanshan").name("南山一棵树").district("南岸区").build();
        Attraction nanbin = Attraction.builder().id("cq-nanbin").name("南滨路海棠烟雨公园").district("南岸区").build();
        Attraction nanshan2 = Attraction.builder().id("cq-huangjueya").name("黄桷垭老街").district("南岸区").build();

        // 动线：南山 (高山) -> 南滨路 (滨江) -> 再次返回南山 (高山折返)
        double mountainPenalty = ChongqingTopographyKnowledge.calculateZigZagPenalty(
                List.of(nanshan1, nanbin), nanshan2);
        assertTrue(mountainPenalty >= 50.0, "山顶->江边->山顶必须触发高额折返惩罚，实际: " + mountainPenalty);

        // 跨江横跳：渝中区 -> 南岸区 -> 又折回渝中区
        Attraction jiefangbei = Attraction.builder().id("cq-jiefangbei").name("解放碑").district("渝中区").build();
        Attraction longmenhao = Attraction.builder().id("cq-longmenhao").name("龙门浩老街").district("南岸区").build();
        Attraction hongyadong = Attraction.builder().id("cq-hongyadong").name("洪崖洞").district("渝中区").build();

        double riverPenalty = ChongqingTopographyKnowledge.calculateZigZagPenalty(
                List.of(jiefangbei, longmenhao), hongyadong);
        assertTrue(riverPenalty >= 40.0, "跨江后立刻又跨回同一侧必须施加跨江折返惩罚，实际: " + riverPenalty);

        // 正常单向流动（解放碑 -> 洪崖洞 -> 跨江去大剧院），不应有折返惩罚
        Attraction grandTheatre = Attraction.builder().id("cq-grand-theatre").name("重庆大剧院").district("江北区").build();
        double normalPenalty = ChongqingTopographyKnowledge.calculateZigZagPenalty(
                List.of(jiefangbei, hongyadong), grandTheatre);
        assertEquals(0.0, normalPenalty, "平滑流动动线不应有任何惩罚");
    }

    @Test
    @DisplayName("验证山城立体交通特色文案生成（皇冠大扶梯、长江索道、千厮门大桥、三层马路步道）")
    void testTransitCharacteristicHints() {
        Attraction eling = Attraction.builder().name("鹅岭二厂文创园").district("渝中区").build();
        Attraction liziba = Attraction.builder().name("李子坝轻轨站").district("渝中区").build();
        Attraction lianglukou = Attraction.builder().name("两路口文化宫").district("渝中区").build();
        Attraction caiyuanba = Attraction.builder().name("菜园坝江畔").district("渝中区").build();
        Attraction jiefangbei = Attraction.builder().name("解放碑步行街").district("渝中区").build();
        Attraction nanbin = Attraction.builder().name("南滨路龙门浩").district("南岸区").build();
        Attraction hongyadong = Attraction.builder().name("洪崖洞景区").district("渝中区").build();
        Attraction grandTheatre = Attraction.builder().name("重庆大剧院").district("江北区").build();

        String hintElingToLiziba = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(eling, liziba, "walking");
        assertTrue(hintElingToLiziba.contains("三层马路步道") && hintElingToLiziba.contains("下行"), "鹅岭至李子坝应提示三层马路下行");

        String hintEscalator = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(lianglukou, caiyuanba, "walking");
        assertTrue(hintEscalator.contains("皇冠大扶梯"), "两路口至菜园坝应提示皇冠大扶梯");

        String hintCableway = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(jiefangbei, nanbin, "transit");
        assertTrue(hintCableway.contains("长江索道") || hintCableway.contains("长江"), "解放碑至南滨路应提示长江索道或长江大桥");

        String hintBridge = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(hongyadong, grandTheatre, "walking");
        assertTrue(hintBridge.contains("千厮门") || hintBridge.contains("嘉陵江"), "洪崖洞至大剧院应提示千厮门大桥观景漫步");
    }
}

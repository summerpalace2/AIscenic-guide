package com.ai.guide.domain.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ai.guide.testing.InMemoryUserPreferenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlotTrackingService 槽位提取与扩容验证测试")
class SlotTrackingServiceTest {

    private InMemoryUserPreferenceStore preferenceStore;
    private SlotTrackingService slotTrackingService;

    @BeforeEach
    void setUp() {
        preferenceStore = new InMemoryUserPreferenceStore();
        slotTrackingService = new SlotTrackingService(preferenceStore, new ObjectMapper(), new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
    }

    @ParameterizedTest(name = "应该准确提取重庆核心景区与地标: {0}")
    @ValueSource(strings = {
            "我想去朝天门看两江交汇",
            "晚上去大剧院拍夜景机位",
            "想在白象居拍打卡照片",
            "去龙门浩老街喝盖碗茶",
            "在下浩老街感受市井烟火",
            "到魁星楼体验1楼和22楼都是平街",
            "观音桥好吃街有哪些推荐",
            "中山四路历史文化街区怎么走",
            "去重庆人民大礼堂打卡",
            "在南滨路江岸散步看夜景",
            "弹子石老街的夜市好玩吗",
            "去歌乐山国家森林公园吸氧",
            "想去渣滓洞缅怀革命先烈",
            "在白公馆参观历史陈列",
            "融汇温泉适合带老人泡吗"
    })
    void testExtractCoreScenicSpots(String userMessage) {
        slotTrackingService.extractAndSave("user-01", userMessage);
        assertFalse(slotTrackingService.getSlots("user-01").getOrDefault("area", List.of()).isEmpty(),
                "必须提取出非空区域地标槽位");
    }

    @ParameterizedTest(name = "应该准确提取重庆主城核心九区行政区划: {0}")
    @ValueSource(strings = {
            "南岸区有什么好吃的火锅",
            "九龙坡区涂鸦街怎么去",
            "沙坪坝区大学城附近有没有茶馆",
            "渝中区半岛一日游路线",
            "江北区铁山坪森林公园",
            "渝北区中央公园环境怎么样",
            "巴南区温泉值得去吗",
            "北碚区缙云山一日游",
            "大渡口区工业博物馆"
    })
    void testExtractNineDistricts(String userMessage) {
        slotTrackingService.extractAndSave("user-02", userMessage);
        String extracted = String.join(" ", slotTrackingService.getSlots("user-02").getOrDefault("area", List.of()));
        assertTrue(extracted.contains("区"), "九大区提取结果应保留区划名称: " + extracted);
    }

    @Test
    @DisplayName("否定前缀不应误提取为目标区域槽位（DEF-R3-01）")
    void testNegatedPrefixShouldNotExtractArea() {
        slotTrackingService.extractAndSave("user-03", "今天时间不够，不要去解放碑了");
        assertFalse(slotTrackingService.getSlots("user-03").containsKey("area"));

        slotTrackingService.extractAndSave("user-03", "别安排南岸区");
        assertFalse(slotTrackingService.getSlots("user-03").containsKey("area"));
    }
}

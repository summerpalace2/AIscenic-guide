package com.ai.guide.domain.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentRadarServiceTest {

    private IntentRadarService radar;

    @BeforeEach
    void setUp() {
        radar = new IntentRadarService();
    }

    @Test
    @DisplayName("DIET 维度测试：吃甜、甜品与糖水")
    void testDietSweets() {
        var result = radar.scan("我想吃点甜的");
        assertNotNull(result, "我想吃点甜的 必须命中意图雷达");
        assertEquals("DIET", result.preference().domain());
        assertTrue(result.preference().trait().contains("甜"));
        assertFalse(result.suggestions().isEmpty());
        assertTrue(result.suggestions().stream().anyMatch(a -> a.action().equals("save_slot_preference")));
        assertTrue(result.suggestions().stream().anyMatch(a -> a.action().equals("add_stop")));

        var result2 = radar.scan("想整两口好吃的糖水和小吃");
        assertNotNull(result2);
        assertEquals("DIET", result2.preference().domain());
    }

    @Test
    @DisplayName("DIET 维度测试：烧烤与老茶馆专属槽位")
    void testDietBbqAndTea() {
        var bbq = radar.scan("路上有没有什么烧烤 我想吃烧烤");
        assertNotNull(bbq, "我想吃烧烤 必须命中烧烤独立规则");
        assertEquals("DIET", bbq.preference().domain());
        assertTrue(bbq.preference().trait().contains("烧烤"));
        assertTrue(bbq.suggestions().stream().anyMatch(a -> a.action().equals("save_slot_preference") && a.label().contains("烧烤")));
        assertTrue(bbq.suggestions().stream().anyMatch(a -> a.payload().contains("\"slot\":\"dining\"") && a.payload().contains("\"tag\":\"烧烤\"")));

        var tea = radar.scan("找个老茶馆喝茶歇歇脚");
        assertNotNull(tea, "老茶馆喝茶 必须命中老茶馆独立规则");
        assertEquals("DIET", tea.preference().domain());
        assertTrue(tea.preference().trait().contains("老茶馆"));
        assertTrue(tea.suggestions().stream().anyMatch(a -> a.action().equals("save_slot_preference") && a.label().contains("老茶馆")));
    }

    @Test
    @DisplayName("DIET 维度测试：火锅重辣与清淡热汤")
    void testDietSpicyAndSoup() {
        var spicy = radar.scan("晚上想吃地道老火锅，多点辣");
        assertNotNull(spicy);
        assertEquals("DIET", spicy.preference().domain());
        assertTrue(spicy.preference().trait().contains("火锅"));
        assertTrue(spicy.suggestions().stream().anyMatch(a -> a.action().equals("save_slot_preference") && a.label().contains("老火锅")));

        var soup = radar.scan("胃有点不舒服，想喝点暖胃的热汤，要清淡少油");
        assertNotNull(soup);
        assertEquals("DIET", soup.preference().domain());
        assertTrue(soup.preference().trait().contains("清淡"));
    }

    @Test
    @DisplayName("PACE 维度测试：脚酸、少走路、晚起")
    void testPaceTiredAndLate() {
        var tired = radar.scan("走得有点脚酸了，后面的台阶能少点吗");
        assertNotNull(tired);
        assertEquals("PACE", tired.preference().domain());
        assertTrue(tired.preference().trait().contains("少走路"));
        assertTrue(tired.suggestions().stream().anyMatch(a -> a.action().equals("replan_pace")));

        var lazy = radar.scan("今天有点懒 想少走一点路");
        assertNotNull(lazy, "今天有点懒 想少走一点路 必须命中 PACE 意图雷达");
        assertEquals("PACE", lazy.preference().domain());
        assertTrue(lazy.preference().trait().contains("少走路"));
        assertTrue(lazy.suggestions().stream().anyMatch(a -> a.action().equals("replan_pace")));

        var late = radar.scan("明天早上起不来，想睡到自然醒再出门");
        assertNotNull(late);
        assertEquals("PACE", late.preference().domain());
        assertTrue(late.preference().trait().contains("晚起"));
        assertTrue(late.suggestions().stream().anyMatch(a -> a.action().equals("adjust_time")));
    }

    @Test
    @DisplayName("ENVIRONMENT 维度测试：避雨与防晒")
    void testEnvironmentRain() {
        var rain = radar.scan("重庆下雨了鞋全湿了，有没有全室内的避雨路线");
        assertNotNull(rain);
        assertEquals("ENVIRONMENT", rain.preference().domain());
        assertTrue(rain.preference().trait().contains("室内"));
        assertTrue(rain.suggestions().stream().anyMatch(a -> a.action().equals("indoor_mode")));

        var hot = radar.scan("大太阳太热了，特别怕晒，想吹空调");
        assertNotNull(hot);
        assertEquals("ENVIRONMENT", hot.preference().domain());
        assertTrue(hot.preference().trait().contains("防晒"));
    }

    @Test
    @DisplayName("EXPERIENCE 维度测试：摄影机位与夜景")
    void testExperiencePhotoAndNight() {
        var photo = radar.scan("想拍绝美大片，哪里有最佳摄影机位");
        assertNotNull(photo);
        assertEquals("EXPERIENCE", photo.preference().domain());
        assertTrue(photo.preference().trait().contains("摄影"));

        var night = radar.scan("晚上想看震撼的两江魔幻夜景");
        assertNotNull(night);
        assertEquals("EXPERIENCE", night.preference().domain());
        assertTrue(night.preference().trait().contains("夜景"));
    }

    @Test
    @DisplayName("COMPANION 维度测试：长辈适老与亲子推车")
    void testCompanion() {
        var elder = radar.scan("这次带着爸妈出来玩，老人腿脚不便");
        assertNotNull(elder);
        assertEquals("COMPANION", elder.preference().domain());
        assertTrue(elder.preference().trait().contains("长辈"));
        assertTrue(elder.suggestions().stream().anyMatch(a -> a.action().equals("senior_friendly")));

        var kids = radar.scan("推着婴儿车带娃出行，要亲子推车平顺动线");
        assertNotNull(kids);
        assertEquals("COMPANION", kids.preference().domain());
        assertTrue(kids.preference().trait().contains("亲子"));
    }

    @Test
    @DisplayName("系统命令与简短回应不误判")
    void testNonIntent() {
        assertNull(radar.scan("/help"));
        assertNull(radar.scan("好"));
        assertNull(radar.scan("确定"));
        assertNull(radar.scan(""));
    }
}

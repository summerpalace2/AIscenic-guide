package com.ai.guide.domain.memory.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelMemoryServiceTest {

    private final TravelMemoryService service = new TravelMemoryService(null);

    @Test
    void explicitLongTermStatementProducesAConfirmableCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("以后出去玩尽量少走路，也少爬坡");
        assertEquals("PACE", candidate.category());
        assertEquals("偏好少走路，优先平路、电梯与短步行", candidate.content());
    }

    @Test
    void OneOffStatementDoesNotSilentlyBecomeMemory() {
        assertNull(service.suggest("今天想少走一点路"));
    }

    @Test
    void NaturalPersonalStatementCanBecomeAConfirmableCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我平时很喜欢逛博物馆和人文历史展览");
        assertEquals("INTEREST", candidate.category());
        assertEquals("偏爱人文历史与室内文化场馆", candidate.content());
    }

    @Test
    void CurrentTripInterestDoesNotBecomeLongTermMemory() {
        assertNull(service.suggest("这次想多逛博物馆"));
    }

    @Test
    void spicyAndHotpotStatementProducesDietCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我想吃火锅 后面帮我推荐辣的食物");
        org.junit.jupiter.api.Assertions.assertNotNull(candidate);
        assertEquals("DIET", candidate.category());
        assertEquals("饮食偏好麻辣、地道重庆火锅与江湖菜风味", candidate.content());
    }

    @Test
    void nonSpicyHotpotStatementProducesNonSpicyCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我不吃辣，我想去吃火锅");
        org.junit.jupiter.api.Assertions.assertNotNull(candidate);
        assertEquals("DIET", candidate.category());
        assertTrue(candidate.content().contains("清淡少辣"));
        org.junit.jupiter.api.Assertions.assertFalse(candidate.content().contains("麻辣"));
    }

    @Test
    void accessibilityAndPhotographyStatementsProduceCandidates() {
        TravelMemoryService.Candidate access = service.suggest("家里老人腿脚不便，以后出行需要无障碍安排");
        org.junit.jupiter.api.Assertions.assertNotNull(access);
        assertEquals("ACCESSIBILITY", access.category());

        TravelMemoryService.Candidate photo = service.suggest("我出门旅游很重视拍照出片和摄影机位");
        org.junit.jupiter.api.Assertions.assertNotNull(photo);
        assertEquals("INTEREST", photo.category());
    }

    @Test
    void teaAndCoffeeStatementsProduceAccurateCandidates() {
        TravelMemoryService.Candidate tea = service.suggest("路上有没有什么茶馆 我想喝茶");
        org.junit.jupiter.api.Assertions.assertNotNull(tea);
        assertEquals("DIET", tea.category());
        assertEquals("偏好特色老茶馆、盖碗茶与慢生活品茗体验", tea.content());

        TravelMemoryService.Candidate coffee = service.suggest("我平时喜欢在江边找景观咖啡馆喝下午茶");
        org.junit.jupiter.api.Assertions.assertNotNull(coffee);
        assertEquals("DIET", coffee.category());
        assertEquals("偏好景观咖啡馆、独立特调与下午茶小憩", coffee.content());
    }

    @Test
    void testSynthesizePrependPromptMultipleDietsCoexist() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        PreferencesService prefService = Mockito.mock(PreferencesService.class);
        TravelMemoryService memService = new TravelMemoryService(jdbc, prefService);

        List<TravelMemory> mockMemories = List.of(
                new TravelMemory("m1", "DIET", "麻辣火锅", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 1000L),
                new TravelMemory("m2", "DIET", "特色甜品冰粉", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 2000L),
                new TravelMemory("m3", "DIET", "街头小吃小面", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 3000L)
        );

        Mockito.when(jdbc.query(Mockito.anyString(), Mockito.any(RowMapper.class), Mockito.eq("user-1")))
                .thenReturn(mockMemories);
        Mockito.when(prefService.get("user-1")).thenReturn(UserPreferences.empty("user-1", 1L, System.currentTimeMillis()));

        String prompt = memService.synthesizePrependPrompt("user-1", false);
        assertTrue(prompt.contains("饮食偏好麻辣火锅与地道巴渝风味"));
        assertTrue(prompt.contains("喜好特色甜品糖水与点心"));
        assertTrue(prompt.contains("地道特色小吃"));
    }

    @Test
    void testConflictingMemoriesCancellationSpicyVsBland() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        TravelMemoryService memService = new TravelMemoryService(jdbc);

        Mockito.when(jdbc.queryForList(Mockito.anyString(), Mockito.eq("user-1")))
                .thenReturn(List.of(Map.of("id", "m-spicy", "content", "麻辣火锅与吃辣")));

        memService.confirm("user-1", "清淡少辣不吃辣", "DIET", "USER_EDIT");

        Mockito.verify(jdbc).update(Mockito.contains("status = 'DELETED'"), Mockito.anyLong(), Mockito.eq("m-spicy"), Mockito.eq("user-1"));
    }

    @Test
    void testConflictingMemoriesCancellationStaminaVsRest() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        TravelMemoryService memService = new TravelMemoryService(jdbc);

        Mockito.when(jdbc.queryForList(Mockito.anyString(), Mockito.eq("user-1")))
                .thenReturn(List.of(Map.of("id", "m-tired", "content", "少走路少爬坡避台阶")));

        memService.confirm("user-1", "特种兵暴走多走走", "PACE", "USER_EDIT");

        Mockito.verify(jdbc).update(Mockito.contains("status = 'DELETED'"), Mockito.anyLong(), Mockito.eq("m-tired"), Mockito.eq("user-1"));
    }
}

package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.port.MemoryRepositoryPort;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelMemoryServiceTest {

    private final TravelMemoryService service = com.ai.guide.testing.MemoryTestFixtures.service((MemoryRepositoryPort) null);

    private MemoryRepositoryPort mockRepo() {
        MemoryRepositoryPort repo = Mockito.mock(MemoryRepositoryPort.class);
        Mockito.when(repo.runInTransaction(Mockito.any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        return repo;
    }

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
    void negativeNightViewStatementDoesNotCreatePositiveInterestMemory() {
        assertNull(service.suggest("我不喜欢夜景"));
        TravelMemoryService.Candidate positive = service.suggest("我平时喜欢夜景");
        assertEquals("INTEREST", positive.category());
        assertEquals("偏好夜景观赏体验", positive.content());
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
        assertEquals("偏好火锅与串串", candidate.content());
    }

    @Test
    void nonSpicyHotpotStatementProducesNonSpicyCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我不吃辣，我想去吃火锅");
        org.junit.jupiter.api.Assertions.assertNotNull(candidate);
        assertEquals("DIET", candidate.category());
        assertEquals("我不吃辣,我想去吃火锅", candidate.content(), "保留具体限制与食物对象");
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
        assertEquals("偏好茶饮与茶歇", tea.content());

        TravelMemoryService.Candidate coffee = service.suggest("我平时喜欢在江边找景观咖啡馆喝下午茶");
        org.junit.jupiter.api.Assertions.assertNotNull(coffee);
        assertEquals("DIET", coffee.category());
        assertEquals("偏好咖啡与下午茶", coffee.content());
    }

    @Test
    void testSynthesizePrependPromptMultipleDietsCoexist() {
        MemoryRepositoryPort repo = mockRepo();
        PreferencesService prefService = Mockito.mock(PreferencesService.class);
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo, prefService);

        List<TravelMemory> mockMemories = List.of(
                new TravelMemory("m1", "DIET", "麻辣火锅", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 1000L),
                new TravelMemory("m2", "DIET", "特色甜品冰粉", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 2000L),
                new TravelMemory("m3", "DIET", "街头小吃小面", "CHAT_CONFIRMED", "", "CONFIRMED", 1.0, 1000L, 3000L)
        );

        Mockito.when(repo.findConfirmedByUserId("user-1")).thenReturn(mockMemories);
        Mockito.when(prefService.get("user-1")).thenReturn(UserPreferences.empty("user-1", 1L, System.currentTimeMillis()));

        String prompt = memService.synthesizePrependPrompt("user-1", false);
        assertTrue(prompt.contains("麻辣火锅"));
        assertTrue(prompt.contains("喜欢甜口、甜品与糖水"));
        assertTrue(prompt.contains("街头小吃小面"));
        org.junit.jupiter.api.Assertions.assertFalse(prompt.contains("巴渝"), "不能自行添加用户没有确认的地域风味");
    }

    @Test
    void synthesisPreservesConfirmedContentAcrossCategoriesWithoutKeywordWhitelist() {
        MemoryRepositoryPort repo = mockRepo();
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo);
        List<TravelMemory> memories = List.of(
                new TravelMemory("h", "INTEREST", "偏好人文历史", "CHAT_CONFIRMED", "", "CONFIRMED", 1, 1, 1),
                new TravelMemory("c", "CUSTOM", "偏好古籍修复和地方口述史", "CHAT_CONFIRMED", "", "CONFIRMED", 1, 1, 2),
                new TravelMemory("d", "DIET", "喜欢甜品，但对花生过敏", "CHAT_CONFIRMED", "", "CONFIRMED", 1, 1, 3),
                new TravelMemory("t", "TRANSPORT", "优先轮渡", "CHAT_CONFIRMED", "", "CONFIRMED", 1, 1, 4));
        Mockito.when(repo.findConfirmedByUserId("user-1")).thenReturn(memories);
        String prompt = memService.synthesizePrependPrompt("user-1", false);
        memories.forEach(memory -> assertTrue(prompt.contains(memory.content()), memory.content()));
    }

    @Test
    void confirmDoesNotReportSuccessWhenProfileSynthesisFails() {
        MemoryRepositoryPort repo = mockRepo();
        PreferencesService preferences = Mockito.mock(PreferencesService.class);
        Mockito.when(preferences.get("user-1")).thenThrow(new IllegalStateException("画像更新失败"));
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo, preferences);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> memService.confirm("user-1", "偏好人文历史", "INTEREST", "CHAT_CONFIRMED"));
    }

    @Test
    void conflictingSpiceCannotDeleteAnInseparableCuisinePreference() {
        MemoryRepositoryPort repo = mockRepo();
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo);

        Mockito.when(repo.findActiveIdAndContent("user-1"))
                .thenReturn(List.of(Map.of("id", "m-spicy", "content", "麻辣火锅与吃辣")));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> memService.confirm("user-1", "清淡少辣不吃辣", "DIET", "USER_EDIT"));

        Mockito.verify(repo, Mockito.never()).softDelete(Mockito.eq("m-spicy"), Mockito.eq("user-1"), Mockito.anyLong());
    }

    @Test
    void pureSpicePreferenceCanBeSuperseded() {
        MemoryRepositoryPort repo = mockRepo();
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo);
        Mockito.when(repo.findActiveIdAndContent("user-1"))
                .thenReturn(List.of(Map.of("id", "m-spicy", "content", "吃辣")));
        memService.confirm("user-1", "清淡少辣不吃辣", "DIET", "USER_EDIT");

        Mockito.verify(repo).softDelete(Mockito.eq("m-spicy"), Mockito.eq("user-1"), Mockito.anyLong());
    }

    @Test
    void testConflictingMemoriesCancellationStaminaVsRest() {
        MemoryRepositoryPort repo = mockRepo();
        TravelMemoryService memService = com.ai.guide.testing.MemoryTestFixtures.service(repo);

        Mockito.when(repo.findActiveIdAndContent("user-1"))
                .thenReturn(List.of(Map.of("id", "m-tired", "content", "少走路少爬坡避台阶")));

        memService.confirm("user-1", "特种兵暴走多走走", "PACE", "USER_EDIT");

        Mockito.verify(repo).softDelete(Mockito.eq("m-tired"), Mockito.eq("user-1"), Mockito.anyLong());
    }
}

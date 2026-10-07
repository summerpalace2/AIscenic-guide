package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TravelMemoryPersistenceTest {
    @Autowired private TravelMemoryService memories;
    @Autowired private PreferencesService preferences;
    @Autowired @Qualifier("knowledgeJdbcTemplate") private JdbcTemplate memoryJdbc;
    @Autowired private JdbcTemplate preferenceJdbc;
    private final String userId = "culture-memory-test-" + UUID.randomUUID();

    @AfterEach
    void cleanupOwnFixtures() {
        memoryJdbc.update("DELETE FROM travel_memory WHERE user_id = ?", userId);
        preferenceJdbc.update("DELETE FROM user_preference_audit WHERE user_id = ?", userId);
        preferenceJdbc.update("DELETE FROM user_preferences WHERE user_id = ?", userId);
    }

    @Test
    void confirmedCultureRoundTripUpdatesImagePreservesNotesAndDeduplicatesRetry() {
        memories.saveUserCustomizedPrompt(userId, "每天留出半小时休息");
        memories.confirm(userId, "偏好特色甜品，但对花生过敏", "DIET", "CHAT_CONFIRMED");
        TravelMemory culture = memories.confirm(userId, "偏好人文历史", "INTEREST", "CHAT_CONFIRMED");
        assertTrue(memories.list(userId).stream().anyMatch(m -> m.id().equals(culture.id())));
        String storedPrompt = (String) preferences.get(userId).legacyMetadata().get("profilePrependPrompt");
        assertTrue(storedPrompt.contains("偏好人文历史"));
        assertTrue(storedPrompt.contains("对花生过敏"));
        assertTrue(storedPrompt.contains("每天留出半小时休息"));
        assertTrue(memories.promptContext(userId).contains("偏好人文历史"));
        assertEquals(culture.id(), memories.confirm(userId, "偏好人文历史", "INTEREST", "CHAT_CONFIRMED").id());
        assertEquals(2, memories.list(userId).size());
        memories.delete(userId, culture.id());
        assertFalse(memories.promptContext(userId).contains("人文历史"));
        assertTrue(memories.promptContext(userId).contains("对花生过敏"));
    }

    @Test
    void synonymousSourcesHaveOneImageFacetAndCanBeRemovedIndividually() {
        TravelMemory first = memories.confirm(userId, "偏好甜品糖水", "DIET", "CHAT_CONFIRMED", "我想吃点甜食的");
        TravelMemory second = memories.confirm(userId, "偏好甜品糖水与特色甜点", "DIET", "CHAT_CONFIRMED", "我想吃点甜食");
        memories.confirm(userId, "饮食偏好辣味，喜欢吃辣", "DIET", "CHAT_CONFIRMED", "我想吃点辣");
        assertEquals("SWEET", first.preferenceKey());
        assertEquals(first.preferenceKey(), second.preferenceKey());
        assertEquals(3, memories.list(userId).size(), "原始确认来源必须保留");
        String prompt = memories.promptContext(userId);
        assertEquals(1, prompt.split("喜欢甜口、甜品与糖水", -1).length - 1);
        assertTrue(prompt.contains("喜欢辣口"));
        memories.delete(userId, first.id());
        assertTrue(memories.promptContext(userId).contains("喜欢甜口、甜品与糖水"));
        assertEquals("我想吃点甜食", memories.list(userId).stream().filter(m -> m.id().equals(second.id())).findFirst().orElseThrow().sourceRef());
        memories.delete(userId, second.id());
        assertFalse(memories.promptContext(userId).contains("喜欢甜口、甜品与糖水"));
        assertTrue(memories.promptContext(userId).contains("喜欢辣口"));
    }
    @Test
    void oldGeneratedImageRefreshesButLaterManualEditStaysUntouched() {
        memories.saveUserCustomizedPrompt(userId, "每天留出半小时休息");
        memories.confirm(userId, "偏好甜品糖水", "DIET", "CHAT_CONFIRMED");
        memories.confirm(userId, "偏好甜品糖水与特色甜点", "DIET", "CHAT_CONFIRMED");
        var current = preferences.get(userId);
        var metadata = new java.util.LinkedHashMap<>(current.legacyMetadata());
        metadata.remove("memoryProjectionVersion");
        metadata.put("profilePrependPrompt", "旧画像：偏好甜品糖水；偏好甜品糖水与特色甜点");
        preferences.merge(userId, new com.ai.guide.domain.preferences.model.UserPreferencesPatch(
                null, null, null, null, null, null, null, metadata, current.revision()), current.revision());
        assertTrue(memories.promptContext(userId).contains("喜欢甜口、甜品与糖水"));
        assertTrue(memories.promptContext(userId).contains("每天留出半小时休息"));
        assertEquals(2, memories.list(userId).size());
        current = preferences.get(userId);
        metadata = new java.util.LinkedHashMap<>(current.legacyMetadata());
        metadata.remove("memoryProjectionVersion");
        metadata.put("profilePrependPrompt", "我手动编辑的完整画像");
        metadata.put("promptLastEditedAt", System.currentTimeMillis() + 1000);
        preferences.merge(userId, new com.ai.guide.domain.preferences.model.UserPreferencesPatch(
                null, null, null, null, null, null, null, metadata, current.revision()), current.revision());
        assertTrue(memories.promptContext(userId).startsWith("我手动编辑的完整画像"));
        assertEquals("我手动编辑的完整画像", preferences.get(userId).legacyMetadata().get("profilePrependPrompt"));
    }
}

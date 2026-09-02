package com.ai.guide.domain.preferences.repository;

import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PreferencesRepositoryTest {

    @Autowired
    private PreferencesRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String userId;

    @AfterEach
    void cleanup() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM user_preference_audit WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_preferences WHERE user_id = ?", userId);
        }
    }

    @Test
    void roundTripPreservesTypedFieldsAndCasRevision() {
        userId = "phase5c-test-" + UUID.randomUUID();
        UserPreferences first = new UserPreferences(
                userId, PreferencesSchema.CURRENT_SCHEMA_VERSION, 1,
                List.of("城市", "夜景"), UserPreferences.WalkingTolerance.LOW,
                new BudgetPreference(BudgetPreference.Level.AMOUNT, new BigDecimal("1200"), "CNY"),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                UserPreferences.DietPreference.VEGETARIAN,
                "解放碑", Map.of("source", "synthetic"), 10L, 20L);

        repository.insert(first);
        Optional<UserPreferences> loaded = repository.findByUserId(userId);
        assertTrue(loaded.isPresent());
        assertEquals(first, loaded.get());

        UserPreferences next = new UserPreferences(
                userId, PreferencesSchema.CURRENT_SCHEMA_VERSION, 2,
                first.interests(), first.walkingTolerance(), first.budget(), first.companions(),
                first.transportPreference(), first.dietPreference(), first.stayArea(),
                first.legacyMetadata(), first.createdAt(), 30L);
        assertTrue(repository.updateCas(next, 1L));
        assertEquals(2L, repository.findByUserId(userId).orElseThrow().revision());
    }
}

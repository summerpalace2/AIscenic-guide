package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.planner.narrative.GroundedNarrativeService;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannerMemorySemanticRegressionTest {
    @AfterEach void clear() { UserContext.clear(); }

    private PlannerPreferencesResolver resolver(String context) {
        var preferences = mock(PreferencesService.class);
        var memory = mock(TravelMemoryService.class);
        var empty = UserPreferences.empty("owner", 0, 0);
        when(preferences.get("owner")).thenReturn(new UserPreferences(empty.userId(), empty.schemaVersion(),
                empty.revision(), empty.interests(), empty.walkingTolerance(), empty.budget(), empty.companions(),
                empty.transportPreference(), empty.dietPreference(), empty.stayArea(),
                Map.of("travelMemoryEnabled", true), 0, 0));
        when(memory.promptContext("owner")).thenReturn(context);
        UserContext.set("owner", "TOURIST");
        return new PlannerPreferencesResolver(preferences, new ObjectMapper(), memory);
    }

    @Test void hotpotAndDessertRemainTogetherWithoutInventingBlandDiet() {
        var base = new TravelConstraints();
        var result = resolver("喜欢吃麻辣火锅；偏好甜品糖水").resolve(base, true).constraints();
        assertEquals(base.getDietPreference(), result.getDietPreference());
        String context = String.valueOf(result.getShadowSemanticPreferences().get("confirmedMemoryContext"));
        assertTrue(context.contains("麻辣火锅"));
        assertTrue(context.contains("甜品糖水"));
    }

    @Test void highBudgetAndFriendsDoNotBecomeLimitedBudgetAndCouple() {
        var base = new TravelConstraints();
        var result = resolver("预算充足；和朋友出行").resolve(base, true).constraints();
        assertEquals(base.getBudget(), result.getBudget());
        assertEquals(base.getCompanions(), result.getCompanions());
    }

    @Test void explicitCurrentConstraintsStayAboveHistoricalMemory() {
        var base = new TravelConstraints();
        base.setDietPreference("素食"); base.markOrigin("dietPreference", ConstraintOrigin.REQUEST);
        base.setBudget("中等"); base.markOrigin("budget", ConstraintOrigin.PROMPT);
        var result = resolver("爱吃麻辣火锅；预算充足").resolve(base, true).constraints();
        assertEquals("素食", result.getDietPreference());
        assertEquals("中等", result.getBudget());
        assertEquals(ConstraintOrigin.REQUEST, result.originOf("dietPreference"));
    }

    @Test void disabledRecallNeverAddsMemoryContext() {
        var result = resolver("喜欢火锅").resolve(new TravelConstraints(), false).constraints();
        assertFalse(result.getShadowSemanticPreferences().containsKey("confirmedMemoryContext"));
    }

    @Test void narrativePromptConsumesOriginalMemoryWithLowerPriority() throws Exception {
        var base = new TravelConstraints();
        base.setDietPreference("素食"); base.markOrigin("dietPreference", ConstraintOrigin.REQUEST);
        var resolved = resolver("喜欢吃麻辣火锅；偏好甜品糖水；预算充足；和朋友出行").resolve(base, true);
        var narrative = new GroundedNarrativeService((system, user) -> { throw new AssertionError("no provider calls"); },
                new ObjectMapper(), true, 1000, true);
        var method = GroundedNarrativeService.class.getDeclaredMethod("buildUserPrompt", Map.class,
                TravelConstraints.class, AppliedPreferencesSnapshot.class);
        method.setAccessible(true);
        String prompt = (String) method.invoke(narrative, Map.of("days", java.util.List.of()), resolved.constraints(), resolved.snapshot());
        var json = new ObjectMapper().readTree(prompt).get("userPreferences");
        assertEquals("素食", json.get("dietPreference").asText());
        assertTrue(json.get("confirmedMemoryContext").asText().contains("甜品糖水"));
        assertTrue(json.get("confirmedMemoryContext").asText().contains("麻辣火锅"));
        assertTrue(json.get("memoryPriority").asText().contains("以当前输入及结构化字段为准"));
    }
}

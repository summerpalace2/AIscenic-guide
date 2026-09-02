package com.ai.guide.domain.planner.service;


import com.ai.guide.domain.planner.model.TravelConstraints;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelConstraintParserTest {

    private final TravelConstraintParser parser = new TravelConstraintParser();

    @Test
    void parsesGoldenPersonaConstraints() {
        TravelConstraints constraints = parser.parse("周六下午到重庆，周日晚上离开，带父母，希望少走路，预算有限，喜欢城市、人文和夜景。");

        assertEquals("周六下午", constraints.getArrivalAt());
        assertEquals("周日晚上", constraints.getDepartureAt());
        assertEquals("带父母", constraints.getCompanions());
        assertEquals("低", constraints.getWalkingTolerance());
        assertEquals("有限", constraints.getBudget());
        assertTrue(constraints.getInterests().contains("城市"));
        assertTrue(constraints.getInterests().contains("人文"));
        assertTrue(constraints.getInterests().contains("夜景"));
    }

    @Test
    void explicitOverridesWinOverPrompt() {
        TravelConstraints base = parser.parse("带父母，喜欢夜景");
        TravelConstraints next = parser.applyOverrides(base, Map.of("walkingTolerance", "正常", "durationDays", 4, "interests", java.util.List.of("美食")));

        assertEquals("正常", next.getWalkingTolerance());
        assertEquals(4, next.getDurationDays());
        assertEquals(java.util.List.of("美食"), next.getInterests());
        assertEquals("request", ((Map<?, ?>) next.asMap().getOrDefault("constraintProvenance", Map.of())).get("durationDays"));
    }

    @Test
    void reportsMissingCriticalTimeWithoutPretendingDefaultsWereExplicit() {
        TravelConstraints constraints = parser.parse("帮我规划重庆旅游");

        assertTrue(constraints.isNeedsClarification());
        assertTrue(constraints.getCriticalMissingFields().contains("arrivalAt"));
        assertTrue(constraints.getCriticalMissingFields().contains("departureAt"));
        assertTrue(constraints.getCriticalMissingFields().contains("durationDays"));
        assertEquals("default", ((Map<?, ?>) constraints.asMap().getOrDefault("constraintProvenance", Map.of())).get("durationDays"));
    }

    @Test
    void doesNotInferWalkingFromCompanionAndHandlesNegation() {
        TravelConstraints constraints = parser.parse("带父母，不喜欢夜景，不坐地铁");

        assertEquals("正常", constraints.getWalkingTolerance());
        assertFalse(constraints.getInterests().contains("夜景"));
        assertTrue(constraints.getAvoid().contains("夜景"));
        assertEquals("未提供", constraints.getTransportPreference());
    }

    @Test
    void dietaryRestrictionWinsOverCuisineKeyword() {
        TravelConstraints constraints = parser.parse("想吃素食火锅");

        assertEquals("素食", constraints.getDietPreference());
    }
}

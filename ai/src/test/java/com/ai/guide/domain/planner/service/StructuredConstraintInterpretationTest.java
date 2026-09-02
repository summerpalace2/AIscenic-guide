package com.ai.guide.domain.planner.service;




import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.api.ShadowPlanRequest;
import com.ai.guide.domain.planner.api.StructuredPreferences;
import com.ai.guide.domain.planner.model.ConstraintConflict;
import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredConstraintInterpretationTest {

    private final ConstraintConflictDetector conflictDetector = new ConstraintConflictDetector();
    private final LlmShadowPreferenceExtractor shadowExtractor = new LlmShadowPreferenceExtractor();
    private final TravelConstraintParser parser = new TravelConstraintParser(conflictDetector, shadowExtractor);

    @Test
    void structuredPreferencesAndFreeTextMergeWithCorrectOrigin() {
        PlanRequest request = PlanRequest.builder()
                .freeText("周六上午到重庆，周日离开，喜欢夜景和美食")
                .structuredPreferences(StructuredPreferences.builder()
                        .durationDays(2)
                        .companion("带父母")
                        .walkingPreference("低")
                        .budget("有限")
                        .build())
                .build();

        assertEquals("周六上午到重庆，周日离开，喜欢夜景和美食", request.effectivePrompt());
        Map<String, Object> constraints = request.effectiveConstraints();
        assertEquals(2, constraints.get("durationDays"));
        assertEquals("带父母", constraints.get("companions"));
        assertEquals("低", constraints.get("walkingTolerance"));
        assertEquals("有限", constraints.get("budget"));

        TravelConstraints base = parser.parse(request.effectivePrompt());
        TravelConstraints resolved = parser.applyOverrides(base, constraints);

        assertEquals("带父母", resolved.getCompanions());
        assertEquals("低", resolved.getWalkingTolerance());
        assertEquals(ConstraintOrigin.REQUEST, resolved.originOf("companions"));
        assertEquals(ConstraintOrigin.REQUEST, resolved.originOf("walkingTolerance"));
        assertEquals(ConstraintOrigin.PROMPT, resolved.originOf("arrivalAt"));
    }

    @Test
    void detectsMustVisitVsAvoidConflict() {
        TravelConstraints base = parser.parse("想去洪崖洞和解放碑");
        TravelConstraints next = parser.applyOverrides(base, Map.of(
                "mustVisit", List.of("洪崖洞"),
                "avoid", List.of("洪崖洞")
        ));

        assertTrue(next.isNeedsClarification());
        assertFalse(next.getConflicts().isEmpty());
        ConstraintConflict conflict = next.getConflicts().get(0);
        assertEquals("mustVisit_vs_avoid", conflict.field());
        assertEquals("HARD_MUTUAL_EXCLUSION", conflict.conflictType());
        assertTrue(conflict.message().contains("洪崖洞"));
    }

    @Test
    void detectsDurationMismatchBetweenTextAndUI() {
        TravelConstraints base = parser.parse("玩3天");
        assertEquals(3, base.getDurationDays());
        assertEquals(ConstraintOrigin.PROMPT, base.originOf("durationDays"));

        TravelConstraints next = parser.applyOverrides(base, Map.of("durationDays", 2));
        assertEquals(2, next.getDurationDays());
        assertEquals(ConstraintOrigin.REQUEST, next.originOf("durationDays"));

        List<ConstraintConflict> conflicts = conflictDetector.detectConflicts(base, next);
        assertFalse(conflicts.isEmpty());
        assertEquals("durationDays", conflicts.get(0).field());
        assertEquals("DURATION_MISMATCH", conflicts.get(0).conflictType());
    }

    @Test
    void detectsDepartureBeforeArrivalConflict() {
        TravelConstraints base = parser.parse("周五到重庆，周三离开");
        TravelConstraints next = parser.applyOverrides(base, Map.of());

        List<ConstraintConflict> conflicts = conflictDetector.detectConflicts(base, next);
        assertFalse(conflicts.isEmpty());
        assertEquals("travel_dates", conflicts.get(0).field());
        assertEquals("DEPARTURE_BEFORE_ARRIVAL", conflicts.get(0).conflictType());
    }

    @Test
    void shadowExtractorCapturesOpenNuancesWithoutAlteringFormalConstraints() {
        LlmShadowPreferenceExtractor.ShadowExtractionResult result =
                shadowExtractor.extractShadowPreferences("带父母，不想太赶，避开网红景点，更想体验地道老重庆和拍照");

        assertTrue(result.isShadowOnly());
        assertEquals(ConstraintOrigin.USER_TEXT, result.origin());
        assertEquals("RELAXED", result.openPreferences().get("pacing"));
        assertEquals("AVOID_CROWDED", result.openPreferences().get("crowdPreference"));
        assertEquals("AUTHENTIC_LOCAL", result.openPreferences().get("experienceTheme"));
        assertEquals(true, result.openPreferences().get("photographyFocus"));
        assertTrue(result.inferredTags().contains("慢节奏游览"));
        assertTrue(result.inferredTags().contains("小众清幽"));
        assertTrue(result.inferredTags().contains("地道老重庆"));
        assertTrue(result.inferredTags().contains("摄影取景"));
    }

    @Test
    void provenancePrecedenceHierarchyIsConsistent() {
        assertTrue(ConstraintOrigin.USER_CONFIRMED.higherOrEqualThan(ConstraintOrigin.USER_TEXT));
        assertTrue(ConstraintOrigin.USER_TEXT.higherOrEqualThan(ConstraintOrigin.UI_FORM));
        assertTrue(ConstraintOrigin.UI_FORM.higherOrEqualThan(ConstraintOrigin.PREFERENCE));
        assertTrue(ConstraintOrigin.PREFERENCE.higherOrEqualThan(ConstraintOrigin.DERIVED));
        assertTrue(ConstraintOrigin.DERIVED.higherOrEqualThan(ConstraintOrigin.LLM_INFERRED));
        assertTrue(ConstraintOrigin.LLM_INFERRED.higherOrEqualThan(ConstraintOrigin.SYSTEM_DEFAULT));
        assertTrue(ConstraintOrigin.PROMPT.higherOrEqualThan(ConstraintOrigin.REQUEST));
    }
}

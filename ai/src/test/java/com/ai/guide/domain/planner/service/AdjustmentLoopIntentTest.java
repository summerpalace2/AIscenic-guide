package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AdjustmentLoopIntentTest {
    @Test void optimizeVerbWorksOnEitherSideOfItsObject() {
        var classifier = new ConversationIntentClassifier();
        for (String text : new String[]{"将傍晚和晚间行程优化为最佳夜景观景点", "优化傍晚和晚间行程为夜景路线"}) {
            var intent = classifier.classify(text, PlanPageContext.builder().activeDay(2).build());
            assertEquals(ConversationIntentType.REPLAN_DAY, intent.getType());
            assertEquals(2, intent.getDayNumber()); assertTrue(intent.getPreferences().contains("NIGHT_VIEW"));
            AdjustmentTimeScopeResolver.resolve(intent, new TaxonomyProvider());
            assertEquals(1020, intent.getTargetStartMinute()); assertEquals(1440, intent.getTargetEndMinute());
        }
    }
    @Test void anUnboundLetterDoesNotSilentlyChooseHistory() {
        var intent = new ConversationIntentClassifier().classify("方案A选这个，帮我局部调整行程", PlanPageContext.builder().activeDay(1).build());
        assertTrue(intent.isRequiresClarification()); assertFalse(intent.getPreferences().contains("HISTORY"));
    }
    @Test void nonContiguousTimePartsRequireClarification() {
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.REPLAN_DAY).rawMessage("优化上午和晚间行程").build();
        AdjustmentTimeScopeResolver.resolve(intent,new TaxonomyProvider());
        assertTrue(intent.isRequiresClarification()); assertNull(intent.getTargetStartMinute());
    }
}

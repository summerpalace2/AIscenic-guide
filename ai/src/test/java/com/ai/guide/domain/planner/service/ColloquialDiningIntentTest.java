package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ColloquialDiningIntentTest {
    private final ConversationIntentClassifier classifier = new ConversationIntentClassifier();
    private final PlanPageContext context = PlanPageContext.builder().activeDay(1).build();

    @Test
    void particlesAndQuantitiesDoNotChangeStructuredDessertIntent() {
        for (String text : List.of("我想吃点甜食的", "我想吃点甜食", "我想吃一点甜的", "想来点甜品吧", "我想吃点甜食的呀")) {
            var intent = classifier.classify(text, context);
            assertEquals(ConversationIntentType.ADD_STOP, intent.getType(), text);
            assertEquals(true, intent.getGenericDiningCraving(), text);
            assertEquals("特色甜品糖水", intent.getCuisineOrPlace(), text);
            assertEquals("TEA_BREAK", intent.getTimeSlot(), text);
            assertNull(intent.getRequestedVenueName(), text);
        }
    }

    @Test
    void falselyClaimedCategoryStoreIsRejectedRatherThanInventingVenue() {
        for (String text : List.of("我想吃点甜食的", "我想吃点甜食", "我想吃火锅")) {
            String entity = text.substring(3);
            var extractor = new LlmIntentExtractor((system, user) ->
                    "{\"operation\":\"ADD_STOP\",\"cuisineOrPlace\":\"" + entity + "\",\"genericDiningCraving\":false,\"timeSlot\":\"LUNCH\",\"confidence\":0.98}",
                    new ObjectMapper(), classifier, true, true, 0.75, 500);
            var result = extractor.extract(text, context);
            assertEquals("INVALID_OUTPUT", result.llmFallbackReason(), text);
            assertEquals(true, result.intent().getGenericDiningCraving(), text);
            assertNull(result.intent().getRequestedVenueName(), text);
        }
    }

    @Test
    void modelOmittingSlotKeepsDeterministicSlotAndExplicitModelSlotStillWins() {
        var extractor = new LlmIntentExtractor((system, user) ->
                "{\"operation\":\"ADD_STOP\",\"cuisineOrPlace\":\"甜食\",\"genericDiningCraving\":true,\"confidence\":0.98}",
                new ObjectMapper(), classifier, true, true, 0.75, 500);
        var result = extractor.extract("我想吃点甜食", context);
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals("TEA_BREAK", result.intent().getTimeSlot());
        assertEquals("特色甜品糖水", result.intent().getCuisineOrPlace());
        var explicit = new LlmIntentExtractor((system, user) ->
                "{\"operation\":\"ADD_STOP\",\"cuisineOrPlace\":\"甜食\",\"genericDiningCraving\":true,\"timeSlot\":\"DINNER\",\"confidence\":0.98}",
                new ObjectMapper(), classifier, true, true, 0.75, 500);
        assertEquals("DINNER", explicit.extract("我想吃点甜食", context).intent().getTimeSlot());
    }

    @Test
    void particlesInsideSpecificRestaurantNameRemainIntact() {
        var intent = classifier.classify("我想去亲爱的甜品店", context);
        assertEquals(false, intent.getGenericDiningCraving());
        assertEquals("亲爱的甜品店", intent.getCuisineOrPlace());
        assertEquals("亲爱的甜品店", intent.getRequestedVenueName());
        var grounder = new DiningCategoryGrounder();
        assertEquals("亲爱的甜品店", grounder.resolveRequestedFlavor(intent, "默认"));
    }

    @Test
    void genericStructuredFieldsNormalizeWithoutWideningUnlistedFood() {
        var grounder = new DiningCategoryGrounder();
        assertEquals("特色甜品糖水", grounder.resolveRequestedFlavor(PlanAdjustmentIntent.builder()
                .genericDiningCraving(true).cuisineOrPlace("点甜食的呀").build(), "默认"));
        assertEquals("特色甜品糖水", grounder.normalizeGenericCuisine("点心"));
        assertEquals("螺蛳粉", grounder.resolveRequestedFlavor(PlanAdjustmentIntent.builder()
                .genericDiningCraving(true).cuisineOrPlace("一点螺蛳粉的呀").build(), "默认"));
    }
}

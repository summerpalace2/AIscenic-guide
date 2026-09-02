package com.ai.guide.domain.planner.service;


import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanPageContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationIntentClassifierTest {

    private final ConversationIntentClassifier classifier = new ConversationIntentClassifier();

    @Test
    void explicitDayAndAttractionArePreservedForAddStop() {
        var intent = classifier.classify("第二天加上北仓", PlanPageContext.builder().activeDay(1).build());

        assertEquals(ConversationIntentType.ADD_STOP, intent.getType());
        assertEquals(2, intent.getDayNumber());
        assertEquals("cq-beicang", intent.getTargetAttractionId());
    }

    @Test
    void explicitReductionCountIsPreserved() {
        var intent = classifier.classify("今天太赶了，少两个", PlanPageContext.builder().activeDay(2).build());

        assertEquals(ConversationIntentType.REDUCE_DAY_DENSITY, intent.getType());
        assertEquals(2, intent.getDayNumber());
        assertEquals(2, intent.getReduceCount());
    }

    @Test
    void candidateOrdinalMapsToExistingOptionId() {
        var intent = classifier.classify("换成第二个", PlanPageContext.builder()
                .activeDay(1)
                .activeProposalId("prop-1")
                .build());

        assertEquals(ConversationIntentType.APPLY_REPLACEMENT, intent.getType());
        assertEquals(2, intent.getCandidateIndex());
        assertEquals("option-2", intent.getOptionId());
        assertEquals("prop-1", intent.getProposalId());
    }

    @Test
    void selectedStopRemainsAuthoritativeForReplacement() {
        var intent = classifier.classify("这个景点不想去，换一个", PlanPageContext.builder()
                .activeDay(1)
                .selectedStopId("selected-stop")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("selected-stop", intent.getTargetStopId());
    }

    @Test
    void alternativeIndoorRecommendationUsesSelectedStopAsReplacementSource() {
        var intent = classifier.classify("推荐同片区其他室内景点", PlanPageContext.builder()
                .activeDay(2)
                .selectedStopId("selected-stop")
                .activeProposalId("prop-1")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("selected-stop", intent.getTargetStopId());
        assertTrue(intent.getPreferences().contains("INDOOR"));
    }

    @Test
    void namedSelectedStopInQuickActionRemainsReplacementSource() {
        var intent = classifier.classify("请推荐【南温泉风景区】的同片区可替换景点", PlanPageContext.builder()
                .activeDay(2)
                .selectedStopId("day2-south-hot-spring")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("day2-south-hot-spring", intent.getTargetStopId());
        assertFalse(intent.isRequiresClarification());
    }

    @Test
    void narrativePlanSelectionBecomesTripReplanWithoutExistingProposal() {
        var intent = classifier.classify("方案A：渝中区文化室内线，选这个帮我改行程", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.REPLAN_DAY, intent.getType());
        assertEquals("TRIP", intent.getScope());
        assertTrue(intent.getPreferences().contains("INDOOR"));
        assertTrue(intent.getPreferences().contains("LOW_WALKING"));
    }

    @Test
    void explicitSourceAndDestinationAreParsedAsDirectReplacement() {
        var intent = classifier.classify("把重庆中国三峡博物馆替换成重庆大剧院外围广场", PlanPageContext.builder()
                .activeDay(2)
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("三峡博物馆", intent.getTargetStopReference());
        assertEquals("cq-grand-theatre", intent.getReplacementPlaceId());
        assertEquals("重庆大剧院外围广场", intent.getReplacementPlaceName());
    }

    @Test
    void relativeLastEveningStopKeepsTheRequestedDestination() {
        var intent = classifier.classify("把晚上最后一个行程改成重庆大剧院外围广场", PlanPageContext.builder()
                .activeDay(2)
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("cq-grand-theatre", intent.getReplacementPlaceId());
        assertTrue(intent.getTargetStopReference().contains("最后一个"));
    }
}

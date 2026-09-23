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

    @Test
    void bracketedDynamicStopReplacementIntentMatches() {
        var intent = classifier.classify("把【凉风桠森林公园】替换为同片区其他景点", PlanPageContext.builder()
                .activeDay(1)
                .selectedStopId("day1-3")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("day1-3", intent.getTargetStopId());
        assertEquals("凉风桠森林公园", intent.getTargetStopReference());
        assertTrue(intent.getPreferences().contains("SAME_DISTRICT"));
    }

    @Test
    void bracketedIndoorReplacementMatches() {
        var intent = classifier.classify("请为【28阙阙馆】推荐适合替换的室内景点", PlanPageContext.builder()
                .activeDay(1)
                .selectedStopId("day1-4")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("day1-4", intent.getTargetStopId());
        assertEquals("28阙阙馆", intent.getTargetStopReference());
        assertTrue(intent.getPreferences().contains("INDOOR"));
    }

    @Test
    void sameDistrictQuickActionOnCoreAttractionRetainsSelectedStop() {
        var intent = classifier.classify("请推荐【解放碑步行街】的同片区可替换景点", PlanPageContext.builder()
                .activeDay(1)
                .selectedStopId("day1-jiefangbei")
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("day1-jiefangbei", intent.getTargetStopId());
        assertEquals("解放碑步行街", intent.getTargetStopReference());
        assertTrue(intent.getPreferences().contains("SAME_DISTRICT"));
    }

    @Test
    void surgeryMobilityLimitationClassifiesAsReduceDensityAndAccessibility() {
        var intent = classifier.classify("我上个月做了手术 不方便走路", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.REDUCE_DAY_DENSITY, intent.getType());
        assertEquals("REDUCE_DENSITY", intent.getOperation());
        assertEquals(1, intent.getDayNumber());
        assertTrue(intent.getPreferences().contains("LOW_WALKING"));
        assertTrue(intent.getConditions().contains("ACCESSIBILITY"));
        assertFalse(intent.isRequiresClarification());
    }

    @Test
    void narrativeDiningPlanSelectionBecomesDiningReplacement() {
        var intent = classifier.classify("方案A选这个，帮我局部调整行程：南山泉水鸡", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("南山泉水鸡", intent.getReplacementPlaceName());
        assertTrue(intent.getPreferences().contains("FOOD"));
        assertFalse(intent.isRequiresClarification());
    }

    @Test
    void narrativeDiningPlanSelectionDynamicDishes() {
        var intentLzj = classifier.classify("方案B选这个，帮我局部调整行程：歌乐山辣子鸡晚餐", PlanPageContext.builder()
                .activeDay(2)
                .build());
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intentLzj.getType());
        assertEquals("歌乐山辣子鸡", intentLzj.getReplacementPlaceName());

        var intentHg = classifier.classify("方案C选这个，帮我调整行程：地道九宫格老火锅", PlanPageContext.builder()
                .activeDay(1)
                .build());
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intentHg.getType());
        assertEquals("地道九宫格老火锅", intentHg.getReplacementPlaceName());
    }

    @Test
    void addStopRecognizesExpandedCatalogVenues() {
        var intentChaotianmen = classifier.classify("第二天加上朝天门", PlanPageContext.builder().activeDay(1).build());
        assertEquals(ConversationIntentType.ADD_STOP, intentChaotianmen.getType());
        assertEquals("cq-chaotianmen", intentChaotianmen.getTargetAttractionId());
        assertEquals(2, intentChaotianmen.getDayNumber());

        var intentZoo = classifier.classify("加上重庆动物园", PlanPageContext.builder().activeDay(1).build());
        assertEquals(ConversationIntentType.ADD_STOP, intentZoo.getType());
        assertEquals("cq-chongqing-zoo", intentZoo.getTargetAttractionId());

        var intentLongmenhao = classifier.classify("把龙门浩老街加到第一天", PlanPageContext.builder().activeDay(2).build());
        assertEquals(ConversationIntentType.ADD_STOP, intentLongmenhao.getType());
        assertEquals("cq-longmenhao", intentLongmenhao.getTargetAttractionId());
        assertEquals(1, intentLongmenhao.getDayNumber());
    }

    @Test
    void questionAboutElderlyOrAccessibilityDoesNotTriggerReduceDensity() {
        var intent = classifier.classify("这个地方适合老人吗？", PlanPageContext.builder().activeDay(1).selectedStopId("stop-1").build());
        assertEquals(ConversationIntentType.PLACE_QUESTION, intent.getType());
        assertEquals("QA", intent.getOperation());

        var intentKids = classifier.classify("带娃去方便吗？", PlanPageContext.builder().activeDay(1).selectedStopId("stop-1").build());
        assertEquals(ConversationIntentType.PLACE_QUESTION, intentKids.getType());
        assertEquals("QA", intentKids.getOperation());
    }

    @Test
    void narrativeBreakfastNoodlesSelectionBecomesDiningReplacement() {
        var intent = classifier.classify("采用方案A：早餐小面+上午平街观展（最贴合\"低步行\"，帮我局部调整行程", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("早餐小面", intent.getReplacementPlaceName());
        assertTrue(intent.getPreferences().contains("FOOD"));
        assertFalse(intent.isRequiresClarification());
    }

    @Test
    void narrativeLunchNoodlesSelectionBecomesDiningReplacement() {
        var intent = classifier.classify("采用方案B：把较场口午餐换成小面（帮我局部调整行程", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("REPLACE_STOP", intent.getOperation());
        assertEquals("小面", intent.getReplacementPlaceName());
        assertTrue(intent.getPreferences().contains("FOOD"));
        assertFalse(intent.isRequiresClarification());
    }

    @Test
    void narrativePlanWithLocalAdjustmentSpecifiesDayScope() {
        var intent = classifier.classify("方案A：渝中区文化室内线，帮我局部调整行程", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.REPLAN_DAY, intent.getType());
        assertEquals("DAY", intent.getScope());
        assertTrue(intent.getPreferences().contains("INDOOR"));
    }

    @Test
    void narrativePlanWithSegmentAdditionSpecifiesDayScopeAndExtractsMuseum() {
        var intent = classifier.classify("在当前行程草稿中新增一段渝中·曾家岩人文观展安排，包含重庆中国三峡博物馆", PlanPageContext.builder()
                .activeDay(1)
                .build());

        assertEquals(ConversationIntentType.REPLAN_DAY, intent.getType());
        assertEquals("DAY", intent.getScope());
        assertEquals(1, intent.getDayNumber());
        assertEquals("cq-museum", intent.getTargetAttractionId());
        assertTrue(intent.getPreferences().contains("INDOOR"));
        assertTrue(intent.getPreferences().contains("HISTORY"));
    }
}

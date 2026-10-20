package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.language.IntentLanguagePolicy;
import com.ai.guide.domain.planner.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ContinuationCorrectionBoundaryTest {
    private final PlanPageContext page=PlanPageContext.builder().activeDay(1).pinnedStopIds(List.of("pin")).build();
    private PlanAdjustmentIntent queuedReply(String text) {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
        var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("添加三峡博物馆，删除洪崖洞",page);
        var pending=store.remember("s",1,intent);
        return store.resume("s",1,pending.intentId(),text,page);
    }
    @Test void refusalNeverSelectsNamedOldAction() {
        var answer=queuedReply("不要添加三峡博物馆");
        assertTrue(answer.isRequiresClarification());assertNotEquals(ConversationIntentType.ADD_STOP,answer.getType());
    }
    @Test void positiveOrdinalWinsOverRefusedVenue() {
        var answer=queuedReply("不要添加三峡博物馆，执行第二个");
        assertEquals("REMOVE_STOP",answer.getOperation());assertEquals("洪崖洞",answer.getTargetStopReference());
    }
    @Test void refusedActionNeverReturnsInQueueAfterApplyingAnotherAction() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier()
                .classify("添加三峡博物馆，删除洪崖洞",page);var p=store.remember("s",1,intent);
        var answer=store.resume("s",1,p.intentId(),"不要添加三峡博物馆，执行第二个",page);
        assertEquals("REMOVE_STOP",answer.getOperation());store.afterApply("s",2);
        assertNull(store.pending("s"));assertNull(store.resume("s",2,null,"执行下一个",page));
    }
    @Test void refusingAdditionDoesNotRejectRemovalOfSameVenue() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier()
                .classify("添加三峡博物馆，删除三峡博物馆",page);var p=store.remember("s",1,intent);
        var answer=store.resume("s",1,p.intentId(),"不要添加三峡博物馆，执行第二个",page);
        assertEquals("REMOVE_STOP",answer.getOperation());assertFalse(answer.isRequiresClarification());
        store.afterApply("s",2);assertNull(store.pending("s"));
    }
    @Test void newCompleteReplacementCannotBeCapturedAsOldAddition() {
        var answer=queuedReply("把三峡博物馆换成重庆大剧院");
        assertEquals("REPLACE_STOP",answer.getOperation());assertEquals("重庆大剧院",answer.getReplacementPlaceName());
        assertTrue(answer.getPinnedStopIds().contains("pin"));
    }
    @Test void sameKindOperationsKeepBothDaysAfterApply() {
        var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("第2天添加三峡博物馆，第3天添加重庆大剧院",page);
        assertTrue(intent.isRequiresClarification());assertEquals(2,intent.getCompoundActions().size());
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var pending=store.remember("s",1,intent);
        var first=store.resume("s",1,pending.intentId(),"执行第一个",page);assertEquals(2,first.getDayNumber());
        store.afterApply("s",2);
        var second=store.resume("s",2,store.pending("s").intentId(),"执行下一个",page);
        assertEquals(3,second.getDayNumber());assertEquals("重庆大剧院",second.getRequestedVenueName());
    }
    @Test void oneClauseWithSeveralVenuesIsNotSplitIntoArtificialActions() {
        assertTrue(IntentLanguagePolicy.distinctActionClauses("添加三峡博物馆和重庆大剧院").isEmpty());
    }
    @Test void completeCorrectionReplacesOldTargetWhilePreservingPins() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
        var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REPLACE_STOP")
                .dayNumber(2).replacementPlaceName("三峡博物馆").replacementPlaceId("cq-museum").targetAttractionId("cq-museum")
                .pinnedStopIds(List.of("saved-pin")).preserveReferences(List.of("保留早上行程"))
                .missingFields(List.of("TARGET_STOP")).requiresClarification(true).rawMessage("第2天换成三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"把洪崖洞换成重庆大剧院",page);
        assertEquals(2,answer.getDayNumber());assertEquals("洪崖洞",answer.getTargetStopReference());
        assertEquals("重庆大剧院",answer.getReplacementPlaceName());assertEquals("cq-grand-theatre",answer.getReplacementPlaceId());
        assertTrue(answer.getPinnedStopIds().containsAll(List.of("pin","saved-pin")));assertTrue(answer.getPreserveReferences().contains("保留早上行程"));
    }
    @Test void bareSourceAnswerStillUsesPreviouslyChosenTarget() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REPLACE_STOP").dayNumber(2).replacementPlaceName("三峡博物馆").missingFields(List.of("TARGET_STOP"))
                .requiresClarification(true).rawMessage("第2天换成三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"洪崖洞",page);
        assertEquals("三峡博物馆",answer.getReplacementPlaceName());assertEquals("洪崖洞",answer.getTargetStopReference());
    }
    @Test void completeAdditionCorrectionDoesNotOnlyFillTimeForOldVenue() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("ADD_STOP").dayNumber(2).requestedVenueName("三峡博物馆").targetAttractionId("cq-museum")
                .pinnedStopIds(List.of("saved-pin")).missingFields(List.of("TIME_RANGE"))
                .requiresClarification(true).rawMessage("第2天18:90添加三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"18:00以后添加重庆大剧院",page);
        assertEquals("ADD_STOP",answer.getOperation());assertEquals("重庆大剧院",answer.getRequestedVenueName());
        assertEquals("cq-grand-theatre",answer.getTargetAttractionId());assertEquals(2,answer.getDayNumber());
        assertEquals(1080,answer.getTargetStartMinute());assertEquals(1440,answer.getTargetEndMinute());
        assertFalse(answer.isRequiresClarification());assertTrue(answer.getPinnedStopIds().contains("saved-pin"));
    }
    @Test void bareTimeCorrectionKeepsOriginallyChosenVenue() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("ADD_STOP").dayNumber(2).requestedVenueName("三峡博物馆").missingFields(List.of("TIME_RANGE"))
                .requiresClarification(true).rawMessage("第2天18:90添加三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"18:00以后",page);
        assertEquals("三峡博物馆",answer.getRequestedVenueName());assertEquals(1080,answer.getTargetStartMinute());
    }
    @Test void invalidTimeInCompleteCorrectionKeepsNewTargetForNextClarification() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("ADD_STOP").dayNumber(2).requestedVenueName("三峡博物馆").missingFields(List.of("TIME_RANGE"))
                .requiresClarification(true).rawMessage("第2天18:90添加三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"18:90以后添加重庆大剧院",page);
        assertEquals("重庆大剧院",answer.getRequestedVenueName());assertTrue(answer.isRequiresClarification());
        assertTrue(answer.getMissingFields().contains("TIME_RANGE"));
        p=store.remember("s",1,answer);var corrected=store.resume("s",1,p.intentId(),"18:00以后",page);
        assertEquals("重庆大剧院",corrected.getRequestedVenueName());assertFalse(corrected.isRequiresClarification());
    }
    @Test void completeRemovalCorrectionUsesNewSourceWhileResolvingTime() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REMOVE_STOP").dayNumber(2).targetStopReference("三峡博物馆").missingFields(List.of("TIME_RANGE"))
                .requiresClarification(true).rawMessage("第2天18:90删除三峡博物馆").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"18:00以后删除洪崖洞",page);
        assertEquals("REMOVE_STOP",answer.getOperation());assertEquals("洪崖洞",answer.getTargetStopReference());
        assertEquals(1080,answer.getTargetStartMinute());assertEquals(2,answer.getDayNumber());
    }
    @Test void completeReplanCorrectionKeepsNewPurposeAndOldProtection() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var old=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REPLAN_DAY").dayNumber(2).preferences(List.of("LOW_WALKING"))
                .missingFields(List.of("TIME_RANGE")).requiresClarification(true).rawMessage("第2天18:90重新规划少走路").build();
        var p=store.remember("s",1,old);var answer=store.resume("s",1,p.intentId(),"18:00以后重新规划夜景",page);
        assertEquals("REPLAN_DAY",answer.getOperation());assertTrue(answer.getPreferences().containsAll(List.of("LOW_WALKING","NIGHT_VIEW")));
        assertEquals(1080,answer.getTargetStartMinute());assertEquals(2,answer.getDayNumber());
    }

    @Test void dateReplyWhileWaitingForTargetKeepsTheOperationAndUnfilledSlot() {
        for (String reply : List.of("第二天", "第2天吧", "改到第二天", "第二天处理", "就第二天")) {
            var store = new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
            var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                    .operation("REPLACE_STOP").dayNumber(1).scope("STOP")
                    .replacementPlaceName("三峡博物馆").replacementPlaceId("cq-museum")
                    .missingFields(List.of("TARGET_STOP")).requiresClarification(true)
                    .preferences(List.of("LOW_WALKING")).conditions(List.of("RAIN"))
                    .preserveReferences(List.of("保留早上行程")).pinnedStopIds(List.of("saved-pin"))
                    .rawMessage("第1天换成三峡博物馆").build();
            var pending = store.remember("s", 1, original);
            var day = store.resume("s", 1, pending.intentId(), reply, page);
            assertEquals("REPLACE_STOP", day.getOperation()); assertEquals(2, day.getDayNumber());
            assertEquals(List.of("TARGET_STOP"), day.getMissingFields()); assertTrue(day.isRequiresClarification());
            assertNull(day.getTargetStopReference()); assertEquals("cq-museum", day.getReplacementPlaceId());
            assertTrue(day.getPinnedStopIds().containsAll(List.of("saved-pin", "pin")));
            assertEquals(original.getPreferences(), day.getPreferences()); assertEquals(original.getConditions(), day.getConditions());
            assertEquals(original.getPreserveReferences(), day.getPreserveReferences());
            pending = store.remember("s", 1, day);
            var completed = store.resume("s", 1, pending.intentId(), "洪崖洞", page);
            assertEquals(2, completed.getDayNumber()); assertEquals("REPLACE_STOP", completed.getOperation());
            assertEquals("洪崖洞", completed.getTargetStopReference()); assertFalse(completed.isRequiresClarification());
        }
    }

    @Test void dayAmendmentClearsConcreteStopIdFromTheOldDayWithoutFillingTime() {
        var store = new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());
        var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REMOVE_STOP").dayNumber(1).targetStopId("day1-stop").targetStopReference("洪崖洞")
                .missingFields(List.of("TIME_RANGE")).requiresClarification(true).rawMessage("第1天18:90删除洪崖洞").build();
        var pending = store.remember("s", 1, original);
        var answer = store.resume("s", 1, pending.intentId(), "改到第二天", page);
        assertEquals(2, answer.getDayNumber()); assertNull(answer.getTargetStopId());
        assertEquals("洪崖洞", answer.getTargetStopReference()); assertEquals(List.of("TIME_RANGE"), answer.getMissingFields());
    }

    @Test void naturalTimeAndCompoundSelectionReuseTheConfiguredTaxonomy() {
        var classifier = com.ai.guide.testing.PlannerTaxonomyFixtures.classifier();
        var store = new PendingIntentContinuations(classifier);
        var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REPLAN_DAY").dayNumber(2).preferences(List.of("NIGHT_VIEW"))
                .missingFields(List.of("TIME_RANGE")).requiresClarification(true).rawMessage("第2天18:90重新规划夜景").build();
        var pending = store.remember("s", 1, original);
        var answer = store.resume("s", 1, pending.intentId(), "晚上", page);
        assertEquals(1140, answer.getTargetStartMinute()); assertEquals(1440, answer.getTargetEndMinute());
        assertFalse(answer.isRequiresClarification()); assertTrue(answer.isTimeScopeResolved());
        var compound = classifier.classify("晚上添加三峡博物馆，删除洪崖洞", page);
        pending = store.remember("s", 1, compound);
        var selected = store.resume("s", 1, pending.intentId(), "执行第一个", page);
        assertEquals("ADD_STOP", selected.getOperation()); assertEquals(1140, selected.getTargetStartMinute());
        assertEquals(1440, selected.getTargetEndMinute());
    }

    @Test void dayReplyCannotBypassWrongIdExpiryCancellationOrACompleteNewCommand() {
        var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        var store = new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier(), clock::get);
        var original = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REMOVE_STOP").dayNumber(1).missingFields(List.of("TARGET_STOP"))
                .requiresClarification(true).rawMessage("删除这个景点").build();
        var pending = store.remember("s", 1, original);
        assertNull(store.resume("s", 1, "wrong-id", "第二天", page)); assertNotNull(store.pending("s"));
        clock.addAndGet(600001);
        assertNull(store.resume("s", 1, pending.intentId(), "第二天", page)); assertNull(store.pending("s"));
        pending = store.remember("s", 1, original);
        assertNull(store.resume("s", 1, pending.intentId(), "取消，改到第二天", page)); assertNull(store.pending("s"));
        pending = store.remember("s", 1, original);
        assertNull(store.resume("s", 1, pending.intentId(), "第2天添加三峡博物馆", page));
        assertFalse(ConversationIntentClassifier.isDayOnlyReply("第2天删除洪崖洞"));
        assertFalse(ConversationIntentClassifier.isDayOnlyReply("第2天的天气怎么样"));
    }
}

package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class ConversationContinuationRegressionTest {
    private PlanPageContext page() { return PlanPageContext.builder().activeDay(1).activeProposalId("p").build(); }
    private PlanAdjustmentIntent pending() { return PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("ADD_STOP").dayNumber(2).scope("DAY").missingFields(List.of("VENUE_OR_PREFERENCE")).requiresClarification(true).conditions(List.of("RAIN")).preserveReferences(List.of("保留早上站点")).rawMessage("第2天添加景点").build(); }
    @Test void completePlaceAnswerFillsOriginalDayAndConstraints() {
        var store=new PendingIntentContinuations();var p=store.remember("s",1,pending());var answer=store.resume("s",1,p.intentId(),"我想去三峡博物馆",page());
        assertNotNull(answer);assertEquals(2,answer.getDayNumber());assertEquals(ConversationIntentType.ADD_STOP,answer.getType());assertTrue(answer.getConditions().contains("RAIN"));assertTrue(answer.getPreserveReferences().contains("保留早上站点"));
    }
    @Test void expiredOrForeignContinuationCannotResume() {
        var now=new AtomicLong(1);var store=new PendingIntentContinuations(now::get);var p=store.remember("s",1,pending());assertNull(store.resume("s",1,"foreign","三峡博物馆",page()));
        now.set(600002);assertNull(store.resume("s",1,p.intentId(),"三峡博物馆",page()));
    }
    @Test void resetAndNewRoundPreventOldPublication() {
        var store=new PendingIntentContinuations();String first=store.begin("s");store.reset("s");assertFalse(store.commit("s",first,()->fail("old callback ran")));
        String second=store.begin("s");store.begin("s");assertFalse(store.commit("s",second,()->fail("superseded callback ran")));assertFalse(store.validProposal("s",0));
    }
    @Test void positiveChoiceKeepsNegationBoundToItsEntity() {
        var classifier=new ConversationIntentClassifier();assertEquals("option-3",classifier.classify("不要选第二个，选第三个",page()).getOptionId());
        assertNotEquals(ConversationIntentType.ADD_STOP,classifier.classify("不要加入三峡博物馆",page()).getType());
        assertFalse(IntentLanguagePolicy.positiveText("不要想吃火锅").contains("火锅"));
    }
    @Test void boundedTimesPreserveOriginalScopeAndRejectPartialModelOutput() {
        var taxonomy=new TaxonomyProvider();var i=PlanAdjustmentIntent.builder().type(ConversationIntentType.REPLAN_DAY).operation("REPLAN_DAY").rawMessage("傍晚保持不动，只优化20:00至21:00").build();
        AdjustmentTimeScopeResolver.resolve(i,taxonomy);assertEquals(1200,i.getTargetStartMinute());assertEquals(1260,i.getTargetEndMinute());assertTrue(AdjustmentTimeScopeResolver.preserves(Map.of("name","旧站点","time","17:30"),i,taxonomy));
        i.setRawMessage("优化当天行程");i.setTargetEndMinute(null);AdjustmentTimeScopeResolver.resolve(i,taxonomy);assertTrue(i.isRequiresClarification());
    }
    @Test void modelFieldNamesAreCanonicalAndDifferentCommandsEndPendingTask() {
        var store=new PendingIntentContinuations();var intent=pending();intent.setMissingFields(List.of("requestedVenueName"));var p=store.remember("s",1,intent);
        assertEquals(List.of("TARGET_PLACE"),p.intent().getMissingFields());
        assertNull(store.resume("s",1,p.intentId(),"删除洪崖洞",page()));
        var again=store.remember("s",1,pending());assertNotNull(store.resume("s",1,again.intentId(),"我想去三峡博物馆",page()));
    }
    @Test void epochAllowsNewProposalAtExactlyResetMillisecond() {
        var clock=new AtomicLong(1000);var store=new PendingIntentContinuations(clock::get);
        String previous=store.epoch("s");store.reset("s");String current=store.epoch("s");
        assertFalse(store.validProposal("s",previous,1000));assertTrue(store.validProposal("s",current,1000));
    }
    @Test void dayAnswerRetainsOriginalActionAndRequestedVenue() {
        var store=new PendingIntentContinuations();var i=pending();i.setDayNumber(null);i.setRequestedVenueName("三峡博物馆");i.setMissingFields(List.of("dayNumber"));var p=store.remember("s",1,i);
        assertNull(p.intent().getDayNumber());var answer=store.resume("s",1,p.intentId(),"第二天",page());assertNotNull(answer);assertEquals(2,answer.getDayNumber());assertEquals(ConversationIntentType.ADD_STOP,answer.getType());assertEquals("三峡博物馆",answer.getRequestedVenueName());
    }
    @Test void removePlaceAnswerRetainsOperationWithoutSyntheticDayPrefix() {
        var store=new PendingIntentContinuations();var intent=pending().toBuilder().operation("REMOVE_STOP").scope("STOP").missingFields(List.of("TARGET_STOP")).build();
        var p=store.remember("s",1,intent);var answer=store.resume("s",1,p.intentId(),"洪崖洞",page());
        assertNotNull(answer);assertEquals(ConversationIntentType.REMOVE_STOP,answer.getType());assertEquals("REMOVE_STOP",answer.getOperation());assertEquals(2,answer.getDayNumber());assertEquals("洪崖洞",answer.getTargetStopReference());
    }
    @Test void shortAnswerInheritsExistingPinsAndMergesAdditionalPinsWithoutDuplicates() {
        var store = new PendingIntentContinuations();
        var i = pending().toBuilder().pinnedStopIds(List.of("original-pin")).build();
        var p = store.remember("s", 1, i);
        var answer = store.resume("s", 1, p.intentId(), "三峡博物馆", page());
        assertEquals(List.of("original-pin"), answer.getPinnedStopIds());
        p = store.remember("s", 1, i);
        answer = store.resume("s", 1, p.intentId(), "三峡博物馆", PlanPageContext.builder().activeDay(1)
                .pinnedStopIds(List.of("original-pin", "additional-pin")).build());
        assertEquals(List.of("original-pin", "additional-pin"), answer.getPinnedStopIds());
    }
    @Test void choosingCompoundActionRetainsServerPinsWithEmptyContextPins() {
        var store = new PendingIntentContinuations();
        var i = pending().toBuilder().missingFields(List.of("COMPOUND_ACTION_ORDER"))
                .compoundActions(List.of("添加三峡博物馆", "删除洪崖洞"))
                .pinnedStopIds(List.of("original-pin")).build();
        var p = store.remember("s", 1, i);
        var answer = store.resume("s", 1, p.intentId(), "选第一项", page());
        assertEquals(List.of("original-pin"), answer.getPinnedStopIds());
    }
    @Test void firstEpochIsRandomAndLegacyOrMissingAuthorityCannotValidateAProposal() {
        var store = new PendingIntentContinuations();
        assertFalse(store.validProposal("s", "INITIAL", 1000));
        assertFalse(store.validProposal("s", null, 1000));
        store.begin("s");
        String epoch = store.epoch("s");
        assertFalse(epoch.isBlank()); assertNotEquals("INITIAL", epoch);
        assertTrue(store.validProposal("s", epoch, 1000));
        assertFalse(store.validProposal("s", null, 1000));
        assertFalse(store.validProposal("s", "INITIAL", 1000));
    }
    @Test void resetEpochRemainsInvalidAfterCapacityEvictionAndFreshReentry() {
        var store = new PendingIntentContinuations();
        store.begin("s");
        String oldEpoch = store.epoch("s");
        store.reset("s");
        String resetEpoch = store.epoch("s");
        assertFalse(store.validProposal("s", oldEpoch, 1000));
        assertTrue(store.validProposal("s", resetEpoch, 1000));
        for (int n = 0; n < 4096; n++) store.begin("other-" + n);
        assertFalse(store.validProposal("s", oldEpoch, 1000));
        assertFalse(store.validProposal("s", resetEpoch, 1000));
        assertFalse(store.validProposal("s", "INITIAL", 1000));
        store.begin("s");
        String freshEpoch = store.epoch("s");
        assertNotEquals(resetEpoch, freshEpoch);
        assertTrue(store.validProposal("s", freshEpoch, 1000));
        assertFalse(store.validProposal("s", oldEpoch, 1000));
    }
    @Test void placeThenTimeThenDayFillsOnlyEachAnsweredSlot() {
        var store = new PendingIntentContinuations();
        var original = pending().toBuilder().dayNumber(null)
                .missingFields(List.of("TARGET_PLACE", "TIME_RANGE", "DAY_NUMBER")).build();
        var p = store.remember("s", 1, original);
        var place = store.resume("s", 1, p.intentId(), "三峡博物馆", page());
        assertTrue(place.isRequiresClarification());
        assertEquals(List.of("TIME_RANGE", "DAY_NUMBER"), place.getMissingFields());
        assertEquals("三峡博物馆", place.getRequestedVenueName());
        p = store.remember("s", 1, place);
        var time = store.resume("s", 1, p.intentId(), "18:00至20:00", page());
        assertTrue(time.isRequiresClarification());
        assertEquals(List.of("DAY_NUMBER"), time.getMissingFields());
        assertEquals(1080, time.getTargetStartMinute());
        assertEquals(1200, time.getTargetEndMinute());
        p = store.remember("s", 1, time);
        var day = store.resume("s", 1, p.intentId(), "第2天", page());
        assertFalse(day.isRequiresClarification());
        assertEquals(ConversationIntentType.ADD_STOP, day.getType());
        assertEquals(2, day.getDayNumber());
        assertEquals("三峡博物馆", day.getRequestedVenueName());
        assertEquals(1080, day.getTargetStartMinute());
        assertEquals(1200, day.getTargetEndMinute());
        assertTrue(day.getConditions().contains("RAIN"));
    }
}

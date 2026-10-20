package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CompoundTimeAndCancellationTest {
    private final PlanPageContext page=PlanPageContext.builder().activeDay(1).build();
    @Test void differentActionTimesRemainIndependentAcrossApply() {
        var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("第2天18:00以后添加三峡博物馆，第3天20:00以后删除洪崖洞",page);
        AdjustmentTimeScopeResolver.resolve(intent,new JsonTaxonomyAdapter());
        assertEquals(List.of("COMPOUND_ACTION_ORDER"),intent.getMissingFields());
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var p=store.remember("s",1,intent);
        var first=store.resume("s",1,p.intentId(),"执行第一个",page);
        assertEquals(2,first.getDayNumber());assertEquals("ADD_STOP",first.getOperation());assertEquals(1080,first.getTargetStartMinute());
        assertFalse(first.isRequiresClarification());store.afterApply("s",2);
        p=store.pending("s");AdjustmentTimeScopeResolver.resolve(p.intent(),new JsonTaxonomyAdapter());
        var second=store.resume("s",2,p.intentId(),"执行下一个",page);
        assertEquals(3,second.getDayNumber());assertEquals("REMOVE_STOP",second.getOperation());assertEquals(1200,second.getTargetStartMinute());
        assertFalse(second.isRequiresClarification());
    }
    @Test void commonTimeStillAppliesToBothActions() {
        var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("18:00以后，添加三峡博物馆，删除洪崖洞",page);
        AdjustmentTimeScopeResolver.resolve(intent,new JsonTaxonomyAdapter());
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var p=store.remember("s",1,intent);
        assertEquals(1080,store.resume("s",1,p.intentId(),"执行第一个",page).getTargetStartMinute());store.afterApply("s",2);
        assertEquals(1080,store.resume("s",2,store.pending("s").intentId(),"执行下一个",page).getTargetStartMinute());
    }
    @Test void correctingInvalidCommonTimeDoesNotLoseCompoundSelection() {
        var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("18:90以后，添加三峡博物馆，删除洪崖洞",page);
        AdjustmentTimeScopeResolver.resolve(intent,new JsonTaxonomyAdapter());
        assertEquals(List.of("TIME_RANGE","COMPOUND_ACTION_ORDER"),intent.getMissingFields());
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var p=store.remember("s",1,intent);
        var corrected=store.resume("s",1,p.intentId(),"18:00以后",page);
        assertEquals(List.of("COMPOUND_ACTION_ORDER"),corrected.getMissingFields());
        p=store.remember("s",1,corrected);
        var first=store.resume("s",1,p.intentId(),"执行第一个",page);
        assertEquals("ADD_STOP",first.getOperation());assertEquals(1080,first.getTargetStartMinute());
    }
    @Test void negatedCancellationStillExecutesPositiveChoice() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var intent=com.ai.guide.testing.PlannerTaxonomyFixtures.classifier().classify("添加三峡博物馆，删除洪崖洞",page);
        var p=store.remember("s",1,intent);var first=store.resume("s",1,p.intentId(),"不要取消，执行第一个",page);
        assertNotNull(first);assertEquals("ADD_STOP",first.getOperation());store.afterApply("s",2);assertNotNull(store.pending("s"));
    }
    @Test void negatedCancellationInSlotAnswerDoesNotDestroyPendingTask() {
        var store=new PendingIntentContinuations(com.ai.guide.testing.PlannerTaxonomyFixtures.classifier());var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("ADD_STOP").dayNumber(2).missingFields(List.of("TARGET_PLACE")).requiresClarification(true)
                .rawMessage("第2天添加景点").build();
        var p=store.remember("s",1,intent);var answer=store.resume("s",1,p.intentId(),"不要取消，三峡博物馆",page);
        assertNotNull(answer);assertEquals("三峡博物馆",answer.getRequestedVenueName());assertEquals(2,answer.getDayNumber());
    }
}

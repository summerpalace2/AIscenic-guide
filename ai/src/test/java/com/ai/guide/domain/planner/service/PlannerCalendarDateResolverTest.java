package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlannerCalendarDateResolverTest {
    Map<String,Object> trip() { return Map.of("days",List.of(Map.of("day",1,"date","2028-05-03"),Map.of("day",2,"date","2028-05-04"))); }
    @Test void mapsChineseAndIsoDatesAgainstTripNotCurrentYear() {
        assertEquals(2,PlannerCalendarDateResolver.resolve("五月四日",trip()).day());
        assertEquals(2,PlannerCalendarDateResolver.resolve("2028-05-04添加一站",trip()).day());
        assertEquals("5月4日",PlannerCalendarDateResolver.resolve("改成5月4日",trip()).literal());
        assertTrue(PlannerCalendarDateResolver.resolve("改成5月4日",trip()).dateOnly());
        assertTrue(PlannerCalendarDateResolver.resolve("前面记混了，是5月4日，按这个做预览",trip()).dateOnly());
        assertFalse(PlannerCalendarDateResolver.resolve("5月4日再添加一个展馆",trip()).dateOnly());
    }
    @Test void invalidOutsideAndAmbiguousDatesCannotSelectActiveDay() {
        assertNull(PlannerCalendarDateResolver.resolve("2月30日",trip()).day());
        assertNull(PlannerCalendarDateResolver.resolve("5月6日",trip()).day());
        assertNull(PlannerCalendarDateResolver.resolve("5月3日或者5月4日",trip()).day());
        var years=Map.<String,Object>of("days",List.of(Map.of("day",1,"date","2028-05-03"),Map.of("day",2,"date","2029-05-03")));
        assertNull(PlannerCalendarDateResolver.resolve("5月3日",years).day());
    }
    @Test void missingDayContinuationKeepsOriginalOperationAndRawCalendarEvidence() {
        var continuations=new PendingIntentContinuations();
        var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REMOVE_STOP")
                .targetStopReference("合成展馆").rawMessage("删除合成展馆").requiresClarification(true).missingFields(List.of("DAY_NUMBER")).build();
        var pending=continuations.remember("calendar",1,intent); String ticket=continuations.begin("calendar");
        var calendar=PlannerCalendarDateResolver.resolve("5月4日",trip());
        var answer=continuations.resume("calendar",1,pending.intentId(),"5月4日",new PlanPageContext(),ticket,calendar);
        PlannerCalendarDateResolver.apply(answer,calendar);
        assertEquals(2,answer.getDayNumber()); assertEquals("REMOVE_STOP",answer.getOperation());
        assertEquals("5月4日",answer.getTargetDayReference()); assertTrue(answer.getRawMessage().contains("5月4日"));
        assertFalse(answer.isRequiresClarification());
    }
    @Test void calendarCorrectionDoesNotFillPendingTargetOrKeepOldStopId() {
        var continuations=new PendingIntentContinuations();
        var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation("REMOVE_STOP")
                .dayNumber(1).targetStopId("day1-old").rawMessage("删除这个景点").requiresClarification(true).missingFields(List.of("TARGET_STOP")).build();
        var pending=continuations.remember("calendar",1,intent); String ticket=continuations.begin("calendar");
        var calendar=PlannerCalendarDateResolver.resolve("改成5月4日",trip());
        var answer=continuations.resume("calendar",1,pending.intentId(),"改成5月4日",new PlanPageContext(),ticket,calendar);
        assertEquals(2,answer.getDayNumber()); assertNull(answer.getTargetStopId());
        assertTrue(answer.isRequiresClarification()); assertEquals(List.of("TARGET_STOP"),answer.getMissingFields());
    }
    @Test void cancellationDoesNotConsumeTheNewCompleteCommandOrNaturalUnpin() {
        assertTrue(PlanAdjustmentService.isCancellationRequest("算了，取消刚才删除某站的请求。"));
        assertTrue(PlanAdjustmentService.isCancellationRequest("0个，不想少了"));
        assertFalse(PlanAdjustmentService.isCancellationRequest("取消刚才的删除；5月4日添加一个合成展馆。"));
        assertFalse(PlanAdjustmentService.isCancellationRequest("取消固定这个站点"));
    }
    @Test void existingQueueCarriesDeferredActionUntilConfirmedApply() {
        var continuations=new PendingIntentContinuations();
        String ticket=continuations.begin("queue");
        var intent=PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP").dayNumber(2).build();
        assertTrue(continuations.deferAfterApply("queue",ticket,intent,List.of("添加合成公园")));
        continuations.afterApply("queue",2);
        assertEquals(List.of("添加合成公园"),continuations.pending("queue").intent().getCompoundActions());
        assertEquals(List.of("COMPOUND_ACTION_ORDER"),continuations.pending("queue").intent().getMissingFields());
    }
    @Test void classifierFallbackLabelsDoNotMakeSlotAnswersExplicitCommands() {
        for (String reply:List.of("第3天","19:00至21:00","少走路","5月4日"))
            assertFalse(com.ai.guide.domain.language.IntentLanguagePolicy.hasAdjustmentAction(reply),reply);
        for (String command:List.of("第3天添加展馆","少推荐一个景点","删减两站","重新规划第三天"))
            assertTrue(com.ai.guide.domain.language.IntentLanguagePolicy.hasAdjustmentAction(command),command);
    }
}

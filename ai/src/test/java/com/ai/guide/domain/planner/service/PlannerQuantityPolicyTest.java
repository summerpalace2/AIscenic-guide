package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlannerQuantityPolicyTest {
    PlanAdjustmentIntent intent(String raw) { return PlanAdjustmentIntent.builder()
            .type(ConversationIntentType.REDUCE_DAY_DENSITY).operation("REDUCE_DENSITY")
            .dayNumber(2).rawMessage(raw).reduceCount(1).build(); }
    @Test void modelDefaultCannotInventMissingQuantity() {
        var intent=intent("第2天想减少景点数量"); PlannerQuantityPolicy.normalize(intent);
        assertNull(intent.getReduceCount()); assertTrue(intent.isRequiresClarification());
        assertEquals(List.of("REDUCE_COUNT"),intent.getMissingFields());
    }
    @Test void numbersInDayDoNotBecomeQuantity() {
        assertNull(PlannerQuantityPolicy.count("第2天想少一些"));
        assertEquals(2,PlannerQuantityPolicy.count("减少两处"));
        assertEquals(1,PlannerQuantityPolicy.count("一个"));
    }
    @Test void finalRetainedCountIsNeverInterpretedAsRemovalCount() {
        var intent=intent("第2天保留两站"); PlannerQuantityPolicy.normalize(intent);
        assertNull(intent.getReduceCount()); assertTrue(intent.isRequiresClarification());
        assertEquals(List.of("REDUCTION_REPHRASE"),intent.getMissingFields());
    }
    @Test void preservationClauseDoesNotChangeReductionIntoRetainedCount() {
        var intent=intent("减少两个景点，其他站点保留"); PlannerQuantityPolicy.normalize(intent);
        assertEquals(2,intent.getReduceCount()); assertFalse(intent.isRequiresClarification());
    }
    @Test void shortQuantityReplyPreservesOperationAndOriginalDay() {
        var original=intent("第2天少排几个景点"); PlannerQuantityPolicy.normalize(original);
        var continuations=new PendingIntentContinuations();
        var pending=continuations.remember("quantity",1,original);
        var answer=continuations.resume("quantity",1,pending.intentId(),"两处",PlanPageContext.builder().activeDay(1).build());
        assertEquals(2,answer.getDayNumber()); assertEquals(2,answer.getReduceCount());
        assertEquals("REDUCE_DENSITY",answer.getOperation()); assertFalse(answer.isRequiresClarification());
        PlannerQuantityPolicy.normalize(answer);
        assertEquals(2,answer.getReduceCount()); assertFalse(answer.isRequiresClarification());
    }
    @Test void signedCountsAndPartySizesAreNotRemovalCounts() {
        assertNull(PlannerQuantityPolicy.count("减少负2个"));
        assertNull(PlannerQuantityPolicy.count("减少-2个"));
        assertNull(PlannerQuantityPolicy.count("我们两个人，减少多少还没想好"));
        assertNull(PlannerQuantityPolicy.count("同行三个人"));
        assertEquals(2,PlannerQuantityPolicy.count("第3天减少两个景点"));
        assertEquals(12,PlannerQuantityPolicy.count("减少12站"));
    }
    @Test void shortReplyOverridesRejectedOldCountWithoutPartyOrDateNumbers() {
        var original=intent("第3天同行两个人，减少5站"); original.setDayNumber(3);
        original.setType(ConversationIntentType.CLARIFICATION); original.setRequiresClarification(true);
        original.setMissingFields(List.of("REDUCE_COUNT")); original.setReduceCount(null);
        var continuations=new PendingIntentContinuations();
        var pending=continuations.remember("quantity",1,original);
        var answer=continuations.resume("quantity",1,pending.intentId(),"一个",PlanPageContext.builder().activeDay(1).build());
        PlannerQuantityPolicy.normalize(answer);
        assertEquals(3,answer.getDayNumber()); assertEquals(1,answer.getReduceCount());
        assertFalse(answer.isRequiresClarification());
    }
    @Test void establishedRecommendReductionGrammarUsesTheSameCountParser() {
        assertEquals(1,PlannerQuantityPolicy.count("今天太赶了，少推荐一个景点"));
        var intent=intent("今天太赶了，少推荐一个景点"); PlannerQuantityPolicy.normalize(intent);
        assertEquals(1,intent.getReduceCount()); assertFalse(intent.isRequiresClarification());
    }
}

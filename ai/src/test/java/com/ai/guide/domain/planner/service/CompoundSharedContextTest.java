package com.ai.guide.domain.planner.service;
import com.ai.guide.domain.planner.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CompoundSharedContextTest {
    @Test void sharedDayConstraintsAndPinsSurviveBothActions() {
        var page=PlanPageContext.builder().activeDay(1).pinnedStopIds(List.of("pin")).build();
        var intent=new ConversationIntentClassifier().classify("第2天，把解放碑换成三峡博物馆，添加重庆大剧院，保留洪崖洞，少走路，下雨",page);
        intent.setTargetStartMinute(1080);intent.setTargetEndMinute(1260);intent.setTimeScopeResolved(true);
        var store=new PendingIntentContinuations();var pending=store.remember("s",1,intent);
        var first=store.resume("s",1,pending.intentId(),"执行第一个",page);
        assertContext(first);store.afterApply("s",2);
        var second=store.resume("s",2,store.pending("s").intentId(),"执行下一个",page);
        assertContext(second);
    }
    private void assertContext(PlanAdjustmentIntent intent) {
        assertEquals(2,intent.getDayNumber());assertTrue(intent.getPreferences().contains("LOW_WALKING"));
        assertTrue(intent.getConditions().contains("RAIN"));assertTrue(intent.getPreserveReferences().contains("保留洪崖洞"));
        assertTrue(intent.getPinnedStopIds().contains("pin"));assertEquals(1080,intent.getTargetStartMinute());assertEquals(1260,intent.getTargetEndMinute());
    }
    @Test void explicitActionDayOverridesSharedDay() {
        var page=PlanPageContext.builder().activeDay(1).build();
        var intent=new ConversationIntentClassifier().classify("第2天，添加三峡博物馆，第3天删除洪崖洞，少走路",page);
        var store=new PendingIntentContinuations();var pending=store.remember("s",1,intent);
        var answer=store.resume("s",1,pending.intentId(),"执行第二个",page);
        assertEquals(3,answer.getDayNumber());assertTrue(answer.getPreferences().contains("LOW_WALKING"));
    }
}

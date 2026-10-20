package com.ai.guide.application.planner;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two independent lifecycle service objects share actual temporary SQLite repositories.
 * This is not a two-HTTP-instance or real Redis probe; root runs that separately. */
@SpringBootTest
class PlannerMultiInstanceLifecycleTest {
    @Autowired ApplicationContext context;
    @Autowired PlannerApplicationService first;
    @Autowired PlannerService planner;
    @Autowired PlannerSessionRepositoryPort sessions;
    @org.springframework.boot.test.mock.mockito.SpyBean
    com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository lifecycleStore;
    private PlannerApplicationService second;
    private PlanningActor actor;
    private String sessionId;
    private String stopId;

    @BeforeEach void setup() {
        String owner = "multi-lifecycle-" + UUID.randomUUID();
        UserContext.set(owner,"USER"); actor = new PlanningActor(owner,false);
        var other = context.getAutowireCapableBeanFactory().createBean(PlanAdjustmentService.class);
        second = new PlannerApplicationService(planner,context.getBean(PlannerSaveUseCasePort.class),sessions,other,
                context.getBean(ProposalRepositoryPort.class),context.getBean(PlannerOpenUseCasePort.class),context.getBean(PlannerRestoreUseCasePort.class));
        var created = planner.create(PlanRequest.builder().prompt("重庆2天旅游，喜欢人文与夜景").idempotencyKey(UUID.randomUUID().toString()).build());
        assertEquals(200,created.status()); sessionId = String.valueOf(created.body().get("sessionId"));
        var trip = (Map<?,?>)created.body().get("trip");
        var day = (Map<?,?>)((List<?>)trip.get("days")).getFirst();
        assertTrue(((List<?>)day.get("stops")).size()>1);
        stopId = String.valueOf(((Map<?,?>)((List<?>)day.get("stops")).getFirst()).get("id"));
    }
    @AfterEach void cleanup() { UserContext.clear(); }

    @Test void previewOnFirstCanBeAppliedOnAnotherService() {
        var request = preview();
        var applied = second.applyAdjustment(actor,sessionId,request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS,applied.outcome(),applied.body().toString());
        assertEquals(2,sessions.find(sessionId,"USER",actor.userId(),"").currentVersion());
    }

    @Test void committedReceiptReplaysOnAnotherServiceWithoutNewRevision() throws Exception {
        var request = preview();
        var applied = first.applyAdjustment(actor,sessionId,request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS,applied.outcome(),applied.body().toString());
        var replay = second.applyAdjustment(actor,sessionId,request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS,replay.outcome(),replay.body().toString());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(mapper.readTree(mapper.writeValueAsString(applied.body())),
                mapper.readTree(mapper.writeValueAsString(replay.body())));
        assertEquals(2,sessions.revisionCount(sessionId));
    }

    @Test void cancellationOnAnotherServiceInvalidatesTheOriginalProposal() {
        var request = preview();
        var cancelled = second.converse(actor,sessionId,PlanConversationRequestDto.builder()
                .baseRevision(1).message("取消本次调整").build());
        assertEquals(Boolean.TRUE,cancelled.body().get("cancelled"));
        var applied = first.applyAdjustment(actor,sessionId,request);
        assertEquals(ApplyAdjustmentResult.Outcome.CONFLICT,applied.outcome(),applied.body().toString());
        assertEquals(1,sessions.find(sessionId,"USER",actor.userId(),"").currentVersion());
    }

    @Test void failedReceiptWriteRollsBackRevisionAndKeepsProposal() {
        var request = preview();
        org.mockito.Mockito.doThrow(new IllegalStateException("isolated receipt write failure")).doCallRealMethod()
                .when(lifecycleStore).saveApplyReceipt(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        assertThrows(IllegalStateException.class,() -> first.applyAdjustment(actor,sessionId,request));
        assertEquals(1,sessions.find(sessionId,"USER",actor.userId(),"").currentVersion());
        assertEquals(1,sessions.revisionCount(sessionId));
        var retry = second.applyAdjustment(actor,sessionId,request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS,retry.outcome(),retry.body().toString());
    }

    @Test void missingSlotAnswerOnAnotherServiceKeepsTheOriginalDay() {
        var clarification = first.converse(actor,sessionId,PlanConversationRequestDto.builder().baseRevision(1)
                .message("改第二天景点").context(PlanPageContext.builder().activeDay(1).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION,clarification.outcome());
        var pending = (Map<?,?>)clarification.body().get("pendingIntent");
        var answer = second.converse(actor,sessionId,PlanConversationRequestDto.builder().baseRevision(1).message("夜景")
                .context(PlanPageContext.builder().activeDay(1).pendingIntentId(String.valueOf(pending.get("intentId"))).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION,answer.outcome(),answer.body().toString());
        assertEquals(2,((Number)((Map<?,?>)answer.body().get("intent")).get("dayNumber")).intValue());
        assertEquals(1,sessions.revisionCount(sessionId));
    }

    @Test void correctedClockFlagSurvivesDurableStateAcrossServices() {
        var classifier=context.getBean(ConversationIntentClassifier.class);
        var state=context.getBean(PlannerConversationStatePort.class);
        var firstState=new PendingIntentContinuations(classifier,System::currentTimeMillis,state);
        var secondState=new PendingIntentContinuations(classifier,System::currentTimeMillis,state);
        var page=PlanPageContext.builder().activeDay(1).build();
        firstState.remember(sessionId,1,PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION)
                .operation("REPLAN_DAY").requiresClarification(true).missingFields(List.of("TIME_RANGE","REPLAN_PREFERENCE"))
                .rawMessage("将18:00到17:00的行程优化").preferences(List.of("低步行")).dayNumber(1).build());
        var corrected=secondState.resume(sessionId,1,null,"19:00至21:00",page);
        firstState.remember(sessionId,1,corrected);
        var completed=secondState.resume(sessionId,1,null,"少走路",page);
        AdjustmentTimeScopeResolver.resolve(completed,new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        assertTrue(completed.isTimeScopeResolved());assertFalse(completed.isRequiresClarification());
        assertEquals(1140,completed.getTargetStartMinute());assertEquals(1260,completed.getTargetEndMinute());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().convertValue(completed,Map.class).containsKey("timeScopeResolved"),
                "the internal persistence flag must still be excluded from the external intent DTO");
    }

    private ApplyAdjustmentRequestDto preview() {
        var result = first.converse(actor,sessionId,PlanConversationRequestDto.builder().baseRevision(1)
                .message("删除当前选中的站点").context(PlanPageContext.builder().activeDay(1).selectedStopId(stopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS,result.outcome(),result.body().toString());
        return ApplyAdjustmentRequestDto.builder().proposalId(String.valueOf(result.body().get("proposalId")))
                .baseRevision(1).idempotencyKey(UUID.randomUUID().toString()).build();
    }
}

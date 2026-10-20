package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.planner.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LatePreviewCancellationLifecycleTest {
    @Test
    void cancellationDuringIntentExtractionPreventsLateProposalPublication() throws Exception {
        Map<String, Object> trip = Map.of("version", 1, "days", List.of(Map.of("day", 1, "stops", List.of(
                Map.of("id", "a", "name", "景点A", "time", "10:00", "duration", "60分钟"),
                Map.of("id", "b", "name", "景点B", "time", "12:00", "duration", "60分钟")))));
        var stored = new PlannerSessionRepositoryPort.StoredSession("s", "USER", "owner", "", "fp", "prompt",
                Map.of(), AppliedPreferencesSnapshot.empty(), trip, 1, 1, "ACTIVE", 1, 1);
        var sessions = mock(PlannerSessionRepositoryPort.class);
        when(sessions.find(anyString(), anyString(), anyString(), anyString())).thenReturn(stored);
        var proposals = mock(ProposalRepositoryPort.class);
        var enteredExtractor = new CountDownLatch(1);
        var releaseExtractor = new CountDownLatch(1);
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.REMOVE_STOP).operation("REMOVE_STOP")
                .scope("STOP").dayNumber(1).targetStopId("b").build();
        var extractor = mock(LlmIntentExtractor.class);
        when(extractor.extract(anyString(), any(), anyMap())).thenAnswer(invocation -> {
            enteredExtractor.countDown();
            if (!releaseExtractor.await(3, TimeUnit.SECONDS)) throw new AssertionError("extractor release timeout");
            return new LlmIntentExtractor.LlmIntentExtractionResult(intent, "TEST", null, 0, "HIGH");
        });
        var service = new PlanAdjustmentService(sessions, mock(ItineraryBuilderPort.class), mock(AttractionService.class),
                new RouteCostProvider(null), mock(PlanVerifier.class), mock(RouteAwarePlanner.class), proposals,
                extractor, new ObjectMapper(), new PlannerMetricsLogger());
        UserContext.set("owner", "USER");
        try {
            var preview = new CompletableFuture<PlannerService.ServiceResult>();
            Thread previewThread = new Thread(() -> {
                UserContext.set("owner", "USER");
                try {
                    preview.complete(service.preview("s", PlanConversationRequestDto.builder().baseRevision(1)
                            .message("删除景点B").context(PlanPageContext.builder().activeDay(1).build()).build()));
                } catch (Throwable failure) {
                    preview.completeExceptionally(failure);
                } finally { UserContext.clear(); }
            }, "late-preview-worker");
            previewThread.start();
            assertTrue(enteredExtractor.await(3, TimeUnit.SECONDS));

            PlannerService.ServiceResult cancelled = service.preview("s",
                    PlanConversationRequestDto.builder().baseRevision(1).message("取消这次调整").build());
            assertEquals(200, cancelled.status());
            assertEquals(Boolean.TRUE, cancelled.body().get("cancelled"));

            releaseExtractor.countDown();
            PlannerService.ServiceResult late = preview.get(3, TimeUnit.SECONDS);
            assertEquals(409, late.status());
            verify(proposals, never()).put(any());
            verify(sessions, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        } finally {
            releaseExtractor.countDown();
            UserContext.clear();
        }
    }
}

package com.ai.guide.application.planner;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.service.ConversationIntentClassifier;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.planner.service.PlanAdjustmentService;
import com.ai.guide.domain.planner.service.LlmIntentExtractor;
import com.ai.guide.adapter.planner.web.PlannerController;
import com.ai.guide.domain.attraction.service.AttractionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.ContentCachingResponseWrapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class PlannerPreviewUseCaseTest {
    @Autowired private PlanningApplicationService applicationService;
    @Autowired private PlannerService plannerService;
    @Autowired private PlannerSessionRepositoryPort sessions;
    @Autowired private ProposalRepositoryPort proposals;
    @Autowired private AttractionService attractions;
    @Autowired private ObjectMapper objectMapper;
    @SpyBean private PlanAdjustmentService planAdjustments;
    @SpyBean private LlmIntentExtractor intentExtractor;
    @SpyBean private ProposalRepositoryPort proposalWriter;

    @BeforeEach
    void setUp() {
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void clarificationPreviewDoesNotMutateDurableTripOrRevision() {
        var created = createSession("2天行程，喜欢夜景和人文");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        int revisionCount = sessions.revisionCount(sessionId);

        PreviewAdjustmentResult result = applicationService.previewAdjustment(
                PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("abcdefg 无关文字").build());

        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION, result.outcome());
        assertEquals(Boolean.TRUE, result.body().get("requiresClarification"));
        var after = sessions.findAnonymousByAccessToken(token);
        assertEquals(before.currentVersion(), after.currentVersion());
        assertEquals(revisionCount, sessions.revisionCount(sessionId));
        assertEquals(before.trip(), after.trip());
    }

    @Test
    void shortClarificationReplyContinuesOriginalDayTarget() {
        var created = createSession("3天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PreviewAdjustmentResult first = applicationService.previewAdjustment(
                PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("改第二天景点")
                        .context(PlanPageContext.builder().activeDay(1).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION, first.outcome());
        assertEquals(Boolean.TRUE, first.body().get("requiresClarification"));
        Map<?, ?> pending = (Map<?, ?>) first.body().get("pendingIntent");
        assertNotNull(pending);

        PreviewAdjustmentResult followup = applicationService.previewAdjustment(
                PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("夜景")
                        .context(PlanPageContext.builder().activeDay(1)
                                .pendingIntentId(String.valueOf(pending.get("intentId"))).build()).build());

        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION, followup.outcome());
        Map<?, ?> intent = (Map<?, ?>) followup.body().get("intent");
        assertNotNull(intent);
        assertEquals(2, ((Number) intent.get("dayNumber")).intValue());
        assertEquals(1, sessions.findAnonymousByAccessToken(token).currentVersion());
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatedPreviewIsPersistedThroughApplicationOwnedProposalPort() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var stored = sessions.findAnonymousByAccessToken(token);
        Map<String, Object> trip = stored.trip();
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));

        PreviewAdjustmentResult result = applicationService.previewAdjustment(
                PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());

        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, result.outcome());
        String proposalId = String.valueOf(result.body().get("proposalId"));
        assertNotNull(proposals.get(proposalId));
        assertTrue(result.body().containsKey("optionStatus"));
        assertTrue(result.body().containsKey("candidateReplacements"));
        assertEquals(1, sessions.findAnonymousByAccessToken(token).currentVersion());
    }

    @Test
    void unauthorizedSessionDoesNotEnterDomainPreview() {
        PreviewAdjustmentResult result = applicationService.previewAdjustment(
                PlanningActor.anonymousActor(), "missing-session",
                PlanConversationRequestDto.builder().sessionId("missing-session").baseRevision(1)
                        .sessionAccessToken("missing-token").message("夜景").build());
        assertEquals(PreviewAdjustmentResult.Outcome.NOT_FOUND, result.outcome());
        assertEquals(Boolean.FALSE, result.body().get("ok"));
    }

    @Test
    void applyUseCaseUsesExplicitActorAndReturnsTypedReceipt() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        int revisionCountBeforeApply = sessions.revisionCount(sessionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));

        PreviewAdjustmentResult preview = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome());

        ApplyAdjustmentResult invalidOption = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).proposalId(String.valueOf(preview.body().get("proposalId")))
                        .optionId("missing-option").baseRevision(before.currentVersion()).build());
        assertEquals(ApplyAdjustmentResult.Outcome.BAD_REQUEST, invalidOption.outcome());
        assertEquals(before.currentVersion(), sessions.findAnonymousByAccessToken(token).currentVersion(),
                "application must finish proposal/option validation before reaching CAS");
        assertEquals(revisionCountBeforeApply, sessions.revisionCount(sessionId));

        String idempotencyKey = "cp3-apply-" + UUID.randomUUID();
        ApplyAdjustmentRequestDto applyRequest = ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                .idempotencyKey(idempotencyKey).proposalId(String.valueOf(preview.body().get("proposalId")))
                .baseRevision(before.currentVersion()).build();
        ApplyAdjustmentResult applied = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, applyRequest);

        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, applied.outcome());
        assertEquals(Boolean.TRUE, applied.body().get("applied"));
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
        assertTrue(sessions.revisionCount(sessionId) > 0);
        ApplyAdjustmentResult retry = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, applyRequest);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, retry.outcome());
        assertJsonReceiptEquals(applied.body(), retry.body());
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());

        // The old PlannerService facade must hit the exact same lock/replay authority.
        UserContext.set(null, "TOURIST");
        PlannerService.ServiceResult legacyRetry = plannerService.applyAdjustment(sessionId, applyRequest);
        assertEquals(200, legacyRetry.status());
        assertJsonReceiptEquals(applied.body(), legacyRetry.body());
        UserContext.clear();
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
    }

    @Test
    void droppedControllerResponseCanBeRetriedWithoutSecondCommit() throws Exception {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));
        PreviewAdjustmentResult preview = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome());
        String idempotencyKey = "cp3-response-drop-" + UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(Map.of("sessionAccessToken", token,
                "proposalId", String.valueOf(preview.body().get("proposalId")), "baseRevision", before.currentVersion(),
                "idempotencyKey", idempotencyKey));
        int revisionsBeforeApply = sessions.revisionCount(sessionId);
        AtomicBoolean dropOnce = new AtomicBoolean(true);
        Filter responseDropAfterController = (request, response, chain) -> {
            ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper((HttpServletResponse) response);
            chain.doFilter(request, buffered);
            if ("true".equals(((HttpServletRequest) request).getHeader("X-Test-Drop-Response")) && dropOnce.compareAndSet(true, false)) {
                buffered.resetBuffer();
                throw new IOException("simulated response loss after controller completed the apply");
            }
            buffered.copyBodyToResponse();
        };
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PlannerController(applicationService))
                .addFilters(responseDropAfterController).build();
        UserContext.clear();

        Exception droppedResponse = assertThrows(Exception.class, () -> mockMvc.perform(post("/ai/planner/v1/sessions/{sessionId}/adjust/apply", sessionId)
                .header("X-Plan-Session-Token", token).header("X-Test-Drop-Response", "true")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andReturn());
        assertTrue(hasCause(droppedResponse, IOException.class), "the filter's injected response-loss IOException must escape MockMvc");
        assertFalse(dropOnce.get(), "the first committed response must reach and consume the one-shot drop filter");

        var afterLostResponse = sessions.findAnonymousByAccessToken(token);
        assertEquals(before.currentVersion() + 1, afterLostResponse.currentVersion());
        assertEquals(revisionsBeforeApply + 1, sessions.revisionCount(sessionId));

        var retry = mockMvc.perform(post("/ai/planner/v1/sessions/{sessionId}/adjust/apply", sessionId)
                        .header("X-Plan-Session-Token", token).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var repeatedRetry = mockMvc.perform(post("/ai/planner/v1/sessions/{sessionId}/adjust/apply", sessionId)
                        .header("X-Plan-Session-Token", token).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(retry, repeatedRetry);
        assertTrue(retry.contains("\"applied\":true"));
        assertTrue(retry.contains("\"currentVersion\":2"));
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
        assertEquals(revisionsBeforeApply + 1, sessions.revisionCount(sessionId));
    }

    @Test
    void postCasExceptionRollsBackRevisionAndReceiptTogether() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));
        PreviewAdjustmentResult preview = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome());
        ApplyAdjustmentRequestDto request = ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                .proposalId(String.valueOf(preview.body().get("proposalId"))).baseRevision(before.currentVersion())
                .idempotencyKey("cp3-post-cas-throw-" + UUID.randomUUID()).build();
        int revisionsBefore = sessions.revisionCount(sessionId);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("simulated failure after SQLite CAS inside transaction");
        }).doCallRealMethod().when(planAdjustments).commitPreparedApply(any());

        assertThrows(IllegalStateException.class,
                () -> applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, request));
        assertEquals(before.currentVersion(), sessions.findAnonymousByAccessToken(token).currentVersion(),
                "revision and receipt must roll back together before the outer transaction commits");
        assertEquals(revisionsBefore, sessions.revisionCount(sessionId));

        ApplyAdjustmentResult retry = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, retry.outcome(), retry.body().toString());
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
        assertEquals(revisionsBefore + 1, sessions.revisionCount(sessionId));
        verify(planAdjustments, times(2)).commitPreparedApply(any());
    }

    @Test
    void proposalRemovalFailureStillReturnsAndReplaysCommittedReceipt() {
        var created = createSession("3天重庆行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) before.trip().get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dayOneStops = (List<Map<String, Object>>) days.get(0).get("stops");
        String targetStopId = String.valueOf(dayOneStops.get(1).get("id"));
        String targetStopName = String.valueOf(dayOneStops.get(1).get("name"));
        java.util.Set<String> plannedNames = new java.util.HashSet<>();
        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> dayStops = (List<Map<String, Object>>) day.get("stops");
            for (Map<String, Object> stop : dayStops) plannedNames.add(String.valueOf(stop.get("name")));
        }
        String nextPlace = attractions.list(null, null).stream()
                .filter(a -> "渝中区".equals(a.getDistrict()))
                .map(a -> a.getName()).filter(name -> name != null && !plannedNames.contains(name))
                .findFirst().orElseThrow();
        PreviewAdjustmentResult first = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("第1天删除" + targetStopName + "，第2天添加" + nextPlace)
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION, first.outcome());
        Map<?, ?> pendingFirst = (Map<?, ?>) first.body().get("pendingIntent");
        assertNotNull(pendingFirst);
        PreviewAdjustmentResult selectedFirst = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("执行第一个")
                        .context(PlanPageContext.builder().activeDay(1)
                                .pendingIntentId(String.valueOf(pendingFirst.get("intentId"))).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, selectedFirst.outcome(), selectedFirst.body().toString());
        String proposalId = String.valueOf(selectedFirst.body().get("proposalId"));
        int revisionsBefore = sessions.revisionCount(sessionId);
        doAnswer(invocation -> { throw new IllegalStateException("simulated proposal cache remove failure"); })
                .when(proposalWriter).remove(proposalId);
        ApplyAdjustmentRequestDto request = ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                .proposalId(proposalId).baseRevision(before.currentVersion())
                .idempotencyKey("cp3-remove-failure-" + UUID.randomUUID()).build();

        ApplyAdjustmentResult applied = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, request);
        ApplyAdjustmentResult retry = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, applied.outcome());
        assertJsonReceiptEquals(applied.body(), retry.body());
        Map<?, ?> remaining = (Map<?, ?>) applied.body().get("pendingIntent");
        assertNotNull(remaining, "the second compound action must survive proposal removal failure");
        assertNotNull(remaining.get("intentId"));
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
        assertEquals(revisionsBefore + 1, sessions.revisionCount(sessionId));
        var storedAfterFirstApply = sessions.findAnonymousByAccessToken(token);
        assertFalse(storedStopNamesForDay(storedAfterFirstApply.trip(), 1).contains(targetStopName),
                "the first committed compound action must remove its requested day-one stop from stored trip data");
        assertFalse(storedStopNamesForDay(storedAfterFirstApply.trip(), 2).contains(nextPlace),
                "the second compound action must not be present before its own apply");
        Map<?, ?> remainingIntent = (Map<?, ?>) remaining.get("intent");
        assertNotNull(remainingIntent, "the committed receipt must preserve the remaining structured intent");
        String remainingQuestion = String.valueOf(remainingIntent.get("clarificationQuestion"));
        assertTrue(remainingQuestion.contains(nextPlace), remainingIntent.toString());
        String remainingActionText = remainingQuestion.lines().filter(line -> line.contains(nextPlace))
                .findFirst().orElseThrow(() -> new AssertionError(remainingIntent.toString()));
        PlanAdjustmentIntent remainingAction = new ConversationIntentClassifier().classify(
                remainingActionText, PlanPageContext.builder().activeDay(1).build());
        assertEquals(2, remainingAction.getDayNumber(), remainingIntent.toString());
        assertEquals(nextPlace, remainingAction.getRequestedVenueName(), remainingIntent.toString());
        verify(proposalWriter, times(1)).remove(proposalId);

        ApplyAdjustmentResult staleProposalRetry = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).proposalId(proposalId)
                        .baseRevision(before.currentVersion() + 1).idempotencyKey("cp3-remove-failure-new-key-" + UUID.randomUUID()).build());
        assertEquals(ApplyAdjustmentResult.Outcome.CONFLICT, staleProposalRetry.outcome());
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
        assertEquals(revisionsBefore + 1, sessions.revisionCount(sessionId));

        PreviewAdjustmentResult next = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion() + 1)
                        .sessionAccessToken(token).message("执行下一个")
                        .context(PlanPageContext.builder().activeDay(1)
                                .pendingIntentId(String.valueOf(remaining.get("intentId"))).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, next.outcome(), next.body().toString());
        ApplyAdjustmentResult appliedNext = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                        .proposalId(String.valueOf(next.body().get("proposalId"))).baseRevision(before.currentVersion() + 1)
                        .idempotencyKey("cp3-remove-failure-next-" + UUID.randomUUID()).build());
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, appliedNext.outcome());
        var storedAfterSecondApply = sessions.findAnonymousByAccessToken(token);
        assertEquals(before.currentVersion() + 2, storedAfterSecondApply.currentVersion());
        assertEquals(revisionsBefore + 2, sessions.revisionCount(sessionId));
        assertTrue(storedStopNamesForDay(storedAfterSecondApply.trip(), 2).contains(nextPlace),
                "the remaining compound action must add its requested place to stored day two");
        assertFalse(storedStopNamesForDay(storedAfterSecondApply.trip(), 1).contains(targetStopName),
                "the second action must preserve the first action's stored day-one removal");
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (expected.isInstance(cause)) return true;
        }
        return false;
    }

    private static List<String> storedStopNamesForDay(Map<String, Object> trip, int dayNumber) {
        Object rawDays = trip == null ? null : trip.get("days");
        if (!(rawDays instanceof List<?> days)) return List.of();
        for (Object rawDay : days) {
            if (!(rawDay instanceof Map<?, ?> day)) continue;
            Object rawNumber = day.get("day");
            if (!(rawNumber instanceof Number number) || number.intValue() != dayNumber) continue;
            Object rawStops = day.get("stops");
            if (!(rawStops instanceof List<?> stops)) return List.of();
            return stops.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(stop -> stop.get("name")).filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    @Test
    void legacyFacadeReplayIsVisibleToApplicationUseCase() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));
        PreviewAdjustmentResult preview = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                        .sessionAccessToken(token).message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome());
        ApplyAdjustmentRequestDto request = ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                .idempotencyKey("cp3-legacy-first-" + UUID.randomUUID())
                .proposalId(String.valueOf(preview.body().get("proposalId"))).baseRevision(before.currentVersion()).build();

        UserContext.set(null, "TOURIST");
        PlannerService.ServiceResult legacyApply = plannerService.applyAdjustment(sessionId, request);
        UserContext.clear();
        assertEquals(200, legacyApply.status());
        ApplyAdjustmentResult applicationRetry = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId, request);
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, applicationRetry.outcome());
        assertJsonReceiptEquals(legacyApply.body(), applicationRetry.body());
        assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
    }

    @Test
    void rejectedApplicationUseCaseDoesNotMutateSession() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        int revisionCount = sessions.revisionCount(sessionId);

        ApplyAdjustmentResult result = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).proposalId("missing-proposal")
                        .baseRevision(before.currentVersion()).build());

        assertEquals(ApplyAdjustmentResult.Outcome.BAD_REQUEST, result.outcome());
        var after = sessions.findAnonymousByAccessToken(token);
        assertEquals(before.currentVersion(), after.currentVersion());
        assertEquals(before.trip(), after.trip());
        assertEquals(revisionCount, sessions.revisionCount(sessionId));
    }

    @Test
    void cancelIsOrchestratedByApplicationAndInvalidatesOldProposalWithoutTripWrite() {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));
        PreviewAdjustmentResult preview = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("不想去这个景点，换成其他景点")
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, preview.outcome());
        int revisionCount = sessions.revisionCount(sessionId);

        PreviewAdjustmentResult cancelled = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("取消这次调整").build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, cancelled.outcome());
        assertEquals(Boolean.TRUE, cancelled.body().get("cancelled"));
        ApplyAdjustmentResult oldApply = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).baseRevision(1)
                        .proposalId(String.valueOf(preview.body().get("proposalId"))).build());

        assertEquals(ApplyAdjustmentResult.Outcome.CONFLICT, oldApply.outcome());
        var after = sessions.findAnonymousByAccessToken(token);
        assertEquals(before.currentVersion(), after.currentVersion());
        assertEquals(before.trip(), after.trip());
        assertEquals(revisionCount, sessions.revisionCount(sessionId));
    }

    @Test
    void previewTicketAdmissionAndCancelShareOneLinearizationBoundary() throws Exception {
        var created = createSession("2天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        int revisionCount = sessions.revisionCount(sessionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) before.trip().get("days")).get(0)).get("stops");
        String targetStopId = String.valueOf(stops.get(0).get("id"));

        CountDownLatch ticketRegistered = new CountDownLatch(1);
        CountDownLatch releaseTicketRegistration = new CountDownLatch(1);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);
        CountDownLatch firstCancelAtLock = new CountDownLatch(1);
        CountDownLatch secondCancelReset = new CountDownLatch(1);
        CountDownLatch releaseSecondCancel = new CountDownLatch(1);
        CountDownLatch secondPreviewAtLock = new CountDownLatch(1);
        AtomicReference<Thread> firstCancelThread = new AtomicReference<>();
        AtomicReference<Thread> secondPreviewThread = new AtomicReference<>();
        doAnswer(invocation -> {
            var ticket = invocation.callRealMethod();
            ticketRegistered.countDown();
            assertTrue(releaseTicketRegistration.await(5, TimeUnit.SECONDS), "test must release registered preview ticket");
            return ticket;
        }).when(planAdjustments).beginPreviewForApplication(any());
        doAnswer(invocation -> {
            extractionStarted.countDown();
            assertTrue(releaseExtraction.await(5, TimeUnit.SECONDS), "test must release paused intent extraction");
            return invocation.callRealMethod();
        }).when(intentExtractor).extract(any(), any(), any());
        doAnswer(invocation -> {
            String threadName = Thread.currentThread().getName();
            if ("cp3-r3-first-cancel".equals(threadName)) firstCancelAtLock.countDown();
            if ("cp3-r3-second-preview".equals(threadName)) secondPreviewAtLock.countDown();
            return invocation.callRealMethod();
        }).when(planAdjustments).withSessionApplyLock(anyString(), any(Supplier.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PreviewAdjustmentResult> previewFuture = pool.submit(() -> applicationService.previewAdjustment(
                    PlanningActor.anonymousActor(), sessionId,
                    PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                            .sessionAccessToken(token).message("帮我调整一下夜景安排").build()));
            assertTrue(ticketRegistered.await(5, TimeUnit.SECONDS), "preview must register its generation under the lifecycle lock");

            Future<PreviewAdjustmentResult> cancelFuture = pool.submit(() -> {
                firstCancelThread.set(Thread.currentThread());
                Thread.currentThread().setName("cp3-r3-first-cancel");
                return applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                        PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                                .sessionAccessToken(token).message("取消这次调整").build());
            });
            assertTrue(firstCancelAtLock.await(5, TimeUnit.SECONDS), "cancel must reach the shared lifecycle lock call");
            assertThrows(java.util.concurrent.TimeoutException.class, () -> cancelFuture.get(100,TimeUnit.MILLISECONDS),
                    "cancel must wait for the database lifecycle lock held by preview admission");
            assertFalse(cancelFuture.isDone(), "cancel must wait while preview admission owns the shared lock");

            releaseTicketRegistration.countDown();
            assertTrue(extractionStarted.await(5, TimeUnit.SECONDS), "admitted preview should proceed after releasing the lock");
            PreviewAdjustmentResult cancelled = cancelFuture.get(5, TimeUnit.SECONDS);
            assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, cancelled.outcome());
            assertEquals(Boolean.TRUE, cancelled.body().get("cancelled"));

            releaseExtraction.countDown();
            PreviewAdjustmentResult stalePreview = previewFuture.get(5, TimeUnit.SECONDS);
            assertEquals(PreviewAdjustmentResult.Outcome.CONFLICT, stalePreview.outcome());
            assertNull(stalePreview.body().get("proposalId"));
            verify(proposalWriter, never()).put(any());
            var after = sessions.findAnonymousByAccessToken(token);
            assertEquals(before.currentVersion(), after.currentVersion());
            assertEquals(before.trip(), after.trip());
            assertEquals(revisionCount, sessions.revisionCount(sessionId));

            ApplyAdjustmentResult staleApply = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                    ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).baseRevision(before.currentVersion())
                            .proposalId("late-preview-proposal").build());
            assertEquals(ApplyAdjustmentResult.Outcome.BAD_REQUEST, staleApply.outcome());
            assertEquals(before.currentVersion(), sessions.findAnonymousByAccessToken(token).currentVersion());

            // Cancel-first: pause after reset while holding the lock; later preview reaches the same lock and waits.
            doAnswer(invocation -> {
                invocation.callRealMethod();
                secondCancelReset.countDown();
                assertTrue(releaseSecondCancel.await(5, TimeUnit.SECONDS), "test must release cancel after its reset");
                return null;
            }).when(planAdjustments).resetAuthorizedContinuation(any());
            Future<PreviewAdjustmentResult> secondCancelFuture = pool.submit(() -> {
                Thread.currentThread().setName("cp3-r3-second-cancel");
                return applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                        PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                                .sessionAccessToken(token).message("取消这次调整").build());
            });
            assertTrue(secondCancelReset.await(5, TimeUnit.SECONDS), "cancel must reset continuation while holding the shared lock");

            doAnswer(invocation -> invocation.callRealMethod()).when(intentExtractor).extract(any(), any(), any());
            Future<PreviewAdjustmentResult> newPreviewFuture = pool.submit(() -> {
                secondPreviewThread.set(Thread.currentThread());
                Thread.currentThread().setName("cp3-r3-second-preview");
                return applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                        PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(before.currentVersion())
                                .sessionAccessToken(token).message("不想去这个景点，换成其他景点")
                                .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build()).build());
            });
            assertTrue(secondPreviewAtLock.await(5, TimeUnit.SECONDS), "new preview must reach shared lock while cancel owns it");
            assertThrows(java.util.concurrent.TimeoutException.class, () -> newPreviewFuture.get(100,TimeUnit.MILLISECONDS),
                    "preview must wait for the database lifecycle lock held by cancel reset");
            assertFalse(newPreviewFuture.isDone(), "preview must not be admitted until cancel releases the shared lock");
            releaseSecondCancel.countDown();

            PreviewAdjustmentResult secondCancelled = secondCancelFuture.get(5, TimeUnit.SECONDS);
            assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, secondCancelled.outcome());
            PreviewAdjustmentResult newPreview = newPreviewFuture.get(10, TimeUnit.SECONDS);
            assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, newPreview.outcome());
            assertNotNull(newPreview.body().get("proposalId"));
            assertEquals(Boolean.TRUE, newPreview.body().get("canApply"));
            ApplyAdjustmentResult applied = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                    ApplyAdjustmentRequestDto.builder().sessionAccessToken(token).baseRevision(before.currentVersion())
                            .proposalId(String.valueOf(newPreview.body().get("proposalId")))
                            .idempotencyKey("cp3-r3-cancel-first-" + UUID.randomUUID()).build());
            assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, applied.outcome());
            assertEquals(before.currentVersion() + 1, sessions.findAnonymousByAccessToken(token).currentVersion());
            assertEquals(revisionCount + 1, sessions.revisionCount(sessionId));
        } finally {
            releaseTicketRegistration.countDown();
            releaseExtraction.countDown();
            releaseSecondCancel.countDown();
            pool.shutdownNow();
        }
    }


    @Test
    void firstAppliedCompoundActionReturnsRemainingTaskForApplicationContinuation() {
        var created = createSession("3天重庆行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var before = sessions.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) before.trip().get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dayOneStops = (List<Map<String, Object>>) days.get(0).get("stops");
        String selectedStopId = String.valueOf(dayOneStops.get(1).get("id"));
        String selectedStopName = String.valueOf(dayOneStops.get(1).get("name"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dayThreeStops = (List<Map<String, Object>>) days.get(2).get("stops");
        java.util.Set<String> plannedNames = new java.util.HashSet<>();
        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> dayStops = (List<Map<String, Object>>) day.get("stops");
            for (Map<String, Object> stop : dayStops) plannedNames.add(String.valueOf(stop.get("name")));
        }
        String newPlace = attractions.list(null, null).stream()
                .filter(a -> "渝中区".equals(a.getDistrict()))
                .map(a -> a.getName()).filter(name -> name != null && !plannedNames.contains(name))
                .findFirst().orElseThrow();
        var first = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("第1天删除" + selectedStopName + "，第2天添加" + newPlace)
                        .context(PlanPageContext.builder().activeDay(1).selectedStopId(selectedStopId).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.CLARIFICATION, first.outcome());
        Map<?, ?> firstPending = (Map<?, ?>) first.body().get("pendingIntent");
        assertNotNull(firstPending);

        var selected = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(1).sessionAccessToken(token)
                        .message("执行第一个").context(PlanPageContext.builder().activeDay(1)
                                .pendingIntentId(String.valueOf(firstPending.get("intentId"))).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, selected.outcome(), selected.body().toString());
        var applied = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                        .proposalId(String.valueOf(selected.body().get("proposalId"))).baseRevision(1).build());
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, applied.outcome());
        assertEquals(2, sessions.findAnonymousByAccessToken(token).currentVersion());
        Map<?, ?> remaining = (Map<?, ?>) applied.body().get("pendingIntent");
        assertNotNull(remaining, "the unapplied compound action must be returned in the receipt");

        var next = applicationService.previewAdjustment(PlanningActor.anonymousActor(), sessionId,
                PlanConversationRequestDto.builder().sessionId(sessionId).baseRevision(2).sessionAccessToken(token)
                        .message("执行下一个").context(PlanPageContext.builder().activeDay(1)
                                .pendingIntentId(String.valueOf(remaining.get("intentId"))).build()).build());
        assertEquals(PreviewAdjustmentResult.Outcome.SUCCESS, next.outcome(), next.body().toString());
        var appliedNext = applicationService.applyAdjustment(PlanningActor.anonymousActor(), sessionId,
                ApplyAdjustmentRequestDto.builder().sessionAccessToken(token)
                        .proposalId(String.valueOf(next.body().get("proposalId"))).baseRevision(2).build());
        assertEquals(ApplyAdjustmentResult.Outcome.SUCCESS, appliedNext.outcome());
        assertEquals(3, sessions.findAnonymousByAccessToken(token).currentVersion());
    }

    private void assertJsonReceiptEquals(Map<String,Object> expected,Map<String,Object> actual) {
        try { assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(expected)),objectMapper.readTree(objectMapper.writeValueAsString(actual))); }
        catch (IOException error) { throw new AssertionError(error); }
    }

    private PlannerService.ServiceResult createSession(String prompt) {
        String scopedPrompt = prompt.contains("重庆") ? prompt : "重庆旅游，" + prompt;
        return plannerService.create(PlanRequest.builder().prompt(scopedPrompt)
                .idempotencyKey("cp2-" + UUID.randomUUID()).build());
    }
}

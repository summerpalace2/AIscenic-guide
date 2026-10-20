package com.ai.guide.adapter.planner.web;

import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.planner.PreviewAdjustmentResult;
import com.ai.guide.application.planner.ApplyAdjustmentResult;
import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlannerControllerTest {

    private PlanningApplicationService applicationService;
    private PlannerController controller;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        applicationService = mock(PlanningApplicationService.class);
        controller = new PlannerController(applicationService);
    }

    @org.junit.jupiter.api.AfterEach
    void clearActor() { UserContext.clear(); }

    @Test
    void planDelegatesToApplicationServiceAndInjectsToken() {
        when(applicationService.create(any(PlanRequest.class)))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("sessionId", "s1")));

        ResponseEntity<?> response = controller.plan(new PlanRequest(), "token-123");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).create(any(PlanRequest.class));
    }

    @Test
    void planHandlesNullRequestAndInjectsHeaderToken() {
        when(applicationService.create(any(PlanRequest.class)))
                .thenAnswer(inv -> {
                    PlanRequest req = inv.getArgument(0);
                    assertEquals("hdr-token", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("ok", true));
                });

        ResponseEntity<?> response = controller.plan(null, "hdr-token");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void shadowDelegatesToApplicationService() {
        when(applicationService.shadow(any()))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("status", "SHADOW")));

        ResponseEntity<?> response = controller.shadow(new ShadowPlanRequest());
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).shadow(any());
    }

    @Test
    void shadowReplanDelegatesToApplicationService() {
        when(applicationService.shadowReplan(any()))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("ok", true)));

        ResponseEntity<?> response = controller.shadowReplan(new ShadowMutationRequest());
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).shadowReplan(any());
    }

    @Test
    void shadowStopsDelegatesToApplicationService() {
        when(applicationService.shadowMutateStops(any()))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("ok", true)));

        ResponseEntity<?> response = controller.shadowStops(new ShadowMutationRequest());
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).shadowMutateStops(any());
    }

    @Test
    void openFormalTripDelegatesToApplicationService() {
        UserContext.set("owner-7", "USER");
        Map<String, Object> body = Map.of("formalTripId", "trip-1", "sessionId", "s-1");
        when(applicationService.openFormalTrip(argThat(actor -> "owner-7".equals(actor.userId()) && !actor.anonymous()), eq("trip-1"), eq("open-attempt-1")))
                .thenReturn(new PlannerOpenResult(200, body));

        try {
            ResponseEntity<?> response = controller.openFormalTrip("trip-1", "open-attempt-1");
            assertEquals(200, response.getStatusCode().value());
            assertEquals(body, response.getBody());
            verify(applicationService).openFormalTrip(argThat(actor -> "owner-7".equals(actor.userId()) && !actor.anonymous()), eq("trip-1"), eq("open-attempt-1"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void discardOpenSessionRouteUsesAuthenticatedOwnerAndTripScope() throws Exception {
        UserContext.set("owner-7", "USER");
        when(applicationService.discardUncommittedOpen(
                argThat(actor -> "owner-7".equals(actor.userId()) && !actor.anonymous()),
                eq("trip-1"), eq("session-1")))
                .thenReturn(new PlannerOpenResult(200, Map.of("ok", true, "discarded", true)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        try {
            mvc.perform(delete("/ai/planner/v1/trips/trip-1/open-sessions/session-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.discarded").value(true));
            verify(applicationService).discardUncommittedOpen(
                    argThat(actor -> "owner-7".equals(actor.userId()) && !actor.anonymous()),
                    eq("trip-1"), eq("session-1"));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void restoreDraftPassesExplicitActorAndUsesHeaderCapabilityFallback() {
        UserContext.set("owner-restore", "USER");
        RestoreDraftRequest input = new RestoreDraftRequest(Map.of("days", java.util.List.of()), 4, " ");
        when(applicationService.restoreDraft(any(PlanningActor.class), eq("s-restore"), any(RestoreDraftRequest.class)))
                .thenAnswer(inv -> {
                    PlanningActor actor = inv.getArgument(0);
                    RestoreDraftRequest request = inv.getArgument(2);
                    assertEquals("owner-restore", actor.userId());
                    assertFalse(actor.anonymous());
                    assertEquals("header-cap", request.sessionAccessToken());
                    assertEquals(4, request.expectedVersion());
                    assertEquals(input.trip(), request.trip());
                    return new PlannerRestoreResult(409, Map.of("ok", false, "message", "stale"));
                });
        try {
            ResponseEntity<?> response = controller.restoreDraft("s-restore", input, "header-cap");
            assertEquals(409, response.getStatusCode().value());
            assertEquals(Map.of("ok", false, "message", "stale"), response.getBody());
            verify(applicationService).restoreDraft(any(PlanningActor.class), eq("s-restore"), any(RestoreDraftRequest.class));
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void listSessionsDelegatesToApplicationService() {
        when(applicationService.list())
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("sessions", java.util.List.of())));

        ResponseEntity<?> response = controller.list();
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).list();
    }

    @Test
    void ordinarySessionGetPassesCapabilityForAuthorizationWithoutReturningIt() throws Exception {
        when(applicationService.get("s1", "my-token"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("sessionId", "s1",
                        "trip", Map.of("title", "Draft", "version", 1))));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/ai/planner/v1/sessions/s1").header("X-Plan-Session-Token", "my-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionAccessToken").doesNotExist())
                .andExpect(jsonPath("$.trip.sessionAccessToken").doesNotExist());
        verify(applicationService).get("s1", "my-token");
    }

    @Test
    void refreshDynamicDataPassesToken() {
        when(applicationService.refreshDynamicData("s1", "my-token"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("refreshed", true)));

        ResponseEntity<?> response = controller.refreshDynamicData("s1", "my-token");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).refreshDynamicData("s1", "my-token");
    }

    @Test
    void replanInjectsTokenWhenNull() {
        when(applicationService.replan(any(PlanningActor.class), eq("s1"), any(PlannerMutationRequest.class)))
                .thenAnswer(inv -> {
                    PlannerMutationRequest req = inv.getArgument(2);
                    PlanningActor actor = inv.getArgument(0);
                    assertTrue(actor.anonymous());
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("replan", true));
                });

        ResponseEntity<?> response = controller.replan("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void stopsInjectsTokenWhenNull() {
        when(applicationService.mutateStops(any(PlanningActor.class), eq("s1"), any(PlannerMutationRequest.class)))
                .thenAnswer(inv -> {
                    PlannerMutationRequest req = inv.getArgument(2);
                    PlanningActor actor = inv.getArgument(0);
                    assertTrue(actor.anonymous());
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("stops", true));
                });

        ResponseEntity<?> response = controller.stops("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void saveDelegatesExplicitActorAndPreservesStatusAndBody() {
        UserContext.set("user-1", "user-1", "USER");
        try {
            when(applicationService.save(any(PlanningActor.class), eq("s1"), eq("tok")))
                    .thenAnswer(inv -> {
                        PlanningActor actor = inv.getArgument(0);
                        assertEquals(new PlanningActor("user-1", false), actor);
                        return new PlannerSaveResult(201, Map.of("ok", true, "tripId", "formal-1"));
                    });

            ResponseEntity<?> response = controller.save("s1", "tok");
            assertEquals(201, response.getStatusCode().value());
            assertEquals(Map.of("ok", true, "tripId", "formal-1"), response.getBody());
            verify(applicationService).save(new PlanningActor("user-1", false), "s1", "tok");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void converseInjectsTokenWhenNull() {
        when(applicationService.converse(any(PlanningActor.class), eq("s1"), any(PlanConversationRequestDto.class)))
                .thenAnswer(inv -> {
                    PlanConversationRequestDto req = inv.getArgument(2);
                    assertEquals("tok", req.getSessionAccessToken());
                    return PreviewAdjustmentResult.success(Map.of("conv", true));
                });

        ResponseEntity<?> response = controller.converse("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).converse(argThat(PlanningActor::anonymous), eq("s1"), any(PlanConversationRequestDto.class));
    }

    @Test
    void previewAdjustmentInjectsTokenWhenNull() {
        when(applicationService.previewAdjustment(any(PlanningActor.class), eq("s1"), any(PlanConversationRequestDto.class)))
                .thenAnswer(inv -> {
                    PlanConversationRequestDto req = inv.getArgument(2);
                    assertEquals("tok", req.getSessionAccessToken());
                    return PreviewAdjustmentResult.success(Map.of("preview", true));
                });

        ResponseEntity<?> response = controller.previewAdjustment("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).previewAdjustment(argThat(PlanningActor::anonymous), eq("s1"), any(PlanConversationRequestDto.class));
    }

    @Test
    void previewApplicationOutcomeMapsToCompatibleHttpStatusAndBody() {
        when(applicationService.previewAdjustment(any(PlanningActor.class), eq("s1"), any(PlanConversationRequestDto.class)))
                .thenReturn(PreviewAdjustmentResult.of(PreviewAdjustmentResult.Outcome.CONFLICT,
                        Map.of("ok", false, "message", "行程版本已更新")));

        ResponseEntity<?> response = controller.previewAdjustment("s1", new PlanConversationRequestDto(), "tok");
        assertEquals(409, response.getStatusCode().value());
        assertEquals(Map.of("ok", false, "message", "行程版本已更新"), response.getBody());
    }

    @Test
    void nonApplicablePreviewOutcomeRemainsHttpSuccessButCannotBeApplied() {
        Map<String, Object> body = Map.of("ok", true, "proposalId", "p1", "canApply", false,
                "candidateReplacements", java.util.List.of());
        when(applicationService.previewAdjustment(any(PlanningActor.class), eq("s1"), any(PlanConversationRequestDto.class)))
                .thenReturn(PreviewAdjustmentResult.of(PreviewAdjustmentResult.Outcome.NO_CANDIDATE, body));

        ResponseEntity<?> response = controller.previewAdjustment("s1", new PlanConversationRequestDto(), "tok");
        assertEquals(200, response.getStatusCode().value());
        assertEquals(body, response.getBody());
        assertEquals(false, ((Map<?, ?>) response.getBody()).get("canApply"));
    }

    @Test
    void applyAdjustmentInjectsTokenWhenNull() {
        when(applicationService.applyAdjustment(any(PlanningActor.class), eq("s1"), any(ApplyAdjustmentRequestDto.class)))
                .thenAnswer(inv -> {
                    ApplyAdjustmentRequestDto req = inv.getArgument(2);
                    assertEquals("tok", req.getSessionAccessToken());
                    return ApplyAdjustmentResult.of(ApplyAdjustmentResult.Outcome.SUCCESS, Map.of("apply", true));
                });

        ResponseEntity<?> response = controller.applyAdjustment("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }
}

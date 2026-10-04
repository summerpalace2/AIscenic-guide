package com.ai.guide.adapter.planner.web;

import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.model.ShadowMutationRequest;
import com.ai.guide.domain.planner.model.ShadowPlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerControllerTest {

    private PlanningApplicationService applicationService;
    private PlannerController controller;

    @BeforeEach
    void setUp() {
        applicationService = mock(PlanningApplicationService.class);
        controller = new PlannerController(applicationService);
    }

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
        when(applicationService.openFormalTrip("trip-1"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("tripId", "trip-1")));

        ResponseEntity<?> response = controller.openFormalTrip("trip-1");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).openFormalTrip("trip-1");
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
    void getSessionPassesToken() {
        when(applicationService.get("s1", "my-token"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("sessionId", "s1")));

        ResponseEntity<?> response = controller.get("s1", "my-token");
        assertEquals(200, response.getStatusCode().value());
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
        when(applicationService.replan(eq("s1"), any(PlannerMutationRequest.class)))
                .thenAnswer(inv -> {
                    PlannerMutationRequest req = inv.getArgument(1);
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("replan", true));
                });

        ResponseEntity<?> response = controller.replan("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void stopsInjectsTokenWhenNull() {
        when(applicationService.mutateStops(eq("s1"), any(PlannerMutationRequest.class)))
                .thenAnswer(inv -> {
                    PlannerMutationRequest req = inv.getArgument(1);
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("stops", true));
                });

        ResponseEntity<?> response = controller.stops("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void saveDelegatesToApplicationService() {
        when(applicationService.save("s1", "tok"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("saved", true)));

        ResponseEntity<?> response = controller.save("s1", "tok");
        assertEquals(200, response.getStatusCode().value());
        verify(applicationService).save("s1", "tok");
    }

    @Test
    void converseInjectsTokenWhenNull() {
        when(applicationService.converse(eq("s1"), any(PlanConversationRequestDto.class)))
                .thenAnswer(inv -> {
                    PlanConversationRequestDto req = inv.getArgument(1);
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("conv", true));
                });

        ResponseEntity<?> response = controller.converse("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void previewAdjustmentInjectsTokenWhenNull() {
        when(applicationService.previewAdjustment(eq("s1"), any(PlanConversationRequestDto.class)))
                .thenAnswer(inv -> {
                    PlanConversationRequestDto req = inv.getArgument(1);
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("preview", true));
                });

        ResponseEntity<?> response = controller.previewAdjustment("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void applyAdjustmentInjectsTokenWhenNull() {
        when(applicationService.applyAdjustment(eq("s1"), any(ApplyAdjustmentRequestDto.class)))
                .thenAnswer(inv -> {
                    ApplyAdjustmentRequestDto req = inv.getArgument(1);
                    assertEquals("tok", req.getSessionAccessToken());
                    return new PlannerService.ServiceResult(200, Map.of("apply", true));
                });

        ResponseEntity<?> response = controller.applyAdjustment("s1", null, "tok");
        assertEquals(200, response.getStatusCode().value());
    }
}

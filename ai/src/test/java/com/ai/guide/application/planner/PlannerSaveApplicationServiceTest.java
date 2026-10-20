package com.ai.guide.application.planner;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.gateway.PlannerSaveUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerSaveActor;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerSaveApplicationServiceTest {
    private final PlannerSessionRepositoryPort sessions = mock(PlannerSessionRepositoryPort.class);
    private final TripService trips = mock(TripService.class);
    private final PlannerSaveApplicationService service = new PlannerSaveApplicationService(sessions, trips);

    private PlannerSessionRepositoryPort.StoredSession session() {
        Map<String, Object> serverTrip = new LinkedHashMap<>();
        serverTrip.put("title", "server snapshot");
        serverTrip.put("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "server-stop")))));
        return new PlannerSessionRepositoryPort.StoredSession("session-1", "USER", "owner-1", "hash",
                "fingerprint", "prompt", Map.of(), AppliedPreferencesSnapshot.empty(), serverTrip,
                7, 7, "ACTIVE", 1, 2);
    }

    @Test
    void savesOwnedServerSnapshotWithSessionRevisionKeyAndReturnsEstablishedFields() {
        var session = session();
        when(sessions.find("session-1", "USER", "owner-1", "capability")).thenReturn(session);
        var savedTrip = new Trip("formal-1", "owner-1", "server snapshot", "ACTIVE", 1, 1, 2,
                session.trip());
        var persistedInput = new AtomicReference<Map<String, Object>>();
        when(trips.savePlannerSnapshot(eq("owner-1"), anyMap(), eq("planner-save-session-1-7")))
                .thenAnswer(inv -> {
                    persistedInput.set(new LinkedHashMap<>(inv.getArgument(1)));
                    return TripService.OperationResult.success(201, "行程已创建", "CREATED", savedTrip);
                });
        when(sessions.linkFormalTrip(eq(session), anyMap())).thenAnswer(inv -> {
            Map<?, ?> linkedPlan = inv.getArgument(1);
            assertEquals("formal-1", linkedPlan.get("formalTripId"));
            assertEquals("formal-1", linkedPlan.get("sourceTripId"));
            assertEquals(1, linkedPlan.get("sourceTripVersion"));
            return true;
        });

        var result = service.save(new PlannerSaveActor("owner-1", false), "session-1", "capability");

        assertEquals(201, result.serviceStatus());
        assertEquals(true, result.body().get("ok"));
        assertEquals("formal-1", result.body().get("tripId"));
        assertEquals(1, result.body().get("currentVersion"));
        assertEquals(true, result.body().get("draftLinked"));
        assertSame(savedTrip, result.body().get("trip"));
        assertTrue(result.body().keySet().containsAll(
                List.of("ok", "trip", "tripId", "currentVersion", "appliedPreferences", "draftLinked")));
        verify(trips).savePlannerSnapshot(eq("owner-1"), anyMap(), eq("planner-save-session-1-7"));
        assertEquals(serverSnapshotBeforeSave(session), persistedInput.get());
        verify(sessions).linkFormalTrip(eq(session), anyMap());
    }

    @Test
    void anonymousSaveIsRejectedBeforeSessionOrFormalTripWrites() {
        var result = service.save(new PlannerSaveActor(null, true), "session-1", "capability");

        assertEquals(401, result.serviceStatus());
        assertEquals(false, result.body().get("ok"));
        assertEquals("请先登录后保存行程。", result.body().get("message"));
        verify(sessions, never()).find(anyString(), anyString(), anyString(), anyString());
        verify(sessions, never()).claimAnonymous(anyString(), anyString(), anyString());
        verify(trips, never()).savePlannerSnapshot(anyString(), anyMap(), anyString());
    }

    @Test
    void missingOwnedSessionMayBeClaimedByCapabilityBeforeSaving() {
        var session = session();
        when(sessions.find("session-1", "USER", "owner-1", "capability")).thenReturn(null);
        when(sessions.claimAnonymous("session-1", "capability", "owner-1")).thenReturn(session);
        var savedTrip = new Trip("formal-1", "owner-1", "server snapshot", "ACTIVE", 1, 1, 2,
                session.trip());
        when(trips.savePlannerSnapshot(eq("owner-1"), anyMap(), eq("planner-save-session-1-7")))
                .thenReturn(TripService.OperationResult.success(200, "行程已保存", "UPDATED", savedTrip));
        when(sessions.linkFormalTrip(eq(session), anyMap())).thenReturn(true);

        var result = service.save(new PlannerSaveActor("owner-1", false), "session-1", "capability");

        assertEquals(200, result.serviceStatus());
        assertEquals("formal-1", result.body().get("tripId"));
        verify(sessions).claimAnonymous("session-1", "capability", "owner-1");
        verify(trips).savePlannerSnapshot(eq("owner-1"), anyMap(), eq("planner-save-session-1-7"));
    }

    @Test
    void legacyPlannerServiceSaveDelegatesToConfiguredUseCaseWithCurrentActor() {
        PlannerSaveUseCasePort saveUseCase = mock(PlannerSaveUseCasePort.class);
        var planner = new PlannerService(null, null, sessions, trips, new com.fasterxml.jackson.databind.ObjectMapper(),
                null, null, null, null, null, true, saveUseCase);
        var delegated = new PlannerSaveResult(200, Map.of("ok", true, "tripId", "formal-legacy"));
        when(saveUseCase.save(new PlannerSaveActor("owner-legacy", false), "session-legacy", "capability"))
                .thenReturn(delegated);
        UserContext.set("owner-legacy", "owner-legacy", "USER");
        try {
            var result = planner.save("session-legacy", "capability");
            assertEquals(200, result.status());
            assertEquals(delegated.body(), result.body());
            verify(saveUseCase).save(new PlannerSaveActor("owner-legacy", false),
                    "session-legacy", "capability");
        } finally {
            UserContext.clear();
        }
    }

    private Map<String, Object> serverSnapshotBeforeSave(PlannerSessionRepositoryPort.StoredSession session) {
        Map<String, Object> expected = new LinkedHashMap<>(session.trip());
        expected.put("plannerSessionId", "session-1");
        expected.put("plannerVersion", 7);
        expected.put("appliedPreferences", session.appliedPreferences().asMap());
        return expected;
    }
}

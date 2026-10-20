package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerOpenActor;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerOpenApplicationServiceTest {
    private final PlannerSessionRepositoryPort sessions = mock(PlannerSessionRepositoryPort.class);
    private final TripService trips = mock(TripService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlannerOpenApplicationService service = new PlannerOpenApplicationService(sessions, trips, mapper, true);

    @BeforeEach
    void resetMocks() {
        reset(sessions, trips);
    }

    @Test
    void opensOneOwnerReadAsCoherentSessionAndPreservesFormalVersion() {
        Map<String, Object> formalSnapshot = new LinkedHashMap<>();
        formalSnapshot.put("version", 4);
        formalSnapshot.put("sourceMode", "高德实时接口");
        formalSnapshot.put("routeDataStatus", "LIVE");
        formalSnapshot.put("prompt", "重庆三日游");
        formalSnapshot.put("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "s1")))));
        Trip formal = new Trip("formal-9", "owner-1", "重庆行程", "ACTIVE", 7, 1L, 2L, formalSnapshot);
        when(trips.get("owner-1", "formal-9")).thenReturn(formal);
        when(sessions.create(eq("USER"), eq("owner-1"), eq("重庆三日游"), anyMap(), any(), anyMap(),
                startsWith("trip-open-"), anyString())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") Map<String, Object> restored = call.getArgument(5);
            StoredSession created = new StoredSession("session-9", "USER", "owner-1", "token-hash", "fp",
                    "重庆三日游", call.getArgument(3), call.getArgument(4), new LinkedHashMap<>(restored),
                    1, 1, "DRAFT", 10, 11);
            // USER-owned sessions rely on the authenticated owner identity; the
            // repository only issues raw access tokens for ANONYMOUS sessions.
            return CreateResult.created(created, "");
        });

        var result = service.openFormalTrip(new PlannerOpenActor("owner-1", false), "formal-9");

        assertEquals(200, result.serviceStatus());
        assertEquals("session-9", result.body().get("sessionId"));
        assertFalse(result.body().containsKey("sessionAccessToken"));
        assertEquals("formal-9", result.body().get("formalTripId"));
        assertEquals(7, result.body().get("formalTripVersion"));
        assertEquals(true, result.body().get("restoredFromTrip"));
        assertEquals("V1_PROPOSAL", result.body().get("adjustmentCapability"));
        @SuppressWarnings("unchecked") Map<String, Object> returnedTrip = (Map<String, Object>) result.body().get("trip");
        assertFalse(returnedTrip.containsKey("sessionAccessToken"));
        assertEquals("formal-9", returnedTrip.get("sourceTripId"));
        assertEquals("formal-9", returnedTrip.get("formalTripId"));
        assertEquals(7, returnedTrip.get("sourceTripVersion"));
        assertEquals(4, returnedTrip.get("sourcePlannerRevision"));
        assertEquals(1, returnedTrip.get("version"));
        assertEquals("重庆行程", returnedTrip.get("title"));
        verify(trips).get("owner-1", "formal-9");
        verify(sessions, times(1)).create(eq("USER"), eq("owner-1"), eq("重庆三日游"), anyMap(), any(), anyMap(), anyString(), anyString());
    }

    @Test
    void anonymousActorIsRejectedBeforeTripReadOrSessionCreation() {
        var result = service.openFormalTrip(new PlannerOpenActor(null, true), "formal-9");
        assertEquals(401, result.serviceStatus());
        assertEquals("请先登录后打开正式行程。", result.body().get("message"));
        verifyNoInteractions(trips, sessions);
    }

    @Test
    void authenticatedActorWithoutOwnerIdentityIsRejectedWithoutSideEffects() {
        var result = service.openFormalTrip(new PlannerOpenActor(" ", false), "formal-9");
        assertEquals(401, result.serviceStatus());
        verifyNoInteractions(trips, sessions);
    }

    @Test
    void missingOrUnownedTripDoesNotCreateSession() {
        when(trips.get("owner-2", "formal-9")).thenReturn(null);
        var result = service.openFormalTrip(new PlannerOpenActor("owner-2", false), "formal-9");
        assertEquals(404, result.serviceStatus());
        verify(trips).get("owner-2", "formal-9");
        verifyNoInteractions(sessions);
    }

    @Test
    void emptyFormalSnapshotDoesNotCreateSession() {
        when(trips.get("owner-1", "formal-9"))
                .thenReturn(new Trip("formal-9", "owner-1", "空行程", "ACTIVE", 3, 1L, 2L, Map.of()));
        var result = service.openFormalTrip(new PlannerOpenActor("owner-1", false), "formal-9");
        assertEquals(400, result.serviceStatus());
        assertEquals("正式行程缺少可恢复的规划快照。", result.body().get("message"));
        verifyNoInteractions(sessions);
    }

    @Test
    void discardRemovesOnlyTheMatchingUnchangedOpenSession() {
        Map<String, Object> openedTrip = Map.of(
                "formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1);
        StoredSession opened = new StoredSession("session-open", "USER", "owner-1", "", "fp",
                "prompt", Map.of(), AppliedPreferencesSnapshot.empty(), openedTrip,
                1, 1, "OPEN_PENDING", 10, 10);
        when(sessions.find("session-open", "USER", "owner-1", "")).thenReturn(opened);
        when(sessions.deleteUnchangedUserSession("session-open", "owner-1", 1)).thenReturn(true);

        var result = service.discardUncommittedOpen(new PlannerOpenActor("owner-1", false), "formal-9", "session-open");

        assertEquals(200, result.serviceStatus());
        assertEquals(true, result.body().get("discarded"));
        verify(sessions).deleteUnchangedUserSession("session-open", "owner-1", 1);
    }

    @Test
    void discardRejectsAnAcknowledgedOpenWithoutDeletingIt() {
        Map<String, Object> openedTrip = Map.of(
                "formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1);
        StoredSession acknowledged = new StoredSession("session-open", "USER", "owner-1", "", "fp",
                "prompt", Map.of(), AppliedPreferencesSnapshot.empty(), openedTrip,
                1, 1, "ACTIVE", 10, 11);
        when(sessions.find("session-open", "USER", "owner-1", "")).thenReturn(acknowledged);

        var result = service.discardUncommittedOpen(new PlannerOpenActor("owner-1", false), "formal-9", "session-open");

        assertEquals(409, result.serviceStatus());
        verify(sessions, never()).deleteUnchangedUserSession(anyString(), anyString(), anyInt());
    }

    @Test
    void discardRejectsOtherTripAndChangedSessionWithoutDeleting() {
        Map<String, Object> openedTrip = Map.of(
                "formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1);
        StoredSession changed = new StoredSession("session-open", "USER", "owner-1", "", "fp",
                "prompt", Map.of(), AppliedPreferencesSnapshot.empty(), openedTrip,
                2, 2, "ACTIVE", 10, 11);
        when(sessions.find("session-open", "USER", "owner-1", "")).thenReturn(changed);

        var wrongTrip = service.discardUncommittedOpen(new PlannerOpenActor("owner-1", false), "other-trip", "session-open");
        var changedSession = service.discardUncommittedOpen(new PlannerOpenActor("owner-1", false), "formal-9", "session-open");

        assertEquals(409, wrongTrip.serviceStatus());
        assertEquals(409, changedSession.serviceStatus());
        verify(sessions, never()).deleteUnchangedUserSession(anyString(), anyString(), anyInt());
    }

    @Test
    void discardIsIdempotentWhenSessionWasAlreadyRemoved() {
        when(sessions.find("missing-session", "USER", "owner-1", "")).thenReturn(null);

        var result = service.discardUncommittedOpen(new PlannerOpenActor("owner-1", false), "formal-9", "missing-session");

        assertEquals(200, result.serviceStatus());
        assertEquals(true, result.body().get("alreadyAbsent"));
        verify(sessions, never()).deleteUnchangedUserSession(anyString(), anyString(), anyInt());
    }

    @Test
    void reusingTheSameOperationKeyUsesTheSameRepositoryIdempotencyKey() {
        Map<String, Object> formalSnapshot = Map.of("version", 1, "prompt", "重庆周末游",
                "days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "s1")))));
        when(trips.get("owner-1", "formal-9"))
                .thenReturn(new Trip("formal-9", "owner-1", "重庆行程", "ACTIVE", 7, 1L, 2L, formalSnapshot));
        StoredSession stored = new StoredSession("session-9", "USER", "owner-1", "", "fp", "重庆周末游",
                Map.of(), AppliedPreferencesSnapshot.empty(), Map.of("formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1), 1, 1, "OPEN_PENDING", 10, 11);
        when(sessions.create(eq("USER"), eq("owner-1"), anyString(), anyMap(), any(), anyMap(), anyString(), anyString()))
                .thenReturn(CreateResult.created(stored, ""));

        service.openFormalTrip(new PlannerOpenActor("owner-1", false), "formal-9", "retry-stable-key");
        service.openFormalTrip(new PlannerOpenActor("owner-1", false), "formal-9", "retry-stable-key");

        var keys = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(sessions, times(2)).create(eq("USER"), eq("owner-1"), anyString(), anyMap(), any(), anyMap(), keys.capture(), anyString());
        assertEquals(keys.getAllValues().get(0), keys.getAllValues().get(1));
        assertTrue(keys.getValue().startsWith("trip-open-"));
    }

    @Test
    void conflictingOperationKeyDoesNotReturnAnOlderFormalSnapshot() {
        Map<String, Object> formalSnapshot = Map.of("version", 1, "prompt", "重庆周末游",
                "days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "s1")))));
        when(trips.get("owner-1", "formal-9"))
                .thenReturn(new Trip("formal-9", "owner-1", "重庆行程", "ACTIVE", 8, 1L, 2L, formalSnapshot));
        StoredSession old = new StoredSession("session-old", "USER", "owner-1", "", "old", "重庆周末游",
                Map.of(), AppliedPreferencesSnapshot.empty(), Map.of("formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1), 1, 1, "ACTIVE", 10, 11);
        when(sessions.create(eq("USER"), eq("owner-1"), anyString(), anyMap(), any(), anyMap(), anyString(), anyString()))
                .thenReturn(CreateResult.conflict(old));

        var result = service.openFormalTrip(new PlannerOpenActor("owner-1", false), "formal-9", "retry-stable-key");

        assertEquals(409, result.serviceStatus());
        assertEquals(false, result.body().get("ok"));
    }

    @Test
    void acknowledgeActivatesOnlyAnOwnedMatchingFormalOpen() {
        Map<String, Object> openedTrip = Map.of("formalTripId", "formal-9", "sourceTripId", "formal-9",
                "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1);
        StoredSession opened = new StoredSession("session-open", "USER", "owner-1", "", "fp", "prompt",
                Map.of(), AppliedPreferencesSnapshot.empty(), openedTrip, 1, 1, "OPEN_PENDING", 10, 11);
        when(sessions.find("session-open", "USER", "owner-1", "")).thenReturn(opened);
        when(sessions.activatePendingFormalOpen("session-open", "owner-1")).thenReturn(true);

        var result = service.acknowledgeOpenSession(new PlannerOpenActor("owner-1", false), "formal-9", "session-open");

        assertEquals(200, result.serviceStatus());
        assertEquals(true, result.body().get("acknowledged"));
        verify(sessions).activatePendingFormalOpen("session-open", "owner-1");
    }
}

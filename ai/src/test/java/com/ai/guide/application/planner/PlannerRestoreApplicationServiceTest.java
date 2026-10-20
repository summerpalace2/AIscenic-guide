package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerRestoreActor;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripPlanLimits;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerRestoreApplicationServiceTest {
    private final PlannerSessionRepositoryPort sessions = mock(PlannerSessionRepositoryPort.class);
    private final TripService trips = mock(TripService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final TripPlanLimits planLimits = new TripPlanLimits(mapper);
    private final PlannerRestoreApplicationService service = new PlannerRestoreApplicationService(
            sessions, trips, planLimits, new com.ai.guide.domain.planner.service.PlannerMetricsLogger(), true);

    @BeforeEach void resetMocks() {
        reset(sessions, trips);
        when(sessions.find("session-1", "USER", "owner-1", "valid-cap")).thenReturn(session("formal-1", 3));
        when(trips.get("owner-1", "formal-1")).thenReturn(formal("formal-1", 5));
    }

    private StoredSession session(String formalId, int revision) {
        Map<String, Object> serverTrip = new LinkedHashMap<>();
        serverTrip.put("sourceTripId", formalId);
        serverTrip.put("formalTripId", formalId);
        serverTrip.put("sourceTripVersion", 5);
        serverTrip.put("version", revision);
        serverTrip.put("sourceMode", "高德实时接口");
        serverTrip.put("routeDataStatus", "LIVE");
        serverTrip.put("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "server-old")))));
        return new StoredSession("session-1", "USER", "owner-1", "hash", "fp", "prompt", Map.of(),
                AppliedPreferencesSnapshot.empty(), serverTrip, revision, 8, "DRAFT", 1, 2);
    }

    private Trip formal(String id, int version) {
        return new Trip(id, "owner-1", "Formal", "ACTIVE", version, 1, 2,
                Map.of("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "formal"))))));
    }

    private Map<String, Object> backup(String sourceId, int sourceVersion) {
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("formalTripId", "browser-formal-spoof");
        trip.put("sourceTripId", sourceId);
        trip.put("sourceTripVersion", sourceVersion);
        trip.put("version", 99);
        trip.put("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "edited-stop")))));
        return trip;
    }

    private void assertNoSessionWrite() {
        verify(sessions, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        verify(trips, never()).update(anyString(), anyString(), anyMap());
    }

    @Test
    void restoresValidatedBackupIntoAuthoritativeSessionAndFormalIdentity() {
        Map<String, Object> backup = backup("browser-source-spoof", 5);
        when(sessions.update(any(StoredSession.class), anyMap(), eq(3), eq("RESTORE_DRAFT"),
                eq("恢复本地草稿"), eq("用户确认恢复离线备份"), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") Map<String, Object> restored = call.getArgument(1);
            return MutationResult.updated(new StoredSession("session-1", "USER", "owner-1", "hash", "fp", "prompt",
                    Map.of(), AppliedPreferencesSnapshot.empty(), new LinkedHashMap<>(restored), 4, 9, "DRAFT", 1, 3));
        });

        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup, 3, "valid-cap"));

        assertEquals(200, result.serviceStatus());
        assertEquals("session-1", result.body().get("sessionId"));
        assertEquals(9, result.body().get("syncRevision"));
        assertEquals("草稿已恢复到当前规划会话。", result.body().get("message"));
        @SuppressWarnings("unchecked") Map<String, Object> restored = (Map<String, Object>) result.body().get("trip");
        assertEquals("formal-1", restored.get("sourceTripId"));
        assertEquals("formal-1", restored.get("formalTripId"));
        assertEquals(5, restored.get("sourceTripVersion"));
        assertEquals(4, restored.get("version"));
        assertEquals("edited-stop", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) restored.get("days")).get(0)).get("stops")).get(0)).get("id"));
        verify(trips).get("owner-1", "formal-1");
        verify(sessions).update(any(), anyMap(), eq(3), eq("RESTORE_DRAFT"), eq("恢复本地草稿"),
                eq("用户确认恢复离线备份"), eq(List.of()));
    }

    @Test void anonymousActorIsRejectedBeforeAnySessionLookupOrWrite() {
        var result = service.restoreDraft(new PlannerRestoreActor(null, true), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));
        assertEquals(401, result.serviceStatus());
        assertEquals("登录后才能恢复正式行程草稿。", result.body().get("message"));
        verifyNoInteractions(sessions, trips);
    }

    @Test void anotherOwnerCannotRestoreAndWrongCapabilityCannotClaimAnonymousSession() {
        when(sessions.find("session-1", "USER", "other-owner", "valid-cap")).thenReturn(null);
        when(sessions.claimAnonymous("session-1", "valid-cap", "other-owner")).thenReturn(null);
        var otherOwner = service.restoreDraft(new PlannerRestoreActor("other-owner", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));
        assertEquals(404, otherOwner.serviceStatus());

        when(sessions.find("session-1", "USER", "owner-1", "wrong-cap")).thenReturn(null);
        when(sessions.claimAnonymous("session-1", "wrong-cap", "owner-1")).thenReturn(null);
        var wrongCapability = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "wrong-cap"));
        assertEquals(404, wrongCapability.serviceStatus());
        verify(sessions).claimAnonymous("session-1", "wrong-cap", "owner-1");
        assertNoSessionWrite();
        verifyNoInteractions(trips);
    }

    @Test void userSessionAuthorizationUsesAuthenticatedOwnerRatherThanCapabilityToken() {
        when(sessions.find("session-1", "USER", "owner-1", "wrong-cap")).thenReturn(session("formal-1", 3));
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 2, "wrong-cap"));

        assertEquals(409, result.serviceStatus());
        @SuppressWarnings("unchecked") Map<String, Object> serverTrip = (Map<String, Object>) result.body().get("trip");
        assertEquals(3, serverTrip.get("version"));
        verify(sessions).find("session-1", "USER", "owner-1", "wrong-cap");
        verify(sessions, never()).claimAnonymous(anyString(), anyString(), anyString());
        verifyNoInteractions(trips);
        assertNoSessionWrite();
    }

    @Test void stalePlannerRevisionIsRejectedBeforeFormalTripRead() {
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 2, "valid-cap"));
        assertEquals(409, result.serviceStatus());
        @SuppressWarnings("unchecked") Map<String, Object> unchanged = (Map<String, Object>) result.body().get("trip");
        assertEquals(3, unchanged.get("version"));
        assertNoSessionWrite();
        verifyNoInteractions(trips);
    }

    @Test void staleFormalVersionIsRejectedWithoutSessionWrite() {
        when(trips.get("owner-1", "formal-1")).thenReturn(formal("formal-1", 6));
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));
        assertEquals(409, result.serviceStatus());
        assertEquals("正式行程已更新，请先查看最新版本再恢复备份。", result.body().get("message"));
        assertNoSessionWrite();
    }

    @Test void missingFormalLinkOrUnownedTripIsRejectedWithoutSessionWrite() {
        when(sessions.find("session-1", "USER", "owner-1", "valid-cap"))
                .thenReturn(session(" ", 3));
        var noLink = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));
        assertEquals(400, noLink.serviceStatus());
        assertNoSessionWrite();

        when(sessions.find("session-1", "USER", "owner-1", "valid-cap"))
                .thenReturn(session("formal-1", 3));
        when(trips.get("owner-1", "formal-1")).thenReturn(null);
        var notFound = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));
        assertEquals(404, notFound.serviceStatus());
        assertNoSessionWrite();
    }

    @Test void invalidBackupIsRejectedWithoutSessionWrite() {
        Map<String, Object> invalid = new LinkedHashMap<>(backup("formal-1", 5));
        invalid.remove("days");
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(invalid, 3, "valid-cap"));
        assertEquals(400, result.serviceStatus());
        assertNoSessionWrite();
    }

    @Test void configuredLowPlanLimitRejectsBackupBeforeSessionUpdate() {
        TripPlanLimits lowLimits = new TripPlanLimits(mapper);
        ReflectionTestUtils.setField(lowLimits, "maxDays", 1);
        PlannerRestoreApplicationService limitedService = new PlannerRestoreApplicationService(
                sessions, trips, lowLimits, new com.ai.guide.domain.planner.service.PlannerMetricsLogger(), true);
        Map<String, Object> twoDayBackup = backup("formal-1", 5);
        twoDayBackup.put("days", List.of(
                Map.of("day", 1, "stops", List.of(Map.of("id", "one"))),
                Map.of("day", 2, "stops", List.of(Map.of("id", "two")))));

        var result = limitedService.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(twoDayBackup, 3, "valid-cap"));

        assertEquals(400, result.serviceStatus());
        assertTrue(String.valueOf(result.body().get("message")).contains("最多支持 1 天"));
        assertNoSessionWrite();
        verify(trips).get("owner-1", "formal-1");
    }

    @Test void updateCasConflictReturnsLatestStateWithoutRetryingUpdate() {
        StoredSession latest = session("formal-1", 4);
        Map<String, Object> latestTrip = new LinkedHashMap<>(latest.trip());
        latestTrip.put("version", 4);
        latestTrip.put("marker", "latest-server-state");
        latest = new StoredSession(latest.sessionId(), latest.ownerType(), latest.ownerId(), latest.accessTokenHash(),
                latest.requestFingerprint(), latest.prompt(), latest.constraints(), latest.appliedPreferences(), latestTrip,
                4, 10, latest.status(), latest.createdAt(), latest.updatedAt());
        when(sessions.update(any(StoredSession.class), anyMap(), eq(3), eq("RESTORE_DRAFT"),
                eq("恢复本地草稿"), eq("用户确认恢复离线备份"), anyList()))
                .thenReturn(MutationResult.conflict(latest));

        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1", 5), 3, "valid-cap"));

        assertEquals(409, result.serviceStatus());
        assertEquals("session-1", result.body().get("sessionId"));
        @SuppressWarnings("unchecked") Map<String, Object> latestResponse = (Map<String, Object>) result.body().get("trip");
        assertEquals(4, latestResponse.get("version"));
        assertEquals("latest-server-state", latestResponse.get("marker"));
        verify(sessions, times(1)).update(any(StoredSession.class), anyMap(), eq(3), eq("RESTORE_DRAFT"),
                eq("恢复本地草稿"), eq("用户确认恢复离线备份"), eq(List.of()));
    }
    @Test void backupCannotRemoveStoredPinnedStopEvenWhenPinsAreOmitted() {
        StoredSession original = session("formal-1",3);
        original.trip().put("pinnedStopIds", List.of("server-old"));
        when(sessions.find("session-1", "USER", "owner-1", "valid-cap")).thenReturn(original);
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1", false), "session-1",
                new RestoreDraftRequest(backup("formal-1",5),3,"valid-cap"));
        assertEquals(400,result.serviceStatus());
        assertNoSessionWrite();
    }

    @Test void backupCannotInjectPinsIntoLegacySession() {
        var proposed = backup("formal-1",5);
        proposed.put("pinnedStopIds",List.of("edited-stop"));
        when(sessions.update(any(),anyMap(),anyInt(),anyString(),anyString(),anyString(),anyList()))
                .thenAnswer(call -> MutationResult.updated(new StoredSession("session-1","USER","owner-1","hash","fp","",Map.of(),
                        AppliedPreferencesSnapshot.empty(),call.getArgument(1),4,9,"DRAFT",1,2)));
        var result = service.restoreDraft(new PlannerRestoreActor("owner-1",false),"session-1",
                new RestoreDraftRequest(proposed,3,"valid-cap"));
        assertEquals(200,result.serviceStatus());
        var restored = (Map<?,?>)result.body().get("trip");
        assertEquals(List.of(),restored.get("pinnedStopIds"));
    }
}

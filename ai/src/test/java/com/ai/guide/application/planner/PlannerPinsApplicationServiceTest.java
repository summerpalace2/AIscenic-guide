package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerPinsApplicationServiceTest {
    final PlannerSessionRepositoryPort repository = mock(PlannerSessionRepositoryPort.class);
    final PlanAdjustmentService adjustments = mock(PlanAdjustmentService.class);
    final PlannerApplicationService application = new PlannerApplicationService(mock(PlannerService.class),
            mock(PlannerSaveUseCasePort.class), repository, adjustments, mock(ProposalRepositoryPort.class),
            mock(PlannerOpenUseCasePort.class), mock(PlannerRestoreUseCasePort.class));
    final PlanningActor owner = new PlanningActor("owner", false);
    StoredSession current;

    @BeforeEach void setup() {
        current = session(new LinkedHashMap<>(Map.of("version", 3,
                "days", List.of(Map.of("day", 1, "stops", List.of(
                        Map.of("id", "a", "venueId", "v-a", "name", "A"),
                        Map.of("id", "b", "venueId", "v-b", "name", "B")))),
                "pinnedStopIds", List.of("a"))), 3);
        when(adjustments.withSessionApplyLock(eq("session"), any())).thenAnswer(call -> ((Supplier<?>)call.getArgument(1)).get());
        when(repository.find("session", "USER", "owner", "cap")).thenAnswer(call -> current);
        when(repository.update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList()))
                .thenAnswer(call -> {
                    current = session(call.getArgument(1), current.currentVersion() + 1);
                    return MutationResult.updated(current);
                });
    }
    StoredSession session(Map<String,Object> trip, int version) {
        return new StoredSession("session", "USER", "owner", "hash", "fp", "", Map.of(),
                AppliedPreferencesSnapshot.empty(), trip, version, version, "ACTIVE", 1, 1);
    }
    PlannerService.ServiceResult change(Integer version, List<String> add, List<String> remove) {
        return application.updatePins(owner, "session", new PlannerPinsRequest(version, add, remove, "cap"));
    }
    @Test void addPersistsInVersionedSnapshotAndReadback() {
        var result = change(3, List.of("b"), null);
        assertEquals(200, result.status());
        assertEquals(List.of("a", "b"), PlannerPins.read(current.trip()));
        assertEquals(4, current.currentVersion());
        assertEquals(4, current.trip().get("version"));
        verify(adjustments).resetAuthorizedContinuation(current);
    }
    @Test void explicitRemovalOnlyRemovesSpecifiedProtection() {
        assertEquals(200, change(3, List.of("b"), List.of("a")).status());
        assertEquals(List.of("b"), PlannerPins.read(current.trip()));
    }
    @Test void emptyDeltaDoesNotClearOrIncreaseVersion() {
        assertEquals(200, change(3, List.of(), List.of()).status());
        assertEquals(List.of("a"), PlannerPins.read(current.trip()));
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void duplicateRequestWithStaleVersionIsConflict() {
        assertEquals(200, change(3, List.of("b"), null).status());
        assertEquals(409, change(3, List.of("b"), null).status());
        verify(repository, times(1)).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void nonexistentTargetsAndContradictoryDeltaRejected() {
        assertEquals(400, change(3, List.of("foreign-stop"), null).status());
        assertEquals(400, change(3, List.of("b"), List.of("b")).status());
        assertEquals(400, change(null, List.of("b"), null).status());
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void foreignOwnerCannotPin() {
        var result = application.updatePins(new PlanningActor("other", false), "session",
                new PlannerPinsRequest(3, List.of("b"), null, "bad-cap"));
        assertEquals(404, result.status());
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void anonymousCapabilityMustMatchSession() {
        when(repository.findAnonymousByAccessToken("bad-cap")).thenReturn(null);
        assertEquals(404, application.updatePins(new PlanningActor("guest", true), "session",
                new PlannerPinsRequest(3, List.of("b"), null, "bad-cap")).status());
    }
    @Test void casFailureDoesNotResetContinuationOrReportSuccess() {
        when(repository.update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList()))
                .thenReturn(MutationResult.conflict(current));
        assertEquals(409, change(3, List.of("b"), null).status());
        verify(adjustments, never()).resetAuthorizedContinuation(any());
    }
}

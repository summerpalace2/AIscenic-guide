package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.application.planner.PlannerRestoreApplicationService;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerDraftRestoreTest {
    private final PlannerSessionRepositoryPort repository = mock(PlannerSessionRepositoryPort.class);
    private final TripService trips = mock(TripService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlannerService service = new PlannerService(null, null, repository, trips,
            mapper, null, null, null, new PlannerMetricsLogger(), null, true, null, null,
            new PlannerRestoreApplicationService(repository, trips, new com.ai.guide.domain.trip.service.TripPlanLimits(mapper),
                    new PlannerMetricsLogger(), true));
    private final Map<String, Object> serverPlan = new LinkedHashMap<>(Map.of(
            "sourceTripId", "formal-a", "sourceTripVersion", 3, "version", 1,
            "days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "a"), Map.of("id", "b"))))));
    private final StoredSession current = new StoredSession("session-a", "USER", "owner", "", "", "",
            Map.of(), AppliedPreferencesSnapshot.empty(), serverPlan, 1, 1, "DRAFT", 1, 1);

    @BeforeEach void setup() {
        UserContext.set("owner", "USER");
        when(repository.find("session-a", "USER", "owner", "")).thenReturn(current);
        when(trips.get("owner", "formal-a")).thenReturn(
                new Trip("formal-a", "owner", "Trip", "ACTIVE", 3, 1, 1, serverPlan));
        when(repository.update(eq(current), anyMap(), eq(1), eq("RESTORE_DRAFT"), anyString(),
                anyString(), anyList())).thenAnswer(call -> {
            Map<String, Object> updated = call.getArgument(1);
            return new MutationResult("UPDATED", new StoredSession("session-a", "USER", "owner", "", "", "",
                    Map.of(), AppliedPreferencesSnapshot.empty(), updated, 2, 2, "DRAFT", 1, 2));
        });
    }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void restoresOnlyToOwnedCurrentSessionAndServerRevision() {
        Map<String, Object> backup = new LinkedHashMap<>(Map.of(
                "sourceTripId", "spoofed", "sourceTripVersion", 3, "version", 9,
                "days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "a"))))));
        var result = service.restoreDraft("session-a", new RestoreDraftRequest(backup, 1, ""));
        assertEquals(200, result.status());
        Map<?, ?> restored = (Map<?, ?>) result.body().get("trip");
        assertEquals("formal-a", restored.get("sourceTripId"));
        assertEquals(2, restored.get("version"));
        assertEquals(1, ((List<?>) ((Map<?, ?>) ((List<?>) restored.get("days")).get(0)).get("stops")).size());
    }

    @Test void rejectsStalePlannerOrFormalVersionWithoutWriting() {
        Map<String, Object> backup = new LinkedHashMap<>(serverPlan);
        assertEquals(409, service.restoreDraft("session-a", new RestoreDraftRequest(backup, 2, "")).status());
        backup.put("sourceTripVersion", 2);
        assertEquals(409, service.restoreDraft("session-a", new RestoreDraftRequest(backup, 1, "")).status());
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
}

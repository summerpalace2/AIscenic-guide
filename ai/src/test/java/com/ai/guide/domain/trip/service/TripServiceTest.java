package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.trip.model.Trip;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TripServiceTest {

    @Autowired
    private TripService tripService;

    @Test
    void createUsesServerTripIdAndCreatesVersionOne() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Map<String, Object> plan = plan("draft-cq-99", "初始行程");
        TripService.OperationResult result = tripService.create(owner, Map.of(
                "title", "正式行程",
                "plan", plan,
                "idempotencyKey", "create-" + UUID.randomUUID()));

        assertEquals(201, result.status());
        assertNotNull(result.trip());
        assertTrue(result.trip().id().startsWith("trip-"));
        assertFalse(result.trip().id().equals("draft-cq-99"));
        assertEquals(1, result.trip().currentVersion());
        assertEquals(result.trip().id(), result.trip().plan().get("formalTripId"));
        assertEquals(1, tripService.versions(owner, result.trip().id()).size());
    }

    @Test
    void updateAppendsFullSnapshotAndNoopDoesNotCreateVersion() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        String key = "create-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", plan("draft-cq-1", "A"), "idempotencyKey", key)).trip();
        Map<String, Object> changed = new LinkedHashMap<>(created.plan());
        changed.put("title", "B");
        TripService.OperationResult updated = tripService.update(owner, created.id(), Map.of(
                "plan", changed, "expectedVersion", 1, "changeReason", "测试更新"));
        assertEquals(200, updated.status());
        assertEquals("UPDATED", updated.operationStatus());
        assertEquals(2, updated.trip().currentVersion());
        assertEquals(2, tripService.versions(owner, created.id()).size());

        TripService.OperationResult noop = tripService.update(owner, created.id(), Map.of(
                "plan", updated.trip().plan(), "expectedVersion", 2));
        assertEquals("NOOP", noop.operationStatus());
        assertEquals(2, noop.trip().currentVersion());
        assertEquals(2, tripService.versions(owner, created.id()).size());
    }

    @Test
    void updateRequiresExpectedVersion() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", plan("draft-cq-required", "A"))).trip();
        Map<String, Object> changed = new LinkedHashMap<>(created.plan());
        changed.put("status", "SAVED");

        TripService.OperationResult result = tripService.update(owner, created.id(), Map.of("plan", changed));

        assertEquals(400, result.status());
        assertEquals("BAD_REQUEST", result.operationStatus());
        assertEquals(1, tripService.get(owner, created.id()).currentVersion());
    }

    @Test
    void replanRequiresExpectedVersion() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", replanPlan(),
                "idempotencyKey", "replan-required-create-" + UUID.randomUUID())).trip();

        TripService.OperationResult result = tripService.replan(owner, created.id(), Map.of(
                "targetStopId", "day1-hongyadong"));

        assertEquals(400, result.status());
        assertEquals("BAD_REQUEST", result.operationStatus());
        assertEquals(1, tripService.get(owner, created.id()).currentVersion());
    }

    @Test
    void staleUpdateReturnsCasConflictMetadata() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", plan("draft-cq-2", "A"))).trip();
        Map<String, Object> changed = new LinkedHashMap<>(created.plan());
        changed.put("status", "SAVED");
        TripService.OperationResult updated = tripService.update(owner, created.id(), Map.of(
                "plan", changed, "expectedVersion", 1));
        assertEquals(2, updated.trip().currentVersion());

        TripService.OperationResult conflict = tripService.update(owner, created.id(), Map.of(
                "plan", Map.of("title", "stale"), "expectedVersion", 1));
        assertEquals(409, conflict.status());
        assertEquals(created.id(), conflict.tripId());
        assertEquals(1, conflict.expectedVersion());
        assertEquals(2, conflict.currentVersion());
    }

    @Test
    void idempotencyReplaysOriginalResultAndRejectsDifferentFingerprint() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        String key = "same-key-" + UUID.randomUUID();
        TripService.OperationResult first = tripService.create(owner, Map.of(
                "plan", plan("draft-cq-3", "A"), "idempotencyKey", key));
        TripService.OperationResult replay = tripService.create(owner, Map.of(
                "plan", plan("draft-cq-3", "A"), "idempotencyKey", key));
        assertEquals("EXISTING", replay.operationStatus());
        assertEquals(first.trip().id(), replay.trip().id());
        assertEquals(1, tripService.list(owner).size());

        TripService.OperationResult conflict = tripService.create(owner, Map.of(
                "plan", plan("draft-cq-4", "different"), "idempotencyKey", key));
        assertEquals(409, conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.operationStatus());
    }

    @Test
    void formalReplanUsesCurrentSnapshotAndAppendsVersion() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", replanPlan(), "idempotencyKey", "replan-create-" + UUID.randomUUID())).trip();
        TripService.OperationResult replanned = tripService.replan(owner, created.id(), Map.of(
                "targetStopId", "day1-hongyadong",
                "reason", "少走路",
                "expectedVersion", 1,
                "idempotencyKey", "replan-" + UUID.randomUUID()));

        assertEquals(200, replanned.status());
        assertEquals(2, replanned.trip().currentVersion());
        assertEquals("cq-grand-theatre", replanned.replacementVenueId());
        assertEquals(2, tripService.versions(owner, created.id()).size());
    }

    @Test
    void physicalDeleteRequiresCurrentVersionAndRemovesTripAndAllVersions() {
        String owner = "trip-test-owner-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of("plan", plan("draft-cq-5", "A"))).trip();
        Map<String, Object> changed = new LinkedHashMap<>(created.plan());
        changed.put("status", "SAVED");
        tripService.update(owner, created.id(), Map.of("plan", changed, "expectedVersion", 1));

        TripService.OperationResult stale = tripService.delete(owner, created.id(), Map.of("expectedVersion", 1));
        assertEquals(409, stale.status());
        assertEquals(2, tripService.get(owner, created.id()).currentVersion());

        TripService.OperationResult deleted = tripService.delete(owner, created.id(), Map.of("expectedVersion", 2));
        assertEquals(200, deleted.status());
        assertEquals("DELETED", deleted.operationStatus());
        assertNull(tripService.get(owner, created.id()));
        assertNull(tripService.versions(owner, created.id()));

        TripService.OperationResult missing = tripService.delete(owner, created.id(), Map.of("expectedVersion", 2));
        assertEquals(404, missing.status());
    }

    private Map<String, Object> plan(String draftId, String title) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", draftId);
        result.put("title", title);
        result.put("days", new ArrayList<>());
        return result;
    }

    private Map<String, Object> replanPlan() {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "day1-hongyadong");
        stop.put("stableStopId", "day1-hongyadong");
        stop.put("entityId", "cq-hongyadong");
        stop.put("venueId", "cq-hongyadong");
        stop.put("name", "洪崖洞");
        stop.put("time", "19:30");
        stop.put("duration", "约 90 分钟");
        stop.put("matchedConstraints", List.of());
        stop.put("routePreference", "walking");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("stops", new ArrayList<>(List.of(stop)));
        Map<String, Object> result = plan("draft-cq-6", "重规划测试");
        result.put("days", new ArrayList<>(List.of(day)));
        result.put("versionHistory", new ArrayList<>());
        result.put("sourceStatus", new LinkedHashMap<>());
        result.put("qualityMetrics", new LinkedHashMap<>());
        result.put("retrieval", new LinkedHashMap<>());
        return result;
    }
}

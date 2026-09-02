package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.trip.model.Trip;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TripOwnershipTest {

    @Autowired
    private TripService tripService;

    @Test
    void anotherOwnerCannotReadOrPhysicallyDeleteTripHistory() {
        String owner = "trip-owner-a-" + UUID.randomUUID();
        String otherOwner = "trip-owner-b-" + UUID.randomUUID();
        Trip created = tripService.create(owner, Map.of(
                "plan", plan("draft-cq-owner", "A"),
                "idempotencyKey", "owner-create-" + UUID.randomUUID())).trip();
        Map<String, Object> changed = new LinkedHashMap<>(created.plan());
        changed.put("title", "B");
        tripService.update(owner, created.id(), Map.of("plan", changed, "expectedVersion", 1));

        assertNull(tripService.get(otherOwner, created.id()));
        assertNull(tripService.versions(otherOwner, created.id()));
        TripService.OperationResult delete = tripService.delete(otherOwner, created.id(),
                Map.of("expectedVersion", 2));
        assertEquals(404, delete.status());
        assertEquals(2, tripService.get(owner, created.id()).currentVersion());
        assertEquals(2, tripService.versions(owner, created.id()).size());
    }

    @Test
    void idempotencyKeysAreScopedByOwner() {
        String key = "owner-scoped-" + UUID.randomUUID();
        Trip first = tripService.create("trip-owner-a-" + UUID.randomUUID(), Map.of(
                "plan", plan("draft-cq-owner-a", "A"), "idempotencyKey", key)).trip();
        Trip second = tripService.create("trip-owner-b-" + UUID.randomUUID(), Map.of(
                "plan", plan("draft-cq-owner-b", "B"), "idempotencyKey", key)).trip();

        assertNotEquals(first.id(), second.id());
        assertTrue(first.id().startsWith("trip-"));
        assertTrue(second.id().startsWith("trip-"));
    }

    private Map<String, Object> plan(String draftId, String title) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", draftId);
        result.put("title", title);
        result.put("days", new ArrayList<>());
        return result;
    }
}

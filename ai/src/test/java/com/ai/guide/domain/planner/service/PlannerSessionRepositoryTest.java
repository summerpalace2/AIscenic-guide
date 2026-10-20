package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PlannerSessionRepositoryTest {

    @Autowired
    private PlannerSessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired private com.ai.guide.application.planner.PlanningApplicationService planningApplication;
    @Autowired private com.ai.guide.domain.planner.gateway.PlannerSaveUseCasePort plannerSave;


    @Test
    void idempotencyReturnsSameSessionAndRevision() {
        String key = "repo-test-" + UUID.randomUUID();
        PlannerSessionRepository.CreateResult first = repository.create("ANONYMOUS", "guest-repository-test", "prompt", Map.of("durationDays", 2), List.of(), Map.of("id", "draft-cq-1"), key, "fingerprint");
        PlannerSessionRepository.CreateResult second = repository.create("ANONYMOUS", "guest-repository-test", "prompt", Map.of("durationDays", 2), List.of(), Map.of("id", "draft-cq-1"), key, "fingerprint");

        assertEquals("CREATED", first.status());
        assertEquals("EXISTING", second.status());
        assertEquals(first.session().sessionId(), second.session().sessionId());
        assertEquals(1, repository.revisionCount(first.session().sessionId()));
        assertNotNull(first.accessToken());
    }

    @Test
    void differentGuestOwnersCannotReplayEachOthersIdempotency() {
        String key = "repo-isolation-" + UUID.randomUUID();
        PlannerSessionRepository.CreateResult first = repository.create(
                "ANONYMOUS", "guest-a-" + UUID.randomUUID(), "prompt",
                Map.of("durationDays", 2), List.of(), Map.of("id", "draft-a"), key, "fingerprint");
        PlannerSessionRepository.CreateResult second = repository.create(
                "ANONYMOUS", "guest-b-" + UUID.randomUUID(), "prompt",
                Map.of("durationDays", 2), List.of(), Map.of("id", "draft-b"), key, "fingerprint");

        assertEquals("CREATED", first.status());
        assertEquals("CREATED", second.status());
        assertNotNull(first.session());
        assertNotNull(second.session());
        assertNotNull(first.accessToken());
        assertNotNull(second.accessToken());
        assertNotNull(repository.findAnonymousByAccessToken(first.accessToken()));
        assertEquals(first.session().sessionId(),
                repository.findAnonymousByAccessToken(first.accessToken()).sessionId());
        assertNull(repository.findAnonymousByAccessToken("wrong-capability"));
    }

    @Test
    void structuredSnapshotRoundTripsAndIsImmutableAcrossMutation() throws Exception {
        String owner = "guest-snapshot-" + UUID.randomUUID();
        String key = "repo-snapshot-" + UUID.randomUUID();
        AppliedPreferencesSnapshot snapshot = AppliedPreferencesSnapshot.create(
                Map.of("walkingTolerance", "LOW", "transportPreference", "PUBLIC_TRANSIT"),
                5, PreferencesSchema.CURRENT_SCHEMA_VERSION,
                PreferencesSchema.NORMALIZATION_VERSION, new ObjectMapper());
        PlannerSessionRepository.CreateResult created = repository.create(
                "ANONYMOUS", owner, "prompt", Map.of("durationDays", 2), snapshot,
                Map.of("id", "draft-snapshot"), key, "snapshot-fingerprint");

        PlannerSessionRepository.StoredSession loaded = repository.find(
                created.session().sessionId(), "ANONYMOUS", owner, created.accessToken());
        assertEquals(snapshot.asMap(), loaded.appliedPreferences().asMap());

        PlannerSessionRepository.MutationResult updated = repository.update(
                loaded, Map.of("id", "draft-snapshot-2"), 1,
                "REPLAN", "重排", "", List.of("day1"));
        assertEquals(snapshot.asMap(), updated.session().appliedPreferences().asMap());

        jdbcTemplate.update("UPDATE planner_session SET applied_preferences_json = '[]' WHERE session_id = ?",
                created.session().sessionId());
        PlannerSessionRepository.StoredSession legacy = repository.find(
                created.session().sessionId(), "ANONYMOUS", owner, created.accessToken());
        assertTrue(legacy.appliedPreferences().isEmpty());
    }

    @Test
    void pinnedStopIdsPersistWithTheTripRevisionAndSurviveRepositoryReadback() {
        String owner = "pins-owner-" + UUID.randomUUID();
        var trip = new java.util.LinkedHashMap<String,Object>(Map.of("version",1,"days",List.of(
                Map.of("day",1,"stops",List.of(Map.of("id","fixed-stop","venueId","venue"))))));
        var created = repository.create("USER", owner, "pins snapshot", Map.of(),
                AppliedPreferencesSnapshot.empty(), trip, "pins-" + UUID.randomUUID(), "pins-fp");
        trip.put("pinnedStopIds", List.of("fixed-stop"));
        trip.put("version",2);
        var updated = repository.update(created.session(),trip,1,"PINS_UPDATED","pins","",List.of());
        assertEquals("UPDATED",updated.status());
        var reloaded = repository.find(created.session().sessionId(),"USER",owner,"");
        assertNotNull(reloaded);
        assertEquals(2,reloaded.currentVersion());
        assertEquals(List.of("fixed-stop"),PlannerPins.read(reloaded.trip()));
        assertEquals("CONFLICT",repository.update(created.session(),Map.of("version",2),1,
                "STALE","stale","",List.of()).status());
        assertEquals(List.of("fixed-stop"),PlannerPins.read(repository.find(created.session().sessionId(),"USER",owner,"").trip()));
    }
    @Test void explicitSessionUnpinPersistsToFormalTripThroughAuthenticatedSaveBridge() {
        String owner = "pin-lifecycle-" + UUID.randomUUID();
        var trip = new java.util.LinkedHashMap<String,Object>(Map.of("version",1,"title","Pin lifecycle",
                "city","重庆","days",List.of(Map.of("day",1,"stops",List.of(
                        Map.of("id","fixed-stop","venueId","cq-hongyadong","name","洪崖洞")))),
                "pinnedStopIds",List.of("fixed-stop")));
        var created = repository.create("USER",owner,"pin lifecycle",Map.of(),AppliedPreferencesSnapshot.empty(),
                trip,"pin-lifecycle-"+UUID.randomUUID(),"pin-lifecycle-fp");
        var actor = new com.ai.guide.domain.planner.model.PlannerSaveActor(owner,false);
        var first = plannerSave.save(actor,created.session().sessionId(),"");
        assertTrue(first.serviceStatus()<400);
        var changed = planningApplication.updatePins(new com.ai.guide.application.planner.PlanningActor(owner,false),
                created.session().sessionId(),new com.ai.guide.domain.planner.model.PlannerPinsRequest(1,List.of(),List.of("fixed-stop"),""));
        assertEquals(200,changed.status());
        var saved = plannerSave.save(actor,created.session().sessionId(),"");
        assertTrue(saved.serviceStatus()<400);
        var formal = (com.ai.guide.domain.trip.model.Trip)saved.body().get("trip");
        assertEquals(List.of(),PlannerPins.read(formal.plan()));
        assertEquals(first.body().get("tripId"),saved.body().get("tripId"));
    }
}


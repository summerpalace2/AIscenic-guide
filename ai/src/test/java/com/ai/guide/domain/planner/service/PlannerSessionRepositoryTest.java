package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.repository.PlannerSessionRepository;
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
}

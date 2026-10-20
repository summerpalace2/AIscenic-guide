package com.ai.guide.infrastructure.planner.repository;

import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository deletion tests use a private temporary SQLite database. Do not use the
 * application context here: its configured knowledge database is a persistent user asset.
 */
class PlannerSessionRepositoryDeletionTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private PlannerSessionRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("planner-test.db"));
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("CREATE TABLE planner_session ("
                + "session_id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, "
                + "access_token_hash TEXT NOT NULL DEFAULT '', idempotency_key TEXT, claimed_idempotency_key TEXT, "
                + "request_fingerprint TEXT NOT NULL DEFAULT '', prompt TEXT NOT NULL DEFAULT '', "
                + "constraints_json TEXT NOT NULL DEFAULT '{}', applied_preferences_json TEXT NOT NULL DEFAULT '{}', "
                + "current_trip_json TEXT NOT NULL DEFAULT '{}', current_version INTEGER NOT NULL, "
                + "sync_revision INTEGER NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE planner_plan_revision ("
                + "session_id TEXT NOT NULL, version INTEGER NOT NULL, mutation_type TEXT NOT NULL, "
                + "label TEXT NOT NULL, reason TEXT NOT NULL, changed_segments_json TEXT NOT NULL, "
                + "trip_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY (session_id, version))");
        jdbc.execute("CREATE UNIQUE INDEX uq_planner_session_idempotency ON planner_session(owner_type, owner_id, idempotency_key)");
        jdbc.execute("CREATE TABLE planner_apply_receipt(session_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,"
                + "request_fingerprint TEXT NOT NULL,http_status INTEGER NOT NULL,response_json TEXT NOT NULL,created_at BIGINT NOT NULL,"
                + "PRIMARY KEY(session_id,idempotency_key))");
        repository = new PlannerSessionRepository(jdbc, new ObjectMapper());
    }

    @Test
    void deletesOnlyAnUnchangedOwnedUserSessionAndItsInitialRevision() {
        String owner = "open-discard-owner-" + UUID.randomUUID();
        String sessionId = createPendingFormalOpen(owner, "discard-key");

        assertEquals(1, repository.revisionCount(sessionId));
        Boolean deleted = transaction.execute(status -> repository.deleteUnchangedUserSession(sessionId, owner, 1));
        assertTrue(Boolean.TRUE.equals(deleted));
        assertNull(repository.find(sessionId, "USER", owner, ""));
        assertEquals(0, repository.revisionCount(sessionId));
        assertFalse(repository.deleteUnchangedUserSession(sessionId, owner, 1));
    }

    @Test
    void refusesWrongOwnerOrSessionThatHasBeenMutated() {
        String owner = "open-discard-owner-" + UUID.randomUUID();
        String sessionId = createPendingFormalOpen(owner, "changed-key");

        assertFalse(repository.deleteUnchangedUserSession(sessionId, "other-owner", 1));
        var opened = repository.find(sessionId, "USER", owner, "");
        var changed = repository.update(opened, Map.of("formalTripId", "formal-1", "version", 2),
                1, "STOP_MUTATION", "remove", "", List.of("stop-1"));
        assertEquals("UPDATED", changed.status());
        assertFalse(repository.deleteUnchangedUserSession(sessionId, owner, 1));
        assertNotNull(repository.find(sessionId, "USER", owner, ""));
        assertEquals(2, repository.revisionCount(sessionId));
    }

    @Test
    void acknowledgedOpenCannotBeDeletedByLateCompensation() {
        String owner = "open-discard-owner-" + UUID.randomUUID();
        String sessionId = createPendingFormalOpen(owner, "acknowledged-key");
        assertTrue(repository.activatePendingFormalOpen(sessionId, owner));

        assertFalse(repository.deleteUnchangedUserSession(sessionId, owner, 1));
        assertEquals("ACTIVE", repository.find(sessionId, "USER", owner, "").status());
        assertEquals(1, repository.revisionCount(sessionId));
    }

    @Test
    void oldAnonymousOwnerCannotMutateAfterSessionClaim() {
        String guestOwner = "guest-" + UUID.randomUUID();
        var created = repository.create("ANONYMOUS", guestOwner, "prompt", Map.of(), List.of(),
                Map.of("version", 1), "claim-key", "fingerprint");
        var guestSnapshot = created.session();
        String newOwner = "user-" + UUID.randomUUID();
        assertNotNull(repository.claimAnonymous(guestSnapshot.sessionId(), created.accessToken(), newOwner));

        var mutation = repository.update(guestSnapshot, Map.of("version", 2), 1,
                "STOP_MUTATION", "add", "", List.of("stop-1"));

        assertEquals("CONFLICT", mutation.status());
        var claimed = repository.find(guestSnapshot.sessionId(), "USER", newOwner, "");
        assertNotNull(claimed);
        assertEquals(1, claimed.currentVersion());
        assertEquals(1, repository.revisionCount(guestSnapshot.sessionId()));
    }

    @Test
    void corruptedTripSnapshotFailsInsteadOfAppearingAsEmptyTrip() {
        String owner = "corrupt-owner-" + UUID.randomUUID();
        String sessionId = createPendingFormalOpen(owner, "corrupt-key");
        jdbc.update("UPDATE planner_session SET current_trip_json = ? WHERE session_id = ?", "not-json", sessionId);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> repository.find(sessionId, "USER", owner, ""));
        assertTrue(error.getMessage().contains(sessionId));
    }

    @Test
    void expiresOnlyOldUnacknowledgedFormalOpenSessionsAndTheirRevisions() {
        String owner = "pending-open-owner-" + UUID.randomUUID();
        String expired = createPendingFormalOpen(owner, "expired-key");
        String fresh = createPendingFormalOpen(owner, "fresh-key");
        String acknowledged = createPendingFormalOpen(owner, "acknowledged-key");
        jdbc.update("UPDATE planner_session SET updated_at = 1 WHERE session_id = ?", expired);
        jdbc.update("UPDATE planner_session SET updated_at = 1 WHERE session_id = ?", acknowledged);
        assertTrue(repository.activatePendingFormalOpen(acknowledged, owner));

        Integer deleted = transaction.execute(status -> repository.deleteExpiredPendingFormalOpens(10));

        assertEquals(1, deleted);
        assertNull(repository.find(expired, "USER", owner, ""));
        assertEquals(0, repository.revisionCount(expired));
        assertNotNull(repository.find(fresh, "USER", owner, ""));
        assertEquals(1, repository.revisionCount(fresh));
        assertEquals("ACTIVE", repository.find(acknowledged, "USER", owner, "").status());
        assertEquals(1, repository.revisionCount(acknowledged));
    }

    @Test
    void activationIsOwnerScopedAndIdempotent() {
        String owner = "pending-open-owner-" + UUID.randomUUID();
        String sessionId = createPendingFormalOpen(owner, "activate-key");

        assertFalse(repository.activatePendingFormalOpen(sessionId, "other-owner"));
        assertTrue(repository.activatePendingFormalOpen(sessionId, owner));
        assertTrue(repository.activatePendingFormalOpen(sessionId, owner));
        assertEquals("ACTIVE", repository.find(sessionId, "USER", owner, "").status());
    }

    @Test
    void duplicateOpenOperationReusesTheSamePendingSessionAndRevision() {
        String owner = "pending-open-owner-" + UUID.randomUUID();
        String key = "stable-open-key";
        var first = transaction.execute(status -> createPendingFormalOpenResult(owner, key));
        var retry = transaction.execute(status -> createPendingFormalOpenResult(owner, key));

        assertEquals(first.session().sessionId(), retry.session().sessionId());
        assertEquals("OPEN_PENDING", retry.session().status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM planner_session WHERE owner_id = ?", Integer.class, owner));
        assertEquals(1, repository.revisionCount(first.session().sessionId()));
    }

    private String createPendingFormalOpen(String owner, String key) {
        return createPendingFormalOpenResult(owner, key).session().sessionId();
    }

    private PlannerSessionRepositoryPort.CreateResult createPendingFormalOpenResult(String owner, String key) {
        return repository.create("USER", owner, "prompt", Map.of(), List.of(),
                Map.of("formalTripId", "formal-1", "sourceTripId", "formal-1",
                        "plannerSessionRestoreSource", "RESTORED_FROM_TRIP", "version", 1),
                key, "fingerprint");
    }
}

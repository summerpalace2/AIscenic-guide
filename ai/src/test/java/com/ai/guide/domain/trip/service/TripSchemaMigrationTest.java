package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class TripSchemaMigrationTest {

    @Autowired
    @Qualifier("knowledgeJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @Test
    void formalTripMigrationsAreRecordedInOrder() {
        List<Integer> versions = jdbcTemplate.query(
                "SELECT version FROM schema_migration ORDER BY version",
                (rs, rowNum) -> rs.getInt("version"));

        assertTrue(versions.contains(1));
        assertTrue(versions.contains(2));
        assertTrue(versions.contains(3));
        assertTrue(versions.contains(4));
        assertTrue(versions.contains(5));
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version IN (1, 2, 3, 4)", Integer.class));
        assertEquals(5, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version IN (1, 2, 3, 4, 5)", Integer.class));
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_version", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_idempotency", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_migration_run", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_user_map", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_trip_map", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_migration_failure", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_preferences", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_preference_audit", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preference_migration_run", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preference_migration_ledger", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM legacy_preference_quarantine", Integer.class) >= 0);
        assertTrue(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM preference_migration_failure", Integer.class) >= 0);
    }

    @Test
    void migrationDescriptionsRemainAuditable() {
        assertEquals("formal Trip and TripVersion storage", jdbcTemplate.queryForObject(
                "SELECT description FROM schema_migration WHERE version = 1", String.class));
        assertEquals("Trip mutation idempotency records", jdbcTemplate.queryForObject(
                "SELECT description FROM schema_migration WHERE version = 2", String.class));
        assertEquals("Phase 5C typed Java Preferences authority", jdbcTemplate.queryForObject(
                "SELECT description FROM schema_migration WHERE version = 4", String.class));
        assertEquals("Phase 5C-2 preference migration ledger and quarantine", jdbcTemplate.queryForObject(
                "SELECT description FROM schema_migration WHERE version = 5", String.class));
    }
}

package com.ai.guide.infrastructure.trip.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** SQLite compatibility for the same conflict-target SQL used by the opt-in real PostgreSQL collision probe. */
class TripIdempotencyReservationTest {
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private TripRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("reservation.db"));
        dataSource.setDriverClassName("org.sqlite.JDBC");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE trip_idempotency(owner_id TEXT NOT NULL, operation TEXT NOT NULL, "
                + "idempotency_key TEXT NOT NULL, request_fingerprint TEXT NOT NULL, trip_id TEXT NOT NULL, "
                + "version_number INTEGER NOT NULL, response_json TEXT NOT NULL, created_at BIGINT NOT NULL, "
                + "PRIMARY KEY(owner_id, operation, idempotency_key))");
        repository = new TripRepository(jdbc,new ObjectMapper());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test void duplicateReservationPreservesOriginalAndPermitsReplayInSameTransaction() {
        transaction.executeWithoutResult(status -> {
            repository.reserveIdempotency("owner","CREATE","key","first","trip-original",1);
            assertThrows(DuplicateKeyException.class, () -> repository.reserveIdempotency("owner","CREATE","key","second","trip-other",2));
            var original = repository.findIdempotency("owner","CREATE","key");
            assertEquals("first",original.requestFingerprint());
            assertEquals("trip-original",original.tripId());
        });
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip_idempotency",Integer.class));
    }

    @Test void keyUniquenessIsScopedToOwnerAndOperation() {
        repository.reserveIdempotency("owner","CREATE","key","fingerprint","trip-a",1);
        repository.reserveIdempotency("other-owner","CREATE","key","fingerprint","trip-b",1);
        repository.reserveIdempotency("owner","UPDATE","key","fingerprint","trip-a",1);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM trip_idempotency",Integer.class));
    }

    @Test void unrelatedConstraintErrorsStillPropagate() {
        assertThrows(DataAccessException.class, () -> repository.reserveIdempotency("owner","CREATE","key",null,"trip",1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trip_idempotency",Integer.class));
    }
}

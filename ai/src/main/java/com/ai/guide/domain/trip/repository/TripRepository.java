package com.ai.guide.domain.trip.repository;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正式行程主表与版本表持久化仓储
 *
 * 所属领域：domain.trip.repository（正式行程仓储层）
 */
@Repository
public class TripRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TripRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Trip> list(String ownerId) {
        return jdbcTemplate.query(
                "SELECT t.id, t.owner_id, t.title, t.status, t.current_version, " +
                        "t.created_at, t.updated_at, v.snapshot " +
                        "FROM trip t JOIN trip_version v ON v.trip_id = t.id " +
                        "AND v.version_number = t.current_version " +
                        "WHERE t.owner_id = ? ORDER BY t.updated_at DESC",
                (rs, rowNum) -> mapTrip(
                        rs.getString("id"), rs.getString("owner_id"), rs.getString("title"),
                        rs.getString("status"), rs.getInt("current_version"),
                        rs.getLong("created_at"), rs.getLong("updated_at"),
                        rs.getString("snapshot")),
                ownerId);
    }

    public Trip find(String ownerId, String tripId) {
        List<Trip> rows = jdbcTemplate.query(
                "SELECT t.id, t.owner_id, t.title, t.status, t.current_version, " +
                        "t.created_at, t.updated_at, v.snapshot " +
                        "FROM trip t JOIN trip_version v ON v.trip_id = t.id " +
                        "AND v.version_number = t.current_version " +
                        "WHERE t.id = ? AND t.owner_id = ?",
                (rs, rowNum) -> mapTrip(
                        rs.getString("id"), rs.getString("owner_id"), rs.getString("title"),
                        rs.getString("status"), rs.getInt("current_version"),
                        rs.getLong("created_at"), rs.getLong("updated_at"),
                        rs.getString("snapshot")),
                tripId, ownerId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public TripVersion findVersion(String ownerId, String tripId, int versionNumber) {
        List<TripVersion> rows = jdbcTemplate.query(
                "SELECT v.id, v.trip_id, v.version_number, v.snapshot, v.change_reason, " +
                        "v.created_at, v.created_by " +
                        "FROM trip_version v JOIN trip t ON t.id = v.trip_id " +
                        "WHERE t.owner_id = ? AND v.trip_id = ? AND v.version_number = ?",
                (rs, rowNum) -> mapVersion(
                        rs.getString("id"), rs.getString("trip_id"), rs.getInt("version_number"),
                        rs.getString("snapshot"), rs.getString("change_reason"),
                        rs.getLong("created_at"), rs.getString("created_by")),
                ownerId, tripId, versionNumber);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<TripVersion> listVersions(String ownerId, String tripId) {
        return jdbcTemplate.query(
                "SELECT v.id, v.trip_id, v.version_number, v.snapshot, v.change_reason, " +
                        "v.created_at, v.created_by " +
                        "FROM trip_version v JOIN trip t ON t.id = v.trip_id " +
                        "WHERE t.owner_id = ? AND v.trip_id = ? ORDER BY v.version_number ASC",
                (rs, rowNum) -> mapVersion(
                        rs.getString("id"), rs.getString("trip_id"), rs.getInt("version_number"),
                        rs.getString("snapshot"), rs.getString("change_reason"),
                        rs.getLong("created_at"), rs.getString("created_by")),
                ownerId, tripId);
    }

    public void insertTripAndVersion(String tripId, String ownerId, String title, String status,
                                     Map<String, Object> snapshot, long now, String createdBy,
                                     String versionId) {
        jdbcTemplate.update(
                "INSERT INTO trip (id, owner_id, title, status, current_version, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                tripId, ownerId, title, status, 1, now, now);
        insertVersion(versionId, tripId, 1, snapshot, "初始保存", now, createdBy);
    }

    public void insertVersion(String versionId, String tripId, int versionNumber,
                              Map<String, Object> snapshot, String changeReason,
                              long now, String createdBy) {
        jdbcTemplate.update(
                "INSERT INTO trip_version (id, trip_id, version_number, snapshot, change_reason, created_at, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                versionId, tripId, versionNumber, write(snapshot),
                changeReason == null ? "" : changeReason, now, createdBy == null ? "" : createdBy);
    }

    /** CAS the aggregate pointer. Version insertion and this update belong to one service transaction. */
    public int advanceCurrentVersion(String ownerId, String tripId, int expectedVersion,
                                     int nextVersion, String title, long now) {
        return jdbcTemplate.update(
                "UPDATE trip SET title = ?, current_version = ?, updated_at = ? " +
                        "WHERE id = ? AND owner_id = ? AND current_version = ?",
                title, nextVersion, now, tripId, ownerId, expectedVersion);
    }

    public int updateTitle(String ownerId, String tripId, String title, long now) {
        return jdbcTemplate.update(
                "UPDATE trip SET title = ?, updated_at = ? WHERE id = ? AND owner_id = ?",
                title, now, tripId, ownerId);
    }

    /** Physically deletes the aggregate only if the caller still owns the expected version. */
    public int deleteIfCurrentVersion(String ownerId, String tripId, int expectedVersion) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM trip WHERE id = ? AND owner_id = ? AND current_version = ?",
                tripId, ownerId, expectedVersion);
        if (deleted == 0) return 0;

        jdbcTemplate.update("DELETE FROM trip_idempotency WHERE trip_id = ?", tripId);
        jdbcTemplate.update("DELETE FROM trip_version WHERE trip_id = ?", tripId);
        return deleted;
    }

    public IdempotencyRecord findIdempotency(String ownerId, String operation, String key) {
        if (key == null || key.isBlank()) return null;
        List<IdempotencyRecord> rows = jdbcTemplate.query(
                "SELECT owner_id, operation, idempotency_key, request_fingerprint, trip_id, " +
                        "version_number, response_json, created_at FROM trip_idempotency " +
                        "WHERE owner_id = ? AND operation = ? AND idempotency_key = ?",
                (rs, rowNum) -> new IdempotencyRecord(
                        rs.getString("owner_id"), rs.getString("operation"),
                        rs.getString("idempotency_key"), rs.getString("request_fingerprint"),
                        rs.getString("trip_id"), rs.getInt("version_number"),
                        rs.getString("response_json"), rs.getLong("created_at")),
                ownerId, operation, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Reserves a scoped idempotency key before a mutation is committed. */
    public void reserveIdempotency(String ownerId, String operation, String key,
                                   String fingerprint, String tripId, long now) {
        jdbcTemplate.update(
                "INSERT INTO trip_idempotency (owner_id, operation, idempotency_key, " +
                        "request_fingerprint, trip_id, version_number, response_json, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ownerId, operation, key, fingerprint, tripId, 0, "{}", now);
    }

    public void completeIdempotency(String ownerId, String operation, String key, Trip trip) {
        jdbcTemplate.update(
                "UPDATE trip_idempotency SET trip_id = ?, version_number = ?, response_json = ? " +
                        "WHERE owner_id = ? AND operation = ? AND idempotency_key = ?",
                trip.id(), trip.currentVersion(), write(trip), ownerId, operation, key);
    }

    public void deleteIdempotency(String ownerId, String operation, String key) {
        jdbcTemplate.update(
                "DELETE FROM trip_idempotency WHERE owner_id = ? AND operation = ? AND idempotency_key = ?",
                ownerId, operation, key);
    }

    /** Kept as a narrow compatibility helper for callers that already have a result. */
    public void insertIdempotency(String ownerId, String operation, String key,
                                  String fingerprint, Trip trip, long now) {
        reserveIdempotency(ownerId, operation, key, fingerprint, trip.id(), now);
        completeIdempotency(ownerId, operation, key, trip);
    }

    public Trip deserializeIdempotentTrip(IdempotencyRecord record) {
        if (record == null || record.versionNumber() <= 0
                || record.responseJson() == null || record.responseJson().isBlank()
                || "{}".equals(record.responseJson().trim())) return null;
        try {
            return objectMapper.readValue(record.responseJson(), Trip.class);
        } catch (Exception error) {
            throw new IllegalStateException("Trip 幂等结果损坏: " + record.tripId(), error);
        }
    }

    private Trip mapTrip(String id, String ownerId, String title, String status, int currentVersion,
                         long createdAt, long updatedAt, String snapshot) {
        return new Trip(id, ownerId, title == null ? "" : title,
                status == null ? "ACTIVE" : status, currentVersion, createdAt, updatedAt,
                readMap(snapshot));
    }

    private TripVersion mapVersion(String id, String tripId, int versionNumber, String snapshot,
                                   String changeReason, long createdAt, String createdBy) {
        return new TripVersion(id, tripId, versionNumber, readMap(snapshot),
                changeReason, createdAt, createdBy);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("Trip snapshot JSON 无效", error);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Trip JSON 数据无效", error);
        }
    }

    public record IdempotencyRecord(String ownerId, String operation, String key,
                                    String requestFingerprint, String tripId,
                                    int versionNumber, String responseJson, long createdAt) {
    }
}

package com.ai.guide.infrastructure.memory.repository;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.port.MemoryRepositoryPort;
import com.ai.guide.domain.memory.service.TravelMemoryService.MemoryCandidate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 记忆域基础设施 JDBC 仓储实现 (JdbcMemoryRepository).
 * 封装与 SQLite/PostgreSQL 表交互的所有 SQL 模板与 RowMapper。
 */
@Repository
@Primary
public class JdbcMemoryRepository implements MemoryRepositoryPort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    private static final RowMapper<TravelMemory> MEMORY_ROW_MAPPER = (rs, rowNum) -> new TravelMemory(
            rs.getString("id"),
            rs.getString("category"),
            rs.getString("content"),
            rs.getString("source_type"),
            rs.getString("source_ref"),
            rs.getString("status"),
            rs.getDouble("confidence"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    private static final RowMapper<MemoryCandidate> CANDIDATE_ROW_MAPPER = (rs, rowNum) -> new MemoryCandidate(
            rs.getString("id"),
            rs.getString("category"),
            rs.getString("content"),
            rs.getString("source_ref"),
            rs.getString("status"),
            rs.getDouble("confidence"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    public JdbcMemoryRepository(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public JdbcMemoryRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc,
                                @Autowired(required = false) TransactionTemplate transactions) {
        this.jdbc = jdbc;
        if (transactions != null) {
            this.transactions = transactions;
        } else if (jdbc != null && jdbc.getDataSource() != null) {
            this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        } else {
            this.transactions = null;
        }
    }

    @Override
    public List<TravelMemory> findConfirmedByUserId(String userId) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at ASC",
                MEMORY_ROW_MAPPER, userId
        );
    }

    @Override
    public List<TravelMemory> findConfirmedByUserIdOrderByUpdatedAtDesc(String userId) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at DESC",
                MEMORY_ROW_MAPPER, userId
        );
    }

    @Override
    public List<TravelMemory> findConfirmedByUserIdPaged(String userId, int limit, int offset) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                MEMORY_ROW_MAPPER, userId, limit, offset
        );
    }

    @Override
    public TravelMemory findByIdAndUserId(String id, String userId) {
        if (jdbc == null) return null;
        List<TravelMemory> rows = jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                MEMORY_ROW_MAPPER, id, userId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public TravelMemory findSameConfirmed(String userId, String category, String content) {
        if (jdbc == null) return null;
        List<TravelMemory> rows = jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND category = ? AND content = ? AND status = 'CONFIRMED' LIMIT 1",
                MEMORY_ROW_MAPPER, userId, category, content
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public boolean existsConfirmed(String id, String userId) {
        if (jdbc == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM travel_memory WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                Integer.class, id, userId
        );
        return count != null && count > 0;
    }

    @Override
    public void save(TravelMemory memory, String userId) {
        if (jdbc == null) return;
        jdbc.update(
                "INSERT INTO travel_memory (id, user_id, category, content, source_type, source_ref, status, confidence, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
                memory.id(), userId, memory.category(), memory.content(),
                memory.sourceType(), memory.sourceRef(), memory.confidence(),
                memory.createdAt(), memory.updatedAt()
        );
    }

    @Override
    public void touchUpdatedAt(String id, String userId, long updatedAt) {
        if (jdbc == null) return;
        jdbc.update("UPDATE travel_memory SET updated_at = ? WHERE id = ? AND user_id = ?", updatedAt, id, userId);
    }

    @Override
    public int updateContentAndCategory(String id, String userId, String content, String category, long updatedAt) {
        if (jdbc == null) return 0;
        return jdbc.update(
                "UPDATE travel_memory SET content = ?, category = ?, source_type = 'USER_EDIT', confidence = 1.0, updated_at = ? " +
                        "WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                content, category, updatedAt, id, userId
        );
    }

    @Override
    public int updateContent(String id, String userId, String content, long updatedAt) {
        if (jdbc == null) return 0;
        return jdbc.update(
                "UPDATE travel_memory SET content = ?, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                content, updatedAt, id, userId
        );
    }

    @Override
    public int softDelete(String id, String userId, long updatedAt) {
        if (jdbc == null) return 0;
        return jdbc.update(
                "UPDATE travel_memory SET status = 'DELETED', updated_at = ? WHERE id = ? AND user_id = ?",
                updatedAt, id, userId
        );
    }

    @Override
    public List<Map<String, Object>> findActiveIdAndContent(String userId) {
        if (jdbc == null) return List.of();
        return jdbc.queryForList(
                "SELECT id, content FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED'",
                userId
        );
    }

    @Override
    public boolean hasPendingObservation(String userId, String content) {
        if (jdbc == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM travel_memory_observation WHERE user_id = ? AND content = ? AND status = 'PENDING'",
                Integer.class, userId, content
        );
        return count != null && count > 0;
    }

    @Override
    public void insertObservation(String id, String userId, String content, String sourceSession, long createdAt) {
        if (jdbc == null) return;
        jdbc.update(
                "INSERT INTO travel_memory_observation (id, user_id, content, source_session, status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'PENDING', ?)",
                id, userId, content, sourceSession, createdAt
        );
    }

    @Override
    public List<MemoryCandidate> findPendingCandidatesByUserId(String userId, int limit) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, category, content, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory_candidate WHERE user_id = ? AND status = 'PENDING' ORDER BY updated_at DESC LIMIT ?",
                CANDIDATE_ROW_MAPPER, userId, limit
        );
    }

    @Override
    public List<MemoryCandidate> findPendingCandidateByIdAndUserId(String id, String userId) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, category, content, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory_candidate WHERE id = ? AND user_id = ? AND status = 'PENDING'",
                CANDIDATE_ROW_MAPPER, id, userId
        );
    }

    @Override
    public int updateCandidateStatus(String id, String userId, String status, long updatedAt) {
        if (jdbc == null) return 0;
        return jdbc.update(
                "UPDATE travel_memory_candidate SET status = ?, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'PENDING'",
                status, updatedAt, id, userId
        );
    }

    @Override
    public List<String> findDistinctPendingObservationUserIds() {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT DISTINCT user_id FROM travel_memory_observation WHERE status = 'PENDING'",
                (rs, row) -> rs.getString(1)
        );
    }

    @Override
    public List<ObservationRecord> findPendingObservationsByUserId(String userId, int limit) {
        if (jdbc == null) return List.of();
        return jdbc.query(
                "SELECT id, content FROM travel_memory_observation WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at ASC LIMIT ?",
                (rs, row) -> new ObservationRecord(rs.getString("id"), rs.getString("content")),
                userId, limit
        );
    }

    @Override
    public int updateObservationStatus(String id, String userId, String status) {
        if (jdbc == null) return 0;
        return jdbc.update(
                "UPDATE travel_memory_observation SET status = ? WHERE id = ? AND user_id = ? AND status = 'PENDING'",
                status, id, userId
        );
    }

    @Override
    public boolean hasPendingCandidate(String userId, String category, String content) {
        if (jdbc == null) return false;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM travel_memory_candidate WHERE user_id = ? AND category = ? AND content = ? AND status = 'PENDING'",
                Integer.class, userId, category, content
        );
        return count != null && count > 0;
    }

    @Override
    public void insertCandidate(String id, String userId, String category, String content, String sourceRef, double confidence, long createdAt, long updatedAt) {
        if (jdbc == null) return;
        jdbc.update(
                "INSERT INTO travel_memory_candidate (id, user_id, category, content, source_ref, status, confidence, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                id, userId, category, content, sourceRef, confidence, createdAt, updatedAt
        );
    }

    @Override
    public <T> T runInTransaction(Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }
}

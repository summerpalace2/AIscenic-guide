package com.ai.guide.domain.memory.port;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService.MemoryCandidate;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 记忆域持久化与查询能力端口 (Memory Repository Port).
 * 遵循 COLA 依赖倒置原则，隔离领域层与底层数据库实现 (JDBC/SQLite/PostgreSQL)。
 */
public interface MemoryRepositoryPort {

    List<TravelMemory> findConfirmedByUserId(String userId);

    List<TravelMemory> findConfirmedByUserIdOrderByUpdatedAtDesc(String userId);

    List<TravelMemory> findConfirmedByUserIdPaged(String userId, int limit, int offset);

    TravelMemory findByIdAndUserId(String id, String userId);

    TravelMemory findSameConfirmed(String userId, String category, String content);

    boolean existsConfirmed(String id, String userId);

    void save(TravelMemory memory, String userId);

    void touchUpdatedAt(String id, String userId, long updatedAt);

    int updateContentAndCategory(String id, String userId, String content, String category, long updatedAt);

    int updateContent(String id, String userId, String content, long updatedAt);

    int softDelete(String id, String userId, long updatedAt);

    List<Map<String, Object>> findActiveIdAndContent(String userId);

    boolean hasPendingObservation(String userId, String content);

    void insertObservation(String id, String userId, String content, String sourceSession, long createdAt);

    List<MemoryCandidate> findPendingCandidatesByUserId(String userId, int limit);

    List<MemoryCandidate> findPendingCandidateByIdAndUserId(String id, String userId);

    int updateCandidateStatus(String id, String userId, String status, long updatedAt);

    List<String> findDistinctPendingObservationUserIds();

    List<ObservationRecord> findPendingObservationsByUserId(String userId, int limit);

    int updateObservationStatus(String id, String userId, String status);

    boolean hasPendingCandidate(String userId, String category, String content);

    void insertCandidate(String id, String userId, String category, String content, String sourceRef, double confidence, long createdAt, long updatedAt);

    <T> T runInTransaction(Supplier<T> action);

    record ObservationRecord(String id, String content) {}
}

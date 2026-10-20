package com.ai.guide.domain.memory.port;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.infrastructure.memory.repository.JdbcMemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.memory.service.TravelMemoryService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S2 试点：MemoryRepositoryPort 独立仓储端口持久化契约测试")
class MemoryRepositoryPortContractTest {

    private MemoryRepositoryPort port;
    private JdbcTemplate jdbc;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("""
            CREATE TABLE travel_memory (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_ref TEXT,
                status TEXT NOT NULL,
                confidence REAL NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """);

        jdbc.execute("""
            CREATE TABLE travel_memory_observation (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                content TEXT NOT NULL,
                source_session TEXT,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """);

        jdbc.execute("""
            CREATE TABLE travel_memory_candidate (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                category TEXT NOT NULL,
                content TEXT NOT NULL,
                source_ref TEXT,
                status TEXT NOT NULL,
                confidence REAL NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """);

        port = new JdbcMemoryRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("TravelMemory 新增、查询、软删除与按更新时间排序")
    void testMemoryCrudAndOrdering() {
        long now = System.currentTimeMillis();
        TravelMemory m1 = new TravelMemory("m-1", "DIET", "不吃辣", "USER_EDIT", "", "CONFIRMED", 1.0, now, now);
        TravelMemory m2 = new TravelMemory("m-2", "PACE", "少走路", "USER_EDIT", "", "CONFIRMED", 1.0, now, now + 1000);

        port.save(m1, "userA");
        port.save(m2, "userA");

        List<TravelMemory> ascList = port.findConfirmedByUserId("userA");
        assertEquals(2, ascList.size());
        assertEquals("m-1", ascList.get(0).id());
        assertEquals("m-2", ascList.get(1).id());

        List<TravelMemory> descList = port.findConfirmedByUserIdOrderByUpdatedAtDesc("userA");
        assertEquals("m-2", descList.get(0).id());

        // 软删除
        port.softDelete("m-1", "userA", now + 2000);
        List<TravelMemory> remaining = port.findConfirmedByUserId("userA");
        assertEquals(1, remaining.size());
        assertEquals("m-2", remaining.get(0).id());
    }

    @Test
    @DisplayName("Observation 与 Candidate 状态流转与幂等防重")
    void testObservationAndCandidateWorkflow() {
        long now = System.currentTimeMillis();
        assertFalse(port.hasPendingObservation("userB", "喜欢甜食"));

        port.insertObservation("obs-1", "userB", "喜欢甜食", "sess-1", now);
        assertTrue(port.hasPendingObservation("userB", "喜欢甜食"));

        List<MemoryRepositoryPort.ObservationRecord> obs = port.findPendingObservationsByUserId("userB", 10);
        assertEquals(1, obs.size());
        assertEquals("obs-1", obs.get(0).id());

        port.updateObservationStatus("obs-1", "userB", "REVIEWED");
        assertFalse(port.hasPendingObservation("userB", "喜欢甜食"));

        // Candidate 写入与认领
        port.insertCandidate("cand-1", "userB", "DIET", "喜欢甜品", "ref", 0.9, now, now);
        assertTrue(port.hasPendingCandidate("userB", "DIET", "喜欢甜品"));

        var candidates = port.findPendingCandidatesByUserId("userB", 5);
        assertEquals(1, candidates.size());
        assertEquals("cand-1", candidates.get(0).id());

        int claimed = port.updateCandidateStatus("cand-1", "userB", "CONFIRMED", now + 500);
        assertEquals(1, claimed);
        assertEquals(0, port.findPendingCandidatesByUserId("userB", 5).size());
    }

    @Test
    @DisplayName("事务回滚验证：单参构造绑定的 TransactionTemplate 在发生异常时回滚数据库写操作")
    void testTransactionRollbackOnException() {
        long now = System.currentTimeMillis();
        port.insertCandidate("cand-tx", "userTx", "DIET", "不吃辣", "ref", 0.9, now, now);
        assertEquals(1, port.findPendingCandidatesByUserId("userTx", 5).size());

        assertThrows(RuntimeException.class, () -> {
            port.runInTransaction(() -> {
                port.updateCandidateStatus("cand-tx", "userTx", "CONFIRMED", now + 100);
                port.save(new TravelMemory("mem-tx", "DIET", "不吃辣", "CHAT_CONFIRMED", "ref", "CONFIRMED", 0.9, now, now), "userTx");
                throw new RuntimeException("Simulated failure inside transaction");
            });
        });

        // 断言已回滚：候选仍为 PENDING，确认记忆数量为 0
        assertEquals(1, port.findPendingCandidatesByUserId("userTx", 5).size());
        assertEquals("cand-tx", port.findPendingCandidatesByUserId("userTx", 5).get(0).id());
        assertTrue(port.findConfirmedByUserId("userTx").isEmpty());
    }

    @Test
    @DisplayName("端到端回滚验证：TravelMemoryService 确认候选时若画像服务失败，候选保持 PENDING 且记忆回滚")
    void testTravelMemoryServiceConfirmCandidateRollbackOnPreferenceFailure() {
        long now = System.currentTimeMillis();
        port.insertCandidate("cand-fail", "userRollback", "PACE", "少走路", "ref", 0.9, now, now);

        PreferencesService mockPref = Mockito.mock(PreferencesService.class);
        Mockito.when(mockPref.get(Mockito.anyString())).thenThrow(new RuntimeException("Simulated preferences CAS failure"));

        TravelMemoryService memoryService = com.ai.guide.testing.MemoryTestFixtures.service(port, mockPref);

        assertThrows(RuntimeException.class, () -> {
            memoryService.confirmCandidate("userRollback", "cand-fail");
        });

        // 验证候选仍然保留 PENDING 状态，可以合法重试
        var pendingList = port.findPendingCandidatesByUserId("userRollback", 5);
        assertEquals(1, pendingList.size());
        assertEquals("cand-fail", pendingList.get(0).id());
        assertTrue(port.findConfirmedByUserId("userRollback").isEmpty());
    }
}

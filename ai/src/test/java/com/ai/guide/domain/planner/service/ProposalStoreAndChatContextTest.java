package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ProposalStore 缓存与并发治理测试")
class ProposalStoreAndChatContextTest {

    private CaffeineRedisProposalRepository proposalStore;

    @BeforeEach
    void setUp() {
        proposalStore = new CaffeineRedisProposalRepository();
        proposalStore.clearLocalCache();
    }

    @Test
    @DisplayName("ProposalStore: 正常存储、读取与移除")
    void testBasicPutAndGet() {
        long createdAt = System.currentTimeMillis();
        PlanAdjustmentProposal proposal = PlanAdjustmentProposal.builder()
                .proposalId("prop-test-1")
                .sessionId("session-1")
                .baseRevision(1)
                .feasible(true)
                .createdAt(createdAt)
                .expiresAt(createdAt + PlanAdjustmentProposal.DEFAULT_TTL_MS)
                .proposedTrip(Map.of("id", "trip-1"))
                .build();

        proposalStore.put(proposal);
        assertEquals(1, proposalStore.localSize());

        PlanAdjustmentProposal retrieved = proposalStore.get("prop-test-1");
        assertNotNull(retrieved);
        assertEquals("session-1", retrieved.getSessionId());

        PlanAdjustmentProposal removed = proposalStore.remove("prop-test-1");
        assertNotNull(removed);
        assertNull(proposalStore.get("prop-test-1"));
    }

    @Test
    @DisplayName("ProposalStore: 过期提案自动失效与清理")
    void testExpiredProposal() {
        PlanAdjustmentProposal expiredProposal = PlanAdjustmentProposal.builder()
                .proposalId("prop-expired")
                .sessionId("session-1")
                .baseRevision(1)
                .feasible(true)
                .createdAt(System.currentTimeMillis() - 10_000L)
                .expiresAt(System.currentTimeMillis() - 1000L) // already expired
                .build();

        proposalStore.put(expiredProposal);
        assertNull(proposalStore.get("prop-expired"), "已过期提案读取时必须返回 null");
    }

    @Test
    @DisplayName("ProposalStore: 高并发容量存储无随机构件误剔除")
    void testHighCapacityWithoutRandomEviction() throws InterruptedException {
        int count = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(count);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < count; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    long createdAt = System.currentTimeMillis();
                    PlanAdjustmentProposal p = PlanAdjustmentProposal.builder()
                            .proposalId("prop-" + id)
                            .sessionId("session-" + (id % 10))
                            .baseRevision(1)
                            .feasible(true)
                            .createdAt(createdAt)
                            .expiresAt(createdAt + PlanAdjustmentProposal.DEFAULT_TTL_MS)
                            .build();
                    proposalStore.put(p);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        assertEquals(count, successCount.get());
        assertTrue(proposalStore.localSize() >= count, "所有 1000 条未过期提案均应保留，严禁容量溢出随机剔除");
        assertNotNull(proposalStore.get("prop-0"));
        assertNotNull(proposalStore.get("prop-500"));
        assertNotNull(proposalStore.get("prop-999"));
    }
}

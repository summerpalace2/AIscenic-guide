package com.ai.guide.infrastructure.agent;

import com.ai.guide.domain.agent.gateway.AgentConfirmationPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentPendingConfirmation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryAgentConfirmationAdapter} 单元测试：
 * 验证 HIL 确认单的原子一次性消费、跨用户防越权隔离与 TTL 过期机制。
 */
class InMemoryAgentConfirmationAdapterTest {

    @Test
    void consumeEnforcesOwnershipSingleUseAndDoesNotDestroyOnForbiddenProbe() {
        InMemoryAgentConfirmationAdapter adapter = new InMemoryAgentConfirmationAdapter();
        AgentExecutionContext aliceCtx = new AgentExecutionContext(
                "alice", false, "s1", "ps-1", "", "", 2, "重庆", 1, "", "", false);

        AgentPendingConfirmation pending = adapter.createPending(
                "confirm_apply_trip_adjustment",
                "call-1",
                "{\"proposalId\":\"p1\"}",
                "确认调整第1天行程",
                Map.of("proposalId", "p1"),
                aliceCtx
        );

        // 其他用户（或游客）尝试消费不应成功，且不能破坏 alice 原有确认单
        var bobAttempt = adapter.consumeIfOwned(pending.confirmId(), "user:bob");
        assertEquals(AgentConfirmationPort.ConsumeStatus.FORBIDDEN, bobAttempt.status());
        assertFalse(bobAttempt.ok());

        // 本人首次消费成功
        var aliceConsume = adapter.consumeIfOwned(pending.confirmId(), "user:alice");
        assertEquals(AgentConfirmationPort.ConsumeStatus.CONSUMED, aliceConsume.status());
        assertTrue(aliceConsume.ok());
        assertEquals("confirm_apply_trip_adjustment", aliceConsume.confirmation().toolName());

        // 重复提交返回 ALREADY_CONSUMED，防止并发重放写入
        var replay = adapter.consumeIfOwned(pending.confirmId(), "user:alice");
        assertEquals(AgentConfirmationPort.ConsumeStatus.ALREADY_CONSUMED, replay.status());
        assertFalse(replay.ok());
    }

    @Test
    void expiredConfirmationCannotBeConsumed() {
        AtomicLong nowMillis = new AtomicLong(1_700_000_000_000L);
        Clock mutableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(nowMillis.get());
            }
        };

        InMemoryAgentConfirmationAdapter adapter = new InMemoryAgentConfirmationAdapter(mutableClock, 5_000L);
        AgentExecutionContext guestCtx = new AgentExecutionContext("", "guest-s1", "", "", "重庆", null, "", "", false);

        AgentPendingConfirmation pending = adapter.createPending(
                "confirm_update_user_preferences", "call-2", "{}", "保存偏好", Map.of(), guestCtx);

        nowMillis.addAndGet(6_000L);
        var expired = adapter.consumeIfOwned(pending.confirmId(), "guest:guest-s1");
        assertEquals(AgentConfirmationPort.ConsumeStatus.EXPIRED, expired.status());
        assertFalse(expired.ok());
    }
}

package com.ai.guide.infrastructure.agent;

import com.ai.guide.domain.agent.gateway.AgentConfirmationPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentPendingConfirmation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于内存并发映射表的人机协同（HIL）确认单仓储适配器。
 *
 * <p>架构定位：COLA 基础设施层（infrastructure.agent）。
 * 负责生成短周期（默认 10 分钟）一次性确认凭证，保证归属校验优先于状态消费、防并发重放与防越权消毁。
 */
@Component
public class InMemoryAgentConfirmationAdapter implements AgentConfirmationPort {

    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_RETAINED_RECORDS = 4096;

    private record Entry(AgentPendingConfirmation confirmation, boolean consumed) {
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    public InMemoryAgentConfirmationAdapter() {
        this(Clock.systemUTC(), DEFAULT_TTL_MILLIS);
    }

    /** 供测试注入可控时钟与 TTL 的包内/公开构造器 */
    public InMemoryAgentConfirmationAdapter(Clock clock, long ttlMillis) {
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.ttlMillis = Math.max(1000L, ttlMillis);
    }

    @Override
    public AgentPendingConfirmation createPending(
            String toolName,
            String toolCallId,
            String argumentsJson,
            String summary,
            Map<String, Object> previewPayload,
            AgentExecutionContext context) {
        long now = clock.millis();
        pruneExpired(now);
        String confirmId = "cfg_" + UUID.randomUUID().toString().replace("-", "");
        String ownerKey = context == null ? "guest:default" : context.ownerKey();
        AgentPendingConfirmation record = new AgentPendingConfirmation(
                confirmId,
                ownerKey,
                toolName,
                toolCallId,
                argumentsJson,
                summary,
                previewPayload,
                context,
                now,
                now + ttlMillis
        );
        store.put(confirmId, new Entry(record, false));
        return record;
    }

    @Override
    public ConsumeResult consumeIfOwned(String confirmId, String ownerKey) {
        if (confirmId == null || confirmId.isBlank()) {
            return new ConsumeResult(ConsumeStatus.NOT_FOUND, null);
        }
        long now = clock.millis();
        String expectedOwner = (ownerKey == null || ownerKey.isBlank()) ? "guest:default" : ownerKey;
        AtomicReference<ConsumeResult> resultRef = new AtomicReference<>(new ConsumeResult(ConsumeStatus.NOT_FOUND, null));

        store.computeIfPresent(confirmId.trim(), (id, existing) -> {
            // 先校验归属身份，再检查状态：防止第三方猜测 ID 时误销毁真实用户的确认单
            if (!Objects.equals(existing.confirmation().ownerKey(), expectedOwner)) {
                resultRef.set(new ConsumeResult(ConsumeStatus.FORBIDDEN, null));
                return existing;
            }
            if (existing.consumed()) {
                resultRef.set(new ConsumeResult(ConsumeStatus.ALREADY_CONSUMED, null));
                return existing;
            }
            if (now > existing.confirmation().expiresAtMillis()) {
                resultRef.set(new ConsumeResult(ConsumeStatus.EXPIRED, null));
                return null;
            }
            resultRef.set(new ConsumeResult(ConsumeStatus.CONSUMED, existing.confirmation()));
            return new Entry(existing.confirmation(), true);
        });

        return resultRef.get();
    }

    private void pruneExpired(long now) {
        if (store.size() < MAX_RETAINED_RECORDS / 2) {
            return;
        }
        store.entrySet().removeIf(e -> now > e.getValue().confirmation().expiresAtMillis());
    }
}

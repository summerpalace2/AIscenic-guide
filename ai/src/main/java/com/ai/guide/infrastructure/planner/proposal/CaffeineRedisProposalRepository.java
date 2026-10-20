package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/** Local proposals tolerate failed asynchronous publication; shared snapshots are validated against Redis on read. */
@Slf4j
@Component
public class CaffeineRedisProposalRepository implements ProposalRepositoryPort {

    private static final int MAX_CAPACITY = 10_000;
    private static final String REDIS_PREFIX = "planner:proposal:";
    static final String REMOVED_SNAPSHOT = "__PLANNER_PROPOSAL_REMOVED__";
    static final DefaultRedisScript<Long> PUBLISH_SNAPSHOT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return 0 end; "
                    + "redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2]); return 1", Long.class);
    static final DefaultRedisScript<Long> DISCARD_SNAPSHOT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end; return 0", Long.class);

    private final Cache<String, CachedProposal> l1Cache = Caffeine.newBuilder()
            .maximumSize(MAX_CAPACITY)
            .expireAfterWrite(PlanAdjustmentProposal.DEFAULT_TTL_MS, TimeUnit.MILLISECONDS)
            .build();
    private final Cache<String, Boolean> removalTombstones = Caffeine.newBuilder()
            .maximumSize(MAX_CAPACITY)
            .expireAfterWrite(PlanAdjustmentProposal.DEFAULT_TTL_MS, TimeUnit.MILLISECONDS)
            .build();

    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private final Executor redisExecutor;
    private final Runnable afterL1WriteHook;
    private final ConcurrentHashMap<String, RedisKeyState> redisKeyStates = new ConcurrentHashMap<>();
    private final Object[] redisKeyLocks = createLocks();

    private static final class RedisKeyState {
        private long generation;
        private int scheduledWrites;
    }

    public CaffeineRedisProposalRepository() {
        this(ForkJoinPool.commonPool());
    }

    CaffeineRedisProposalRepository(Executor redisExecutor) {
        this(redisExecutor, () -> { });
    }

    CaffeineRedisProposalRepository(Executor redisExecutor, Runnable afterL1WriteHook) {
        this.redisExecutor = redisExecutor;
        this.afterL1WriteHook = afterL1WriteHook;
    }

    public CaffeineRedisProposalRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, ForkJoinPool.commonPool());
    }

    CaffeineRedisProposalRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                    Executor redisExecutor) {
        this(redisTemplate, objectMapper, redisExecutor, () -> { });
    }

    CaffeineRedisProposalRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                    Executor redisExecutor, Runnable afterL1WriteHook) {
        this(redisExecutor, afterL1WriteHook);
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Autowired(required = false)
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void put(PlanAdjustmentProposal proposal) {
        if (proposal == null || proposal.getProposalId() == null) return;
        String proposalId = proposal.getProposalId();
        long now = System.currentTimeMillis();
        long remainingMs = proposal.getExpiresAt() - now;
        long lifetime = proposal.getExpiresAt() - proposal.getCreatedAt();
        boolean expired = remainingMs <= 0 || proposal.getCreatedAt() <= 0 || proposal.getCreatedAt() > now
                || lifetime <= 0 || lifetime > PlanAdjustmentProposal.DEFAULT_TTL_MS;
        // Invalid input is not a cancellation command and must not disturb a newer live snapshot.
        if (expired) return;
        boolean shouldWriteRedis = redisTemplate != null && objectMapper != null;
        RedisWrite pendingWrite = null;
        synchronized (lockFor(proposalId)) {
            RedisKeyState state = redisKeyStates.computeIfAbsent(proposalId, ignored -> new RedisKeyState());
            // L1 visibility and L2 generation share one per-key linearization point.
            l1Cache.put(proposalId, new CachedProposal(proposal, false));
            afterL1WriteHook.run();
            long generation = ++state.generation;
            if (shouldWriteRedis) {
                state.scheduledWrites++;
                pendingWrite = new RedisWrite(state, generation);
            }
            removeStateIfIdle(proposalId, state);
        }

        if (pendingWrite != null) {
            RedisWrite write = pendingWrite;
            try {
                redisExecutor.execute(() -> executeRedisWrite(proposal, write));
            } catch (RuntimeException e) {
                finishScheduledWrite(proposalId, write.state());
                log.debug("[ProposalRepository] L2 Redis 任务未入队，保留 L1 降级结果: {}", e.getMessage());
            }
        }
    }

    private void executeRedisWrite(PlanAdjustmentProposal proposal, RedisWrite write) {
        try {
            synchronized (lockFor(proposal.getProposalId())) {
                if (redisKeyStates.get(proposal.getProposalId()) == write.state()
                        && write.state().generation == write.generation()) {
                    long now = System.currentTimeMillis();
                    long remainingMs = proposal.getExpiresAt() - now;
                    if (remainingMs <= 0 || remainingMs > PlanAdjustmentProposal.DEFAULT_TTL_MS
                            || proposal.getCreatedAt() <= 0 || proposal.getCreatedAt() > now
                            || proposal.getExpiresAt() <= proposal.getCreatedAt()
                            || proposal.getExpiresAt() - proposal.getCreatedAt() > PlanAdjustmentProposal.DEFAULT_TTL_MS) {
                        l1Cache.invalidate(proposal.getProposalId());
                        return;
                    }
                    String json = objectMapper.writeValueAsString(proposal);
                    Long published = redisTemplate.execute(PUBLISH_SNAPSHOT, List.of(REDIS_PREFIX + proposal.getProposalId()),
                            REMOVED_SNAPSHOT, json, String.valueOf(remainingMs));
                    if (Long.valueOf(1).equals(published)) {
                        l1Cache.put(proposal.getProposalId(), new CachedProposal(proposal, true));
                        removalTombstones.invalidate(proposal.getProposalId());
                    } else if (Long.valueOf(0).equals(published)) {
                        l1Cache.invalidate(proposal.getProposalId());
                        removalTombstones.put(proposal.getProposalId(), Boolean.TRUE);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[ProposalRepository] L2 Redis 异步备份失败（降级由 L1 承载）: {}", e.getMessage());
        } finally {
            finishScheduledWrite(proposal.getProposalId(), write.state());
        }
    }

    private void finishScheduledWrite(String proposalId, RedisKeyState state) {
        synchronized (lockFor(proposalId)) {
            if (redisKeyStates.get(proposalId) == state) {
                state.scheduledWrites--;
                removeStateIfIdle(proposalId, state);
            }
        }
    }

    @Override
    public PlanAdjustmentProposal get(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;
        synchronized (lockFor(proposalId)) {
                CachedProposal cached = l1Cache.getIfPresent(proposalId);
                PlanAdjustmentProposal proposal = cached == null ? null : cached.proposal();
                if (proposal != null) {
                    if (!cached.shared() && proposal.isExpired()) {
                        l1Cache.invalidate(proposalId);
                        RedisKeyState state = redisKeyStates.get(proposalId);
                        if (state != null) state.generation++;
                        discardRedisSnapshot(proposalId, snapshotOf(cached));
                        removeStateIfIdle(proposalId, state);
                        return null;
                    } else if (!cached.shared()) {
                        // Unpublished local proposals remain available when L2 writes fail or are queued.
                        if (redisTemplate != null) {
                            try {
                                if (REMOVED_SNAPSHOT.equals(redisTemplate.opsForValue().get(REDIS_PREFIX + proposalId))) {
                                    l1Cache.invalidate(proposalId);
                                    return null;
                                }
                            } catch (Exception ignored) { /* Preserve the established unpublished-local fallback. */ }
                        }
                        return proposal;
                    }
                }

                if (removalTombstones.getIfPresent(proposalId) != null) return null;

                if (redisTemplate != null && objectMapper != null) {
                    try {
                        String json = redisTemplate.opsForValue().get(REDIS_PREFIX + proposalId);
                        if (json != null && !json.isBlank() && !REMOVED_SNAPSHOT.equals(json)) {
                            PlanAdjustmentProposal restored = objectMapper.readValue(json, PlanAdjustmentProposal.class);
                            if (isRestorable(proposalId, restored)) {
                                l1Cache.put(proposalId, new CachedProposal(restored, true));
                                return restored;
                            } else if (restored != null) {
                                RedisKeyState state = redisKeyStates.get(proposalId);
                                if (state != null) state.generation++;
                                discardRedisSnapshot(proposalId, json);
                                removeStateIfIdle(proposalId, state);
                            }
                        }
                        // Shared proposals must observe remote cancellation, replacement and expiry.
                        l1Cache.invalidate(proposalId);
                    } catch (Exception e) {
                        log.debug("[ProposalRepository] L2 Redis 读取失败，未返回共享快照: {}", e.getMessage());
                    }
                }
                return null;
        }
    }

    @Override
    public PlanAdjustmentProposal remove(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;
        synchronized (lockFor(proposalId)) {
                CachedProposal cached = l1Cache.getIfPresent(proposalId);
                PlanAdjustmentProposal existing = cached == null ? null : cached.proposal();
                l1Cache.invalidate(proposalId);
                RedisKeyState state = redisKeyStates.get(proposalId);
                if (state != null) state.generation++;
                deleteRedisSnapshot(proposalId);
                removeStateIfIdle(proposalId, state);
                return existing;
        }
    }

    private void deleteRedisSnapshot(String proposalId) {
        if (redisTemplate == null) {
            removalTombstones.put(proposalId, Boolean.TRUE);
            return;
        }
        try {
            // Proposal IDs are single-use. The tombstone outlives every accepted pre-removal proposal.
            redisTemplate.opsForValue().set(REDIS_PREFIX + proposalId, REMOVED_SNAPSHOT,
                    Duration.ofMillis(PlanAdjustmentProposal.DEFAULT_TTL_MS));
            removalTombstones.invalidate(proposalId);
        } catch (Exception ignored) {
            // Prevent this process from rehydrating a snapshot whose deletion failed.
            removalTombstones.put(proposalId, Boolean.TRUE);
        }
    }

    private String snapshotOf(CachedProposal cached) {
        if (cached == null || objectMapper == null) return null;
        try { return objectMapper.writeValueAsString(cached.proposal()); }
        catch (Exception ignored) { return null; }
    }

    private void discardRedisSnapshot(String proposalId, String expectedSnapshot) {
        if (redisTemplate == null || expectedSnapshot == null) return;
        try {
            redisTemplate.execute(DISCARD_SNAPSHOT, List.of(REDIS_PREFIX + proposalId), expectedSnapshot);
        } catch (Exception ignored) {
            removalTombstones.put(proposalId, Boolean.TRUE);
        }
    }

    private boolean isRestorable(String requestedId, PlanAdjustmentProposal proposal) {
        if (proposal == null || !requestedId.equals(proposal.getProposalId())) return false;
        if (proposal.getSessionId() == null || proposal.getSessionId().isBlank()) return false;
        if (proposal.getConversationEpoch() == null || proposal.getConversationEpoch().isBlank()
                || "INITIAL".equals(proposal.getConversationEpoch())) return false;
        long now = System.currentTimeMillis();
        if (proposal.getBaseRevision() <= 0 || proposal.getCreatedAt() <= 0 || proposal.getCreatedAt() > now) return false;
        long lifetime = proposal.getExpiresAt() - proposal.getCreatedAt();
        return lifetime > 0 && lifetime <= PlanAdjustmentProposal.DEFAULT_TTL_MS && !proposal.isExpired(now);
    }

    private void removeStateIfIdle(String proposalId, RedisKeyState state) {
        if (state != null && state.scheduledWrites == 0) {
            redisKeyStates.remove(proposalId, state);
        }
    }

    private Object lockFor(String proposalId) {
        return redisKeyLocks[(proposalId.hashCode() & Integer.MAX_VALUE) % redisKeyLocks.length];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private record RedisWrite(RedisKeyState state, long generation) { }

    private record CachedProposal(PlanAdjustmentProposal proposal, boolean shared) { }

    /** L1-only maintenance hook; Redis snapshots expire by their proposal TTL. */
    public void clearLocalCache() {
        l1Cache.invalidateAll();
    }

    public void cleanExpired() {
        l1Cache.cleanUp();
    }

    public int localSize() {
        l1Cache.cleanUp();
        return (int) l1Cache.estimatedSize();
    }
}

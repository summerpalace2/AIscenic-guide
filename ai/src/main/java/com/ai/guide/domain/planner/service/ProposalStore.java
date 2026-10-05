package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 调整方案提案分级缓存与有效期管理（L1 本地 Caffeine + L2 分布式 Redis 兜底）
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：
 * 1. L1 内存层：基于 Caffeine 实施会话级高容量配额（最大 10,000 条），严格遵循 15 分钟写入过期（expireAfterWrite）；
 * 2. L2 兜底层：异步/安全同步快照至 Redis（Key 前缀 planner:proposal:），防止容器重启或实例切换时方案提案意外丢失；
 * 3. 彻底消除写死容量小并发下随机剔除未过期提案的隐患。
 */
@Slf4j
@Component
public class ProposalStore {

    public static final long DEFAULT_TTL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int MAX_CAPACITY = 10000;
    private static final String REDIS_PREFIX = "planner:proposal:";

    private final Cache<String, PlanAdjustmentProposal> l1Cache = Caffeine.newBuilder()
            .maximumSize(MAX_CAPACITY)
            .expireAfterWrite(15, TimeUnit.MINUTES)
            .build();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    public void put(PlanAdjustmentProposal proposal) {
        if (proposal == null || proposal.getProposalId() == null) return;
        l1Cache.put(proposal.getProposalId(), proposal);

        // L2 快照备份（异步非阻塞执行，已过期提案严禁写入 Redis）
        long remainingMs = proposal.getExpiresAt() - System.currentTimeMillis();
        if (remainingMs <= 0) return; // 提案已过期，直接放弃 Redis 备份

        if (redisTemplate != null && objectMapper != null) {
            final long ttlSec = Math.max(60, remainingMs / 1000L);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String json = objectMapper.writeValueAsString(proposal);
                    redisTemplate.opsForValue().set(REDIS_PREFIX + proposal.getProposalId(), json, Duration.ofSeconds(ttlSec));
                } catch (Exception e) {
                    log.debug("[ProposalStore] L2 Redis 异步备份失败（降级由 L1 承载）: {}", e.getMessage());
                }
            });
        }
    }

    public PlanAdjustmentProposal get(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;

        // 1. 优先从 L1 Caffeine 缓存读取
        PlanAdjustmentProposal proposal = l1Cache.getIfPresent(proposalId);
        if (proposal != null) {
            if (proposal.isExpired()) {
                remove(proposalId);
                return null;
            }
            return proposal;
        }

        // 2. L1 未命中，尝试从 L2 Redis 快照回补
        if (redisTemplate != null && objectMapper != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_PREFIX + proposalId);
                if (json != null && !json.isBlank()) {
                    PlanAdjustmentProposal restored = objectMapper.readValue(json, PlanAdjustmentProposal.class);
                    if (restored != null && !restored.isExpired()) {
                        l1Cache.put(proposalId, restored);
                        return restored;
                    } else if (restored != null && restored.isExpired()) {
                        remove(proposalId);
                    }
                }
            } catch (Exception e) {
                log.debug("[ProposalStore] L2 Redis 回补失败: {}", e.getMessage());
            }
        }

        return null;
    }

    public PlanAdjustmentProposal remove(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;
        PlanAdjustmentProposal existing = l1Cache.getIfPresent(proposalId);
        l1Cache.invalidate(proposalId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_PREFIX + proposalId);
            } catch (Exception ignored) {}
        }
        return existing;
    }

    public void cleanExpired() {
        l1Cache.cleanUp();
    }

    public int size() {
        l1Cache.cleanUp();
        return (int) l1Cache.estimatedSize();
    }

    public void clear() {
        l1Cache.invalidateAll();
    }
}

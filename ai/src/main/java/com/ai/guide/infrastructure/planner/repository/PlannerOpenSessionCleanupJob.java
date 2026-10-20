package com.ai.guide.infrastructure.planner.repository;

import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires only untouched formal-trip sessions that BFF never acknowledged. */
@Component
public class PlannerOpenSessionCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(PlannerOpenSessionCleanupJob.class);

    private final PlannerSessionRepositoryPort sessions;
    private final long pendingTtlMillis;

    public PlannerOpenSessionCleanupJob(PlannerSessionRepositoryPort sessions,
                                        @Value("${planner.open.pending-session-ttl-ms:86400000}") long pendingTtlMillis) {
        this.sessions = sessions;
        this.pendingTtlMillis = Math.max(60_000L, pendingTtlMillis);
    }

    @Scheduled(fixedDelayString = "${planner.open.pending-cleanup-interval-ms:60000}")
    public void cleanupExpiredOpenSessions() {
        int removed = sessions.deleteExpiredPendingFormalOpens(System.currentTimeMillis() - pendingTtlMillis);
        if (removed > 0) log.info("[PLANNER_OPEN_CLEANUP] removed {} unacknowledged open sessions", removed);
    }
}

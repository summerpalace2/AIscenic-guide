package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.engine.RouteCost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 规划器性能与决策链路指标监控日志记录器
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
@Component
public class PlannerMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger(PlannerMetricsLogger.class);

    private final AtomicLong planCreateCount = new AtomicLong();
    private final AtomicLong proposalPreviewCount = new AtomicLong();
    private final AtomicLong proposalApplyCount = new AtomicLong();
    private final AtomicLong revisionConflictCount = new AtomicLong();
    private final AtomicLong repairCount = new AtomicLong();

    private final AtomicLong routeQueryCount = new AtomicLong();
    private final AtomicLong amapVerifiedCount = new AtomicLong();
    private final AtomicLong routeEstimatedCount = new AtomicLong();
    private final AtomicLong routeUnavailableCount = new AtomicLong();

    private final AtomicLong lastCreateLatencyMs = new AtomicLong(-1);
    private final AtomicLong lastPreviewLatencyMs = new AtomicLong(-1);
    private final AtomicLong lastApplyLatencyMs = new AtomicLong(-1);

    private final List<String> recentStructuredLogs = new CopyOnWriteArrayList<>();

    public void logPlanCreated(String sessionId, int version, long latencyMs, String routeDataStatus, boolean degraded) {
        planCreateCount.incrementAndGet();
        lastCreateLatencyMs.set(latencyMs);
        String entry = String.format("action=CREATE_PLAN sessionId=%s version=%d latencyMs=%d routeDataStatus=%s degraded=%b",
                maskId(sessionId), version, latencyMs, routeDataStatus, degraded);
        appendLog(entry);
        log.info("[PLANNER_METRIC] {}", entry);
    }

    public void logProposalPreviewed(String sessionId, String proposalId, String intentType, boolean feasible, long latencyMs) {
        proposalPreviewCount.incrementAndGet();
        lastPreviewLatencyMs.set(latencyMs);
        String entry = String.format("action=PREVIEW_PROPOSAL sessionId=%s proposalId=%s intent=%s feasible=%b latencyMs=%d",
                maskId(sessionId), maskId(proposalId), intentType, feasible, latencyMs);
        appendLog(entry);
        log.info("[PLANNER_METRIC] {}", entry);
    }

    public void logProposalApplied(String sessionId, String proposalId, int nextVersion, long latencyMs) {
        proposalApplyCount.incrementAndGet();
        lastApplyLatencyMs.set(latencyMs);
        String entry = String.format("action=APPLY_PROPOSAL sessionId=%s proposalId=%s nextVersion=%d latencyMs=%d",
                maskId(sessionId), maskId(proposalId), nextVersion, latencyMs);
        appendLog(entry);
        log.info("[PLANNER_METRIC] {}", entry);
    }

    public void logRevisionConflict(String sessionId, int expectedVersion, int actualVersion) {
        revisionConflictCount.incrementAndGet();
        String entry = String.format("action=REVISION_CONFLICT sessionId=%s expectedVersion=%d actualVersion=%d",
                maskId(sessionId), expectedVersion, actualVersion);
        appendLog(entry);
        log.warn("[PLANNER_METRIC] {}", entry);
    }

    public void logPlanRepaired(String sessionId, int dayNumber, String violationType, int attemptRound) {
        repairCount.incrementAndGet();
        String entry = String.format("action=LOCAL_REPAIR sessionId=%s day=%d violation=%s round=%d",
                maskId(sessionId), dayNumber, violationType, attemptRound);
        appendLog(entry);
        log.info("[PLANNER_METRIC] {}", entry);
    }

    public void recordRouteCost(RouteCost.RouteDataStatus status) {
        if (status == null) return;
        routeQueryCount.incrementAndGet();
        switch (status) {
            case VERIFIED_AMAP, CACHED -> amapVerifiedCount.incrementAndGet();
            case ESTIMATED -> routeEstimatedCount.incrementAndGet();
            case UNAVAILABLE -> routeUnavailableCount.incrementAndGet();
        }
    }

    public long getPlanCreateCount() { return planCreateCount.get(); }
    public long getProposalPreviewCount() { return proposalPreviewCount.get(); }
    public long getProposalApplyCount() { return proposalApplyCount.get(); }
    public long getRevisionConflictCount() { return revisionConflictCount.get(); }
    public long getRepairCount() { return repairCount.get(); }

    public long getRouteQueryCount() { return routeQueryCount.get(); }
    public long getAmapVerifiedCount() { return amapVerifiedCount.get(); }
    public long getRouteEstimatedCount() { return routeEstimatedCount.get(); }
    public long getRouteUnavailableCount() { return routeUnavailableCount.get(); }

    public long getLastCreateLatencyMs() { return lastCreateLatencyMs.get(); }
    public long getLastPreviewLatencyMs() { return lastPreviewLatencyMs.get(); }
    public long getLastApplyLatencyMs() { return lastApplyLatencyMs.get(); }

    public List<String> getRecentStructuredLogs() {
        return List.copyOf(recentStructuredLogs);
    }

    private void appendLog(String entry) {
        if (recentStructuredLogs.size() > 200) {
            recentStructuredLogs.remove(0);
        }
        recentStructuredLogs.add(entry);
    }

    private String maskId(String id) {
        if (id == null || id.isBlank()) return "null";
        if (id.length() <= 8) return id;
        return id.substring(0, 8) + "...";
    }
}

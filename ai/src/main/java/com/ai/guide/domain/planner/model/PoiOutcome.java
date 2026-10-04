package com.ai.guide.domain.planner.model;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import java.time.Instant;
import java.util.List;

/**
 * POI 检索与周边结果领域模型
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：封装 POI 搜索候选列表、执行状态与获取时间戳。
 *
 * @param status     调用状态
 * @param candidates POI 候选对象列表
 * @param reason     未命中或失败原因说明
 * @param fetchedAt  获取时间戳
 */
public record PoiOutcome(
        OutcomeStatus status,
        List<AmapResponseNormalizer.PoiCandidate> candidates,
        String reason,
        Instant fetchedAt
) {
}

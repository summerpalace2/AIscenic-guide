package com.ai.guide.domain.planner.model.category;

import java.time.LocalTime;
import java.util.List;

/**
 * 垂直品类排程行为规约与约束策略接口
 */
public interface PoiCategoryPolicy {

    /** 关联的业务业态 */
    PoiDomainType category();

    /** 游玩/停留时长下限（分钟） */
    int minDurationMinutes();

    /** 游玩/停留时长上限（分钟） */
    int maxDurationMinutes();

    /** 推荐默认时长（分钟） */
    int defaultDurationMinutes();

    /** 推荐的时间窗口区间列表（如 DINING 仅限中午与晚间） */
    List<TimeWindow> preferredTimeWindows();

    /** 是否允许作为全天的第一站（如 HOTEL 通常不作为出发游玩第1站） */
    boolean allowedAsFirstStop();

    /** 是否允许作为全天的收尾站（如 THEATER/HOTEL 极适合收尾） */
    boolean allowedAsLastStop();

    /** 单日内允许的最大排程上限（如 THEATER 单日上限为 1 场） */
    int maxOccurrencesPerDay();

    /** 同类节点最小时间间隔（分钟，如正餐与正餐之间至少相隔 240 分钟） */
    int minIntervalMinutesBetweenSameCategory();

    record TimeWindow(LocalTime start, LocalTime end, String label) {
        public boolean contains(LocalTime time) {
            if (time == null) return false;
            return (time.equals(start) || time.isAfter(start)) && time.isBefore(end);
        }
    }
}

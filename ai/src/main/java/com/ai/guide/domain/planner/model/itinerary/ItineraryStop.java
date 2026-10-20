package com.ai.guide.domain.planner.model.itinerary;

import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.category.PoiDomainType;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * 单个游览/餐饮打卡站点实体 (Stop Point Value Object / Entity)
 */
public record ItineraryStop(
        String id,
        String stableStopId,
        String venueId,
        String name,
        String displayName,
        String district,
        PoiDomainType type,
        String categoryTag,
        String icon,
        LocalTime startTime,
        Duration duration,
        String locationCoordinate,
        String summary,
        String detailNarrative,
        String aiGuide,
        boolean isPinned,
        RouteCost estimatedRouteCost,
        ScoreBreakdown scoreBreakdown,
        String topographyTransitHint,
        List<String> highlights,
        Map<String, Object> extraAttributes
) {
    public String formattedTime() {
        return startTime == null ? "待安排" : startTime.toString();
    }

    public String formattedDuration() {
        if (duration == null) return "待安排";
        long mins = duration.toMinutes();
        return mins >= 60 && mins % 60 == 0 ? "约 " + (mins / 60) + " 小时" : "约 " + mins + " 分钟";
    }
}

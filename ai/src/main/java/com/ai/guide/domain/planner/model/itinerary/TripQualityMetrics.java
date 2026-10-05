package com.ai.guide.domain.planner.model.itinerary;

import java.util.Map;

/**
 * 行程质量与健康度评估指标
 */
public record TripQualityMetrics(
        double overallScore,
        double fatigueIndex,
        double culturalDepthIndex,
        Map<String, Object> details
) {}

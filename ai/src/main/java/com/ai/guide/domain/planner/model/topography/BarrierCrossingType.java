package com.ai.guide.domain.planner.model.topography;

/**
 * 天然地理阻隔跨越分类枚举
 */
public enum BarrierCrossingType {
    NONE("无阻隔跨越"),
    MAJOR_RIVER_PRIMARY("跨越已配置的主要江河一"),
    MAJOR_RIVER_SECONDARY("跨越已配置的主要江河二"),
    CONFLUENCE_BOTH("水系交汇枢纽"),
    HISTORICAL_WALL("跨越古城墙内/外圈层"),
    MOUNTAIN_PASS("跨越外围山体山口");

    private final String description;
    BarrierCrossingType(String description) { this.description = description; }
    public String getDescription() { return description; }
}

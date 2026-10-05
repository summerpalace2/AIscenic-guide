package com.ai.guide.domain.planner.model.topography;

/**
 * 垂直坡度动线流向枚举
 */
public enum ElevationFlow {
    DOWNHILL("顺势下行（省力）"),
    UPHILL("逆势爬坡（耗费体力）"),
    FLAT("平级流动（平缓）");

    private final String description;
    ElevationFlow(String description) { this.description = description; }
    public String getDescription() { return description; }
}

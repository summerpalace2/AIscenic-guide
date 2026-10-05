package com.ai.guide.domain.planner.model.topography;

/**
 * 通用高程圈层划分枚举
 */
public enum ElevationTier {
    HIGH_ALTITUDE(3, "高山/制高点高地"),
    UPPER_PLATEAU(2, "台地/高架上半城"),
    LOWLAND_VALLEY(1, "谷地/平坦江岸带"),
    STANDARD_PLAIN(2, "常规平原/平街浅丘");

    private final int rank;
    private final String description;

    ElevationTier(int rank, String description) {
        this.rank = rank;
        this.description = description;
    }

    public int getRank() { return rank; }
    public String getDescription() { return description; }
}

package com.ai.guide.domain.planner.model.category;

/**
 * 智慧文旅 POI 多品类垂直业态类型枚举
 */
public enum PoiDomainType {
    /** 核心地标景区（博物馆、古镇、自然风光、观景台） */
    SCENIC("SCENIC", "景", "景点游览", 120),

    /** 特色餐饮（正餐、地方小吃、茶歇饮品） */
    DINING("DINING", "餐", "特色餐饮", 75),

    /** 演艺剧场视听（实景演出、歌剧院、脱口秀、室内剧场） */
    THEATER("THEATER", "剧", "演艺剧场", 100),

    /** 商业文创商圈（天街商圈、文创街区、夜市市集） */
    SHOPPING("SHOPPING", "购", "休闲购物", 90),

    /** 度假品质住宿（星级酒店、特色民宿、温泉客栈） */
    HOTEL("HOTEL", "宿", "酒店住宿", 60),

    /** 立体特色交通枢纽/接驳（缆车索道、扶梯、轮渡码头、高铁站） */
    TRANSIT("TRANSIT", "行", "特色交通", 30);

    private final String code;
    private final String defaultIcon;
    private final String displayName;
    private final int defaultDurationMinutes;

    PoiDomainType(String code, String defaultIcon, String displayName, int defaultDurationMinutes) {
        this.code = code;
        this.defaultIcon = defaultIcon;
        this.displayName = displayName;
        this.defaultDurationMinutes = defaultDurationMinutes;
    }

    public String getCode() { return code; }
    public String getDefaultIcon() { return defaultIcon; }
    public String getDisplayName() { return displayName; }
    public int getDefaultDurationMinutes() { return defaultDurationMinutes; }

    public static PoiDomainType fromCode(String code) {
        if (code == null || code.isBlank()) return SCENIC;
        for (PoiDomainType type : values()) {
            if (type.code.equalsIgnoreCase(code.trim()) || type.name().equalsIgnoreCase(code.trim())) {
                return type;
            }
        }
        // 向前兼容历史 "ATTRACTION" 标识
        if ("ATTRACTION".equalsIgnoreCase(code.trim())) return SCENIC;
        return SCENIC;
    }
}

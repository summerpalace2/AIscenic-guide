package com.ai.guide.domain.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

/**
 * 受控核心景点的结构化元数据实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Attraction {

    /** 景点唯一 ID */
    private String id;

    /** 景点名称 */
    private String name;

    /** 展现名称 */
    private String displayName;

    /** 所在行政区 */
    private String district;

    /** 景点分类 */
    private String category;

    /** 标签列表 */
    private List<String> tags;

    /** 地图图标标识 */
    private String icon;

    /** 渲染色调 */
    private String tone;

    /** 经纬度坐标 (格式: "lng,lat") */
    private String location;

    /** 简要概述 */
    private String summary;

    /** 步行或交通提示 */
    private String walk;

    /** 建议时长 */
    private String duration;

    /** 是否为室内景点 */
    private Boolean indoor;

    /** 步行难度 (低 / 中 / 高) */
    private String walkDifficulty;

    /** 门票及预约策略 */
    private String ticket;

    /** 最佳游览时间 */
    private String bestTime;

    /** 高德 POI 匹配规则 (包含 keywords, alternatives, matches) */
    private Map<String, Object> amapQuery;

    /** 详细深度介绍 */
    private String intro;

    /** 适宜人群与偏好匹配 */
    private String fit;

    /** 无障碍等级 (SUPPORTED / PARTIAL / UNSUPPORTED / UNKNOWN) */
    private String accessibility;

    /** 环境类型 (INDOOR / OUTDOOR / MIXED) */
    private String environment;

    /** 建议游览分钟数 */
    private Integer recommendedVisitMinutes;

    /** 适宜同行人群标签 (例如：带父母, 带孩子, 情侣出游, 朋友出游, 独自出发) */
    private List<String> companionTags;

    /** 核心特色标签 */
    private List<String> featureTags;

    /** 当前请求从 AMap 返回的图片 URL；静态目录不保存该值。 */
    private String image;

    /** 图片来源（例如 AMap Web Service）。 */
    private String imageSource;

    /** 图片查询状态。 */
    private String imageStatus;

    /** 图片查询失败或无图片时的明确原因。 */
    private String imageReason;

    /** AMap POI 标识与查询时间，便于前端展示来源。 */
    private String imagePoiId;
    private String imageFetchedAt;

    public String getEffectiveAccessibility() {
        if (accessibility != null && !accessibility.isBlank()) return accessibility;
        if ("低".equals(walkDifficulty) || hasTagOrFeature("无障碍") || hasTagOrFeature("少走路") || hasTagOrFeature("长辈友好")) {
            return "SUPPORTED";
        }
        if ("中".equals(walkDifficulty)) return "PARTIAL";
        if ("高".equals(walkDifficulty)) return "UNSUPPORTED";
        return "UNKNOWN";
    }

    public String getEffectiveEnvironment() {
        if (environment != null && !environment.isBlank()) return environment;
        if (Boolean.TRUE.equals(indoor)) return "INDOOR";
        if (hasTagOrFeature("室内外") || hasTagOrFeature("室内外展陈")) return "MIXED";
        return "OUTDOOR";
    }

    public int getEffectiveVisitMinutes() {
        if (recommendedVisitMinutes != null && recommendedVisitMinutes > 0) return recommendedVisitMinutes;
        String dur = duration == null ? "" : duration;
        if (dur.contains("180")) return 180;
        if (dur.contains("120")) return 120;
        if (dur.contains("90")) return 90;
        if (dur.contains("60")) return 60;
        if (dur.contains("45")) return 45;
        return 90;
    }

    public boolean hasTagOrFeature(String keyword) {
        if (keyword == null || keyword.isBlank()) return false;
        if (tags != null && tags.contains(keyword)) return true;
        if (featureTags != null && featureTags.contains(keyword)) return true;
        if (companionTags != null && companionTags.contains(keyword)) return true;
        return false;
    }

    public boolean matchesAnyTagOrFeature(String... keywords) {
        if (keywords == null) return false;
        for (String kw : keywords) {
            if (hasTagOrFeature(kw)) return true;
        }
        return false;
    }
}

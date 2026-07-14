package com.ai.guide.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 景点信息实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScenicItem {

    /** 景点 ID */
    private Long id;

    /** 景点名称 */
    private String name;

    /** 景点分类 */
    private String category;

    /** 景点描述 */
    private String description;

    /** 所在位置 */
    private String location;

    /** 图片链接 */
    private String imageUrl;

    /** 门票价格 */
    private Double price;

    /** 纬度 */
    private Double latitude;

    /** 经度 */
    private Double longitude;

    /** 开放时间 */
    private String openTime;

    /** 游玩贴士 */
    private String tips;
}

package com.ai.guide.domain.taxonomy.gateway;

/**
 * 餐饮品牌知识库与商户字典端口 (Domain Gateway Port)
 *
 * 架构定位：COLA 领域端口
 * 职责：提供全国知名品牌商户判定能力，解耦硬编码 Set，支持外部配置或 LBS 动态注入。
 */
public interface BrandDictionaryPort {

    /**
     * 判断给定名称是否属于已知餐饮连锁/知名商户实体
     *
     * @param brandName 商户或品牌名称
     * @return true 表示属于已知商户实体，false 表示非知名品牌
     */
    boolean isKnownBrand(String brandName);
}

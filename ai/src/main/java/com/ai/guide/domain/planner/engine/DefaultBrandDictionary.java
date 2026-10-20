package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.taxonomy.gateway.BrandDictionaryPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认餐饮品牌字典实现
 *
 * 架构定位：可扩展领域设施
 * 支持从外部配置文件动态加载、外部动态注资与运行时扩展，消除静态硬编码。
 */
@Component
public class DefaultBrandDictionary implements BrandDictionaryPort {

    private final PlannerTaxonomyPort taxonomy;
    private final java.util.Set<String> registeredBrands = ConcurrentHashMap.newKeySet();

    public DefaultBrandDictionary(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = Objects.requireNonNull(taxonomy);
    }

    @Override
    public boolean isKnownBrand(String brandName) {
        if (brandName == null || brandName.isBlank()) return false;
        return taxonomy.isKnownBrand(brandName) || registeredBrands.contains(brandName.trim());
    }

    /**
     * 运行时动态注册新品牌（供高德 LBS 或外部配置热载入）
     */
    public void registerBrand(String brandName) {
        if (brandName != null && !brandName.isBlank()) {
            String trimmed = brandName.trim();
            if (!trimmed.startsWith("#")) {
                registeredBrands.add(trimmed);
            }
        }
    }
}

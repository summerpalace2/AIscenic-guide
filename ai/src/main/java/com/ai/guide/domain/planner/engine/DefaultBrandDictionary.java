package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.gateway.BrandDictionaryPort;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认餐饮品牌字典实现
 *
 * 架构定位：可扩展领域设施
 * 支持从外部配置文件动态加载、外部动态注资与运行时扩展，消除静态硬编码。
 */
@Component
public class DefaultBrandDictionary implements BrandDictionaryPort {

    private final Set<String> knownBrands = ConcurrentHashMap.newKeySet();

    public DefaultBrandDictionary() {
        loadBrandsFromResource();
    }

    private void loadBrandsFromResource() {
        knownBrands.addAll(new com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider().getBrands());
    }

    @Override
    public boolean isKnownBrand(String brandName) {
        if (brandName == null || brandName.isBlank()) return false;
        return knownBrands.contains(brandName.trim());
    }

    /**
     * 运行时动态注册新品牌（供高德 LBS 或外部配置热载入）
     */
    public void registerBrand(String brandName) {
        if (brandName != null && !brandName.isBlank()) {
            String trimmed = brandName.trim();
            if (!trimmed.startsWith("#")) {
                knownBrands.add(trimmed);
            }
        }
    }
}

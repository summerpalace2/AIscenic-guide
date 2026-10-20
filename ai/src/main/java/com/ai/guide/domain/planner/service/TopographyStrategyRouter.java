package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 城市时空阻抗策略路由器与门面工厂 (Strategy Router)
 */
@Component
public class TopographyStrategyRouter {

    private final Map<String, TopographyStrategyPort> strategies = new ConcurrentHashMap<>();
    private final TopographyStrategyPort defaultStrategy;
    private final TopographyStrategyPort genericPlainStrategy;
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public TopographyStrategyRouter(List<TopographyStrategyPort> strategyList, PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy;
        TopographyStrategyPort plainFallback = null;
        for (TopographyStrategyPort strategy : strategyList) {
            String code = strategy.supportedCity().toUpperCase(Locale.ROOT);
            strategies.put(code, strategy);
            if ("GENERIC".equalsIgnoreCase(code)) plainFallback = strategy;
        }
        this.genericPlainStrategy = plainFallback != null ? plainFallback : new GenericPlainTopographyStrategy();
        this.defaultStrategy = this.genericPlainStrategy;
    }

    public TopographyStrategyRouter(List<TopographyStrategyPort> strategyList) {
        this(strategyList, TaxonomyPort.empty());
    }

    /**
     * 根据城市名称解析对应的拓扑阻抗策略
     *
     * @param cityName 目的地城市名称或其已配置别名
     * @return 返回对应策略；未配置城市使用通用地形策略
     */
    public TopographyStrategyPort route(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return genericPlainStrategy;
        }
        String clean = cityName.trim();
        String normalized = clean.toUpperCase(Locale.ROOT);
        String canonical = taxonomy.getCityAliases().entrySet().stream()
                .filter(entry -> clean.contains(entry.getKey()) || normalized.equals(entry.getKey().toUpperCase(Locale.ROOT)))
                .sorted(java.util.Comparator.<Map.Entry<String,String>>comparingInt(entry -> entry.getKey().length()).reversed())
                .map(Map.Entry::getValue).findFirst().orElse(clean);
        String strategyCode = taxonomy.getCityStrategyMappings().entrySet().stream()
                .filter(entry -> canonical.equals(entry.getKey()) || clean.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(normalized);
        return strategies.getOrDefault(strategyCode.toUpperCase(Locale.ROOT), genericPlainStrategy != null ? genericPlainStrategy : defaultStrategy);
    }

    /**
     * 获取指定城市的策略（按枚举代码查找）
     */
    public TopographyStrategyPort getByCityCode(String cityCode) {
        if (cityCode == null) return defaultStrategy;
        String code = cityCode.trim().toUpperCase(Locale.ROOT);
        return strategies.getOrDefault(code, defaultStrategy);
    }
}

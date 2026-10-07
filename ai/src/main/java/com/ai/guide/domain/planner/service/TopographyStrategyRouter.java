package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
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

    @Autowired
    public TopographyStrategyRouter(List<TopographyStrategyPort> strategyList) {
        TopographyStrategyPort cqFallback = null;
        TopographyStrategyPort plainFallback = null;
        for (TopographyStrategyPort strategy : strategyList) {
            String code = strategy.supportedCity().toUpperCase(Locale.ROOT);
            strategies.put(code, strategy);
            if ("CHONGQING".equalsIgnoreCase(code)) {
                cqFallback = strategy;
            } else if ("GENERIC".equalsIgnoreCase(code) || "CHENGDU".equalsIgnoreCase(code)) {
                if (plainFallback == null || "GENERIC".equalsIgnoreCase(code)) {
                    plainFallback = strategy;
                }
            }
        }
        this.genericPlainStrategy = plainFallback != null ? plainFallback : new GenericPlainTopographyStrategy();
        this.defaultStrategy = this.genericPlainStrategy;
    }

    /**
     * 根据城市名称解析对应的拓扑阻抗策略
     *
     * @param cityName 城市名称（如 "重庆", "重庆市", "成都", "西安", "CHENGDU", "XiAn"）
     * @return 对应的策略实现，未命中时返回通用平原策略（防止非重庆城市被套用长江天堑）
     */
    public TopographyStrategyPort route(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return genericPlainStrategy;
        }
        String clean = cityName.trim();
        String upper = clean.toUpperCase(Locale.ROOT);

        if (clean.contains("重庆") || upper.contains("CHONGQING")) {
            return strategies.getOrDefault("CHONGQING", defaultStrategy);
        }
        if (clean.contains("成都") || upper.contains("CHENGDU")) {
            return strategies.getOrDefault("CHENGDU", defaultStrategy);
        }
        if (clean.contains("西安") || upper.contains("XIAN") || upper.contains("XI'AN")) {
            return strategies.getOrDefault("XIAN", defaultStrategy);
        }

        // 未知城市：返回通用平原策略，杜绝将重庆特有的长江天堑强加给其他城市
        return genericPlainStrategy != null ? genericPlainStrategy : defaultStrategy;
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

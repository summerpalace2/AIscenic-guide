package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.category.PoiCategoryPolicy;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 垂直业态策略注册中心 (Policy Registry)
 */
@Component
public class PoiCategoryPolicyRegistry {

    private final Map<PoiDomainType, PoiCategoryPolicy> policies = new EnumMap<>(PoiDomainType.class);

    public PoiCategoryPolicyRegistry() {
        registerDefaultPolicies();
    }

    public PoiCategoryPolicy getPolicy(PoiDomainType type) {
        if (type == null) return policies.get(PoiDomainType.SCENIC);
        return policies.getOrDefault(type, policies.get(PoiDomainType.SCENIC));
    }

    /**
     * 判断一个站点 Map 是否属于餐饮节点（向后兼容通用方法）
     */
    public boolean isDiningStop(Map<?, ?> stop) {
        if (stop == null) return false;
        String type = String.valueOf(stop.get("type"));
        if ("DINING".equalsIgnoreCase(type)) return true;
        Object isDiningObj = stop.get("isDining");
        if (Boolean.TRUE.equals(isDiningObj) || "true".equalsIgnoreCase(String.valueOf(isDiningObj))) return true;
        String name = String.valueOf(stop.get("name"));
        String venueId = String.valueOf(stop.get("venueId"));
        return name.contains("午餐") || name.contains("晚餐") || name.contains("就餐")
                || venueId.startsWith("dining-") || venueId.startsWith("lunch-") || venueId.startsWith("dinner-");
    }

    /**
     * 解析站点 Map 对应的业态类型
     */
    public PoiDomainType resolveCategory(Map<?, ?> stop) {
        if (stop == null) return PoiDomainType.SCENIC;
        if (isDiningStop(stop)) return PoiDomainType.DINING;
        String type = String.valueOf(stop.get("type"));
        String name = String.valueOf(stop.get("name"));
        if (name.contains("剧场") || name.contains("演艺") || name.contains("大剧院") || name.contains("川剧")
                || name.contains("盛典") || name.contains("大戏") || name.contains("实景演出") || name.contains("歌舞") || name.contains("演出")) {
            return PoiDomainType.THEATER;
        }
        if (name.contains("天街") || name.contains("商圈") || name.contains("购物") || name.contains("步行街")) {
            return PoiDomainType.SHOPPING;
        }
        if (name.contains("索道") || name.contains("大扶梯") || name.contains("缆车") || name.contains("轮渡")) {
            return PoiDomainType.TRANSIT;
        }
        if (name.contains("酒店") || name.contains("民宿") || name.contains("客栈")) {
            return PoiDomainType.HOTEL;
        }
        return PoiDomainType.fromCode(type);
    }

    private void registerDefaultPolicies() {
        // 1. 景区景点 (SCENIC)
        policies.put(PoiDomainType.SCENIC, new DefaultPolicy(
                PoiDomainType.SCENIC, 45, 240, 120,
                List.of(new PoiCategoryPolicy.TimeWindow(LocalTime.of(8, 30), LocalTime.of(21, 30), "全天适宜游览")),
                true, true, 4, 0
        ));

        // 2. 特色餐饮 (DINING)
        policies.put(PoiDomainType.DINING, new DefaultPolicy(
                PoiDomainType.DINING, 40, 100, 75,
                List.of(
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(7, 30), LocalTime.of(9, 30), "早餐"),
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(11, 30), LocalTime.of(13, 30), "午餐"),
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(14, 30), LocalTime.of(16, 30), "下午茶歇"),
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(17, 30), LocalTime.of(20, 30), "晚餐"),
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(21, 0), LocalTime.of(23, 30), "夜市宵夜")
                ),
                false, true, 3, 180
        ));

        // 3. 演艺剧场 (THEATER)
        policies.put(PoiDomainType.THEATER, new DefaultPolicy(
                PoiDomainType.THEATER, 70, 150, 100,
                List.of(
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(14, 0), LocalTime.of(16, 30), "下午专场"),
                        new PoiCategoryPolicy.TimeWindow(LocalTime.of(19, 0), LocalTime.of(22, 0), "晚间黄金档")
                ),
                false, true, 1, 300 // 单日最多1场演艺，防视听疲劳
        ));

        // 4. 商业购物 (SHOPPING)
        policies.put(PoiDomainType.SHOPPING, new DefaultPolicy(
                PoiDomainType.SHOPPING, 45, 150, 90,
                List.of(new PoiCategoryPolicy.TimeWindow(LocalTime.of(10, 0), LocalTime.of(22, 0), "商圈营业时间")),
                false, true, 2, 120
        ));

        // 5. 酒店住宿 (HOTEL)
        policies.put(PoiDomainType.HOTEL, new DefaultPolicy(
                PoiDomainType.HOTEL, 30, 90, 60,
                List.of(new PoiCategoryPolicy.TimeWindow(LocalTime.of(14, 0), LocalTime.of(23, 59), "入住与休憩")),
                false, true, 1, 0
        ));

        // 6. 特色交通 (TRANSIT)
        policies.put(PoiDomainType.TRANSIT, new DefaultPolicy(
                PoiDomainType.TRANSIT, 15, 60, 30,
                List.of(new PoiCategoryPolicy.TimeWindow(LocalTime.of(7, 0), LocalTime.of(22, 30), "交通运营时段")),
                true, true, 3, 60
        ));
    }

    public record DefaultPolicy(
            PoiDomainType category,
            int minDurationMinutes,
            int maxDurationMinutes,
            int defaultDurationMinutes,
            List<PoiCategoryPolicy.TimeWindow> preferredTimeWindows,
            boolean allowedAsFirstStop,
            boolean allowedAsLastStop,
            int maxOccurrencesPerDay,
            int minIntervalMinutesBetweenSameCategory
    ) implements PoiCategoryPolicy {}
}

package com.ai.guide.domain.dining.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.AmapRouteService;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge.DiningOption;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 美食餐饮智能规划服务（本地知识库检索 + 高德 LBS 动态周边兜底）
 */
@Slf4j
@Service
public class DiningVenueService {

    private final JdbcTemplate jdbcTemplate;
    private final AmapRouteService amapRouteService;
    private final ObjectMapper objectMapper;
    private final List<DiningVenue> inMemorySeedCache = new CopyOnWriteArrayList<>();

    @Autowired
    public DiningVenueService(@Autowired(required = false) JdbcTemplate jdbcTemplate,
                              @Autowired(required = false) AmapRouteService amapRouteService,
                              @Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.amapRouteService = amapRouteService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        loadSeedResource();
    }

    public DiningVenueService() {
        this(null, null, new ObjectMapper());
    }

    private void loadSeedResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("dining_venues.json")) {
            if (is != null) {
                List<Map<String, Object>> list = objectMapper.readValue(is, new TypeReference<>() {});
                for (Map<String, Object> map : list) {
                    inMemorySeedCache.add(mapToVenue(map));
                }
                log.info("[DiningVenueService] 加载内置美食种子数据 {} 条", inMemorySeedCache.size());
            }
        } catch (Exception e) {
            log.warn("[DiningVenueService] 加载内置美食种子失败: {}", e.getMessage());
        }
    }

    public List<DiningVenue> listAll() {
        if (jdbcTemplate != null) {
            try {
                String sql = "SELECT * FROM dining_venue";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
                if (rows != null && !rows.isEmpty()) {
                    List<DiningVenue> list = new ArrayList<>();
                    for (Map<String, Object> row : rows) {
                        list.add(mapToVenue(row));
                    }
                    return list;
                }
            } catch (Exception e) {
                log.debug("[DiningVenueService] 查询数据库失败，回退到内存种子: {}", e.getMessage());
            }
        }
        return new ArrayList<>(inMemorySeedCache);
    }

    /**
     * 根据邻近景区与偏好匹配单餐最佳周边餐饮推荐（生成行程节点）
     */
    public DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, TravelConstraints constraints) {
        return resolveNearbyDining(anchorAttraction, slotType, constraints, java.util.Collections.emptySet());
    }

    /**
     * 根据邻近景区实体与用户偏好，匹配最佳周边餐饮推荐（支持排除已安排餐厅，防止同日午晚餐重复推荐同一家店）
     */
    public DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, TravelConstraints constraints, java.util.Set<String> excludedNames) {
        String district = anchorAttraction == null ? "渝中区" : anchorAttraction.getDistrict();
        String loc = anchorAttraction != null && anchorAttraction.getLocation() != null && !anchorAttraction.getLocation().isBlank()
                ? anchorAttraction.getLocation() : "106.577000,29.556000";
        String dietPref = constraints == null ? "本地菜优先" : constraints.getDietPreference();
        if (dietPref == null || dietPref.isBlank() || "未提供".equals(dietPref)) {
            dietPref = "本地菜优先";
        }

        String normalizedMeal = "BREAKFAST".equalsIgnoreCase(slotType) ? "BREAKFAST"
                : ("DINNER".equalsIgnoreCase(slotType) ? "DINNER" : "LUNCH");

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);

        List<DiningVenue> candidates = new ArrayList<>();
        // 1. 若配置了高德 API 且有定位，优先通过高德 LBS 检索当前景点周边的真实在营餐厅（解决跨山跨江与虚假数据问题）
        if (amapRouteService != null && amapRouteService.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, district, normalizedMeal, dietPref, proximityPriority);
        }

        // 2. 高德未配置或无结果时，回退到本地精选知识库
        if (candidates.isEmpty()) {
            candidates = findCandidates(district, loc, normalizedMeal, dietPref, constraints, proximityPriority, 6);
        }

        if (candidates.isEmpty()) {
            // 最底兜底
            return fallbackOption(district, loc, normalizedMeal, dietPref);
        }

        DiningVenue best = candidates.get(0);
        if (excludedNames != null && !excludedNames.isEmpty()) {
            for (DiningVenue venue : candidates) {
                String vName = venue.getName() != null ? venue.getName().replaceAll("^【[^】]+】", "").trim() : "";
                boolean matched = excludedNames.stream().anyMatch(ex -> {
                    String cleanEx = ex.replaceAll("^【[^】]+】", "").trim();
                    return cleanEx.equalsIgnoreCase(vName) || cleanEx.contains(vName) || vName.contains(cleanEx)
                            || (venue.getId() != null && ex.equalsIgnoreCase(venue.getId()));
                });
                if (!matched) {
                    best = venue;
                    break;
                }
            }
        }
        return toDiningOption(best, normalizedMeal);
    }

    /**
     * 判断当前规划是否属于“短途限时/定时旅游/同城一日游/就近优先”场景
     * （统一覆盖 4/6/8/10 小时同城游，时间紧凑，兼顾顺路与商圈特色）
     */
    public boolean isProximityPriority(TravelConstraints constraints, Attraction anchor) {
        if (anchor != null && anchor.getId() != null && anchor.getId().startsWith("amap-")) {
            return true; // 来自运行时动态高德规划的锚点，属于局部紧凑动线，必须严格就近
        }
        if (constraints == null) return false;
        if (constraints.getTimeBudgetMinutes() > 0 && constraints.getTimeBudgetMinutes() <= 600) {
            return true; // 10 小时以内的一日游/短途限时规划（统一覆盖 6 小时、8 小时、10 小时同城一日游）
        }
        if (constraints.getDurationDays() <= 1 && constraints.getTimeBudgetMinutes() > 0) {
            return true;
        }
        if ("低".equals(constraints.getWalkingTolerance())) {
            return true; // 低步行耐受度，严格就近
        }
        String prompt = constraints.getRawPrompt();
        if (prompt != null && (prompt.contains("小时") || prompt.contains("半天") || prompt.contains("就近")
                || prompt.contains("附近") || prompt.contains("短途") || prompt.contains("定时") || prompt.contains("一日游"))) {
            return true;
        }
        return false;
    }

    /**
     * 为餐饮节点生成局部替换候选列表（覆盖火锅、小面/早点、江湖菜、清淡等）
     */
    public List<DiningOption> resolveDiningReplacementCandidates(
            String district,
            String currentDiningName,
            String requestedFlavor,
            String slotType,
            Attraction anchorAttraction,
            TravelConstraints constraints
    ) {
        String dist = district == null ? "" : district.trim();
        String loc = anchorAttraction != null && anchorAttraction.getLocation() != null && !anchorAttraction.getLocation().isBlank()
                ? anchorAttraction.getLocation() : "106.577000,29.556000";
        boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(slotType)
                || (requestedFlavor != null && (requestedFlavor.contains("早") || requestedFlavor.contains("晨")));
        String normalizedMeal = isBreakfast ? "BREAKFAST" : ("DINNER".equalsIgnoreCase(slotType) ? "DINNER" : "LUNCH");

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);
        List<DiningVenue> candidates = new ArrayList<>();
        // 优先调用高德周边实时搜索，获取真实在营餐厅（包括茶馆、咖啡、特定风味火锅等）
        if (amapRouteService != null && amapRouteService.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, dist, normalizedMeal, requestedFlavor, proximityPriority);
        }

        // 本地候选补充（若高德结果不足3个）
        if (candidates.size() < 3) {
            List<DiningVenue> localCandidates = findCandidates(dist, loc, normalizedMeal, requestedFlavor, constraints, proximityPriority, 5);
            for (DiningVenue v : localCandidates) {
                if (candidates.stream().noneMatch(c -> c.getName().equals(v.getName()))) {
                    candidates.add(v);
                }
            }
        }

        String curr = currentDiningName == null ? "" : currentDiningName;
        List<DiningOption> options = new ArrayList<>();
        for (DiningVenue venue : candidates) {
            String nameClean = venue.getName();
            if (!nameClean.equals(curr) && !curr.contains(nameClean)) {
                options.add(toDiningOption(venue, normalizedMeal));
            }
        }

        if (options.isEmpty() && !candidates.isEmpty()) {
            for (DiningVenue venue : candidates) {
                options.add(toDiningOption(venue, normalizedMeal));
            }
        }
        return options;
    }

    private List<DiningVenue> findCandidates(String district,
                                             String anchorCoordinate,
                                             String mealType,
                                             String requestedFlavor,
                                             TravelConstraints constraints,
                                             int limit) {
        return findCandidates(district, anchorCoordinate, mealType, requestedFlavor, constraints, false, limit);
    }

    private List<DiningVenue> findCandidates(String district,
                                             String anchorCoordinate,
                                             String mealType,
                                             String requestedFlavor,
                                             TravelConstraints constraints,
                                             boolean proximityPriority,
                                             int limit) {
        List<DiningVenue> all = listAll();
        if (all.isEmpty()) return new ArrayList<>();

        String flavorNorm = normalizeFlavor(requestedFlavor);

        List<ScoredVenue> scored = new ArrayList<>();
        for (DiningVenue v : all) {
            // 餐型硬过滤（早餐 vs 正餐）
            if ("BREAKFAST".equalsIgnoreCase(mealType)) {
                if (!v.servesMeal("BREAKFAST") && !"重庆小面".equals(v.getCategory())) {
                    continue;
                }
            } else {
                if (v.servesMeal("BREAKFAST") && v.getMealTypes().size() == 1 && !"重庆小面".equals(v.getCategory())) {
                    continue; // 仅早餐专用店不作为午餐/晚餐主推荐
                }
                if (!"休闲茶歇".equals(flavorNorm) && !"特色风味小吃".equals(flavorNorm)) {
                    if ("市井茶歇早点".equals(v.getCategory()) || "特色小吃早点".equals(v.getCategory())) {
                        continue; // 茶歇早点不作为正餐主推荐
                    }
                }
            }

            int score = 0;
            // 1. 行政区匹配得分
            if (district != null && !district.isBlank()) {
                if (v.getDistrict() != null && (v.getDistrict().contains(district) || district.contains(v.getDistrict()))) {
                    score += 100;
                }
            }

            // 2. 口味与风味匹配得分
            boolean hasSpecificFlavor = requestedFlavor != null && !requestedFlavor.isBlank()
                    && !"特色美食".equals(requestedFlavor)
                    && !"本地菜优先".equals(requestedFlavor)
                    && !"地道美食".equals(requestedFlavor);

            boolean flavorMatched = flavorNorm.equals(v.getCategory())
                    || (v.getTags() != null && (v.getTags().contains(flavorNorm) || v.getTags().contains(requestedFlavor)))
                    || (v.getSpecialtyDish() != null && requestedFlavor != null && !requestedFlavor.isBlank() && v.getSpecialtyDish().contains(requestedFlavor));

            if (flavorMatched) {
                score += 150; // 强力激励明确匹配的风味
            } else if ("本地菜优先".equals(flavorNorm)) {
                if ("地道江湖菜".equals(v.getCategory()) || "传统老川菜".equals(v.getCategory()) || "重庆火锅".equals(v.getCategory())) {
                    score += 80;
                } else if ("传统养生川菜".equals(v.getCategory()) || "清淡不辣".equals(v.getCategory())) {
                    score += 70;
                } else if ("重庆小面".equals(v.getCategory())) {
                    score += "BREAKFAST".equalsIgnoreCase(mealType) ? 90 : 25; // 早餐优先小面，午晚正餐避免小面垄断
                }
            } else if ("重庆小面".equals(flavorNorm) && v.getSpecialtyDish() != null && v.getSpecialtyDish().contains("面")) {
                score += 120;
            } else if ("清淡不辣".equals(flavorNorm) && "NONE".equalsIgnoreCase(v.getSpicinessLevel())) {
                score += 120;
            } else if (hasSpecificFlavor) {
                // 用户明确指定了口味（如小面、火锅、清淡等），而该餐厅完全不属于该风味类别
                score -= 100; // 显著扣分，杜绝用户要“小面”却推荐“火锅”的倒挂现象
            }

            // 晚餐时段避免推荐早点小面（晚餐主推火锅、江湖菜、正餐热炒或温润汤锅）
            if ("DINNER".equalsIgnoreCase(mealType) && "重庆小面".equals(v.getCategory())) {
                score -= 60;
            }

            // 3. 物理距离截断与评分
            double distKm = distanceKm(anchorCoordinate, v.getLocation());
            boolean isSameDistrict = district != null && !district.isBlank()
                    && v.getDistrict() != null && (v.getDistrict().contains(district) || district.contains(v.getDistrict()));

            double maxAllowedDistKm;
            if (proximityPriority) {
                // 定时旅游/短途限时/同城一日游（如 <= 10小时、运行时动态规划、低耐受度）：
                // 推荐相邻核心商圈名店（约 2-3km），杜绝远距跨区
                maxAllowedDistKm = 2.8;
            } else {
                // 常规多日度假漫游：同区远郊大景区允许 15.0km，主城同区允许 5.0km，跨区允许 3.5km
                boolean isSuburban = district != null && (district.contains("武隆") || district.contains("大足")
                        || district.contains("江津") || district.contains("涪陵") || district.contains("巴南"));
                if (isSameDistrict) {
                    maxAllowedDistKm = isSuburban ? 15.0 : 5.0;
                } else {
                    maxAllowedDistKm = 3.5;
                }
            }

            if (distKm > maxAllowedDistKm) {
                continue; // 超出有效辐射半径，直接排除
            }

            // 距离阶梯打分：强力优先 < 600米极近步行，平滑衰减至 1.5km
            if (distKm < 0.6) {
                score += 60; // 极近步行圈（< 600米），近在咫尺强力优先
            } else if (distKm < 1.0) {
                score += 45; // 舒适商圈步程
            } else if (distKm < 1.5) {
                score += 30; // 相邻核心街区 / 起步价
            } else {
                score += 15;
            }

            // 4. 同行人约束偏好
            if (constraints != null) {
                if ("带父母".equals(constraints.getCompanions()) && (Boolean.TRUE.equals(v.getElderFriendly())
                        || "清淡不辣".equals(v.getCategory()) || "传统老川菜".equals(v.getCategory()) || "传统养生川菜".equals(v.getCategory()))) {
                    score += 25;
                }
                if ("低".equals(constraints.getWalkingTolerance()) && (v.getDistanceDesc().contains("150") || v.getDistanceDesc().contains("100") || v.getDistanceDesc().contains("80") || v.getDistanceDesc().contains("50"))) {
                    score += 20;
                }
            }

            scored.add(new ScoredVenue(v, score, distKm));
        }

        scored.sort(Comparator.comparingInt(ScoredVenue::score).reversed()
                .thenComparingDouble(ScoredVenue::distanceKm));

        List<DiningVenue> result = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            result.add(scored.get(i).venue());
        }
        return result;
    }

    private List<DiningVenue> searchAmapNearby(String coordinate, String district, String mealType, String flavor) {
        return searchAmapNearby(coordinate, district, mealType, flavor, false);
    }

    private boolean isNonMealPoi(String name, String type) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        for (String blacklisted : List.of("咖啡", "coffee", "cafe", "星巴克", "瑞幸", "库迪", "幸运咖",
                "奶茶", "茶百道", "喜茶", "奈雪", "蜜雪冰城", "霸王茶姬", "茶颜悦色", "一点点", "古茗", "coco", "沪上阿姨",
                "果汁", "水吧", "甜品", "蛋糕", "面包", "烘焙", "糕点", "好利来", "鲍师傅", "西饼",
                "水果", "果切", "便利店", "罗森", "7-eleven", "711", "全家", "零食", "超市", "鸭脖", "绝味", "紫燕")) {
            if (n.contains(blacklisted)) return true;
        }
        return t.contains("咖啡厅") || t.contains("冷饮店") || t.contains("甜品店") || t.contains("糕点店");
    }

    private String inferSpecialtyDish(String poiName, String flavor, boolean isBreakfast) {
        String n = poiName == null ? "" : poiName;
        if (isBreakfast || n.contains("面") || n.contains("抄手") || n.contains("早点") || n.contains("米线") || n.contains("牛肉面")) {
            return n + "招牌干熘小面、手工红油抄手、现磨热豆浆";
        }
        if (n.contains("火锅") || n.contains("串串") || n.contains("九宫格") || n.contains("老火锅") || (flavor != null && flavor.contains("火锅"))) {
            return n + "招牌牛油锅底、屠场鲜毛肚、时令鲜鸭肠、手打鲜虾滑";
        }
        if (n.contains("鸡杂") || n.contains("泉水鸡") || n.contains("梁山鸡") || n.contains("芋儿鸡") || n.contains("辣子鸡")) {
            return n + "招牌现炒特色鸡杂、地道风味鸡、秘制农家配菜";
        }
        if (n.contains("鱼") || n.contains("酸菜鱼") || n.contains("水煮鱼") || n.contains("鱼庄") || n.contains("美蛙")) {
            return n + "招牌地道酸菜鱼、麻辣美蛙鱼头、滑嫩鲜鱼片";
        }
        if (n.contains("烧烤") || n.contains("烤肉") || n.contains("烤鱼")) {
            return n + "炭火烤五花肉、香烤脑花、招牌万州烤鱼";
        }
        if (n.contains("快餐") || n.contains("便当") || n.contains("饭堂") || n.contains("食堂") || n.contains("套饭")) {
            return n + "特色营养套饭、双拼现炒小菜、出餐快捷";
        }
        if (n.contains("清淡") || n.contains("素食") || n.contains("养生") || n.contains("汤") || (flavor != null && (flavor.contains("清淡") || flavor.contains("素")))) {
            return n + "滋补养生瓦罐汤、清蒸时鲜、手作嫩豆花";
        }
        if (n.contains("茶") || n.contains("咖啡") || n.contains("甜品") || n.contains("小憩") || (flavor != null && (flavor.contains("茶") || flavor.contains("咖啡")))) {
            return n + "招牌现泡原盅盖碗茶、特色冷萃咖啡、手作茶点配时令干果";
        }
        return n + "店内招牌特色菜、地道老重庆风味小炒";
    }

    private List<DiningVenue> searchAmapNearby(String coordinate, String district, String mealType, String flavor, boolean proximityPriority) {
        if (amapRouteService == null || !amapRouteService.isConfigured() || coordinate == null || coordinate.isBlank()) {
            return Collections.emptyList();
        }
        try {
            // 控制在周边真实步行或极近车程范围内（800~1200米），避免跨山/跨江
            int radius = proximityPriority ? 1000 : 1500;
            boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(mealType);
            boolean isNightSnack = "NIGHT_SNACK".equalsIgnoreCase(mealType);
            boolean isTeaBreak = "TEA_BREAK".equalsIgnoreCase(mealType) || "AFTERNOON_TEA".equalsIgnoreCase(mealType)
                    || (flavor != null && (flavor.contains("茶") || flavor.contains("咖啡") || flavor.contains("休")
                    || flavor.contains("甜") || flavor.contains("糖水") || flavor.contains("冰粉") || flavor.contains("点心")));
            String keyword;
            String searchTypes;
            if (isBreakfast) {
                keyword = "小面 早点 包子 抄手 粥";
                searchTypes = "中餐厅|快餐厅";
            } else if (isNightSnack) {
                keyword = (flavor != null && !flavor.isBlank()) ? flavor : "夜市 烧烤 烤鱼 串串 宵夜";
                searchTypes = "中餐厅|风味餐厅|快餐厅";
            } else if (isTeaBreak) {
                keyword = (flavor != null && (flavor.contains("甜") || flavor.contains("糖水") || flavor.contains("点心")))
                        ? "甜品 糖水 冰粉 凉糕 蛋糕 点心 茶馆"
                        : "茶馆 茶楼 咖啡 盖碗茶";
                searchTypes = "咖啡厅|茶艺馆|冷饮店|甜品店|风味餐厅";
            } else {
                if (flavor != null && !flavor.isBlank() && !"本地菜优先".equals(flavor) && !"特色美食".equals(flavor) && !"地道美食".equals(flavor)) {
                    keyword = flavor;
                } else {
                    keyword = "川菜 炒菜 饭店 中餐 火锅 江湖菜 汤锅";
                }
                searchTypes = "中餐厅|风味餐厅|快餐厅";
            }

            var outcome = amapRouteService.searchAround(coordinate, radius, searchTypes, keyword, "重庆市");
            if (outcome.candidates() == null || outcome.candidates().isEmpty()) {
                outcome = amapRouteService.searchAround(coordinate, radius + 500, "餐饮服务", keyword, "重庆市");
            }
            if (outcome.candidates() == null || outcome.candidates().isEmpty()) {
                outcome = amapRouteService.searchAround(coordinate, 1800, "餐饮服务", isBreakfast ? "早点" : (isTeaBreak ? "茶馆" : "饭店"), "重庆市");
            }
            if (outcome.candidates() == null || outcome.candidates().isEmpty()) {
                return Collections.emptyList();
            }

            List<DiningVenue> list = new ArrayList<>();
            int idx = 1;

            for (AmapResponseNormalizer.PoiCandidate poi : outcome.candidates()) {
                if (idx > 5) break;
                String poiName = poi.name() != null ? poi.name() : "周边特色餐饮";
                // 仅非茶歇模式过滤饮品店
                if (!isTeaBreak && isNonMealPoi(poiName, poi.type())) {
                    continue;
                }

                String poiCoord = poi.coordinate() != null
                        ? poi.coordinate().longitude() + "," + poi.coordinate().latitude()
                        : coordinate;
                double distKm = distanceKm(coordinate, poiCoord);
                int distMeters = (int) Math.round(distKm * 1000);
                if (distMeters <= 0) distMeters = 80 * idx;

                String cost = isBreakfast ? "人均约 15-25 元" : (isNightSnack ? "人均约 35-60 元" : (isTeaBreak ? "人均约 25-45 元" : "人均约 45-75 元"));
                String duration = isBreakfast ? "约 30 分钟" : (isNightSnack ? "约 45 分钟" : (isTeaBreak ? "约 50 分钟" : "约 60 分钟"));

                String summary;
                String recommendationReason;
                String specialty = inferSpecialtyDish(poiName, flavor, isBreakfast);

                if (isTeaBreak) {
                    summary = "高德实时精选周边高品质茶舍咖啡馆。环境舒适安谧，适合观景小憩、品茗聊天，为后续行程蓄力。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前游览节点（步行约 " + distMeters + " 米），环境优雅舒适，适合停步小歇。"
                            : "相邻街区舒适茶歇处（约 " + distMeters + " 米），茶香清幽，氛围悠闲。";
                } else if (isBreakfast || (flavor != null && (flavor.contains("面") || flavor.contains("早")))) {
                    summary = "高德实时精选周边高分早点。结合渝悠悠小面指南：建议长辈选二两干熘或清汤牛肉面，少辣微辣，配热豆浆更暖胃。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前游览节点（步行约 " + distMeters + " 米），出餐快、零折返，适合短途行程快速就餐。"
                            : "相邻商圈特色早点（约 " + distMeters + " 米），出餐迅速、顺路便捷。";
                } else if (isNightSnack) {
                    summary = "高德实时精选周边热门宵夜小馆。出餐迅速、烟火气浓厚，适合游玩归来短憩品尝山城风味。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前位置特色宵夜（步行约 " + distMeters + " 米），无需深夜远途折返，安全便捷。"
                            : "相邻特色宵夜街区（约 " + distMeters + " 米），车程仅数分钟，烟火气浓郁。";
                } else if (flavor != null && flavor.contains("火锅")) {
                    summary = "高德实时推荐附近高口碑老火锅。支持根据同行人要求定制微辣红汤或鸳鸯骨汤，配屠场鲜毛肚与时令鲜蔬。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前游览节点（步行约 " + distMeters + " 米），无需远行折返，省时省力。"
                            : "相邻核心商圈高分老火锅（约 " + distMeters + " 米），顺路通达，锅底醇厚、菜品新鲜。";
                } else if (flavor != null && (flavor.contains("清淡") || flavor.contains("适老") || flavor.contains("家常") || flavor.contains("汤"))) {
                    summary = "高德实时精选周边温和风味餐厅。少盐少油、菜品软烂细嫩，提供养生瓦罐汤与家常时蔬，长辈就餐无负担。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前游览点（步行约 " + distMeters + " 米），平路直达，菜品清淡温和易消化，适合长辈短歇。"
                            : "相邻街区温和风味餐厅（约 " + distMeters + " 米），菜品清爽不油腻，老少皆宜。";
                } else {
                    summary = "高德地图实时智能检索推荐周边高赞地道餐厅，位置临近游览路线，出餐快捷、风味正宗。";
                    recommendationReason = distMeters <= 600
                            ? "紧邻当前位置优质餐厅（步行约 " + distMeters + " 米），顺路便捷、出餐快捷。"
                            : "相邻商圈高赞餐厅（约 " + distMeters + " 米），顺路通达，兼顾地道特色与游玩节奏。";
                }

                String spiciness = isTeaBreak ? "NONE"
                        : ((flavor != null && (flavor.contains("清淡") || flavor.contains("不辣"))) ? "NONE"
                        : ((flavor != null && flavor.contains("火锅")) ? "MEDIUM" : "MILD"));

                List<String> tags = new ArrayList<>(List.of("高德实时推荐", distMeters <= 600 ? "景区周边极近" : "相邻商圈特色",
                        isBreakfast ? "晨间早点" : (isNightSnack ? "夜市宵夜" : (isTeaBreak ? "休闲茶歇" : "周边精选"))));
                if (flavor != null && !flavor.isBlank()) {
                    tags.add(flavor);
                }

                String distanceDesc = distMeters <= 600 ? ("步行约 " + distMeters + " 米") : ("约 " + distMeters + " 米 (顺路/车程约5分钟)");

                DiningVenue venue = DiningVenue.builder()
                        .id("amap-dining-" + poi.poiId())
                        .name(poiName)
                        .district(district != null && !district.isBlank() ? district : "重庆市")
                        .category(flavor != null && !flavor.isBlank() ? flavor : (isBreakfast ? "重庆小面" : (isNightSnack ? "夜市特色" : (isTeaBreak ? "休闲茶歇" : "周边精选美食"))))
                        .mealTypes(List.of(mealType))
                        .location(poiCoord)
                        .specialtyDish(specialty)
                        .averageCost(cost)
                        .duration(duration)
                        .distanceDesc(distanceDesc)
                        .summary(summary)
                        .recommendationReason(recommendationReason)
                        .spicinessLevel(spiciness)
                        .elderFriendly(true)
                        .tone("gold")
                        .tags(tags)
                        .build();
                list.add(venue);
                idx++;
            }
            list.sort(Comparator.comparingDouble(v -> distanceKm(coordinate, v.getLocation())));
            return list;
        } catch (Exception e) {
            log.warn("[DiningVenueService] 高德周边餐饮检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private DiningOption toDiningOption(DiningVenue venue, String mealType) {
        String prefix = "BREAKFAST".equalsIgnoreCase(mealType) ? "【早餐推荐】"
                : ("NIGHT_SNACK".equalsIgnoreCase(mealType) ? "【宵夜推荐】"
                : (("TEA_BREAK".equalsIgnoreCase(mealType) || "AFTERNOON_TEA".equalsIgnoreCase(mealType) || "SNACK".equalsIgnoreCase(mealType) || "休闲茶歇".equals(venue.getCategory())) ? "【特色茶歇】"
                : ("DINNER".equalsIgnoreCase(mealType) ? "【晚餐推荐】" : "【午餐推荐】")));
        String name = venue.getName();
        if (!name.startsWith("【")) {
            name = prefix + name;
        }
        return new DiningOption(
                venue.getId(),
                name,
                venue.getSpecialtyDish(),
                venue.getCategory(),
                venue.getAverageCost(),
                venue.getDuration(),
                venue.getDistanceDesc(),
                venue.getSummary(),
                venue.getRecommendationReason(),
                venue.getTone() != null ? venue.getTone() : "gold",
                venue.getLocation()
        );
    }

    private DiningOption fallbackOption(String district, String location, String mealType, String flavor) {
        String prefix = "BREAKFAST".equalsIgnoreCase(mealType) ? "【早餐推荐】"
                : ("NIGHT_SNACK".equalsIgnoreCase(mealType) ? "【宵夜推荐】"
                : (("TEA_BREAK".equalsIgnoreCase(mealType) || "AFTERNOON_TEA".equalsIgnoreCase(mealType) || "SNACK".equalsIgnoreCase(mealType) || "休闲茶歇".equals(flavor)) ? "【特色茶歇】"
                : ("DINNER".equalsIgnoreCase(mealType) ? "【晚餐推荐】" : "【午餐推荐】")));
        boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(mealType);
        String summary = isBreakfast
                ? "周边精选老重庆早点铺，按需提供二两干熘小面、清汤抄手与热唯怡豆奶，出餐快捷。"
                : "周边精选地道川味餐厅，支持少辣或免辣清淡定制，菜品软烂可口，兼顾全家老少风味偏好。";
        String reason = "位于游览路线黄金服务半径内，步行便捷平坦，就近用餐无大幅折返负担。";
        return new DiningOption(
                "dining-fallback-default",
                prefix + "地道老重庆风味餐馆",
                isBreakfast ? "二两地道干熘小面、手工红油抄手、现磨热豆浆" : "地道特色小炒、清蒸江团鱼、石磨养生豆花",
                isBreakfast ? "重庆小面" : (flavor != null && !flavor.isBlank() ? flavor : "地道江湖菜"),
                isBreakfast ? "人均约 15-20 元" : "人均约 45-65 元",
                isBreakfast ? "约 30 分钟" : "约 60 分钟",
                "步行约 200 米",
                summary,
                reason,
                "gold",
                location
        );
    }

    private String normalizeFlavor(String requestedFlavor) {
        if (requestedFlavor == null || requestedFlavor.isBlank()) return "本地菜优先";
        if (requestedFlavor.matches(".*(火锅|九宫格|老火锅|串串).*")) return "重庆火锅";
        if (requestedFlavor.matches(".*(小面|面馆|面条|豌杂|早餐|早点).*")) return "重庆小面";
        if (requestedFlavor.matches(".*(清淡|不辣|汤锅|炖鸡|老鸭汤|蹄花).*")) return "清淡不辣";
        if (requestedFlavor.matches(".*(茶|咖啡|小憩|歇脚|观景茶|下午茶|甜点|甜食|糖水|冰粉|凉糕|糍粑|点心|蛋糕|烘焙).*")) return "休闲茶歇";
        if (requestedFlavor.matches(".*(小吃|夜市|烧烤|烤脑花).*")) return "特色风味小吃";
        if (requestedFlavor.matches(".*(江湖菜|川菜|泉水鸡|毛血旺|酸菜鱼|辣子鸡).*")) return "地道江湖菜";
        return "本地菜优先";
    }

    public static double distanceKm(String coord1, String coord2) {
        if (coord1 == null || coord2 == null || !coord1.contains(",") || !coord2.contains(",")) return 5.0;
        try {
            String[] p1 = coord1.split(",");
            String[] p2 = coord2.split(",");
            double lon1 = Math.toRadians(Double.parseDouble(p1[0].trim()));
            double lat1 = Math.toRadians(Double.parseDouble(p1[1].trim()));
            double lon2 = Math.toRadians(Double.parseDouble(p2[0].trim()));
            double lat2 = Math.toRadians(Double.parseDouble(p2[1].trim()));
            double dlat = lat2 - lat1;
            double dlon = lon2 - lon1;
            double a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
                    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dlon / 2) * Math.sin(dlon / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return 6371.0 * c;
        } catch (Exception e) {
            return 5.0;
        }
    }

    @SuppressWarnings("unchecked")
    private DiningVenue mapToVenue(Map<String, Object> map) {
        List<String> mealTypes = new ArrayList<>();
        Object mt = map.get("meal_types");
        if (mt == null) mt = map.get("mealTypes");
        if (mt instanceof List<?> l) {
            for (Object o : l) mealTypes.add(String.valueOf(o));
        } else if (mt instanceof String s && !s.isBlank()) {
            if (s.startsWith("[")) {
                try {
                    mealTypes = objectMapper.readValue(s, new TypeReference<>() {});
                } catch (Exception ignored) {}
            } else {
                mealTypes = Arrays.asList(s.split(","));
            }
        }

        List<String> tags = new ArrayList<>();
        Object tg = map.get("tags");
        if (tg instanceof List<?> l) {
            for (Object o : l) tags.add(String.valueOf(o));
        } else if (tg instanceof String s && !s.isBlank() && s.startsWith("[")) {
            try {
                tags = objectMapper.readValue(s, new TypeReference<>() {});
            } catch (Exception ignored) {}
        }

        boolean elder = true;
        Object ef = map.get("elder_friendly");
        if (ef == null) ef = map.get("elderFriendly");
        if (ef instanceof Boolean b) elder = b;
        else if (ef instanceof Number n) elder = n.intValue() == 1;

        return DiningVenue.builder()
                .id(String.valueOf(map.getOrDefault("id", "")))
                .name(String.valueOf(map.getOrDefault("name", "")))
                .district(String.valueOf(map.getOrDefault("district", "")))
                .category(String.valueOf(map.getOrDefault("category", "")))
                .mealTypes(mealTypes)
                .location(String.valueOf(map.getOrDefault("location", "")))
                .specialtyDish(String.valueOf(map.getOrDefault("specialty_dish", map.getOrDefault("specialtyDish", ""))))
                .averageCost(String.valueOf(map.getOrDefault("average_cost", map.getOrDefault("averageCost", ""))))
                .duration(String.valueOf(map.getOrDefault("duration", "约 60 分钟")))
                .distanceDesc(String.valueOf(map.getOrDefault("distance_desc", map.getOrDefault("distanceDesc", "步行约 180 米"))))
                .summary(String.valueOf(map.getOrDefault("summary", "")))
                .recommendationReason(String.valueOf(map.getOrDefault("recommendation_reason", map.getOrDefault("recommendationReason", ""))))
                .spicinessLevel(String.valueOf(map.getOrDefault("spiciness_level", map.getOrDefault("spicinessLevel", "MILD"))))
                .elderFriendly(elder)
                .tone(String.valueOf(map.getOrDefault("tone", "gold")))
                .tags(tags)
                .build();
    }

    private record ScoredVenue(DiningVenue venue, int score, double distanceKm) {}
}

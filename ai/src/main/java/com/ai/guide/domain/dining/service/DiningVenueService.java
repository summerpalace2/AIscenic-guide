package com.ai.guide.domain.dining.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.ai.guide.domain.planner.engine.DynamicGeoCentroidResolver;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
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
    private final GeoRouteGatewayPort geoRouteGateway;
    private final ObjectMapper objectMapper;
    private TaxonomyPort taxonomy = new TaxonomyProvider();

    @Autowired(required = false)
    public void setTaxonomy(TaxonomyPort taxonomy) {
        if (taxonomy != null) this.taxonomy = taxonomy;
    }
    private final List<DiningVenue> inMemorySeedCache = new CopyOnWriteArrayList<>();

    @Autowired
    public DiningVenueService(@Autowired(required = false) JdbcTemplate jdbcTemplate,
                              @Autowired(required = false) GeoRouteGatewayPort geoRouteGateway,
                              @Autowired(required = false) ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.geoRouteGateway = geoRouteGateway;
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
        String district = anchorAttraction == null ? "" : anchorAttraction.getDistrict();
        String loc = resolveAnchorCoordinate(anchorAttraction, constraints);
        String dietPref = constraints == null ? "本地菜优先" : constraints.getDietPreference();
        if (dietPref == null || dietPref.isBlank() || "未提供".equals(dietPref)) {
            dietPref = "本地菜优先";
        }

        String normalizedMeal = "BREAKFAST".equalsIgnoreCase(slotType) ? "BREAKFAST"
                : ("DINNER".equalsIgnoreCase(slotType) ? "DINNER" : "LUNCH");

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);

        List<DiningVenue> candidates = new ArrayList<>();
        // 1. 若配置了地理网关且有定位，优先通过 LBS 检索当前景点周边的真实在营餐厅（解决跨山跨江与虚假数据问题）
        if (geoRouteGateway != null && geoRouteGateway.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, district, normalizedMeal, dietPref, proximityPriority, destination(constraints));
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
        String loc = resolveAnchorCoordinate(anchorAttraction, constraints);
        boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(slotType);
        boolean isTeaBreak = "TEA_BREAK".equalsIgnoreCase(slotType) || "AFTERNOON_TEA".equalsIgnoreCase(slotType);
        boolean isNightSnack = "NIGHT_SNACK".equalsIgnoreCase(slotType);
        boolean isDinner = "DINNER".equalsIgnoreCase(slotType);
        boolean isLunch = "LUNCH".equalsIgnoreCase(slotType);

        if (!isBreakfast && !isTeaBreak && !isNightSnack && !isDinner && !isLunch) {
            isBreakfast = (requestedFlavor != null && (requestedFlavor.contains("早") || requestedFlavor.contains("晨")));
            isTeaBreak = "休闲茶歇".equals(requestedFlavor)
                    || (requestedFlavor != null && (requestedFlavor.contains("茶") || requestedFlavor.contains("甜") || requestedFlavor.contains("咖啡") || requestedFlavor.contains("糖水") || requestedFlavor.contains("品茗") || requestedFlavor.contains("小憩")));
            isNightSnack = (requestedFlavor != null && (requestedFlavor.contains("宵夜") || requestedFlavor.contains("夜市")));
            isDinner = (requestedFlavor != null && requestedFlavor.contains("晚"));
        }

        String normalizedMeal = isBreakfast ? "BREAKFAST"
                : (isTeaBreak ? "TEA_BREAK"
                : (isNightSnack ? "NIGHT_SNACK"
                : (isDinner ? "DINNER" : "LUNCH")));

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);
        List<DiningVenue> candidates = new ArrayList<>();
        // 优先调用地理网关周边实时搜索，获取真实在营餐厅（包括茶馆、咖啡、特定风味火锅等）
        if (geoRouteGateway != null && geoRouteGateway.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, dist, normalizedMeal, requestedFlavor, proximityPriority, destination(constraints));
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

        return options;
    }

    /**
     * 高德实景搜索优先的美食/茶歇/甜品检索方法（高德实景 POI 优先 -> 本地精选知识库兜底）
     * 解决无网络或无 Key 时的降级，同时确保有高德配置时优先检索 POI 候选，杜绝硬编码。
     */
    public List<DiningVenue> searchLiveDiningOrTea(String district, String coordinate, String keyword, String category, int limit) {
        return searchLiveDiningOrTea(district,coordinate,keyword,category,limit, "");
    }

    public List<DiningVenue> searchLiveDiningOrTea(String district, String coordinate, String keyword, String category, int limit, String city) {
        String dist = district == null ? "" : district.trim();
        String kw = keyword == null ? "" : keyword.trim();
        int maxResults = limit > 0 ? limit : 3;

        boolean isTea = "TEA_BREAK".equalsIgnoreCase(category) || "AFTERNOON_TEA".equalsIgnoreCase(category);
        boolean isSweet = "SWEET".equalsIgnoreCase(category);
        boolean isHotpot = "HOTPOT".equalsIgnoreCase(category);
        boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(category);

        // 1. 若地理网关已配置，优先进行高德实景 POI 搜索
        if (geoRouteGateway != null && geoRouteGateway.isConfigured()) {
            try {
                // 1.1 若存在锚点坐标，优先以坐标为中心进行真实周边搜索
                if (coordinate != null && !coordinate.isBlank() && !coordinate.contains("null")) {
                    String mealType = isBreakfast ? "BREAKFAST" : (isTea ? "TEA_BREAK" : (isSweet ? "TEA_BREAK" : ("DINNER".equalsIgnoreCase(category) ? "DINNER" : "LUNCH")));
                    List<DiningVenue> nearby = searchAmapNearby(coordinate, dist, mealType, kw, false, city);
                    if (nearby != null && !nearby.isEmpty()) {
                        return nearby.stream().limit(maxResults).toList();
                    }
                }

                // 1.2 若无坐标或周边无结果，使用行政区 + 关键词进行 POI 检索
                var flavorPolicy = flavorPoiPolicy(kw);
                String query = flavorPolicy != null && flavorPolicy.searchKeyword() != null ? flavorPolicy.searchKeyword() : kw;
                String searchKeywords = (dist + " " + (query.isBlank() ? "餐厅" : query)).trim();
                String searchTypes = flavorPolicy != null && !flavorPolicy.poiTypeKeywords().isEmpty()
                        ? String.join("|", flavorPolicy.poiTypeKeywords()) : "05";

                String searchCity = city == null ? "" : city;
                var poiOutcome = geoRouteGateway.searchPoi(searchKeywords.trim(), searchCity, searchTypes);
                if (poiOutcome != null && poiOutcome.status() == com.ai.guide.domain.planner.model.OutcomeStatus.SUCCESS
                        && poiOutcome.candidates() != null && !poiOutcome.candidates().isEmpty()) {
                    List<DiningVenue> liveVenues = new ArrayList<>();
                    int idx = 1;
                    for (AmapResponseNormalizer.PoiCandidate poi : poiOutcome.candidates()) {
                        if (idx > maxResults) break;
                        if (!validDiningPoi(poi) || !matchesFlavorPoiPolicy(poi, flavorPolicy)) continue;
                        String poiName = poi.name() != null ? poi.name() : "周边特色在营商户";
                        String poiCoord = poi.coordinate().longitude() + "," + poi.coordinate().latitude();
                        String addr = (poi.address() != null && !poi.address().isBlank()) ? poi.address() : (dist + "核心商圈");
                        String cost = "人均消费待确认";
                        String duration = isBreakfast ? "约 30 分钟"
                                : (isSweet ? "约 30 分钟"
                                : (isTea ? "约 50 分钟" : "约 60 分钟"));
                        String specialty = inferSpecialtyDish(poiName, kw, isBreakfast);

                        String summary = "高德 POI 搜索结果。地址：" + addr + "。营业状态与菜单需确认。";
                        String reason = "位于 " + addr + "；环境、无障碍设施和路线需确认。";

                        List<String> tags = new ArrayList<>(List.of("高德POI", "营业状态待确认", dist));
                        if (!kw.isBlank()) tags.add(kw);

                        liveVenues.add(DiningVenue.builder()
                                .id("amap-live-" + poi.poiId())
                                .name(poiName)
                                .district(dist)
                                .category(isTea ? "休闲茶歇" : (isSweet ? "特色甜品" : (isHotpot ? "火锅" : (isBreakfast ? taxonomy.getCityProfile(city).defaultBreakfast() : "特色美食"))))
                                .mealTypes(List.of(isBreakfast ? "BREAKFAST" : (isTea ? "TEA_BREAK" : (isSweet ? "TEA_BREAK" : (isHotpot ? "DINNER" : "LUNCH")))))
                                .location(poiCoord)
                                .specialtyDish(specialty)
                                .averageCost(cost)
                                .duration(duration)
                                .distanceDesc(addr)
                                .summary(summary)
                                .recommendationReason(reason)
                                .spicinessLevel("UNKNOWN")
                                .elderFriendly(null)
                                .tone("gold")
                                .tags(tags)
                                .build());
                        idx++;
                    }
                    if (!liveVenues.isEmpty()) {
                        log.info("[DiningVenueService] 高德实景检索成功返回 {} 条鲜活商户数据 (query={})", liveVenues.size(), searchKeywords);
                        return liveVenues;
                    }
                }
            } catch (Exception e) {
                log.warn("[DiningVenueService] 高德实景检索异常，准备安全降级到本地精选知识库: {}", e.getMessage());
            }
        }

        // 2. 降级方案：从本地数据库/种子库检索契合的商户（绝不使用硬编码字符串）
        if (!PlanAdjustmentHelper.isValidCoordinate(coordinate)) return List.of();
        List<DiningVenue> allVenues = listAll().stream()
                .filter(v -> distanceKm(coordinate,v.getLocation()) <= 5.0).toList();
        List<DiningVenue> filtered = allVenues.stream()
                .filter(v -> (dist.equals(v.getDistrict()) || (v.getDistrict() != null && v.getDistrict().contains(dist)))
                        && matchesPreference(v, isTea, isSweet, isHotpot, isBreakfast, kw))
                .limit(maxResults)
                .toList();



        return filtered;
    }

    private boolean matchesPreference(DiningVenue v, boolean isTea, boolean isSweet, boolean isHotpot, boolean isBreakfast, String kw) {
        String slot = isBreakfast ? "BREAKFAST" : (isTea || isSweet ? "TEA_BREAK" : "LUNCH");
        return v.servesMeal(slot) && (kw == null || kw.isBlank()
                || taxonomy.getHints("genericFlavors").contains(kw) || matchesFlavor(v, kw));
    }

    private boolean matchesFlavor(DiningVenue venue, String flavor) {
        if (flavor == null || flavor.isBlank()) return false;
        if (taxonomy.getHints("nonSpicyFlavors").contains(flavor)
                && "NONE".equalsIgnoreCase(venue.getSpicinessLevel())) return true;
        String searchable = String.join(" ", Objects.toString(venue.getName(), ""),
                Objects.toString(venue.getCategory(), ""), Objects.toString(venue.getSpecialtyDish(), ""),
                venue.getTags() == null ? "" : String.join(" ", venue.getTags()));
        if (searchable.contains(flavor)) return true;
        // Expand only a canonical taxonomy label. A specific dish or brand must keep its identity.
        return taxonomy.getFlavorAliases().stream().filter(alias -> alias.label().equals(flavor))
                .flatMap(alias -> alias.keywords().stream()).anyMatch(searchable::contains);
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
        if (!PlanAdjustmentHelper.isValidCoordinate(anchorCoordinate)) return List.of();
        List<DiningVenue> all = listAll();
        if (all.isEmpty()) return new ArrayList<>();

        String flavorNorm = normalizeFlavor(requestedFlavor, district);

        List<ScoredVenue> scored = new ArrayList<>();
        for (DiningVenue v : all) {
            // 餐型硬过滤（早餐 vs 正餐）
            if (!v.servesMeal(mealType)) continue;
            if (("LUNCH".equalsIgnoreCase(mealType) || "DINNER".equalsIgnoreCase(mealType))
                    && taxonomy.getHints("nonMealCategories").contains(v.getCategory())) continue;

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

            boolean flavorMatched = matchesFlavor(v, flavorNorm);
            if (hasSpecificFlavor && !taxonomy.getHints("genericFlavors").contains(requestedFlavor) && !flavorMatched) continue;

            if (flavorMatched) {
                score += 150; // 强力激励明确匹配的风味
            } else if (taxonomy.getHints("genericFlavors").contains(flavorNorm)) {
                var profile = taxonomy.getCityProfile(destination(constraints));
                if (profile.signatureDishes().stream().anyMatch(dish -> matchesFlavor(v, dish))) score += 80;
            } else if (hasSpecificFlavor) {
                // 用户明确指定了口味（如小面、火锅、清淡等），而该餐厅完全不属于该风味类别
                score -= 100; // 显著扣分，杜绝用户要“小面”却推荐“火锅”的倒挂现象
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
                boolean isSuburban = district != null && taxonomy.getHints("suburbanDistricts").stream().anyMatch(district::contains);
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
                if ("带父母".equals(constraints.getCompanions()) && Boolean.TRUE.equals(v.getElderFriendly())) {
                    score += 25;
                }
                if ("低".equals(constraints.getWalkingTolerance()) && distKm <= 0.5) {
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
        String category=type == null ? "" : type;
        return List.of("咖啡厅","冷饮店","甜品店","糕点店").stream().anyMatch(category::contains);
    }

    private boolean validDiningPoi(AmapResponseNormalizer.PoiCandidate poi) {
        if (poi == null || poi.coordinate() == null || poi.name() == null || poi.name().isBlank()) return false;
        if (!PlanAdjustmentHelper.isValidCoordinate(poi.coordinate().longitude()+","+poi.coordinate().latitude())) return false;
        return poi.typecode() == null || poi.typecode().isBlank() || poi.typecode().startsWith("05");
    }

    private TaxonomyPort.FlavorAlias flavorPoiPolicy(String flavor) {
        return taxonomy.getFlavorAliases().stream()
                .filter(alias -> alias.label().equals(flavor) || alias.keywords().contains(flavor))
                .findFirst().orElse(null);
    }

    private boolean matchesFlavorPoiPolicy(AmapResponseNormalizer.PoiCandidate poi, TaxonomyPort.FlavorAlias policy) {
        return policy == null || policy.poiTypeKeywords().isEmpty()
                || (poi.type() != null && policy.poiTypeKeywords().stream().anyMatch(poi.type()::contains));
    }

    private boolean hasUsableDiningPois(com.ai.guide.domain.planner.model.PoiOutcome outcome, TaxonomyPort.FlavorAlias policy) {
        return outcome != null && outcome.status() == com.ai.guide.domain.planner.model.OutcomeStatus.SUCCESS
                && outcome.candidates() != null && outcome.candidates().stream()
                .anyMatch(poi -> validDiningPoi(poi) && matchesFlavorPoiPolicy(poi, policy));
    }

    private String inferSpecialtyDish(String poiName, String flavor, boolean isBreakfast) {
        return "菜单与招牌菜待商户确认";
    }

    private List<DiningVenue> searchAmapNearby(String coordinate, String district, String mealType, String flavor, boolean proximityPriority) {
        return searchAmapNearby(coordinate,district,mealType,flavor,proximityPriority, "");
    }

    private List<DiningVenue> searchAmapNearby(String coordinate, String district, String mealType, String flavor, boolean proximityPriority, String city) {
        if (geoRouteGateway == null || !geoRouteGateway.isConfigured() || coordinate == null || coordinate.isBlank()) {
            return Collections.emptyList();
        }
        try {
            // 控制在周边真实步行或极近车程范围内（800~1200米），避免跨山/跨江
            int radius = proximityPriority ? 1000 : 1500;
            boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(mealType);
            boolean isNightSnack = "NIGHT_SNACK".equalsIgnoreCase(mealType);
            boolean isTeaBreak = "TEA_BREAK".equalsIgnoreCase(mealType) || "AFTERNOON_TEA".equalsIgnoreCase(mealType);
            String keyword;
            String searchTypes;
            if (isBreakfast) {
                keyword = flavor != null && !flavor.isBlank() && !"本地菜优先".equals(flavor)
                        ? flavor : taxonomy.getCityProfile(city).defaultBreakfast();
                searchTypes = "中餐厅|快餐厅";
            } else if (isNightSnack) {
                keyword = (flavor != null && !flavor.isBlank()) ? flavor : "餐厅";
                searchTypes = "中餐厅|风味餐厅|快餐厅";
            } else if (isTeaBreak) {
                keyword = flavor != null && !flavor.isBlank() ? flavor : "餐饮";
                searchTypes = "咖啡厅|茶艺馆|冷饮店|甜品店|风味餐厅";
            } else {
                if (flavor != null && !flavor.isBlank() && !"本地菜优先".equals(flavor) && !"特色美食".equals(flavor) && !"地道美食".equals(flavor)) {
                    keyword = flavor;
                } else {
                    keyword = "餐厅";
                }
                searchTypes = "中餐厅|风味餐厅|快餐厅";
            }

            var flavorPolicy = flavorPoiPolicy(flavor);
            if (flavorPolicy != null) {
                if (flavorPolicy.searchKeyword() != null) keyword = flavorPolicy.searchKeyword();
                if (!flavorPolicy.poiTypeKeywords().isEmpty()) searchTypes = String.join("|", flavorPolicy.poiTypeKeywords());
            }
            String searchCity = city == null ? "" : city;
            var outcome = geoRouteGateway.searchAround(coordinate, radius, searchTypes, keyword, searchCity);
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                outcome = geoRouteGateway.searchAround(coordinate, radius + 500, "餐饮服务", keyword, searchCity);
            }
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                outcome = geoRouteGateway.searchAround(coordinate, 1800, "餐饮服务", keyword, searchCity);
            }
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                return Collections.emptyList();
            }

            List<DiningVenue> list = new ArrayList<>();
            int idx = 1;

            for (AmapResponseNormalizer.PoiCandidate poi : outcome.candidates()) {
                if (idx > 5) break;
                if (!validDiningPoi(poi) || !matchesFlavorPoiPolicy(poi, flavorPolicy)) continue;
                String poiName = poi.name() != null ? poi.name() : "周边特色餐饮";
                // 仅非茶歇模式过滤饮品店
                if (!isTeaBreak && (poi.typecode() != null && !poi.typecode().isBlank()
                        ? taxonomy.getHints("mealExcludedTypes").stream().anyMatch(poi.typecode()::startsWith)
                        : isNonMealPoi(poiName, poi.type()))) {
                    continue;
                }

                String poiCoord = poi.coordinate().longitude() + "," + poi.coordinate().latitude();
                double distKm = distanceKm(coordinate, poiCoord);
                int distMeters = (int) Math.round(distKm * 1000);


                String cost = "人均消费待确认";
                String specialty = inferSpecialtyDish(poiName,flavor,isBreakfast);
                String duration = isBreakfast ? "约 30 分钟" : (isNightSnack ? "约 45 分钟" : (isTeaBreak ? "约 50 分钟" : "约 60 分钟"));

                String summary = "高德 POI 搜索结果；营业状态、菜单和消费需商户确认。";
                String recommendationReason = "距离当前锚点的直线距离约 " + distMeters + " 米，实际路线及无障碍设施待确认。";

                String spiciness = "UNKNOWN";

                List<String> tags = new ArrayList<>(List.of("高德实时推荐", distMeters <= 600 ? "景区周边极近" : "相邻商圈特色",
                        isBreakfast ? "晨间早点" : (isNightSnack ? "夜市宵夜" : (isTeaBreak ? "休闲茶歇" : "周边精选"))));
                if (flavor != null && !flavor.isBlank()) {
                    tags.add(flavor);
                }

                String distanceDesc = distMeters <= 600 ? ("直线距离约 " + distMeters + " 米，路线待确认") : ("直线距离约 " + distMeters + " 米，路线待确认");

                DiningVenue venue = DiningVenue.builder()
                        .id("amap-dining-" + poi.poiId())
                        .name(poiName)
                        .district(district == null ? "" : district)
                        .category(flavor != null && !flavor.isBlank() ? flavor : (isBreakfast ? taxonomy.getCityProfile(city).defaultBreakfast() : (isNightSnack ? "夜市特色" : (isTeaBreak ? "休闲茶歇" : "周边精选美食"))))
                        .mealTypes(List.of(mealType))
                        .location(poiCoord)
                        .specialtyDish(specialty)
                        .averageCost(cost)
                        .duration(duration)
                        .distanceDesc(distanceDesc)
                        .summary(summary)
                        .recommendationReason(recommendationReason)
                        .spicinessLevel(spiciness)
                        .elderFriendly(null)
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
        boolean isTeaVenue = "TEA_BREAK".equalsIgnoreCase(mealType) || "AFTERNOON_TEA".equalsIgnoreCase(mealType)
                || "SNACK".equalsIgnoreCase(mealType) || "休闲茶歇".equals(venue.getCategory())
                || (venue.getCategory() != null && venue.getCategory().contains("茶"))
                || (venue.getName() != null && (venue.getName().contains("茶") || venue.getName().contains("咖啡") || venue.getName().contains("糖水") || venue.getName().contains("冰粉")));
        String prefix = "BREAKFAST".equalsIgnoreCase(mealType) ? "【早餐推荐】"
                : ("NIGHT_SNACK".equalsIgnoreCase(mealType) ? "【宵夜推荐】"
                : (isTeaVenue ? "【特色茶歇】"
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
        return new DiningOption("dining-fallback-default", "附近用餐（待确认）", "菜单待确认",
                flavor == null ? "餐饮" : flavor, "消费待确认", "约 60 分钟", "距离待确认",
                "尚未找到可核验的周边精选餐厅，请确认用餐地点后安排。", "餐厅与路线待确认", "gold", null);
    }

    private String normalizeFlavor(String requestedFlavor) { return normalizeFlavor(requestedFlavor,null); }
    private String normalizeFlavor(String requestedFlavor, String cityOrDistrict) {
        return requestedFlavor == null || requestedFlavor.isBlank() ? "本地菜优先" : requestedFlavor.trim();
    }

    public static double distanceKm(String coord1, String coord2) {
        if (coord1 == null || coord2 == null || !coord1.contains(",") || !coord2.contains(",")) return Double.POSITIVE_INFINITY;
        try {
            String[] p1 = coord1.split(",");
            String[] p2 = coord2.split(",");
            if (p1.length != 2 || p2.length != 2 || !PlanAdjustmentHelper.isValidCoordinate(coord1)
                    || !PlanAdjustmentHelper.isValidCoordinate(coord2)) return Double.POSITIVE_INFINITY;
            double lon1 = Math.toRadians(Double.parseDouble(p1[0].trim()));
            double lat1 = Math.toRadians(Double.parseDouble(p1[1].trim()));
            double lon2 = Math.toRadians(Double.parseDouble(p2[0].trim()));
            double lat2 = Math.toRadians(Double.parseDouble(p2[1].trim()));
            double dlat = lat2 - lat1;
            double dlon = lon2 - lon1;
            double a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
                    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dlon / 2) * Math.sin(dlon / 2);
            a = Math.max(0.0, Math.min(1.0, a));
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return 6371.0 * c;
        } catch (Exception e) {
            return Double.POSITIVE_INFINITY;
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
                .distanceDesc(String.valueOf(map.getOrDefault("distance_desc", map.getOrDefault("distanceDesc", "距离待确认"))))
                .summary(String.valueOf(map.getOrDefault("summary", "")))
                .recommendationReason(String.valueOf(map.getOrDefault("recommendation_reason", map.getOrDefault("recommendationReason", ""))))
                .spicinessLevel(String.valueOf(map.getOrDefault("spiciness_level", map.getOrDefault("spicinessLevel", "MILD"))))
                .elderFriendly(elder)
                .tone(String.valueOf(map.getOrDefault("tone", "gold")))
                .tags(tags)
                .build();
    }

    private static String destination(TravelConstraints constraints) {
        return constraints == null || constraints.getDestination() == null ? "" : constraints.getDestination().trim();
    }

    private String resolveAnchorCoordinate(Attraction anchorAttraction, TravelConstraints constraints) {
        if (anchorAttraction != null && PlanAdjustmentHelper.isValidCoordinate(anchorAttraction.getLocation()))
            return anchorAttraction.getLocation();
        return new DynamicGeoCentroidResolver(geoRouteGateway).resolveCityCenter(destination(constraints));
    }

    private record ScoredVenue(DiningVenue venue, int score, double distanceKm) {}
}


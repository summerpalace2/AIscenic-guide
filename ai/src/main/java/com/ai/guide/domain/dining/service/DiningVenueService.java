package com.ai.guide.domain.dining.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.model.DiningOption;
import com.ai.guide.domain.dining.gateway.DiningVenueCatalogPort;
import com.ai.guide.domain.dining.model.DiningSearchContext;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinateRules;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.service.GeoCityCenterResolver;
import com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 美食餐饮智能规划服务（本地知识库检索 + 高德 LBS 动态周边兜底）
 */
@Slf4j
@Service
public class DiningVenueService {

    private final DiningVenueCatalogPort catalog;
    private final GeoRouteGatewayPort geoRouteGateway;
    private DiningTaxonomyPort taxonomy = DiningTaxonomyPort.empty();

    @Autowired(required = false)
    public void setTaxonomy(DiningTaxonomyPort taxonomy) {
        if (taxonomy != null) this.taxonomy = taxonomy;
    }

    @Autowired
    public DiningVenueService(DiningVenueCatalogPort catalog,
                              GeoRouteGatewayPort geoRouteGateway,
                              DiningTaxonomyPort taxonomy) {
        this.catalog = catalog;
        this.geoRouteGateway = geoRouteGateway;
        if (taxonomy != null) this.taxonomy = taxonomy;
    }

    /** Convenience constructor retained for isolated domain tests and local fixtures. */
    public DiningVenueService(GeoRouteGatewayPort geoRouteGateway, ObjectMapper objectMapper) {
        this(null, geoRouteGateway, DiningTaxonomyPort.empty());
    }

    /** Convenience constructor retained for isolated domain tests and local fixtures. */
    public DiningVenueService(DiningVenueCatalogPort catalog, GeoRouteGatewayPort geoRouteGateway, ObjectMapper objectMapper) {
        this(catalog, geoRouteGateway, DiningTaxonomyPort.empty());
    }

    public DiningVenueService() {
        this(null, null, DiningTaxonomyPort.empty());
    }

    public List<DiningVenue> listAll() {
        if (catalog == null) return List.of();
        try {
            List<DiningVenue> venues = catalog.findAll();
            return venues == null ? List.of() : List.copyOf(venues);
        } catch (Exception e) {
            log.debug("[DiningVenueService] 餐饮目录暂不可用: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据邻近景区与偏好匹配单餐最佳周边餐饮推荐（生成行程节点）
     */
    public DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, DiningSearchContext constraints) {
        return resolveNearbyDining(anchorAttraction, slotType, constraints, java.util.Collections.emptySet());
    }

    /**
     * 根据邻近景区实体与用户偏好，匹配最佳周边餐饮推荐（支持排除已安排餐厅，防止同日午晚餐重复推荐同一家店）
     */
    public DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, DiningSearchContext constraints, java.util.Set<String> excludedNames) {
        String district = anchorAttraction == null ? "" : anchorAttraction.getDistrict();
        String loc = resolveAnchorCoordinate(anchorAttraction, constraints);
        String dietPref = constraints == null ? defaultDiningPreference() : constraints.dietPreference();
        if (dietPref == null || dietPref.isBlank() || taxonomy.getHints("missingPreferenceValues").contains(dietPref)) {
            dietPref = defaultDiningPreference();
        }

        String normalizedMeal = normalizeMealSlot(slotType);

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);

        List<DiningVenue> candidates = new ArrayList<>();
        // 1. 若配置了地理网关且有定位，优先通过 LBS 检索当前景点周边的真实在营餐厅（解决跨山跨江与虚假数据问题）
        if (geoRouteGateway != null && geoRouteGateway.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, district, normalizedMeal, dietPref, proximityPriority, destination(constraints));
        }

        // 2. 高德未配置或无结果时，回退到本地精选知识库
        if (candidates.isEmpty()) {
            candidates = findCandidates(district, loc, normalizedMeal, dietPref, constraints, proximityPriority,
                    configuredPositiveInt("diningFallbackCandidateLimit"));
        }

        if (candidates.isEmpty()) {
            // 最底兜底
            return null;
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
    public boolean isProximityPriority(DiningSearchContext constraints, Attraction anchor) {
        if (anchor != null && anchor.getId() != null && anchor.getId().startsWith("amap-")) {
            return true; // 来自运行时动态高德规划的锚点，属于局部紧凑动线，必须严格就近
        }
        if (constraints == null) return false;
        if (constraints.timeBudgetMinutes() > 0 && constraints.timeBudgetMinutes() <= diningPolicy().proximityTimeBudgetMinutes()) {
            return true; // 10 小时以内的一日游/短途限时规划（统一覆盖 6 小时、8 小时、10 小时同城一日游）
        }
        if (constraints.durationDays() <= 1 && constraints.timeBudgetMinutes() > 0) {
            return true;
        }
        if ("低".equals(constraints.walkingTolerance())) {
            return true; // 低步行耐受度，严格就近
        }
        String prompt = constraints.rawPrompt();
        if (matchesTaxonomyHint(prompt, "diningProximityPromptTerms")) {
            return true;
        }
        return false;
    }

    /**
     * 为餐饮节点生成局部替换候选列表（覆盖用户指定风味、早点和清淡类需求）
     */
    public List<DiningOption> resolveDiningReplacementCandidates(
            String district,
            String currentDiningName,
            String requestedFlavor,
            String slotType,
            Attraction anchorAttraction,
            DiningSearchContext constraints
    ) {
        String dist = district == null ? "" : district.trim();
        String loc = resolveAnchorCoordinate(anchorAttraction, constraints);
        boolean isBreakfast = "BREAKFAST".equalsIgnoreCase(slotType);
        boolean isTeaBreak = "TEA_BREAK".equalsIgnoreCase(slotType) || "AFTERNOON_TEA".equalsIgnoreCase(slotType);
        boolean isNightSnack = "NIGHT_SNACK".equalsIgnoreCase(slotType);
        boolean isDinner = "DINNER".equalsIgnoreCase(slotType);
        boolean isLunch = "LUNCH".equalsIgnoreCase(slotType);

        if (!isBreakfast && !isTeaBreak && !isNightSnack && !isDinner && !isLunch) {
            isBreakfast = matchesTaxonomyHint(requestedFlavor, "diningBreakfastFlavorTerms");
            isTeaBreak = isConfiguredTeaBreakFlavor(requestedFlavor);
            isNightSnack = matchesTaxonomyHint(requestedFlavor, "diningNightSnackFlavorTerms");
            isDinner = matchesTaxonomyHint(requestedFlavor, "diningDinnerFlavorTerms");
        }

        String normalizedMeal = isBreakfast ? "BREAKFAST"
                : (isTeaBreak ? "TEA_BREAK"
                : (isNightSnack ? "NIGHT_SNACK"
                : (isDinner ? "DINNER" : "LUNCH")));

        boolean proximityPriority = isProximityPriority(constraints, anchorAttraction);
        List<DiningVenue> candidates = new ArrayList<>();
        // 优先调用地理网关周边实时搜索，获取真实在营餐厅（包括茶馆、咖啡和用户指定风味餐饮）
        if (geoRouteGateway != null && geoRouteGateway.isConfigured() && loc != null && !loc.isBlank()) {
            candidates = searchAmapNearby(loc, dist, normalizedMeal, requestedFlavor, proximityPriority, destination(constraints));
        }

        // 本地候选补充（若高德结果不足3个）
        if (candidates.size() < configuredPositiveInt("diningReplacementSupplementThreshold")) {
            List<DiningVenue> localCandidates = findCandidates(dist, loc, normalizedMeal, requestedFlavor, constraints, proximityPriority,
                    configuredPositiveInt("diningReplacementSupplementLimit"));
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
        int maxResults = limit > 0 ? limit : configuredPositiveInt("diningDefaultResultLimit");

        String mealSlot = resolveMealSlot(category);
        boolean isBreakfast = "BREAKFAST".equals(mealSlot);

        // 1. 若地理网关已配置，优先进行高德实景 POI 搜索
        if (geoRouteGateway != null && geoRouteGateway.isConfigured()) {
            try {
                // 1.1 若存在锚点坐标，优先以坐标为中心进行真实周边搜索
                if (coordinate != null && !coordinate.isBlank() && !coordinate.contains("null")) {
                    String mealType = mealSlot;
                    List<DiningVenue> nearby = searchAmapNearby(coordinate, dist, mealType, kw, false, city);
                    if (nearby != null && !nearby.isEmpty()) {
                        return nearby.stream().limit(maxResults).toList();
                    }
                }

                // 1.2 若无坐标或周边无结果，使用行政区 + 关键词进行 POI 检索
                var flavorPolicy = flavorPoiPolicy(kw);
                String query = flavorPolicy != null && flavorPolicy.searchKeyword() != null ? flavorPolicy.searchKeyword() : kw;
                com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.DiningSearchPolicy searchPolicy = taxonomy.getDiningSearchPolicy();
                String searchKeywords = (dist + " " + (query.isBlank() ? searchPolicy.genericMealKeyword() : query)).trim();
                String searchTypes = flavorPolicy != null && !flavorPolicy.poiTypeKeywords().isEmpty()
                        ? String.join("|", flavorPolicy.poiTypeKeywords()) : searchPolicy.diningTypePrefix();

                String searchCity = city == null ? "" : city;
                var poiOutcome = geoRouteGateway.searchPoi(searchKeywords.trim(), searchCity, searchTypes);
                if (poiOutcome != null && poiOutcome.status() == com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS
                        && poiOutcome.candidates() != null && !poiOutcome.candidates().isEmpty()) {
                    List<DiningVenue> liveVenues = new ArrayList<>();
                    int idx = 1;
                    for (com.ai.guide.domain.geo.model.GeoPoiCandidate poi : poiOutcome.candidates()) {
                        if (idx > maxResults) break;
                        if (!validDiningPoi(poi) || !matchesFlavorPoiPolicy(poi, flavorPolicy)) continue;
                        String poiName = poi.name() != null ? poi.name() : "周边特色在营商户";
                        String poiCoord = poi.coordinate().longitude() + "," + poi.coordinate().latitude();
                        String addr = (poi.address() != null && !poi.address().isBlank()) ? poi.address() : (dist + "核心商圈");
                        String cost = "人均消费待确认";
                        String duration = mealDurationLabel(mealSlot);
                        String specialty = inferSpecialtyDish(poiName, kw, isBreakfast);

                        String summary = "高德 POI 搜索结果。地址：" + addr + "。营业状态与菜单需确认。";
                        String reason = "位于 " + addr + "；环境、无障碍设施和路线需确认。";

                        List<String> tags = new ArrayList<>(List.of("高德POI", "营业状态待确认", dist));
                        if (!kw.isBlank()) tags.add(kw);

                        liveVenues.add(DiningVenue.builder()
                                .id("amap-live-" + poi.poiId())
                                .name(poiName)
                                .district(dist)
                                .category(flavorPolicy != null ? flavorPolicy.label()
                                        : (isBreakfast ? taxonomy.getCityProfile(city).defaultBreakfast() : category))
                                .mealTypes(List.of(mealSlot))
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
        if (!GeoCoordinateRules.isValid(coordinate)) return List.of();
        List<DiningVenue> allVenues = listAll().stream()
                .filter(v -> distanceKm(coordinate,v.getLocation()) <= diningPolicy().localFallbackMaxDistanceKm()).toList();
        List<DiningVenue> filtered = allVenues.stream()
                .filter(v -> (dist.equals(v.getDistrict()) || (v.getDistrict() != null && v.getDistrict().contains(dist)))
                        && matchesPreference(v, mealSlot, kw))
                .limit(maxResults)
                .toList();



        return filtered;
    }

    private boolean matchesPreference(DiningVenue v, String mealSlot, String kw) {
        String slot = "AFTERNOON_TEA".equalsIgnoreCase(mealSlot) ? "TEA_BREAK" : mealSlot;
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
                                             DiningSearchContext constraints,
                                             int limit) {
        return findCandidates(district, anchorCoordinate, mealType, requestedFlavor, constraints, false, limit);
    }

    private List<DiningVenue> findCandidates(String district,
                                             String anchorCoordinate,
                                             String mealType,
                                             String requestedFlavor,
                                             DiningSearchContext constraints,
                                             boolean proximityPriority,
                                             int limit) {
        if (!GeoCoordinateRules.isValid(anchorCoordinate)) return List.of();
        List<DiningVenue> all = listAll();
        if (all.isEmpty()) return new ArrayList<>();

        String flavorNorm = normalizeFlavor(requestedFlavor, district);
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.DiningSearchPolicy policy = diningPolicy();

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
                    score += policy.districtMatchScore();
                }
            }

            // 2. 口味与风味匹配得分
            boolean hasSpecificFlavor = requestedFlavor != null && !requestedFlavor.isBlank()
                    && !taxonomy.getHints("genericFlavors").contains(requestedFlavor);

            boolean flavorMatched = matchesFlavor(v, flavorNorm);
            if (hasSpecificFlavor && !taxonomy.getHints("genericFlavors").contains(requestedFlavor) && !flavorMatched) continue;

            if (flavorMatched) {
                score += policy.exactFlavorScore();
            } else if (taxonomy.getHints("genericFlavors").contains(flavorNorm)) {
                var profile = taxonomy.getCityProfile(destination(constraints));
                if (profile.signatureDishes().stream().anyMatch(dish -> matchesFlavor(v, dish))) score += policy.citySignatureScore();
            } else if (hasSpecificFlavor) {
                // 用户明确指定了口味偏好，而该餐厅完全不属于该风味类别
                score -= policy.mismatchPenalty();
            }

            // 3. 物理距离截断与评分
            double distKm = distanceKm(anchorCoordinate, v.getLocation());
            boolean isSameDistrict = district != null && !district.isBlank()
                    && v.getDistrict() != null && (v.getDistrict().contains(district) || district.contains(v.getDistrict()));

            double maxAllowedDistKm;
            if (proximityPriority) {
                // 定时旅游/短途限时/同城一日游（如 <= 10小时、运行时动态规划、低耐受度）：
                // 推荐相邻核心商圈名店（约 2-3km），杜绝远距跨区
                maxAllowedDistKm = policy.proximityMaxDistanceKm();
            } else {
                // 常规多日度假漫游：同区远郊大景区允许 15.0km，主城同区允许 5.0km，跨区允许 3.5km
                boolean isSuburban = district != null && taxonomy.getSuburbanDistricts(destination(constraints)).stream().anyMatch(district::contains);
                if (isSameDistrict) {
                    maxAllowedDistKm = isSuburban ? policy.suburbanMaxDistanceKm() : policy.sameDistrictMaxDistanceKm();
                } else {
                    maxAllowedDistKm = policy.crossDistrictMaxDistanceKm();
                }
            }

            if (distKm > maxAllowedDistKm) {
                continue; // 超出有效辐射半径，直接排除
            }

            // 距离阶梯打分：强力优先 < 600米极近步行，平滑衰减至 1.5km
            if (distKm * 1000 < policy.veryNearDistanceMeters()) {
                score += policy.distanceBandScores().get(0);
            } else if (distKm < policy.nearDistanceKm()) {
                score += policy.distanceBandScores().get(1);
            } else if (distKm < policy.mediumDistanceKm()) {
                score += policy.distanceBandScores().get(2);
            } else {
                score += policy.distanceBandScores().get(3);
            }

            // 4. 同行人约束偏好
            if (constraints != null) {
                if ("带父母".equals(constraints.companions()) && Boolean.TRUE.equals(v.getElderFriendly())) {
                    score += policy.elderFriendlyBonus();
                }
                if ("低".equals(constraints.walkingTolerance()) && distKm <= policy.lowWalkingDistanceKm()) {
                    score += policy.lowWalkingBonus();
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
        return taxonomy.getDiningSearchPolicy().nonMealTypeKeywords().stream().anyMatch(category::contains);
    }

    private boolean validDiningPoi(com.ai.guide.domain.geo.model.GeoPoiCandidate poi) {
        if (poi == null || poi.coordinate() == null || poi.name() == null || poi.name().isBlank()) return false;
        if (!poi.coordinate().isValid()) return false;
        String prefix = taxonomy.getDiningSearchPolicy().diningTypePrefix();
        return poi.typecode() == null || poi.typecode().isBlank() || (!prefix.isBlank() && poi.typecode().startsWith(prefix));
    }

    private boolean isConfiguredTeaBreakFlavor(String flavor) {
        return flavor != null && !flavor.isBlank() && taxonomy.getHints("teaBreakFlavorKeywords").stream().anyMatch(flavor::contains);
    }

    private boolean matchesTaxonomyHint(String text, String key) {
        return text != null && !text.isBlank()
                && taxonomy.getHints(key).stream().filter(term -> term != null && !term.isBlank()).anyMatch(text::contains);
    }

    private String defaultDiningPreference() {
        return taxonomy.getHints("defaultDiningPreference").stream().findFirst().orElse("");
    }

    private String configuredLabel(String key) {
        return taxonomy.getHints(key).stream().findFirst().orElse("");
    }

    private int configuredPositiveInt(String key) {
        return taxonomy.getHints(key).stream().findFirst().map(String::trim).map(Integer::parseInt)
                .filter(value -> value > 0)
                .orElseThrow(() -> new IllegalStateException("Missing positive dining taxonomy value: " + key));
    }

    private String normalizeMealSlot(String slotType) {
        String normalized = slotType == null ? "" : slotType.trim().toUpperCase(Locale.ROOT);
        if ("AFTERNOON_TEA".equals(normalized)) return "TEA_BREAK";
        return taxonomy.getHints("supportedMealSlots").stream().anyMatch(normalized::equals)
                ? normalized : taxonomy.getHints("defaultMealSlot").stream().findFirst().orElse("");
    }

    private String resolveMealSlot(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (taxonomy.getHints("supportedMealSlots").stream().anyMatch(normalized::equals)) return normalized;
        if ("SWEET".equalsIgnoreCase(category) || matchesTaxonomyHint(category, "teaBreakFlavorKeywords")) return "TEA_BREAK";
        if ("HOTPOT".equalsIgnoreCase(category)) return "DINNER";
        if (matchesTaxonomyHint(category, "diningBreakfastFlavorTerms")) return "BREAKFAST";
        return taxonomy.getHints("defaultMealSlot").stream().findFirst().orElse("");
    }

    private com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias flavorPoiPolicy(String flavor) {
        return taxonomy.getFlavorAliases().stream()
                .filter(alias -> alias.label().equals(flavor) || alias.keywords().contains(flavor))
                .findFirst().orElse(null);
    }

    private boolean matchesFlavorPoiPolicy(com.ai.guide.domain.geo.model.GeoPoiCandidate poi, com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias policy) {
        return policy == null || policy.poiTypeKeywords().isEmpty()
                || (poi.type() != null && policy.poiTypeKeywords().stream().anyMatch(poi.type()::contains));
    }

    private boolean hasUsableDiningPois(com.ai.guide.domain.geo.model.GeoPoiOutcome outcome, com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias policy) {
        return outcome != null && outcome.status() == com.ai.guide.domain.geo.model.GeoOutcomeStatus.SUCCESS
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
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.DiningSearchPolicy searchPolicy = taxonomy.getDiningSearchPolicy();
        if (!searchPolicy.configured()) return Collections.emptyList();
        try {
            int radius = proximityPriority ? searchPolicy.proximityRadiusMeters() : searchPolicy.nearbyRadiusMeters();
            String normalizedMeal = normalizeMealSlot(mealType);
            boolean isBreakfast = "BREAKFAST".equals(normalizedMeal);
            boolean isDinner = "DINNER".equals(normalizedMeal);
            boolean isNightSnack = "NIGHT_SNACK".equals(normalizedMeal);
            boolean isTeaBreak = "TEA_BREAK".equals(normalizedMeal) || "AFTERNOON_TEA".equals(normalizedMeal);
            String keyword;
            String searchTypes;
            if (isBreakfast) {
                keyword = flavor != null && !flavor.isBlank() && !taxonomy.getHints("genericFlavors").contains(flavor)
                        ? flavor : taxonomy.getCityProfile(city).defaultBreakfast();
                searchTypes = searchPolicy.breakfastTypes();
            } else if (isNightSnack) {
                keyword = (flavor != null && !flavor.isBlank()) ? flavor : searchPolicy.genericMealKeyword();
                searchTypes = searchPolicy.mealTypes();
            } else if (isTeaBreak) {
                keyword = flavor != null && !flavor.isBlank() ? flavor : searchPolicy.genericTeaBreakKeyword();
                searchTypes = searchPolicy.teaBreakTypes();
            } else {
                if (flavor != null && !flavor.isBlank() && !taxonomy.getHints("genericFlavors").contains(flavor)) {
                    keyword = flavor;
                } else {
                    keyword = searchPolicy.genericMealKeyword();
                }
                searchTypes = searchPolicy.mealTypes();
            }

            var flavorPolicy = flavorPoiPolicy(flavor);
            if (flavorPolicy != null) {
                if (flavorPolicy.searchKeyword() != null) keyword = flavorPolicy.searchKeyword();
                if (!flavorPolicy.poiTypeKeywords().isEmpty()) searchTypes = String.join("|", flavorPolicy.poiTypeKeywords());
            }
            String searchCity = city == null ? "" : city;
            var outcome = geoRouteGateway.searchAround(coordinate, radius, searchTypes, keyword, searchCity);
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                outcome = geoRouteGateway.searchAround(coordinate, radius + searchPolicy.expansionStepMeters(), searchPolicy.fallbackPoiType(), keyword, searchCity);
            }
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                outcome = geoRouteGateway.searchAround(coordinate, searchPolicy.fallbackRadiusMeters(), searchPolicy.fallbackPoiType(), keyword, searchCity);
            }
            if (!hasUsableDiningPois(outcome, flavorPolicy)) {
                return Collections.emptyList();
            }

            List<DiningVenue> list = new ArrayList<>();
            int idx = 1;

            for (com.ai.guide.domain.geo.model.GeoPoiCandidate poi : outcome.candidates()) {
                if (idx > configuredPositiveInt("diningAmapMaxResults")) break;
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
                String duration = mealDurationLabel(normalizedMeal);

                String summary = "高德 POI 搜索结果；营业状态、菜单和消费需商户确认。";
                String recommendationReason = "距离当前锚点的直线距离约 " + distMeters + " 米，实际路线及无障碍设施待确认。";

                String spiciness = "UNKNOWN";

                List<String> tags = new ArrayList<>(List.of("高德实时推荐", distMeters <= diningPolicy().veryNearDistanceMeters() ? "景区周边极近" : "相邻商圈特色",
                        isBreakfast ? configuredLabel("breakfastCategoryLabel") : (isNightSnack ? configuredLabel("nightSnackCategoryLabel") : (isTeaBreak ? configuredLabel("teaBreakCategoryLabel") : (isDinner ? configuredLabel("dinnerTagLabel") : configuredLabel("genericDiningCategoryLabel"))))));
                if (flavor != null && !flavor.isBlank()) {
                    tags.add(flavor);
                }

                String distanceDesc = "直线距离约 " + distMeters + " 米，路线待确认";

                DiningVenue venue = DiningVenue.builder()
                        .id("amap-dining-" + poi.poiId())
                        .name(poiName)
                        .district(district == null ? "" : district)
                        .category(flavor != null && !flavor.isBlank() ? flavor : (isBreakfast ? taxonomy.getCityProfile(city).defaultBreakfast() : (isNightSnack ? configuredLabel("nightSnackCategoryLabel") : (isTeaBreak ? configuredLabel("teaBreakCategoryLabel") : configuredLabel("genericDiningCategoryLabel")))))
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
                || "SNACK".equalsIgnoreCase(mealType) || configuredLabel("teaBreakCategoryLabel").equals(venue.getCategory())
                || matchesTaxonomyHint(venue.getCategory(), "teaVenueCategoryKeywords")
                || (venue.getName() != null && taxonomy.getHints("teaVenueNameKeywords").stream().anyMatch(venue.getName()::contains));
        String prefixKey = "BREAKFAST".equalsIgnoreCase(mealType) ? "mealDisplayPrefixBreakfast"
                : ("NIGHT_SNACK".equalsIgnoreCase(mealType) ? "mealDisplayPrefixNightSnack"
                : (isTeaVenue ? "mealDisplayPrefixTeaBreak"
                : ("DINNER".equalsIgnoreCase(mealType) ? "mealDisplayPrefixDinner" : "mealDisplayPrefixLunch")));
        String prefix = configuredLabel(prefixKey);
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

    private String normalizeFlavor(String requestedFlavor) { return normalizeFlavor(requestedFlavor,null); }
    private String normalizeFlavor(String requestedFlavor, String cityOrDistrict) {
        return requestedFlavor == null || requestedFlavor.isBlank() ? defaultDiningPreference() : requestedFlavor.trim();
    }

    public static double distanceKm(String coord1, String coord2) {
        if (coord1 == null || coord2 == null || !coord1.contains(",") || !coord2.contains(",")) return Double.POSITIVE_INFINITY;
        try {
            String[] p1 = coord1.split(",");
            String[] p2 = coord2.split(",");
            if (p1.length != 2 || p2.length != 2 || !GeoCoordinateRules.isValid(coord1)
                    || !GeoCoordinateRules.isValid(coord2)) return Double.POSITIVE_INFINITY;
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

    private com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.DiningSearchPolicy diningPolicy() {
        com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.DiningSearchPolicy policy = taxonomy.getDiningSearchPolicy();
        if (policy == null || !policy.configured())
            throw new IllegalStateException("Dining taxonomy policy is not configured");
        return policy;
    }

    private String mealDurationLabel(String slot) {
        String policySlot = "AFTERNOON_TEA".equalsIgnoreCase(slot) ? "TEA_BREAK" : slot;
        Integer minutes = diningPolicy().mealDurationsMinutes().get(policySlot);
        if (minutes == null || minutes <= 0) throw new IllegalStateException("Missing meal duration policy: " + slot);
        return "约 " + minutes + " 分钟";
    }

    private static String destination(DiningSearchContext constraints) {
        return constraints == null || constraints.destination() == null ? "" : constraints.destination().trim();
    }

    private String resolveAnchorCoordinate(Attraction anchorAttraction, DiningSearchContext constraints) {
        if (anchorAttraction != null && GeoCoordinateRules.isValid(anchorAttraction.getLocation()))
            return anchorAttraction.getLocation();
        return new GeoCityCenterResolver(geoRouteGateway).resolve(destination(constraints));
    }

    private record ScoredVenue(DiningVenue venue, int score, double distanceKm) {}
}


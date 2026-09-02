package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 行程结构化组装与候选景点管理服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：负责 24 景候选召回、景点标签匹配、游玩时长推算及多天行程 JSON 结构的标准化组装。
 */
@Service
public class ItineraryBuilder implements ItineraryBuilderPort {

    /**
     * Keep tie-breaking aligned with the legacy Node catalog order without
     * duplicating attraction metadata in the Planner domain.
     */
    private static final List<String> CATALOG_ORDER = List.of(
            "cq-hongyadong", "cq-jiefangbei", "cq-museum", "cq-liziba",
            "cq-grand-theatre", "cq-ciqikou", "cq-kuixinglou", "cq-shibati",
            "cq-erling", "cq-changjiang-cable", "cq-nanshan-yikeshu", "cq-danzi-shi",
            "cq-guanyinqiao", "cq-luohan-temple", "cq-huguang-guild", "cq-zhongshan-road",
            "cq-baiheliang", "cq-wulong-tiankeng", "cq-dazu-rock", "cq-ronghui-hotspring",
            "cq-south-hotspring", "cq-geleyuan", "cq-maanshan", "cq-beicang"
    );

    private static final List<PatternId> EXPLICIT_ATTRACTIONS = List.of(
            new PatternId("武隆|天坑|天生三桥", "cq-wulong-tiankeng"),
            new PatternId("融汇温泉", "cq-ronghui-hotspring"),
            new PatternId("南温泉", "cq-south-hotspring"),
            new PatternId("温泉|水疗|泡汤", "cq-ronghui-hotspring"),
            new PatternId("大足|石刻|宝顶山", "cq-dazu-rock"),
            new PatternId("白鹤梁|水下博物馆", "cq-baiheliang"),
            new PatternId("磁器口|古镇", "cq-ciqikou"),
            new PatternId("观音桥|九街", "cq-guanyinqiao"),
            new PatternId("北仓", "cq-beicang"),
            new PatternId("二厂|鹅岭", "cq-erling"),
            new PatternId("马鞍山", "cq-maanshan"),
            new PatternId("一棵树|南山", "cq-nanshan-yikeshu"),
            new PatternId("弹子石", "cq-danzi-shi"),
            new PatternId("索道|长江索道", "cq-changjiang-cable"),
            new PatternId("魁星楼", "cq-kuixinglou"),
            new PatternId("罗汉寺", "cq-luohan-temple"),
            new PatternId("湖广会馆", "cq-huguang-guild"),
            new PatternId("中山四路", "cq-zhongshan-road"),
            new PatternId("歌乐山|渣滓洞|白公馆|红岩", "cq-geleyuan"));

    private final AttractionService attractionService;
    private final RouteAwarePlanner routeAwarePlanner;
    private final PlanVerifier planVerifier;
    private final LocalPlanRepairer localPlanRepairer;
    private final PlanNarrativeService planNarrativeService;

    @Autowired
    public ItineraryBuilder(AttractionService attractionService,
                            RouteAwarePlanner routeAwarePlanner,
                            PlanVerifier planVerifier,
                            LocalPlanRepairer localPlanRepairer,
                            PlanNarrativeService planNarrativeService) {
        this.attractionService = attractionService;
        this.routeAwarePlanner = routeAwarePlanner;
        this.planVerifier = planVerifier;
        this.localPlanRepairer = localPlanRepairer;
        this.planNarrativeService = planNarrativeService;
    }

    public ItineraryBuilder(AttractionService attractionService) {
        PlanVerifier verifier = new PlanVerifier();
        LocalPlanRepairer repairer = new LocalPlanRepairer();
        RouteCostProvider costProvider = new RouteCostProvider(null);
        this.attractionService = attractionService;
        this.planVerifier = verifier;
        this.localPlanRepairer = repairer;
        this.routeAwarePlanner = new RouteAwarePlanner(costProvider, verifier, repairer);
        this.planNarrativeService = new PlanNarrativeService();
    }

    public Map<String, Object> build(int version, TravelConstraints constraints) {
        List<Attraction> catalog = attractionService.list(null, null);
        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction attraction : catalog) byId.put(attraction.getId(), attraction);
        List<String> explicitIds = explicitIds(constraints.getRawPrompt(), byId);
        int requestedDays = constraints.getDurationDays() <= 0 ? 2 : constraints.getDurationDays();
        int dayCount = Math.max(1, Math.min(7, requestedDays));
        boolean isGoldenBaseline = explicitIds.isEmpty() && dayCount == 2;

        List<Map<String, Object>> days = isGoldenBaseline
                ? routeAwarePlanner.resolveAndVerifyDays(buildBaselineDays(dayCount, constraints, byId), byId, constraints)
                : routeAwarePlanner.planDays(dayCount, constraints, byId, explicitIds, this);

        String title = isGoldenBaseline
                ? "重庆" + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·山城漫游线"
                : "重庆" + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·专属定制方案";
        String subtitle = ("低".equals(constraints.getWalkingTolerance()) ? "少走路优先" : "按约束排序")
                + " · " + constraints.getCompanions() + " · " + String.join(" + ", constraints.getInterests().stream().limit(2).toList());

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-cq-" + version);
        trip.put("version", version);
        trip.put("title", title);
        trip.put("subtitle", subtitle);
        trip.put("constraints", constraints.asMap());
        trip.put("sourceMode", "演示回退模式");
        trip.put("factPolicy", "动态 / 未知 / 冲突事实显式标注");
        trip.put("days", days);
        trip.put("planContext", planContext(constraints));
        trip.put("summary", summary(dayCount));
        trip.put("citations", citations());
        trip.put("retrieval", retrievalStatus());
        trip.put("sourceStatus", sourceStatus());
        trip.put("qualityMetrics", qualityMetrics(constraints, requestedDays, days));
        trip.put("versionHistory", List.of(versionEntry(version, "初始规划", List.of(), "")));

        // V1 Version metadata and explanation source
        PlannerVersionMetadata versionMeta = PlannerVersionMetadata.defaultV1("ESTIMATED", false, List.of());
        trip.put("plannerVersion", versionMeta.getPlannerVersion());
        trip.put("policyVersion", versionMeta.getPolicyVersion());
        trip.put("explanationSource", versionMeta.getExplanationSource());
        trip.put("degraded", versionMeta.isDegraded());
        trip.put("degradationReasons", versionMeta.getDegradationReasons());

        return trip;
    }

    @Override
    public Map<String, Object> buildLegacy(int version, TravelConstraints constraints) {
        List<Attraction> catalog = attractionService.list(null, null);
        Map<String, Attraction> byId = new LinkedHashMap<>();
        for (Attraction attraction : catalog) byId.put(attraction.getId(), attraction);
        int requestedDays = constraints.getDurationDays() <= 0 ? 2 : constraints.getDurationDays();
        int dayCount = Math.max(1, Math.min(7, requestedDays));

        // Legacy path uses pure baseline template without RouteAwarePlanner or route resolution
        List<Map<String, Object>> days = buildBaselineDays(dayCount, constraints, byId);

        String title = "重庆" + dayCount + "天" + (dayCount == 1 ? "" : "一夜") + "·传统基准方案";
        String subtitle = "传统规划引擎 · 演示基准";

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-cq-" + version);
        trip.put("version", version);
        trip.put("title", title);
        trip.put("subtitle", subtitle);
        trip.put("constraints", constraints.asMap());
        trip.put("sourceMode", "演示回退模式");
        trip.put("factPolicy", "静态回退");
        trip.put("days", days);
        trip.put("planContext", planContext(constraints));
        trip.put("summary", summary(dayCount));
        trip.put("citations", citations());
        trip.put("retrieval", retrievalStatus());
        trip.put("sourceStatus", sourceStatus());
        trip.put("qualityMetrics", qualityMetrics(constraints, requestedDays, days));
        trip.put("versionHistory", List.of(versionEntry(version, "初始规划", List.of(), "")));

        trip.put("plannerVersion", "legacy-v0");
        trip.put("policyVersion", "legacy-2026.07");
        trip.put("explanationSource", "LEGACY_STATIC");
        trip.put("degraded", true);
        trip.put("degradationReasons", List.of("LEGACY_PLANNER_ACTIVE"));

        return trip;
    }

    public Map<String, Object> createStop(Attraction attraction, String stopId, String time,
                                          String duration, String summary, String detail) {
        return createStop(attraction, stopId, time, duration, summary, detail, null);
    }

    public Map<String, Object> createStop(Attraction attraction, String stopId, String time,
                                          String duration, String summary, String detail,
                                          ScoreBreakdown scoreBreakdown) {
        String effectiveDuration = duration == null || duration.isBlank() ? attraction.getDuration() : duration;
        String effectiveWalk = attraction.getWalk() == null ? "路线待计算" : attraction.getWalk();
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", stopId);
        stop.put("stableStopId", stopId);
        stop.put("entityId", attraction.getId());
        stop.put("venueId", attraction.getId());
        stop.put("name", attraction.getName());
        stop.put("displayName", attraction.getDisplayName());
        stop.put("district", attraction.getDistrict());
        stop.put("time", time);
        stop.put("startTime", time);
        stop.put("duration", effectiveDuration);
        stop.put("icon", attraction.getIcon());
        stop.put("tone", attraction.getTone());
        stop.put("summary", summary == null || summary.isBlank() ? attraction.getSummary() : summary);
        stop.put("walk", effectiveWalk);
        stop.put("walkDifficulty", attraction.getWalkDifficulty());
        stop.put("walkingDifficulty", attraction.getWalkDifficulty());
        stop.put("indoor", Boolean.TRUE.equals(attraction.getIndoor()));
        stop.put("ticket", attraction.getTicket());
        stop.put("bestTime", attraction.getBestTime());
        stop.put("estimatedCost", "未知·需用户确认");
        stop.put("costSummary", "费用待确认");
        String reason = (detail != null && !detail.isBlank()) ? detail
                : (planNarrativeService != null) ? planNarrativeService.buildStopReason(attraction, scoreBreakdown, null, null)
                : (attraction.getFit() != null && !attraction.getFit().isBlank() ? attraction.getFit() : attraction.getIntro());
        stop.put("recommendationReason", reason);
        stop.put("detail", detail == null || detail.isBlank() ? attraction.getIntro() : detail);
        stop.put("matchedConstraints", new ArrayList<>());
        stop.put("routePreference", "walking");
        stop.put("walkingInfo", Map.of("summary", effectiveWalk, "status", "未知"));
        stop.put("address", "");
        stop.put("location", attraction.getLocation());
        stop.put("amapPoiId", "");
        stop.put("mapContext", mapContext(attraction.getLocation()));
        stop.put("facts", defaultFacts(attraction));
        stop.put("citations", citations());
        if (scoreBreakdown != null) {
            stop.put("scoreBreakdown", scoreBreakdown.asMap());
        }
        return stop;
    }

    public Attraction attraction(String id) {
        return attractionService.get(id);
    }

    public List<Attraction> attractionServiceList() {
        return attractionService.list(null, null);
    }

    @Override
    public int catalogRank(String attractionId) {
        int index = CATALOG_ORDER.indexOf(attractionId);
        return index >= 0 ? index : Integer.MAX_VALUE;
    }

    private List<Map<String, Object>> buildBaselineDays(int dayCount, TravelConstraints constraints,
                                                          Map<String, Attraction> byId) {
        List<List<Spec>> templates = List.of(
                List.of(new Spec("day1-jiefangbei", "cq-jiefangbei", "14:30", "约 90 分钟", "城市地标与街区热身，平街漫步把节奏放慢。", "适合作为抵渝第一站，先完成城市地标认知与小吃品尝。"),
                        new Spec("day1-hongyadong", "cq-hongyadong", "19:30", "约 90 分钟", "夜景主场，金碧辉煌吊脚楼依山就势。", "以夜景和临江层次为核心，动态拥挤度保留弹性。")),
                List.of(new Spec("day2-museum", "cq-museum", "10:00", "约 120 分钟", "全馆无障碍平滑观展，壮丽三峡与巴渝历史史诗。", "室内环境冬暖夏凉，是长辈与亲子最舒适的室内场馆。"),
                        new Spec("day2-liziba", "cq-liziba", "14:30", "约 45 分钟", "单轨穿楼地面平坦观景台，捕捉山城8D魔幻奇观。", "停留时间短、识别度高，作为收束站点极其稳妥。"),
                        new Spec("day2-shibati", "cq-shibati", "18:00", "约 90 分钟", "上下半城烟火记忆，青石板路与吊脚楼灯火温馨如画。", "保留老重庆市井韵味与特色茶铺，夜间灯光富有层次。")),
                List.of(new Spec("day3-erling", "cq-erling", "14:00", "约 90 分钟", "工业红砖厂房与潮流文创，天台俯瞰两江壮阔景观。", "适合文艺摄影与慢节奏品味咖啡，天台视野绝佳。"),
                        new Spec("day3-grand-theatre", "cq-grand-theatre", "19:30", "约 90 分钟", "隔江远眺千厮门与洪崖洞全景，江风拂面且免受人潮拥挤。", "适合少走路、带长辈，开阔全景机位极其舒适。")),
                List.of(new Spec("day4-ciqikou", "cq-ciqikou", "10:30", "约 120 分钟", "千年水陆码头，陈麻花、毛血旺与老茶馆烟火气十足。", "明清院落与道地巴渝非遗美食汇集。"),
                        new Spec("day4-danzi-shi", "cq-danzi-shi", "17:30", "约 90 分钟", "全覆盖自动扶梯对长辈友好，正对朝天门来福士江景。", "十里老街重温开埠岁月，兼顾美食与惬意江景漫步。")));
        String[] labels = {"母城地标与山城夜景", "文博静谧与8D魔幻穿楼", "江岸开阔视野与工业文创", "千年古镇与巴渝风情"};
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 1; day <= dayCount; day++) {
            List<Spec> specs = templates.get((day - 1) % templates.size());
            List<Map<String, Object>> stops = new ArrayList<>();
            for (Spec spec : specs) {
                Attraction attraction = byId.get(spec.attractionId());
                if (attraction != null) {
                    boolean shouldAvoid = false;
                    if (constraints.getAvoid() != null) {
                        for (String av : constraints.getAvoid()) {
                            if (attraction.getName().contains(av) || attraction.getId().contains(av)
                                    || (attraction.getTags() != null && attraction.getTags().contains(av))
                                    || av.equals(attraction.getCategory())) {
                                shouldAvoid = true;
                                break;
                            }
                        }
                    }
                    if (!shouldAvoid) {
                        stops.add(createStop(attraction, spec.id().replaceFirst("day\\d+", "day" + day), spec.time(), spec.duration(), spec.summary(), spec.detail()));
                    }
                }
            }
            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", day);
            String itineraryDate = itineraryDate(constraints, day);
            dayMap.put("date", itineraryDate == null ? dateLabel(constraints, day) : itineraryDate);
            dayMap.put("dateLabel", itineraryDate == null
                    ? "第" + day + "天 · " + labels[(day - 1) % labels.length]
                    : itineraryDate + " · 第" + day + "天 · " + labels[(day - 1) % labels.length]);
            dayMap.put("weather", weather());
            dayMap.put("stops", stops);
            days.add(dayMap);
        }
        annotateStops(days, constraints);
        return days;
    }

    private record SlotSpec(String time, Predicate<Attraction> preferredPredicate) {}

    private List<Map<String, Object>> buildCustomDays(int dayCount, TravelConstraints constraints,
                                                      Map<String, Attraction> byId, List<String> explicitIds) {
        List<Map<String, Object>> days = routeAwarePlanner.planDays(dayCount, constraints, byId, explicitIds, this);
        annotateStops(days, constraints);
        return days;
    }

    private List<SlotSpec> buildDaySlotSpecs(int day, TravelConstraints constraints) {
        boolean isAfternoonStart = day == 1 && (constraints.getArrivalAt().contains("下午") || constraints.getArrivalAt().contains("晚上"));
        if (isAfternoonStart) {
            return List.of(
                    new SlotSpec("14:30", attraction -> !isNightAttraction(attraction)),
                    new SlotSpec("19:30", this::isNightAttraction)
            );
        }
        return List.of(
                new SlotSpec("10:00", attraction -> !isNightAttraction(attraction)),
                new SlotSpec("14:30", attraction -> !isNightAttraction(attraction)),
                new SlotSpec("18:30", this::isNightAttraction)
        );
    }

    private Scored selectBestCandidateScored(List<Scored> scored, Set<String> used, Predicate<Attraction> preferred) {
        return scored.stream()
                .filter(s -> !used.contains(s.attraction().getId()) && preferred.test(s.attraction()))
                .findFirst()
                .orElseGet(() -> scored.stream()
                        .filter(s -> !used.contains(s.attraction().getId()))
                        .findFirst()
                        .orElse(null));
    }

    private List<Scored> scoreAttractions(Collection<Attraction> attractions, TravelConstraints constraints, Set<String> explicitIds) {
        List<Scored> scored = new ArrayList<>();
        for (Attraction attraction : attractions) {
            ScoreBreakdown breakdown = score(attraction, constraints, explicitIds);
            scored.add(new Scored(attraction, breakdown.totalScore(), breakdown));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt(item -> catalogRank(item.attraction().getId()))
                .thenComparing(item -> item.attraction().getId()));
        return scored;
    }

    public ScoreBreakdown score(Attraction a, TravelConstraints c, Set<String> explicitIds) {
        List<String> reasonCodes = new ArrayList<>();
        int explicitBonus = explicitIds != null && explicitIds.contains(a.getId()) ? 200 : 0;
        if (explicitBonus > 0) reasonCodes.add("EXPLICIT_MENTION");

        int mustVisitScore = (c.getMustVisit() != null && c.getMustVisit().stream().anyMatch(m -> a.getName().contains(m) || a.getId().contains(m))) ? 300 : 0;
        if (mustVisitScore > 0) reasonCodes.add("MUST_VISIT");

        int avoidPenalty = (c.getAvoid() != null && c.getAvoid().stream().anyMatch(av -> a.getName().contains(av) || a.getId().contains(av) || av.equals(a.getCategory()))) ? -500 : 0;
        if (avoidPenalty < 0) reasonCodes.add("AVOID_PENALTY");

        int interestScore = 0;
        for (String interest : c.getInterests()) {
            if ("自然奇观".equals(interest) || "自然".equals(interest)) {
                if ("自然".equals(a.getCategory()) || a.matchesAnyTagOrFeature("自然", "自然奇观", "峡谷", "喀斯特峡谷")) {
                    interestScore += 55;
                    reasonCodes.add("INTEREST_NATURE");
                }
            } else if ("人文历史".equals(interest) || "人文".equals(interest)) {
                if ("人文".equals(a.getCategory()) || a.matchesAnyTagOrFeature("人文", "历史", "古建", "抗战遗址", "千年古刹", "世界文化遗产")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_HISTORY");
                }
            } else if ("8D魔幻".equals(interest)) {
                if ("8D魔幻".equals(a.getCategory()) || a.matchesAnyTagOrFeature("8D魔幻", "单轨", "天桥", "空中连廊", "空中巴士")) {
                    interestScore += 55;
                    reasonCodes.add("INTEREST_8D_MAGIC");
                }
            } else if ("山城夜景".equals(interest) || "夜景".equals(interest)) {
                if ("夜景".equals(a.getCategory()) || a.matchesAnyTagOrFeature("夜景", "临江", "江岸夜景", "俯瞰全城")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_NIGHT_VIEW");
                }
            } else if ("天然温泉".equals(interest) || "温泉".equals(interest)) {
                if ("温泉".equals(a.getCategory()) || a.matchesAnyTagOrFeature("温泉", "天然地热温泉", "古温泉", "养生放松") || a.getName().contains("温泉")) {
                    interestScore += 60;
                    reasonCodes.add("INTEREST_HOTSPRING");
                }
            } else if ("市井烟火".equals(interest) || "市井".equals(interest)) {
                if ("市井".equals(a.getCategory()) || a.matchesAnyTagOrFeature("市井", "市井烟火", "老街", "古镇", "老重庆")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_LOCAL_LIFE");
                }
            } else if (a.matchesAnyTagOrFeature(interest) || interest.equals(a.getCategory()) || a.getName().contains(interest)) {
                interestScore += 40;
                reasonCodes.add("INTEREST_TAG_MATCH");
            }
        }

        int walkingScore = evaluateWalkingScore(a, c.getWalkingTolerance(), reasonCodes);
        int companionScore = evaluateCompanionScore(a, c.getCompanions(), reasonCodes);
        int diningScore = evaluateDiningScore(a, c.getDietPreference(), reasonCodes);

        int totalScore = explicitBonus + mustVisitScore + avoidPenalty + interestScore + walkingScore + companionScore + diningScore;
        return new ScoreBreakdown(interestScore, companionScore, walkingScore, diningScore, mustVisitScore, avoidPenalty, explicitBonus, totalScore, reasonCodes);
    }

    private int evaluateDiningScore(Attraction a, String dietPreference, List<String> reasonCodes) {
        if ("清淡不辣".equals(dietPreference) || "清淡".equals(dietPreference)) {
            boolean isPeacefulLeisure = ("人文".equals(a.getCategory()) || "文创".equals(a.getCategory()) || "休闲".equals(a.getCategory()) || "INDOOR".equals(a.getEffectiveEnvironment()) || a.matchesAnyTagOrFeature("最美林荫道", "静谧散步", "养生放松", "城市书房", "清淡友好"))
                    && !a.matchesAnyTagOrFeature("火锅", "重庆火锅", "江湖菜", "老火锅");
            if (isPeacefulLeisure) {
                reasonCodes.add("DINING_NON_SPICY_FIT");
                return 30;
            }
            if (a.matchesAnyTagOrFeature("火锅", "重庆火锅", "江湖菜", "老火锅")) {
                reasonCodes.add("DINING_SPICY_PENALTY");
                return -20;
            }
        } else if ("重庆火锅".equals(dietPreference) || "九宫格老火锅".equals(dietPreference)) {
            if ("美食".equals(a.getCategory()) || a.matchesAnyTagOrFeature("火锅", "重庆火锅", "美食", "街头小吃", "非遗小吃")) {
                reasonCodes.add("DINING_HOTPOT_FIT");
                return 40;
            }
        } else if (!"未提供".equals(dietPreference)) {
            if ("美食".equals(a.getCategory()) || a.matchesAnyTagOrFeature("美食", "小吃", "老字号")) {
                reasonCodes.add("DINING_FOOD_FIT");
                return 30;
            }
        }
        return 0;
    }

    private int evaluateWalkingScore(Attraction a, String walkingTolerance, List<String> reasonCodes) {
        if ("低".equals(walkingTolerance)) {
            int delta = 0;
            if ("低".equals(a.getWalkDifficulty()) || "SUPPORTED".equals(a.getEffectiveAccessibility())) {
                delta += 35;
                reasonCodes.add("WALKING_EASY");
            }
            if (a.matchesAnyTagOrFeature("少走路", "长辈友好", "平街", "无障碍", "扶梯全覆盖", "多级户外扶梯")) {
                delta += 25;
                reasonCodes.add("WALKING_ACCESSIBLE");
            }
            if ("高".equals(a.getWalkDifficulty()) || "UNSUPPORTED".equals(a.getEffectiveAccessibility())) {
                delta -= 50;
                reasonCodes.add("WALKING_DIFFICULT_PENALTY");
            }
            if ("中".equals(a.getWalkDifficulty()) || "PARTIAL".equals(a.getEffectiveAccessibility())) {
                delta -= 15;
            }
            return delta;
        }
        if ("高".equals(walkingTolerance) && (a.matchesAnyTagOrFeature("深度打卡", "徒步", "徒步摄影") || "高".equals(a.getWalkDifficulty()))) {
            reasonCodes.add("WALKING_HIGH_TOLERANCE");
            return 30;
        }
        return 0;
    }

    private int evaluateCompanionScore(Attraction a, String companions, List<String> reasonCodes) {
        return switch (companions) {
            case "带孩子", "亲子家庭", "亲子" -> {
                int delta = "INDOOR".equals(a.getEffectiveEnvironment()) ? 25 : 0;
                if (a.matchesAnyTagOrFeature("亲子", "科普", "国宝级", "水下博物馆", "三峡变迁") || ("INDOOR".equals(a.getEffectiveEnvironment()) && "人文".equals(a.getCategory()))) {
                    delta += 45;
                    reasonCodes.add("COMPANION_KIDS_EDUCATION");
                }
                if ("自然".equals(a.getCategory()) || a.matchesAnyTagOrFeature("自然", "峡谷", "森林氧吧", "地质奇观")) {
                    delta += 35;
                    reasonCodes.add("COMPANION_KIDS_NATURE");
                }
                if (a.matchesAnyTagOrFeature("8D魔幻", "单轨", "交通", "空中巴士")) {
                    delta += 30;
                    reasonCodes.add("COMPANION_KIDS_MAGIC");
                }
                yield delta;
            }
            case "带父母", "带老人", "长辈同行" -> {
                int delta = "INDOOR".equals(a.getEffectiveEnvironment()) ? 25 : 0;
                if (a.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "无障碍", "少走路", "养生放松")) {
                    delta += 35;
                    reasonCodes.add("COMPANION_SENIOR_FRIENDLY");
                }
                if ("低".equals(a.getWalkDifficulty()) && (a.matchesAnyTagOrFeature("少走路", "长辈友好", "街区", "视野开阔", "开埠文化") || "INDOOR".equals(a.getEffectiveEnvironment()))) {
                    delta += 30;
                    reasonCodes.add("COMPANION_SENIOR_EASY");
                }
                if ("高".equals(a.getWalkDifficulty())) {
                    delta -= 40;
                    reasonCodes.add("COMPANION_SENIOR_STEEP_PENALTY");
                }
                yield delta;
            }
            case "情侣出游", "情侣双人", "情侣" -> {
                int delta = 0;
                if ("夜景".equals(a.getCategory()) || a.matchesAnyTagOrFeature("夜景", "浪漫", "清幽", "拍照出片")) {
                    delta += 35;
                    reasonCodes.add("COMPANION_COUPLE_ROMANTIC");
                }
                if (a.matchesAnyTagOrFeature("温泉", "天然地热温泉", "养生放松") || a.getName().contains("温泉")) {
                    delta += 45;
                    reasonCodes.add("COMPANION_COUPLE_SPA");
                }
                if (a.matchesAnyTagOrFeature("俯瞰全城", "夜景大片", "开埠文化", "工业风", "文创艺术")) {
                    delta += 30;
                    reasonCodes.add("COMPANION_COUPLE_SCENIC");
                }
                yield delta;
            }
            case "朋友出游", "朋友结伴" -> {
                if (a.matchesAnyTagOrFeature("8D魔幻", "美食", "打卡", "夜生活", "潮流活力", "非遗小吃") || "城市".equals(a.getCategory()) || "美食".equals(a.getCategory())) {
                    reasonCodes.add("COMPANION_FRIENDS_VIBRANT");
                    yield 35;
                }
                yield 0;
            }
            case "独自出发", "一个人", "自由行" -> {
                if (a.matchesAnyTagOrFeature("地标", "文创", "城市书房", "文艺社区", "空中连廊", "短停留", "清幽") || "文创".equals(a.getCategory())) {
                    reasonCodes.add("COMPANION_SOLO_CALM");
                    yield 30;
                }
                yield 0;
            }
            default -> 0;
        };
    }

    private boolean isNightAttraction(Attraction a) {
        String bestTime = String.valueOf(a.getBestTime() == null ? "" : a.getBestTime());
        List<String> tags = a.getTags() == null ? List.of() : a.getTags();
        return "夜景".equals(a.getCategory()) || tags.contains("夜景") || bestTime.contains("18:") || bestTime.contains("19:");
    }

    private void annotateStops(List<Map<String, Object>> days, TravelConstraints constraints) {
        List<String> matches = new ArrayList<>();
        if (!"未提供".equals(constraints.getCompanions()) && constraints.originOf("companions") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("同行人：" + constraints.getCompanions());
        }
        if (constraints.originOf("walkingTolerance") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT && !"正常".equals(constraints.getWalkingTolerance())) {
            matches.add("步行：" + constraints.getWalkingTolerance());
        }
        if (!"未提供".equals(constraints.getBudget()) && constraints.originOf("budget") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("预算：" + constraints.getBudget());
        }
        if (!"未提供".equals(constraints.getTransportPreference()) && constraints.originOf("transportPreference") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            matches.add("交通：" + constraints.getTransportPreference());
        }
        if (constraints.originOf("interests") != com.ai.guide.domain.planner.model.ConstraintOrigin.DEFAULT) {
            for (String interest : constraints.getInterests()) matches.add("兴趣：" + interest);
        }
        String route = routePreference(constraints);
        for (Map<String, Object> day : days) {
            day.put("departureContext", "未提供".equals(constraints.getStayArea()) ? "未指定住宿区域，路线从首个景点开始计算。" : "已将“" + constraints.getStayArea() + "”作为出发衔接参考，具体酒店地址仍需高德解析。");
            day.put("mealGuidance", mealGuidance(constraints));
            Object stopsValue = day.get("stops");
            if (!(stopsValue instanceof List<?> stops)) continue;
            for (Object value : stops) if (value instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked") Map<String, Object> stop = (Map<String, Object>) raw;
                stop.put("matchedConstraints", new ArrayList<>(matches));
                stop.put("routePreference", route);
                stop.put("walkingInfo", Map.of("summary", stop.get("walk"), "status", "未知", "preference", route));
                Attraction attraction = attractionService.get(String.valueOf(stop.get("venueId")));
                ScoreBreakdown breakdown = scoreBreakdown(stop.get("scoreBreakdown"));
                RouteCost routeCost = RouteCost.fromMap(stop.get("resolvedRouteCost"));
                if (attraction != null && planNarrativeService != null) {
                    stop.put("recommendationReason", planNarrativeService.buildStopReason(attraction, breakdown, routeCost, constraints));
                } else {
                    stop.put("recommendationReason", recommendationReason(stop, constraints));
                }
                stop.put("explanationSource", "TEMPLATE");
                stop.put("costSummary", "有限".equals(constraints.getBudget()) ? "预算友好优先 · 费用需确认" : "费用待确认");
                stop.put("estimatedCost", stop.get("costSummary"));
            }
        }
    }

    private String recommendationReason(Map<String, Object> stop, TravelConstraints constraints) {
        List<String> parts = new ArrayList<>();
        if ("低".equals(constraints.getWalkingTolerance())) parts.add("优先减少连续步行");
        if ("有限".equals(constraints.getBudget())) parts.add("纳入预算有限策略");
        if (!"未提供".equals(constraints.getTransportPreference())) parts.add("交通偏好：" + constraints.getTransportPreference());
        return parts.isEmpty() ? String.valueOf(stop.get("detail")) : String.join("，", parts) + "。";
    }

    private Map<String, Object> planContext(TravelConstraints c) {
        String route = routePreference(c);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startingArea", c.getStayArea());
        result.put("startingAreaStatus", "未提供".equals(c.getStayArea()) ? "未指定住宿区域，路线从首个景点开始计算。" : "已将“" + c.getStayArea() + "”作为每日出发衔接参考，具体酒店地址仍需高德解析。");
        result.put("routePreference", route);
        result.put("routeStrategy", "transit".equals(route) ? "公共交通优先；路线服务不可用时保留静态回退。" : "按步行路线计算，必要时结合公交衔接。");
        result.put("budgetStrategy", "有限".equals(c.getBudget()) ? "预算有限：优先公共空间与低消费段，费用出发前确认。" : "费用以动态核验为准。");
        result.put("foodGuidance", mealGuidance(c));
        return result;
    }

    private String routePreference(TravelConstraints c) {
        String transport = c.getTransportPreference();
        // Explicit/current transport always wins over the low-walking compatibility fallback.
        if ("打车优先".equals(transport)) return "taxi";
        if ("地铁优先".equals(transport) || "公共交通优先".equals(transport)
                || "公交优先".equals(transport) || "综合交通优先".equals(transport)) return "transit";
        if ("步行优先".equals(transport)) return "walking";
        // The provider has no bicycle/driving implementation in this phase. Keep
        // the preference visible while using a conservative static walking route.
        if ("驾车优先".equals(transport) || "骑行优先".equals(transport)) return "unsupported";
        if ("低".equals(c.getWalkingTolerance())) return "transit";
        return "walking";
    }

    private String mealGuidance(TravelConstraints c) {
        if ("素食".equals(c.getDietPreference())) return "饮食策略：优先筛选素食可选项，具体餐厅与营业状态出发前确认。";
        if ("清真".equals(c.getDietPreference())) return "饮食策略：优先筛选清真可选项，具体餐厅与营业状态出发前确认。";
        if ("本地菜优先".equals(c.getDietPreference()) || "重庆火锅".equals(c.getDietPreference())) return "饮食策略：优先安排重庆本地菜与老火锅体验，具体店铺与消费出发前确认。";
        return "饮食策略：未指定饮食限制，餐饮安排保持弹性。";
    }

    private Map<String, Object> qualityMetrics(TravelConstraints c, int requestedDays, List<Map<String, Object>> days) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(Map.of("key", "durationDays", "satisfied", requestedDays <= 7, "note", requestedDays <= 7 ? "排程覆盖请求天数" : "请求天数超过当前排程上限 7 天"));
        checks.add(Map.of("key", "duplicateVenues", "satisfied", noDuplicates(days), "note", "全行程景点不重复"));

        List<String> stopNames = days.stream()
                .filter(d -> d.get("stops") instanceof List<?>)
                .flatMap(d -> ((List<?>) d.get("stops")).stream())
                .filter(s -> s instanceof Map<?, ?>)
                .map(s -> String.valueOf(((Map<?, ?>) s).get("name")))
                .toList();

        if (c.getMustVisit() != null && !c.getMustVisit().isEmpty()) {
            boolean allMustSatisfied = true;
            for (String must : c.getMustVisit()) {
                if (stopNames.stream().noneMatch(name -> name.contains(must))) {
                    allMustSatisfied = false;
                    break;
                }
            }
            checks.add(Map.of("key", "mustVisit", "satisfied", allMustSatisfied, "note", allMustSatisfied ? "必游景点已排入" : "部分必游景点未排入"));
        }

        if (c.getAvoid() != null && !c.getAvoid().isEmpty()) {
            boolean avoidSatisfied = true;
            for (String av : c.getAvoid()) {
                if (stopNames.stream().anyMatch(name -> name.contains(av))) {
                    avoidSatisfied = false;
                    break;
                }
            }
            checks.add(Map.of("key", "avoidance", "satisfied", avoidSatisfied, "note", avoidSatisfied ? "避开项未包含在行程中" : "行程中包含了避开项"));
        }

        long satisfied = checks.stream().filter(item -> Boolean.TRUE.equals(item.get("satisfied"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("constraintSatisfaction", Map.of("score", checks.isEmpty() ? 0 : (double) satisfied / checks.size(), "satisfied", satisfied, "total", checks.size(), "checks", checks));
        result.put("poiCoverage", 0.0);
        result.put("routeCoverage", 0.0);
        result.put("weatherCoverage", 0.0);
        result.put("dataCoverage", 0.0);
        result.put("unknownFacts", 1);
        result.put("dynamicFacts", 1);
        return result;
    }

    private boolean noDuplicates(List<Map<String, Object>> days) {
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> day : days) if (day.get("stops") instanceof List<?> stops) for (Object item : stops) if (item instanceof Map<?, ?> stop) if (!ids.add(stop.get("venueId"))) return false;
        return true;
    }

    private Map<String, Object> sourceStatus() { return Map.of("poi", "待查询", "route", "待查询", "weather", "待查询", "fallback", true); }
    private Map<String, Object> retrievalStatus() { return Map.of("mode", "java-planner-local", "status", "未执行外部检索", "facts", List.of(), "citations", List.of()); }
    private Map<String, Object> summary(int dayCount) { return Map.of("confirmed", List.of("路线骨架", "景点顺序", dayCount + "日节奏"), "dynamic", List.of("天气", "实时人流", "公交与步行耗时"), "unknown", List.of("门票与预约", "营业时间", "最终消费"), "conflict", List.of("热门景点高峰期停留时长需现场决策")); }
    private Map<String, Object> weather() { return fact("天气", "未知·待查询", "未知", "天气必须在出行前再次确认，当前尚未取得动态结果。", List.of(citation("高德天气接口", "/v3/weather/weatherInfo"))); }

    /** Returns an ISO date only when the request contains a concrete date or weekday. */
    private String itineraryDate(TravelConstraints constraints, int day) {
        String source = String.join(" ", constraints.getArrivalAt(), constraints.getRawPrompt());
        java.util.regex.Matcher iso = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(source);
        if (iso.find()) {
            try {
                return LocalDate.parse(iso.group(1), DateTimeFormatter.ISO_LOCAL_DATE).plusDays(day - 1).toString();
            } catch (DateTimeParseException ignored) { }
        }
        java.util.regex.Matcher weekday = Pattern.compile("(?:周|星期)([一二三四五六日天])").matcher(source);
        if (!weekday.find()) return null;
        DayOfWeek target = switch (weekday.group(1)) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
        return LocalDate.now().with(TemporalAdjusters.nextOrSame(target)).plusDays(day - 1).toString();
    }

    private String dateLabel(TravelConstraints c, int day) { return "未提供".equals(c.getArrivalAt()) ? "旅行第" + day + "天" : c.getArrivalAt() + "起第" + day + "天"; }

    private List<Map<String, Object>> defaultFacts(Attraction a) {
        return List.of(fact("开放与营业", "未知·需实时确认", "未知", "当前规划未取得开放状态。", List.of(citation("高德 POI 详情接口", "/v5/place/detail"))),
                fact("门票与消费", "未知·需用户确认", "未知", "价格与预约规则可能变化。", List.of(citation("用户确认项", "用户输入"))),
                fact("到达方式", a.getWalk(), "未知", "静态目录中的到达提示仅作回退，路线需由高德接口或出发前确认。", List.of(citation("高德路径规划接口", "/v5/direction/walking"))));
    }

    private List<Map<String, Object>> citations() { return List.of(citation("高德 POI 搜索接口", "/v5/place/text"), citation("高德步行路线接口", "/v5/direction/walking"), citation("高德公交路线接口", "/v5/direction/transit/integrated")); }
    private Map<String, Object> citation(String title, String endpoint) { return citation(title, endpoint, "待查询"); }
    private Map<String, Object> citation(String title, String endpoint, String status) { return Map.of("title", title, "publisher", "高德开放平台", "endpoint", endpoint, "status", status, "note", "当前为规划阶段的保守来源标记。"); }
    private Map<String, Object> fact(String label, Object value, String status, String note, List<Map<String, Object>> citations) { return Map.of("label", label, "value", value, "status", status, "note", note, "citations", citations); }
    private Map<String, Object> mapContext(String location) {
        List<Double> coordinates = new ArrayList<>();
        if (location != null && location.contains(",")) try { coordinates.add(Double.parseDouble(location.split(",")[0])); coordinates.add(Double.parseDouble(location.split(",")[1])); } catch (NumberFormatException ignored) { }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("coordinates", coordinates.isEmpty() ? null : coordinates);
        result.put("polyline", List.of());
        result.put("routeFromPrevious", null);
        return result;
    }
    private Map<String, Object> versionEntry(int version, String label, List<String> changed, String reason) { return Map.of("version", version, "label", label, "changedSegments", changed, "reason", reason, "createdAt", Instant.now().toString()); }

    private ScoreBreakdown scoreBreakdown(Object value) {
        if (value instanceof ScoreBreakdown sb) return sb;
        if (value instanceof Map<?, ?> map) {
            int interest = (int) number(map.get("interest"));
            int companion = (int) number(map.get("companion"));
            int walking = (int) number(map.get("walking"));
            int dining = (int) number(map.get("dining"));
            int mustVisit = (int) number(map.get("mustVisit"));
            int avoid = (int) number(map.get("avoid"));
            int explicit = (int) number(map.get("explicit"));
            int total = (int) number(map.get("total"));
            List<String> codes = new ArrayList<>();
            if (map.get("reasonCodes") instanceof List<?> list) {
                for (Object item : list) if (item != null) codes.add(String.valueOf(item));
            }
            return new ScoreBreakdown(interest, companion, walking, dining, mustVisit, avoid, explicit, total, codes);
        }
        return ScoreBreakdown.zero();
    }

    private long number(Object value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private List<String> explicitIds(String prompt, Map<String, Attraction> byId) {
        Set<String> result = new LinkedHashSet<>();
        for (PatternId item : EXPLICIT_ATTRACTIONS) if (Pattern.compile(item.pattern()).matcher(prompt == null ? "" : prompt).find() && byId.containsKey(item.id())) result.add(item.id());
        return new ArrayList<>(result);
    }

    private record PatternId(String pattern, String id) { }
    private record Spec(String id, String attractionId, String time, String duration, String summary, String detail) { }
    private record Scored(Attraction attraction, int score, ScoreBreakdown breakdown) { }
}

package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.DynamicGeoCentroidResolver;
import com.ai.guide.domain.planner.engine.MealGapSolver;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.model.StopPlan;
import com.ai.guide.domain.planner.model.AdjustmentPreview;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge;
import com.ai.guide.domain.planner.service.PlannerDiningContextMapper;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.language.IntentLanguagePolicy;
import com.ai.guide.domain.planner.service.util.ChineseDateTimeParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/**
 * 站点追加调整策略（处理 ADD_STOP 意图）
 *
 * 核心职责：
 * 1. 甄别途经多站连游 vs 返程终点；
 * 2. 识别前置/后置方位语法（在...之前 vs 在...之后），精确锚定前后插入下标；
 * 3. 沿途加餐、下午茶歇与甜水铺（结合 DiningCategoryGrounder 泛需求判定与高德实景搜索）；
 * 4. 沿途加景点（最优邻近动线插入与自适应紧凑度控制）。
 */
@Component
public class AddStopAdjustmentStrategy implements AdjustmentStrategy {

    private final AttractionService attractionService;
    private final ItineraryBuilderPort itineraryBuilder;
    private final RouteAwarePlanner routeAwarePlanner;
    private final CandidateRankingEngine candidateRankingEngine;
    private final ItineraryTimeEngine itineraryTimeEngine;
    private final DiningCategoryGrounder diningCategoryGrounder;
    private AttractionAliasMatcher aliasMatcher;
    private PlanVerifier planVerifier;

    @Autowired(required = false)
    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

    @Autowired
    private DynamicGeoCentroidResolver geoCentroidResolver;

    @Autowired
    private MealGapSolver mealGapSolver;

    @Autowired
    private PlannerTaxonomyPort taxonomy;

    @Autowired
    public AddStopAdjustmentStrategy(AttractionService attractionService,
                                     ItineraryBuilderPort itineraryBuilder,
                                     RouteAwarePlanner routeAwarePlanner,
                                     CandidateRankingEngine candidateRankingEngine,
                                     ItineraryTimeEngine itineraryTimeEngine,
                                     DiningCategoryGrounder diningCategoryGrounder) {
        this.attractionService = attractionService;
        this.itineraryBuilder = itineraryBuilder;
        this.routeAwarePlanner = routeAwarePlanner;
        this.candidateRankingEngine = candidateRankingEngine;
        this.itineraryTimeEngine = itineraryTimeEngine;
        this.diningCategoryGrounder = diningCategoryGrounder;
        this.aliasMatcher = new AttractionAliasMatcher(attractionService);
    }

    public void setRuntimeAttractionDetailService(RuntimeAttractionDetailService runtimeAttractionDetailService) {
        this.runtimeAttractionDetailService = runtimeAttractionDetailService;
    }

    public void setDiningVenueService(DiningVenueService diningVenueService) {
        this.diningVenueService = diningVenueService;
    }

    public void setAliasMatcher(AttractionAliasMatcher aliasMatcher) {
        if (aliasMatcher != null) this.aliasMatcher = aliasMatcher;
    }

    public void setPlanVerifier(PlanVerifier planVerifier) {
        if (planVerifier != null) this.planVerifier = planVerifier;
    }

    public void setDiningScheduling(PlannerTaxonomyPort taxonomy, MealGapSolver mealGapSolver, DynamicGeoCentroidResolver geoCentroidResolver) {
        this.taxonomy = taxonomy;
        this.mealGapSolver = mealGapSolver;
        this.geoCentroidResolver = geoCentroidResolver;
    }

    @Override
    public boolean supports(ConversationIntentType intentType) {
        return intentType == ConversationIntentType.ADD_STOP;
    }

    @Override
    public void apply(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        // Structured dining semantics are authoritative even when a long sentence mentions route landmarks.
        if (intent.getGenericDiningCraving() != null || !safe(intent.getCuisineOrPlace()).isBlank()) {
            handleDiningAddition(ctx);
            return;
        }

        String rawMsg = intent.getRawMessage() != null ? intent.getRawMessage().replaceAll("[\\u200B-\\u200D\\uFEFF]", "") : "";
        String city = resolveTripCity(draftTrip, constraints);
        List<Attraction> cityCatalog = city == null ? List.of() : attractionService.list(city, null, null);
        Set<String> cityIds = cityCatalog.stream().map(Attraction::getId).collect(java.util.stream.Collectors.toSet());
        List<String> mentions = aliasMatcher.findExplicitAttractionIdsInOrder(rawMsg, null);
        if (mentions.stream().anyMatch(id -> !cityIds.contains(id))) {
            rejectOutsideCity(ctx, city);
            return;
        }
        String explicitId = intent.getTargetAttractionId();
        if (explicitId != null && !cityIds.contains(explicitId) && !explicitId.startsWith("amap-")) {
            rejectOutsideCity(ctx, city);
            return;
        }
        List<String> explicitInOrder = aliasMatcher.findExplicitAttractionIdsInOrder(rawMsg, cityIds);
        Set<String> used = usedVenues(draftTrip);

        // Multi-stop sequential route addition detection
        List<String> validCandidateVenues = new ArrayList<>();
        for (String vId : explicitInOrder) {
            String canonical = aliasMatcher.getVenueIdToCanonicalMap().getOrDefault(vId, vId);
            boolean isTransitWaypoint = rawMsg.matches(".*(?:先回|先到|先去|回\\s*" + Pattern.quote(canonical) + "[，, ]*?[^。！？!?;]{0,25}?(?:换|吃|拿|放|看|歇|再|然后|之后|接着)).*");
            boolean isReturnTerminus = !isTransitWaypoint && rawMsg.matches(".*(?:最后|随后|晚间|晚上|游玩后|结束)?(?:打车|坐车|乘车|步行)?(?:返回|回|回住处|回酒店)\\s*" + Pattern.quote(canonical) + "(?:附近|商圈|酒店|住处)?(?:结束|休息|住宿|离开|。|！|!|$)");
            if (isReturnTerminus) {
                continue;
            }
            validCandidateVenues.add(vId);
        }

        List<String> newVenuesToAdd = new ArrayList<>();
        String anchorVenueId = null;
        boolean isBefore = false;

        for (String vId : validCandidateVenues) {
            if (used.contains(vId)) {
                if (anchorVenueId == null) {
                    anchorVenueId = vId;
                }
            } else {
                if (!newVenuesToAdd.contains(vId)) {
                    newVenuesToAdd.add(vId);
                }
            }
        }

        if (anchorVenueId != null) {
            String anchorCanonical = aliasMatcher.getVenueIdToCanonicalMap().getOrDefault(anchorVenueId, anchorVenueId);
            String anchorPattern = Pattern.quote(anchorCanonical);
            if (rawMsg.matches(".*(?:在|去)?\\s*" + anchorPattern + "\\s*(?:之前|前|前先|之前先|前先去|前面).*")
                    || rawMsg.matches(".*(?:加在|放在|排在)\\s*" + anchorPattern + "\\s*(?:前面|之前).*")
                    || rawMsg.matches(".*(?:先去|先到|先逛|先玩)?.*再去\\s*" + anchorPattern + ".*")) {
                isBefore = true;
            }
        }

        if (newVenuesToAdd.size() > 1 || (newVenuesToAdd.size() == 1 && anchorVenueId != null && (rawMsg.contains("之后接") || rawMsg.contains("后面接") || rawMsg.contains("动线") || rawMsg.contains("接上") || rawMsg.contains("接入") || isBefore))) {
            handleMultiStopRouteAddition(ctx, newVenuesToAdd, anchorVenueId, isBefore, rawMsg, cityCatalog);
            return;
        }

        handleSingleStopAddition(ctx, rawMsg, cityCatalog);
    }

    private void handleMultiStopRouteAddition(AdjustmentContext ctx,
                                              List<String> newVenuesToAdd,
                                              String anchorVenueId,
                                              boolean isBefore,
                                              String rawMsg, List<Attraction> cityCatalog) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = new LinkedHashMap<>(ctx.getAttractionsById());
        cityCatalog.forEach(attraction -> byId.put(attraction.getId(), attraction));

        int targetDayNumber;
        int insertIndex = -1;
        Map<String, Object> targetDayMap = null;

        if (anchorVenueId != null) {
            StopLocation anchorLoc = locateStopByVenue(draftTrip, anchorVenueId);
            if (anchorLoc != null) {
                targetDayMap = anchorLoc.day();
                targetDayNumber = number(anchorLoc.day().get("day"));
                insertIndex = isBefore ? anchorLoc.index() : anchorLoc.index() + 1;
            } else {
                targetDayNumber = intent.getDayNumber() != null ? intent.getDayNumber() : (ctx.getPageContext().getActiveDay() != null ? ctx.getPageContext().getActiveDay() : 1);
                targetDayMap = findDay(draftTrip, targetDayNumber);
            }
        } else {
            targetDayNumber = intent.getDayNumber() != null ? intent.getDayNumber() : (ctx.getPageContext().getActiveDay() != null ? ctx.getPageContext().getActiveDay() : 1);
            targetDayMap = findDay(draftTrip, targetDayNumber);
        }

        if (targetDayMap == null && !days(draftTrip).isEmpty()) {
            targetDayMap = days(draftTrip).get(0);
            targetDayNumber = number(targetDayMap.get("day"));
        }

        if (targetDayMap != null) {
            List<Map<String, Object>> targetDayStops = dayStops(targetDayMap);
            if (insertIndex < 0 || insertIndex > targetDayStops.size()) {
                insertIndex = targetDayStops.size();
            }

            Integer baseTimeMinute = null;
            Integer explicitTime = ChineseDateTimeParser.extractExplicitTimeMinutes(rawMsg);
            if (explicitTime != null) {
                baseTimeMinute = explicitTime;
            }

            // Option 1: Full sequential route addition
            Map<String, Object> draftOpt1 = deepCopy(sourceTrip, ctx.getObjectMapper());
            Map<String, Object> dayOpt1 = findDay(draftOpt1, targetDayNumber);
            List<Map<String, Object>> stopsOpt1 = dayStops(dayOpt1);
            int curInsert = insertIndex;
            Integer curMinute = baseTimeMinute;
            List<String> opt1AddedStopIds = new ArrayList<>();
            List<String> addedNames = new ArrayList<>();
            boolean option1Feasible = true;

            for (String vId : newVenuesToAdd) {
                Attraction attr = byId.get(vId);
                if (attr == null) continue;
                String cleanId = attr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                String slotTime = curMinute == null ? null : itineraryTimeEngine.formatTime(curMinute);

                Map<String, Object> rawStop = itineraryBuilder.createStop(
                        attr, stopId, slotTime, attr.getDuration(),
                        "串联游览：" + attr.getName() + "（" + PlannerService.stripTrailingPunctuation(attr.getSummary()) + "）。",
                        attr.getIntro()
                );
                rawStop.put("time", slotTime);
                rawStop.put("startTime", slotTime);
                StopPlan addedStop = StopPlan.fromMap(rawStop);
                stopsOpt1.add(Math.min(curInsert, stopsOpt1.size()), addedStop.toMap());
                curInsert++;
                int dur = itineraryTimeEngine.parseKnownDurationMinutes(attr.getDuration());
                if (curMinute != null && dur > 0) {
                    curMinute += dur;
                } else {
                    curMinute = null;
                }
                opt1AddedStopIds.add(stopId);
                addedNames.add(attr.getName());
            }

            ctx.getRequiredStopIdsByOption().put("option-1", List.copyOf(opt1AddedStopIds));

            for (int i = insertIndex; i < stopsOpt1.size(); i++) {
                Map<String, Object> s = stopsOpt1.get(i);
                Integer sMin = itineraryTimeEngine.parseExplicitTimeMinutes(safe(s.get("time")));
                if (sMin != null && sMin >= 24 * 60) {
                    option1Feasible = false;
                    ctx.getReasonCodes().add("MIDNIGHT_OVERFLOW");
                    ctx.getAlternatives().add("插入新站点后导致后续行程（" + s.get("name") + "）推迟至深夜/次日凌晨，建议调整至其他日期");
                    break;
                }
            }

            resolveTimingAndAffectedDay(draftOpt1, targetDayNumber, byId, constraints, insertIndex);
            stopsOpt1 = dayStops(findDay(draftOpt1, targetDayNumber));
            if (hasUnresolvedTimedInsertion(stopsOpt1, insertIndex)) {
                option1Feasible = false;
                recordUnknownRouteDuration(ctx);
            }

            for (int i = insertIndex; i < stopsOpt1.size(); i++) {
                Map<String, Object> s = stopsOpt1.get(i);
                Integer sMin = itineraryTimeEngine.parseExplicitTimeMinutes(safe(s.get("time")));
                if (sMin != null && sMin >= 24 * 60) {
                    option1Feasible = false;
                    if (!ctx.getReasonCodes().contains("MIDNIGHT_OVERFLOW")) {
                        ctx.getReasonCodes().add("MIDNIGHT_OVERFLOW");
                        ctx.getAlternatives().add("插入新站点后导致后续行程（" + s.get("name") + "）推迟至深夜/次日凌晨，建议调整至其他日期");
                    }
                    break;
                }
            }

            PlanVerifier verifier = planVerifier == null ? new PlanVerifier() : planVerifier;
            PlanVerifier.VerificationResult verification = verifier.verify(days(draftOpt1), constraints);
            option1Feasible &= verification.feasible();
            verification.violations().forEach(v -> ctx.getReasonCodes().add(v.type()));
            Set<String> retained = stopsOpt1.stream().map(s -> safe(s.get("id"))).collect(java.util.stream.Collectors.toSet());
            if (!retained.containsAll(opt1AddedStopIds)) {
                option1Feasible = false;
                ctx.getReasonCodes().add("REQUESTED_STOP_NOT_RETAINED");
                ctx.getAlternatives().add("当前时间无法完整容纳所有指定站点，请选择单站方案或调整日期");
            }
            ctx.getOptionPlans().put("option-1", draftOpt1);

            long addedVisitMinutes = stopsOpt1.stream()
                    .filter(s -> opt1AddedStopIds.contains(safe(s.get("id"))))
                    .mapToLong(s -> itineraryTimeEngine.parseKnownDurationMinutes(safe(s.get("duration"))))
                    .sum();
            AdjustmentPreview cand1 = new AdjustmentPreview(
                    "option-1",
                    newVenuesToAdd.get(0),
                    String.join(" + ", addedNames) + "（完整动线）",
                    String.join(" + ", addedNames),
                    "依序串联" + String.join("、", addedNames) + "，顺路衔接且游玩动线连贯，适合完整打卡该片区核心地标。",
                    "约 " + addedVisitMinutes + " 分钟",
                    byId.get(newVenuesToAdd.get(0)) != null ? byId.get(newVenuesToAdd.get(0)).getDistrict() : "主城区",
                    "低",
                    "适合少走路、行程充实、一次性体验连贯风貌路线的旅行者。",
                    false,
                    java.util.Collections.emptyMap()
            );
            ctx.getCandidateReplacements().add(cand1.toMap());

            // Option 2: First stop only
            if (newVenuesToAdd.size() > 1) {
                String firstVenueId = newVenuesToAdd.get(0);
                Attraction firstAttr = byId.get(firstVenueId);
                if (firstAttr != null) {
                    Map<String, Object> draftOpt2 = deepCopy(sourceTrip, ctx.getObjectMapper());
                    Map<String, Object> dayOpt2 = findDay(draftOpt2, targetDayNumber);
                    List<Map<String, Object>> stopsOpt2 = dayStops(dayOpt2);
                    String cleanId = firstAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                    String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                    String slotTime = baseTimeMinute == null ? null : itineraryTimeEngine.formatTime(baseTimeMinute);
                    Map<String, Object> rawStop2 = itineraryBuilder.createStop(
                            firstAttr, stopId, slotTime, firstAttr.getDuration(),
                            "单站体验：" + firstAttr.getName(), firstAttr.getIntro()
                    );
                    rawStop2.put("time", slotTime);
                    rawStop2.put("startTime", slotTime);
                    StopPlan addedStop2 = StopPlan.fromMap(rawStop2);
                    stopsOpt2.add(Math.min(insertIndex, stopsOpt2.size()), addedStop2.toMap());
                    ctx.getRequiredStopIdsByOption().put("option-2", List.of(stopId));
                    resolveTimingAndAffectedDay(draftOpt2, targetDayNumber, byId, constraints, insertIndex);
                    ctx.getOptionPlans().put("option-2", draftOpt2);

                    String opt2Label = "（精选单站）";
                    AdjustmentPreview cand2 = new AdjustmentPreview(
                            "option-2",
                            firstAttr.getId(),
                            firstAttr.getName() + opt2Label,
                            firstAttr.getName(),
                            "仅保留" + firstAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(firstAttr.getSummary()) + "），游玩节奏更紧凑轻松。",
                            firstAttr.getDuration(),
                            firstAttr.getDistrict(),
                            firstAttr.getWalkDifficulty() != null ? firstAttr.getWalkDifficulty() : "低",
                            firstAttr.getFit() != null ? firstAttr.getFit() : "适合时间有限、想体验核心地标的旅行者。",
                            false,
                            java.util.Collections.emptyMap()
                    );
                    ctx.getCandidateReplacements().add(cand2.toMap());
                }
            }

            // Option 3: Last stop only
            if (newVenuesToAdd.size() > 2) {
                String lastVenueId = newVenuesToAdd.get(newVenuesToAdd.size() - 1);
                Attraction lastAttr = byId.get(lastVenueId);
                if (lastAttr != null) {
                    Map<String, Object> draftOpt3 = deepCopy(sourceTrip, ctx.getObjectMapper());
                    Map<String, Object> dayOpt3 = findDay(draftOpt3, targetDayNumber);
                    List<Map<String, Object>> stopsOpt3 = dayStops(dayOpt3);
                    String cleanId = lastAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                    String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                    String slotTime = baseTimeMinute == null ? null : itineraryTimeEngine.formatTime(baseTimeMinute);
                    Map<String, Object> rawStop3 = itineraryBuilder.createStop(
                            lastAttr, stopId, slotTime, lastAttr.getDuration(),
                            "休闲体验：" + lastAttr.getName(), lastAttr.getIntro()
                    );
                    rawStop3.put("time", slotTime);
                    rawStop3.put("startTime", slotTime);
                    StopPlan addedStop3 = StopPlan.fromMap(rawStop3);
                    stopsOpt3.add(Math.min(insertIndex, stopsOpt3.size()), addedStop3.toMap());
                    ctx.getRequiredStopIdsByOption().put("option-3", List.of(stopId));
                    resolveTimingAndAffectedDay(draftOpt3, targetDayNumber, byId, constraints, insertIndex);
                    ctx.getOptionPlans().put("option-3", draftOpt3);

                    String opt3Label = "（精选单站）";
                    AdjustmentPreview cand3 = new AdjustmentPreview(
                            "option-3",
                            lastAttr.getId(),
                            lastAttr.getName() + opt3Label,
                            lastAttr.getName(),
                            "重点安排" + lastAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(lastAttr.getSummary()) + "），全程节奏舒适平缓。",
                            lastAttr.getDuration(),
                            lastAttr.getDistrict(),
                            lastAttr.getWalkDifficulty() != null ? lastAttr.getWalkDifficulty() : "低",
                            lastAttr.getFit() != null ? lastAttr.getFit() : "适合喜爱开阔视野、慢节奏观景的长辈友好路线。",
                            false,
                            java.util.Collections.emptyMap()
                    );
                    ctx.getCandidateReplacements().add(cand3.toMap());
                }
            }

            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().addAll(opt1AddedStopIds);
            ctx.setFeasible(option1Feasible);
            ctx.getAdjustmentDiagnostics().put("addedStopName", String.join("+", addedNames));
            ctx.getAdjustmentDiagnostics().put("addedDayNumber", targetDayNumber);
        }
    }

    private void handleSingleStopAddition(AdjustmentContext ctx, String rawMsg, List<Attraction> cityCatalog) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        if (isDiningIntent(intent)) {
            handleDiningAddition(ctx);
            return;
        }
        String catalogCity = resolveTripCity(draftTrip, constraints);

        String targetVenueId = intent.getTargetAttractionId();
        if (targetVenueId == null && intent.getTargetStopId() != null) {
            StopLocation s = locateStop(draftTrip, intent.getTargetStopId());
            if (s != null) targetVenueId = String.valueOf(s.stop().get("venueId"));
        }
        if (targetVenueId == null && !isDiningIntent(intent) && intent.getRequestedVenueName() != null) {
            for (Attraction a : cityCatalog) {
                if (intent.getRequestedVenueName().contains(a.getName()) || a.getName().contains(intent.getRequestedVenueName())) {
                    targetVenueId = a.getId();
                    break;
                }
            }
        }
        if (targetVenueId == null && !isDiningIntent(intent) && intent.getPreferences() != null && !intent.getPreferences().isEmpty()) {
            Set<String> used = usedVenues(draftTrip);
            String startCoord = extractStartCoordinate(draftTrip, constraints);
            CandidateRankingEngine.CandidateContext candContext = new CandidateRankingEngine.CandidateContext(null, null, null, startCoord);
            List<Attraction> candidates = findCandidates(cityCatalog, candContext, used, constraints, intent);
            if (!candidates.isEmpty()) {
                targetVenueId = candidates.get(0).getId();
            }
        }
        String requestedId = targetVenueId;
        Attraction attraction = cityCatalog.stream().filter(a -> a.getId().equals(requestedId)).findFirst().orElse(null);
        if (targetVenueId != null && attraction == null && !targetVenueId.startsWith("amap-")) {
            rejectOutsideCity(ctx, catalogCity);
            return;
        }
        if (attraction == null && targetVenueId != null && targetVenueId.startsWith("amap-") && runtimeAttractionDetailService != null) {
            attraction = runtimeAttractionDetailService.toAttraction(targetVenueId);
            if (attraction != null && !sameCity(catalogCity, attraction.getCity())) {
                rejectOutsideCity(ctx, catalogCity);
                return;
            }
        }
        if (attraction == null && !isDiningIntent(intent) && runtimeAttractionDetailService != null
                && intent.getRequestedVenueName() != null && !intent.getRequestedVenueName().isBlank()) {
            String searchCity = catalogCity;
            Attraction dynamicPoi = runtimeAttractionDetailService.searchAndBuildAttraction(intent.getRequestedVenueName(), searchCity);
            if (dynamicPoi != null) {
                if (!sameCity(catalogCity, dynamicPoi.getCity())) {
                    rejectOutsideCity(ctx, catalogCity);
                    return;
                }
                attraction = dynamicPoi;
                targetVenueId = dynamicPoi.getId();
                byId.put(targetVenueId, dynamicPoi);
            }
        }

        if (attraction == null) {
            if (isDiningIntent(intent)) {
                handleDiningAddition(ctx);
                return;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("requiresClarification", true);
            String venue = intent.getRequestedVenueName() == null ? "该地点" : ("“" + intent.getRequestedVenueName() + "”");
            body.put("clarificationQuestion", "在高德地图上未能检索到" + venue + "。请尝试补充所属区县、详细路名，或直接告诉我特色打卡地标。");
            ctx.setFatalError(new PlannerService.ServiceResult(200, body));
            return;
        }

        // Check duplicate
        if (usedVenues(draftTrip).contains(targetVenueId)
                && !IntentLanguagePolicy.explicitRepeat(intent.getRawMessage())) {
            ctx.setFeasible(false);
            ctx.getReasonCodes().add("DUPLICATE_VENUE");
            ctx.getAlternatives().addAll(List.of("该景点已在行程中，无需重复添加", "选择其他未去过的景点", "取消添加"));
        }

        int totalTripDays = days(draftTrip).size();
        int dayNumber;
        if (intent.getDayNumber() != null && intent.getDayNumber() == totalTripDays + 1) {
            Map<String, Object> newDay = new LinkedHashMap<>();
            newDay.put("day", totalTripDays + 1);
            newDay.put("title", "第" + (totalTripDays + 1) + "天");
            // The displayed trip dates are authoritative; do not invent an opening weekday.
            List<Map<String,Object>> existingDays = days(draftTrip);
            if (!existingDays.isEmpty()) {
                try {
                    String nextDate = java.time.LocalDate.parse(safe(existingDays.get(existingDays.size() - 1).get("date")))
                            .plusDays(1).toString();
                    newDay.put("date", nextDate);
                    newDay.put("dateLabel", nextDate + " · 第" + (totalTripDays + 1) + "天");
                } catch (java.time.DateTimeException unknownDate) { /* retain an explicitly unknown date */ }
            }
            newDay.put("stops", new ArrayList<>());
            days(draftTrip).add(newDay);
            dayNumber = totalTripDays + 1;
        } else if (intent.getDayNumber() != null && findDay(draftTrip, intent.getDayNumber()) != null) {
            dayNumber = intent.getDayNumber();
        } else {
            int matchedDay = findBestMatchingDayForDistrict(draftTrip, attraction.getDistrict());
            if (matchedDay > 0 && findDay(draftTrip, matchedDay) != null) {
                dayNumber = matchedDay;
            } else if (ctx.getPageContext() != null && ctx.getPageContext().getActiveDay() != null && findDay(draftTrip, ctx.getPageContext().getActiveDay()) != null) {
                dayNumber = ctx.getPageContext().getActiveDay();
            } else {
                dayNumber = days(draftTrip).isEmpty() ? 1 : number(days(draftTrip).get(0).get("day"));
            }
        }

        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) {
            ctx.setFatalError(error(400, "目标日期不存在。", ctx));
            return;
        }

        List<Map<String, Object>> stops = dayStops(day);
        long scenicCount = stops.stream()
                .filter(s -> !"DINING".equals(s.get("type")) && !"餐".equals(s.get("icon")))
                .count();
        boolean denseDay = scenicCount >= 4;
        if (denseDay) {
            ctx.getAdjustmentDiagnostics().put("denseDay", true);
            ctx.getAdjustmentDiagnostics().put("totalDayScenicCount", scenicCount + 1);
            ctx.getAlternatives().addAll(List.of("替换当天已有某一景点", "保持原行程"));
        }

        String cleanId = attraction.getId().replaceAll("[^a-zA-Z0-9_-]", "");
        String stopId = "day" + dayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
        if (IntentLanguagePolicy.explicitRepeat(intent.getRawMessage())) stopId += "-" + java.util.UUID.randomUUID();
        Integer requestedMinute = ChineseDateTimeParser.extractExplicitTimeMinutes(rawMsg);
        String requestedClock = requestedMinute == null ? "待安排" : itineraryTimeEngine.formatTime(requestedMinute);
        Map<String, Object> rawStop = itineraryBuilder.createStop(
                attraction, stopId, requestedClock, attraction.getDuration(),
                "将" + attraction.getName() + "加入当前行程。", attraction.getIntro()
        );
        rawStop.put("time", requestedClock);
        rawStop.put("startTime", requestedClock);
        StopPlan addedStop = StopPlan.fromMap(rawStop);
        int addedIndex = stops.size();
        stops.add(addedStop.toMap());
        ctx.getChangedSegments().add(stopId);
        ctx.getRequiredStopIdsByOption().put("option-1", List.of(stopId));
        resolveTimingAndAffectedDay(draftTrip, dayNumber, byId, constraints, addedIndex);
        if (hasUnresolvedTimedInsertion(dayStops(findDay(draftTrip, dayNumber)), addedIndex)) recordUnknownRouteDuration(ctx);
        ctx.getOptionPlans().put("option-1", draftTrip);

        AdjustmentPreview cand = new AdjustmentPreview(
                "option-1",
                attraction.getId(),
                attraction.getName(),
                intent.getRequestedVenueName() != null ? intent.getRequestedVenueName() : attraction.getName(),
                attraction.getSummary(),
                attraction.getDuration(),
                attraction.getDistrict(),
                attraction.getWalkDifficulty() != null ? attraction.getWalkDifficulty() : "低",
                attraction.getFit() != null ? attraction.getFit() : "符合用户偏好",
                false,
                java.util.Collections.emptyMap()
        );
        ctx.getCandidateReplacements().add(cand.toMap());

        ctx.getAdjustmentDiagnostics().put("addedStopName", attraction.getName());
        ctx.getAdjustmentDiagnostics().put("addedDayNumber", dayNumber);
    }

    private boolean sameCity(String expected, String actual) {
        return expected != null && actual != null && !expected.isBlank()
                && expected.trim().replaceFirst("市$", "").equals(actual.trim().replaceFirst("市$", ""));
    }

    private void rejectOutsideCity(AdjustmentContext ctx, String city) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("requiresClarification", true);
        body.put("reasonCode", "TARGET_CITY_MISMATCH");
        body.put("clarificationQuestion", "当前行程目的地为" + (city == null ? "待确认城市" : city)
                + "，请求中的景点不在该城市目录内或所属城市尚未核实。请确认目的地；跨城游览需要另行规划。");
        ctx.setFatalError(new PlannerService.ServiceResult(200, body));
    }

    private void handleDiningAddition(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        TravelConstraints constraints = ctx.getConstraints();

        int dayNumber = (intent.getDayNumber() != null && findDay(draftTrip, intent.getDayNumber()) != null)
                ? intent.getDayNumber()
                : (ctx.getPageContext().getActiveDay() != null && findDay(draftTrip, ctx.getPageContext().getActiveDay()) != null
                        ? ctx.getPageContext().getActiveDay()
                        : (days(draftTrip).isEmpty() ? 1 : number(days(draftTrip).get(0).get("day"))));
        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) {
            ctx.setFatalError(error(400, "目标日期不存在。", ctx));
            return;
        }
        List<Map<String, Object>> stops = dayStops(day);

        String slotType = diningCategoryGrounder.resolveSlotType(intent);
        String requestedFlavor = diningCategoryGrounder.resolveRequestedFlavor(intent, "餐饮");
        var mealWindow = taxonomy == null ? null : taxonomy.getMealWindow(slotType);
        if (mealWindow == null || mealGapSolver == null) {
            ctx.setFeasible(false);
            ctx.getReasonCodes().add("MEAL_WINDOW_UNAVAILABLE");
            return;
        }
        int plannedInsertIdx = stops.size();
        for (int si = 0; si < stops.size(); si++) {
            Integer minutes = itineraryTimeEngine.parseExplicitTimeMinutes(safe(stops.get(si).get("time")));
            if (minutes != null && minutes > mealWindow.idealMinute()) {
                plannedInsertIdx = si;
                break;
            }
        }

        Map<String, Object> anchorStop = null;
        if (ctx.getPageContext() != null && ctx.getPageContext().getSelectedStopId() != null && !ctx.getPageContext().getSelectedStopId().isBlank()) {
            StopLocation selectedLoc = locateStop(draftTrip, ctx.getPageContext().getSelectedStopId());
            if (selectedLoc != null && selectedLoc.day() == day) {
                anchorStop = selectedLoc.stop();
            }
        }
        if (anchorStop == null) {
            if (plannedInsertIdx > 0 && plannedInsertIdx - 1 < stops.size()) {
                anchorStop = stops.get(plannedInsertIdx - 1);
            } else if (plannedInsertIdx < stops.size()) {
                anchorStop = stops.get(plannedInsertIdx);
            } else if (!stops.isEmpty()) {
                anchorStop = stops.get(stops.size() - 1);
            }
        }

        Attraction anchor = anchorStop != null ? resolveStopAnchor(anchorStop, itineraryBuilder) : null;
        String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank() && !"待确认".equals(anchor.getDistrict()))
                ? anchor.getDistrict()
                : (anchorStop != null ? extractDistrictFromStop(anchorStop) : "");

        String directDiningName = firstNonBlank(intent.getCuisineOrPlace(), intent.getReplacementPlaceName(), intent.getRequestedVenueName());
        if ((directDiningName == null || directDiningName.isBlank()) && intent.getRawMessage() != null) {
            Matcher bm = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(intent.getRawMessage());
            if (bm.find()) {
                directDiningName = bm.group(1).trim();
            } else {
                Matcher rm = Pattern.compile("(?:改成|换成|替换为|改为|换为|换到|改到|加上|添加|加个|吃个|去吃)\\s*([^\\s，,。！!？?；;]+)").matcher(intent.getRawMessage());
                if (rm.find()) {
                    String cand = rm.group(1).trim();
                    if (cand.matches(".*[（(].*[）)]?$") && !cand.matches(".*[（(][^）)]*店[）)]$")) {
                        cand = cand.replaceFirst("[（(][^）)]*[）)]?$", "").trim();
                    }
                    if (!cand.isBlank()) {
                        directDiningName = cand;
                    }
                }
            }
        }

        boolean isGenericDining = diningCategoryGrounder.isGenericDiningCraving(intent, directDiningName);
        boolean isDirectDining = !isGenericDining;

        List<com.ai.guide.domain.dining.model.DiningOption> diningCandidates = new ArrayList<>();
        if (isDirectDining) {
            String city = resolveTripCity(draftTrip, constraints);
            String searchArea = firstNonBlank(district, city);
            String coordinate = anchor != null && isValidCoordinate(anchor.getLocation()) ? anchor.getLocation()
                    : (geoCentroidResolver == null ? null
                    : geoCentroidResolver.resolve(draftTrip, constraints, dayStops(day)));
            if (diningVenueService != null && searchArea != null && coordinate != null) {
                try {
                    var found = diningVenueService.searchLiveDiningOrTea(searchArea, coordinate, directDiningName, slotType, 1, city);
                    if (found != null && !found.isEmpty() && matchesRequestedDiningVenue(directDiningName, found.get(0).getName())) {
                        var option = verifiedDiningOption(found.get(0), slotType, diningCategoryGrounder);
                        if (option != null) diningCandidates.add(option);
                    }
                } catch (RuntimeException ignored) {
                    ctx.getAdjustmentDiagnostics().put("diningSearchStatus", "UNAVAILABLE");
                }
            }
            if (diningCandidates.isEmpty()) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("DINING_POI_UNRESOLVED");
                ctx.getAlternatives().add("核实餐厅名称或选择其他餐厅");
                return;
            }
            ctx.getAdjustmentDiagnostics().put("replacementMode", "DIRECT");
            ctx.getAdjustmentDiagnostics().put("directReplacement", true);
            ctx.getAdjustmentDiagnostics().put("requestedReplacementName", directDiningName);
        } else {
            diningCandidates.addAll(diningVenueService == null ? List.of() : diningVenueService.resolveDiningReplacementCandidates(
                    district, "", requestedFlavor, slotType, anchor, PlannerDiningContextMapper.from(constraints)));
            boolean isTea = "TEA_BREAK".equals(slotType);
            ctx.getAdjustmentDiagnostics().put("replacementMode", isTea ? "TEA_BREAK" : "DINING_ADD");
            ctx.getAdjustmentDiagnostics().put("requestedFlavor", requestedFlavor);
            if (diningCandidates.isEmpty()) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("DINING_CANDIDATES_UNAVAILABLE");
                ctx.getAlternatives().add("扩大搜索范围或选择其他餐饮地点");
                return;
            }
        }

        ctx.getAdjustmentDiagnostics().put("sourceStopName", anchorStop != null ? String.valueOf(anchorStop.getOrDefault("name", "沿途加站")) : "沿途加站");
        ctx.getAdjustmentDiagnostics().put("forceApplicable", true);

        String baseStopId = "day" + dayNumber + "-added-dining";
        Set<String> existingStopIds = dayStops(findDay(sourceTrip, dayNumber)).stream()
                .map(stop -> String.valueOf(stop.get("id"))).collect(java.util.stream.Collectors.toSet());
        String effectiveStopId = baseStopId;
        for (int suffix = 2; existingStopIds.contains(effectiveStopId); suffix++) {
            effectiveStopId = baseStopId + "-" + suffix;
        }

        for (int i = 0; i < diningCandidates.size(); i++) {
            com.ai.guide.domain.dining.model.DiningOption dining = diningCandidates.get(i);
            String optId = "option-" + (i + 1);
            Map<String, Object> optionDraft = deepCopy(sourceTrip, ctx.getObjectMapper());
            Map<String, Object> optDay = findDay(optionDraft, dayNumber);
            if (optDay == null) continue;
            List<Map<String, Object>> optStops = dayStops(optDay);

            Attraction meal = Attraction.builder().id(dining.id()).name(dining.name()).location(dining.location()).district(district).build();
            var placement = mealGapSolver.findGap(optStops, meal,
                    itineraryTimeEngine.parseKnownDurationMinutes(dining.duration()), mealWindow,
                    constraints == null ? null : constraints.getTransportPreference(), itineraryBuilder);
            if (placement.isEmpty()) continue;
            String effectiveSlotTime = itineraryTimeEngine.formatTime(placement.get().startMinute());
            Map<String, Object> rawDiningStop = itineraryBuilder.createDiningStop(dining, effectiveStopId, effectiveSlotTime, district);
            rawDiningStop.put("time", effectiveSlotTime);
            rawDiningStop.put("startTime", effectiveSlotTime);
            rawDiningStop.put("district", district);
            StopPlan addedDiningStop = StopPlan.fromMap(rawDiningStop);
            optStops.add(placement.get().index(), addedDiningStop.toMap());
            mealGapSolver.applyRouteMetadata(optStops, placement.get());
            ctx.getAdjustmentDiagnostics().put("diningRouteDataStatus", "ESTIMATED");
            optId = "option-" + (ctx.getOptionPlans().size() + 1);

            ctx.getRequiredStopIdsByOption().put(optId, List.of(effectiveStopId));
            ctx.getOptionPlans().put(optId, optionDraft);

            Map<String, Object> diningExtras = new LinkedHashMap<>();
            diningExtras.put("specialtyDish", dining.specialtyDish());
            diningExtras.put("costSummary", dining.averageCost());
            diningExtras.put("walk", dining.distanceFromAttraction());
            diningExtras.put("type", "DINING");
            diningExtras.put("isDining", true);

            AdjustmentPreview cand = new AdjustmentPreview(
                    optId,
                    dining.id(),
                    dining.name(),
                    (isDirectDining && i == 0) ? directDiningName : dining.name(),
                    dining.summary(),
                    dining.duration(),
                    district,
                    "低",
                    dining.recommendationReason(),
                    isDirectDining && i == 0,
                    diningExtras
            );
            ctx.getCandidateReplacements().add(cand.toMap());
        }

        if (ctx.getOptionPlans().isEmpty()) {
            ctx.setFeasible(false);
            ctx.getReasonCodes().add("NO_FEASIBLE_MEAL_GAP");
            ctx.getAlternatives().addAll(List.of("调整用餐时段或缩短景点停留", "保持原行程安排"));
        } else {
            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().add(effectiveStopId);
        }
    }

    private List<Attraction> findCandidates(List<Attraction> catalog,
                                            CandidateRankingEngine.CandidateContext context,
                                            Set<String> used,
                                            TravelConstraints constraints,
                                            PlanAdjustmentIntent intent) {
        return catalog.stream()
                .filter(a -> !used.contains(a.getId()))
                .map(a -> new CandidateRankingEngine.ScoredCandidate(a, candidateRankingEngine.scoreCandidate(a, context, constraints, intent)))
                .sorted(Comparator.comparingInt(CandidateRankingEngine.ScoredCandidate::score).reversed()
                        .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                .map(CandidateRankingEngine.ScoredCandidate::attraction)
                .limit(3)
                .toList();
    }

    private int findBestMatchingDayForDistrict(Map<String, Object> trip, String district) {
        if (trip == null || district == null || district.isBlank() || "待确认".equals(district)) return -1;
        List<Map<String, Object>> days = days(trip);
        if (days == null || days.isEmpty()) return -1;

        String normDist = district.replaceAll("区$", "");
        int bestDay = -1;
        int maxMatches = 0;

        for (Map<String, Object> day : days) {
            int dayNum = number(day.get("day"));
            List<Map<String, Object>> stops = dayStops(day);
            int matches = 0;
            for (Map<String, Object> stop : stops) {
                String stopDist = extractDistrictFromStop(stop);
                if (stopDist != null && (stopDist.contains(normDist) || normDist.contains(stopDist.replaceAll("区$", "")))) {
                    matches++;
                }
                String addr = String.valueOf(stop.getOrDefault("address", ""));
                if (addr.contains(normDist)) {
                    matches++;
                }
            }
            if (matches > maxMatches) {
                maxMatches = matches;
                bestDay = dayNum;
            }
        }
        return maxMatches > 0 ? bestDay : -1;
    }

    private boolean isDiningIntent(PlanAdjustmentIntent intent) {
        if (intent == null) return false;
        if (intent.getGenericDiningCraving() != null || !safe(intent.getCuisineOrPlace()).isBlank()) return true;
        String raw = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), intent.getRequestedVenueName(), "");
        return diningCategoryGrounder.hasDiningKeywords(raw);
    }

    private void resolveTimingAndAffectedDay(Map<String, Object> trip, int dayNumber,
                                            Map<String, Attraction> byId, TravelConstraints constraints,
                                            int firstInsertedIndex) {
        Map<String, Object> affected = findDay(trip, dayNumber);
        if (affected == null) return;
        routeAwarePlanner.backfillAdjacentEdgeCosts(List.of(affected), byId,
                constraints == null ? null : constraints.getTransportPreference());
        List<Map<String, Object>> stops = dayStops(affected);
        if (firstInsertedIndex > 0 && firstInsertedIndex < stops.size()) {
            Map<String, Object> previous = stops.get(firstInsertedIndex - 1);
            Map<String, Object> first = stops.get(firstInsertedIndex);
            Integer previousStart = itineraryTimeEngine.parseExplicitTimeMinutes(stopClock(previous));
            if (previousStart != null) {
                int previousDuration = itineraryTimeEngine.parseKnownDurationMinutes(safe(previous.get("duration")));
                int transfer = previousDuration > 0 ? itineraryTimeEngine.transferMinutes(previous, first) : -1;
                if (transfer >= 0) {
                    int earliest = previousStart + previousDuration + transfer;
                    Integer requestedStart = itineraryTimeEngine.parseExplicitTimeMinutes(stopClock(first));
                    String clock = itineraryTimeEngine.formatTime(requestedStart == null ? earliest : Math.max(earliest, requestedStart));
                    first.put("time", clock);
                    first.put("startTime", clock);
                }
            }
        }
        itineraryTimeEngine.rippleRecalculateStopTimesFromExplicitFacts(stops, firstInsertedIndex + 1);
        resolveAffectedDay(trip, dayNumber, byId, constraints);
    }

    private String stopClock(Map<String, Object> stop) {
        return firstNonBlank(safe(stop.get("time")), safe(stop.get("startTime")), "");
    }

    private boolean hasUnresolvedTimedInsertion(List<Map<String, Object>> stops, int insertedIndex) {
        if (stops == null || insertedIndex <= 0 || insertedIndex >= stops.size()) return false;
        Map<String, Object> previous = stops.get(insertedIndex - 1);
        Map<String, Object> inserted = stops.get(insertedIndex);
        return itineraryTimeEngine.parseExplicitTimeMinutes(stopClock(previous)) != null
                && itineraryTimeEngine.parseKnownDurationMinutes(safe(previous.get("duration"))) > 0
                && itineraryTimeEngine.parseExplicitTimeMinutes(stopClock(inserted)) == null;
    }

    private void recordUnknownRouteDuration(AdjustmentContext ctx) {
        ctx.setFeasible(false);
        if (!ctx.getReasonCodes().contains("ROUTE_DURATION_UNKNOWN")) {
            ctx.getReasonCodes().add("ROUTE_DURATION_UNKNOWN");
            ctx.getAlternatives().add("相邻站点缺少可核验的接驳耗时，暂时无法安全安排时间；请补充位置或选择其他站点");
        }
    }

    private List<Map<String, Object>> resolveAffectedDay(Map<String, Object> trip, Integer dayNumber,
                                                         Map<String, Attraction> byId, TravelConstraints constraints) {
        Map<String, Object> affected = dayNumber == null ? null : findDay(trip, dayNumber);
        if (affected == null) return List.of();
        List<Map<String, Object>> tripDays = new ArrayList<>(days(trip));
        int affectedIndex = tripDays.indexOf(affected);
        TravelConstraints local = constraints == null ? new TravelConstraints() : constraints.copy();
        // A one-day repair must not mistake an interior day for the trip's arrival/departure day.
        if (affectedIndex != 0) local.setArrivalAt("未提供");
        if (affectedIndex != tripDays.size() - 1) local.setDepartureAt("未提供");
        List<Map<String, Object>> resolved = routeAwarePlanner.resolveAndVerifyDays(new ArrayList<>(List.of(affected)), byId, local);
        if (resolved != null && !resolved.isEmpty() && affectedIndex >= 0) {
            tripDays.set(affectedIndex, resolved.get(0));
            trip.put("days", tripDays);
        }
        return resolved == null ? List.of() : resolved;
    }

    private PlannerService.ServiceResult error(int status, String message, AdjustmentContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (ctx != null && ctx.getSession() != null) {
            body.put("sessionId", ctx.getSession().sessionId());
            body.put("trip", ctx.getSession().trip());
            body.put("appliedPreferences", ctx.getSession().appliedPreferences().asMap());
        }
        return new PlannerService.ServiceResult(status, body);
    }
}

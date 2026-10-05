package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.PlannerService;
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
    private final AttractionAliasMatcher aliasMatcher;

    @Autowired(required = false)
    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

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

        String rawMsg = intent.getRawMessage() != null ? intent.getRawMessage().replaceAll("[\\u200B-\\u200D\\uFEFF]", "") : "";
        List<String> explicitInOrder = aliasMatcher.findExplicitAttractionIdsInOrder(rawMsg, null);
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
            handleMultiStopRouteAddition(ctx, newVenuesToAdd, anchorVenueId, isBefore, rawMsg);
            return;
        }

        handleSingleStopAddition(ctx, rawMsg);
    }

    private void handleMultiStopRouteAddition(AdjustmentContext ctx,
                                              List<String> newVenuesToAdd,
                                              String anchorVenueId,
                                              boolean isBefore,
                                              String rawMsg) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

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

            int baseTimeMinute = 20 * 60; // 默认晚间 20:00
            Integer explicitTime = ChineseDateTimeParser.extractExplicitTimeMinutes(rawMsg);
            if (explicitTime != null) {
                baseTimeMinute = explicitTime;
            } else if (isBefore) {
                if (insertIndex > 0 && insertIndex - 1 < targetDayStops.size()) {
                    Map<String, Object> prevStop = targetDayStops.get(insertIndex - 1);
                    int prevStart = itineraryTimeEngine.parseTimeMinutes(String.valueOf(prevStop.getOrDefault("time", "")));
                    int prevDur = itineraryTimeEngine.parseDurationMinutes(String.valueOf(prevStop.getOrDefault("duration", "")));
                    if (prevStart > 0) {
                        baseTimeMinute = prevStart + (prevDur > 0 ? prevDur : 60) + 15;
                    }
                } else if (insertIndex < targetDayStops.size()) {
                    Map<String, Object> anchorStop = targetDayStops.get(insertIndex);
                    int anchorStart = itineraryTimeEngine.parseTimeMinutes(String.valueOf(anchorStop.getOrDefault("time", "")));
                    if (anchorStart > 0) {
                        int totalNewDuration = 0;
                        for (String vId : newVenuesToAdd) {
                            Attraction a = byId.get(vId);
                            if (a == null) a = attractionService.get(vId);
                            int d = a != null ? itineraryTimeEngine.parseDurationMinutes(a.getDuration()) : 45;
                            totalNewDuration += (d > 0 ? d : 45) + 15;
                        }
                        baseTimeMinute = Math.max(9 * 60, anchorStart - totalNewDuration);
                    }
                }
            } else {
                if (insertIndex > 0 && insertIndex - 1 < targetDayStops.size()) {
                    Map<String, Object> prevStop = targetDayStops.get(insertIndex - 1);
                    int prevStart = itineraryTimeEngine.parseTimeMinutes(String.valueOf(prevStop.getOrDefault("time", "")));
                    int prevDur = itineraryTimeEngine.parseDurationMinutes(String.valueOf(prevStop.getOrDefault("duration", "")));
                    if (prevStart > 0) {
                        if (prevDur > 60 && newVenuesToAdd.size() > 1 && prevStart >= 18 * 60) {
                            prevDur = 60;
                        }
                        baseTimeMinute = prevStart + (prevDur > 0 ? prevDur : 60) + 15;
                    }
                }
            }

            // Option 1: Full sequential route addition
            Map<String, Object> draftOpt1 = deepCopy(sourceTrip, ctx.getObjectMapper());
            Map<String, Object> dayOpt1 = findDay(draftOpt1, targetDayNumber);
            List<Map<String, Object>> stopsOpt1 = dayStops(dayOpt1);
            int curInsert = insertIndex;
            int curMinute = baseTimeMinute;
            List<String> opt1AddedStopIds = new ArrayList<>();
            List<String> addedNames = new ArrayList<>();
            boolean option1Feasible = true;

            for (String vId : newVenuesToAdd) {
                Attraction attr = byId.get(vId);
                if (attr == null) attr = attractionService.get(vId);
                if (attr == null) continue;
                String cleanId = attr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                String slotTime = itineraryTimeEngine.formatTime(curMinute);

                if (curMinute >= 24 * 60 || !candidateRankingEngine.isOpeningCompatible(attr, slotTime, itineraryTimeEngine)) {
                    option1Feasible = false;
                    ctx.getReasonCodes().add(curMinute >= 24 * 60 ? "MIDNIGHT_OVERFLOW" : "CLOSED_AT_SLOT_TIME");
                    ctx.getAlternatives().add("当前排程时间较晚（" + attr.getName() + "在" + slotTime + "可能已闭园/停运或跨夜），建议提早出发或精简站点");
                }

                Map<String, Object> addedStop = itineraryBuilder.createStop(
                        attr, stopId, slotTime, attr.getDuration(),
                        "串联游览：" + attr.getName() + "（" + PlannerService.stripTrailingPunctuation(attr.getSummary()) + "）。",
                        attr.getIntro()
                );
                addedStop.put("time", slotTime);
                addedStop.put("startTime", slotTime);
                stopsOpt1.add(Math.min(curInsert, stopsOpt1.size()), addedStop);
                curInsert++;
                int dur = itineraryTimeEngine.parseDurationMinutes(attr.getDuration());
                if (dur > 50 && newVenuesToAdd.size() > 1 && baseTimeMinute >= 18 * 60) {
                    dur = 45;
                }
                curMinute += (dur > 0 ? dur : 45) + 15;
                opt1AddedStopIds.add(stopId);
                addedNames.add(attr.getName());
            }

            itineraryTimeEngine.rippleRecalculateStopTimes(stopsOpt1, curInsert);

            for (int i = curInsert; i < stopsOpt1.size(); i++) {
                Map<String, Object> s = stopsOpt1.get(i);
                int sMin = itineraryTimeEngine.parseTimeMinutes(String.valueOf(s.getOrDefault("time", "")));
                if (sMin >= 24 * 60) {
                    option1Feasible = false;
                    ctx.getReasonCodes().add("MIDNIGHT_OVERFLOW");
                    ctx.getAlternatives().add("插入新站点后导致后续行程（" + s.get("name") + "）推迟至深夜/次日凌晨，建议调整至其他日期");
                    break;
                }
            }

            resolveAffectedDay(draftOpt1, targetDayNumber, byId, constraints);
            ctx.getOptionPlans().put("option-1", draftOpt1);

            Map<String, Object> cand1 = new LinkedHashMap<>();
            cand1.put("optionId", "option-1");
            cand1.put("venueId", newVenuesToAdd.get(0));
            cand1.put("name", String.join(" + ", addedNames) + "（完整动线）");
            cand1.put("requestedName", String.join(" + ", addedNames));
            cand1.put("summary", "依序串联" + String.join("、", addedNames) + "，顺路衔接且游玩动线连贯，适合完整打卡该片区核心地标。");
            cand1.put("duration", "约 " + (addedNames.size() * 50) + " 分钟");
            cand1.put("district", byId.get(newVenuesToAdd.get(0)) != null ? byId.get(newVenuesToAdd.get(0)).getDistrict() : "主城区");
            cand1.put("walkDifficulty", "低");
            cand1.put("fit", "适合少走路、行程充实、一次性体验连贯风貌路线的旅行者。");
            ctx.getCandidateReplacements().add(cand1);

            // Option 2: First stop only
            if (newVenuesToAdd.size() > 1) {
                String firstVenueId = newVenuesToAdd.get(0);
                Attraction firstAttr = byId.get(firstVenueId);
                if (firstAttr == null) firstAttr = attractionService.get(firstVenueId);
                if (firstAttr != null) {
                    Map<String, Object> draftOpt2 = deepCopy(sourceTrip, ctx.getObjectMapper());
                    Map<String, Object> dayOpt2 = findDay(draftOpt2, targetDayNumber);
                    List<Map<String, Object>> stopsOpt2 = dayStops(dayOpt2);
                    String cleanId = firstAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                    String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                    String slotTime = itineraryTimeEngine.formatTime(baseTimeMinute);
                    Map<String, Object> addedStop = itineraryBuilder.createStop(
                            firstAttr, stopId, slotTime, firstAttr.getDuration(),
                            "单站体验：" + firstAttr.getName(), firstAttr.getIntro()
                    );
                    addedStop.put("time", slotTime);
                    addedStop.put("startTime", slotTime);
                    stopsOpt2.add(Math.min(insertIndex, stopsOpt2.size()), addedStop);
                    itineraryTimeEngine.rippleRecalculateStopTimes(stopsOpt2, insertIndex + 1);
                    resolveAffectedDay(draftOpt2, targetDayNumber, byId, constraints);
                    ctx.getOptionPlans().put("option-2", draftOpt2);

                    Map<String, Object> cand2 = new LinkedHashMap<>();
                    cand2.put("optionId", "option-2");
                    cand2.put("venueId", firstAttr.getId());
                    String opt2Label = (firstAttr.getTags() != null && firstAttr.getTags().contains("索道")) || firstAttr.getName().contains("索道") ? "（单程过江体验）" : "（精选单站）";
                    cand2.put("name", firstAttr.getName() + opt2Label);
                    cand2.put("requestedName", firstAttr.getName());
                    cand2.put("summary", "仅保留" + firstAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(firstAttr.getSummary()) + "），游玩节奏更紧凑轻松。");
                    cand2.put("duration", firstAttr.getDuration());
                    cand2.put("district", firstAttr.getDistrict());
                    cand2.put("walkDifficulty", firstAttr.getWalkDifficulty() != null ? firstAttr.getWalkDifficulty() : "低");
                    cand2.put("fit", firstAttr.getFit() != null ? firstAttr.getFit() : "适合时间有限、想体验核心地标的旅行者。");
                    ctx.getCandidateReplacements().add(cand2);
                }
            }

            // Option 3: Last stop only
            if (newVenuesToAdd.size() > 2) {
                String lastVenueId = newVenuesToAdd.get(newVenuesToAdd.size() - 1);
                Attraction lastAttr = byId.get(lastVenueId);
                if (lastAttr == null) lastAttr = attractionService.get(lastVenueId);
                if (lastAttr != null) {
                    Map<String, Object> draftOpt3 = deepCopy(sourceTrip, ctx.getObjectMapper());
                    Map<String, Object> dayOpt3 = findDay(draftOpt3, targetDayNumber);
                    List<Map<String, Object>> stopsOpt3 = dayStops(dayOpt3);
                    String cleanId = lastAttr.getId().replaceAll("[^a-zA-Z0-9_-]", "");
                    String stopId = "day" + targetDayNumber + "-added-" + (cleanId.startsWith("cq-") ? cleanId.substring(3) : cleanId);
                    String slotTime = itineraryTimeEngine.formatTime(baseTimeMinute);
                    Map<String, Object> addedStop = itineraryBuilder.createStop(
                            lastAttr, stopId, slotTime, lastAttr.getDuration(),
                            "休闲体验：" + lastAttr.getName(), lastAttr.getIntro()
                    );
                    addedStop.put("time", slotTime);
                    addedStop.put("startTime", slotTime);
                    stopsOpt3.add(Math.min(insertIndex, stopsOpt3.size()), addedStop);
                    itineraryTimeEngine.rippleRecalculateStopTimes(stopsOpt3, insertIndex + 1);
                    resolveAffectedDay(draftOpt3, targetDayNumber, byId, constraints);
                    ctx.getOptionPlans().put("option-3", draftOpt3);

                    Map<String, Object> cand3 = new LinkedHashMap<>();
                    cand3.put("optionId", "option-3");
                    cand3.put("venueId", lastAttr.getId());
                    String opt3Label = (lastAttr.getTags() != null && (lastAttr.getTags().contains("夜景") || lastAttr.getTags().contains("江景"))) || lastAttr.getName().contains("滨江") || lastAttr.getName().contains("老街") ? "（江岸夜景茶歇）" : "（休闲漫步体验）";
                    cand3.put("name", lastAttr.getName() + opt3Label);
                    cand3.put("requestedName", lastAttr.getName());
                    cand3.put("summary", "重点安排" + lastAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(lastAttr.getSummary()) + "），全程节奏舒适平缓。");
                    cand3.put("duration", lastAttr.getDuration());
                    cand3.put("district", lastAttr.getDistrict());
                    cand3.put("walkDifficulty", lastAttr.getWalkDifficulty() != null ? lastAttr.getWalkDifficulty() : "低");
                    cand3.put("fit", lastAttr.getFit() != null ? lastAttr.getFit() : "适合喜爱开阔视野、慢节奏观景的长辈友好路线。");
                    ctx.getCandidateReplacements().add(cand3);
                }
            }

            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().addAll(opt1AddedStopIds);
            ctx.setFeasible(option1Feasible);
            ctx.getAdjustmentDiagnostics().put("addedStopName", String.join("+", addedNames));
            ctx.getAdjustmentDiagnostics().put("addedDayNumber", targetDayNumber);
        }
    }

    private void handleSingleStopAddition(AdjustmentContext ctx, String rawMsg) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        String targetVenueId = intent.getTargetAttractionId();
        if (targetVenueId == null && intent.getTargetStopId() != null) {
            StopLocation s = locateStop(draftTrip, intent.getTargetStopId());
            if (s != null) targetVenueId = String.valueOf(s.stop().get("venueId"));
        }
        if (targetVenueId == null && !isDiningIntent(intent) && intent.getRequestedVenueName() != null) {
            for (Attraction a : attractionService.list(null, null)) {
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
            List<Attraction> candidates = findCandidates(candContext, used, constraints, intent);
            if (!candidates.isEmpty()) {
                targetVenueId = candidates.get(0).getId();
            }
        }
        Attraction attraction = targetVenueId != null ? attractionService.get(targetVenueId) : null;
        if (attraction == null && targetVenueId != null && targetVenueId.startsWith("amap-") && runtimeAttractionDetailService != null) {
            attraction = runtimeAttractionDetailService.toAttraction(targetVenueId);
        }
        if (attraction == null && !isDiningIntent(intent) && runtimeAttractionDetailService != null
                && intent.getRequestedVenueName() != null && !intent.getRequestedVenueName().isBlank()) {
            String searchCity = resolveTripCity(draftTrip, ctx.getSession(), intent);
            Attraction dynamicPoi = runtimeAttractionDetailService.searchAndBuildAttraction(intent.getRequestedVenueName(), searchCity);
            if (dynamicPoi != null) {
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
        if (usedVenues(draftTrip).contains(targetVenueId)) {
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
        Map<String, Object> addedStop = itineraryBuilder.createStop(
                attraction, stopId, "待安排", attraction.getDuration(),
                "将" + attraction.getName() + "加入当前行程。", attraction.getIntro()
        );
        stops.add(addedStop);
        ctx.getChangedSegments().add(stopId);
        resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        ctx.getOptionPlans().put("option-1", draftTrip);

        Map<String, Object> cand = new LinkedHashMap<>();
        cand.put("optionId", "option-1");
        cand.put("venueId", attraction.getId());
        cand.put("name", attraction.getName());
        cand.put("requestedName", intent.getRequestedVenueName() != null ? intent.getRequestedVenueName() : attraction.getName());
        cand.put("district", attraction.getDistrict());
        cand.put("summary", attraction.getSummary());
        cand.put("duration", attraction.getDuration());
        cand.put("fit", attraction.getFit());
        ctx.getCandidateReplacements().add(cand);

        ctx.getAdjustmentDiagnostics().put("addedStopName", attraction.getName());
        ctx.getAdjustmentDiagnostics().put("addedDayNumber", dayNumber);
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
        if (stops.isEmpty()) {
            ctx.setFatalError(error(400, "当前日期没有任何景点，无法添加餐饮站点。", ctx));
            return;
        }

        String rawCombined = ((intent.getRequestedVenueName() != null ? intent.getRequestedVenueName() : "") + " "
                + (intent.getReplacementPlaceName() != null ? intent.getReplacementPlaceName() : "") + " "
                + (intent.getRawMessage() != null ? intent.getRawMessage() : "")).trim();

        String slotType = diningCategoryGrounder.resolveSlotType(rawCombined);
        String defaultSlotTime = switch (slotType) {
            case "BREAKFAST" -> "08:00";
            case "NIGHT_SNACK" -> "21:00";
            case "DINNER" -> "18:00";
            case "TEA_BREAK" -> "15:30";
            default -> "12:00";
        };
        String requestedFlavor = diningCategoryGrounder.resolveRequestedFlavor(rawCombined, "特色风味小吃");

        int targetMinutes = itineraryTimeEngine.parseTimeMinutes(defaultSlotTime);
        int plannedInsertIdx = stops.size();
        for (int si = 0; si < stops.size(); si++) {
            String stTime = String.valueOf(stops.get(si).getOrDefault("time", ""));
            int stMinutes = itineraryTimeEngine.parseTimeMinutes(stTime);
            if (stMinutes > targetMinutes) {
                plannedInsertIdx = si;
                break;
            }
        }

        Map<String, Object> anchorStop = null;
        if (ctx.getPageContext() != null && ctx.getPageContext().getSelectedStopId() != null && !ctx.getPageContext().getSelectedStopId().isBlank()) {
            StopLocation selectedLoc = locateStop(draftTrip, ctx.getPageContext().getSelectedStopId());
            if (selectedLoc != null) {
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
                : (anchorStop != null ? extractDistrictFromStop(anchorStop) : "沙坪坝区");

        String directDiningName = firstNonBlank(intent.getReplacementPlaceName(), intent.getRequestedVenueName());
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

        boolean isGenericDining = diningCategoryGrounder.isGenericDiningCraving(directDiningName);
        boolean isDirectDining = !isGenericDining;

        List<AttractionDiningKnowledge.DiningOption> diningCandidates = new ArrayList<>();
        if (isDirectDining) {
            String slotPrefix = "BREAKFAST".equalsIgnoreCase(slotType) ? "【早餐推荐】"
                    : ("NIGHT_SNACK".equalsIgnoreCase(slotType) ? "【宵夜推荐】"
                    : ("TEA_BREAK".equalsIgnoreCase(slotType) ? "【特色茶歇】"
                    : ("DINNER".equalsIgnoreCase(slotType) ? "【晚餐推荐】" : "【午餐推荐】")));
            String directFullName = directDiningName.startsWith("【") ? directDiningName : slotPrefix + directDiningName;
            String directCoord = (anchor != null && anchor.getLocation() != null && !anchor.getLocation().isBlank())
                    ? anchor.getLocation() : "106.577000,29.556000";
            String directVenueId = "direct-dining-" + Math.abs(directDiningName.hashCode());
            if (diningVenueService != null) {
                try {
                    var foundList = diningVenueService.searchLiveDiningOrTea(district != null ? district : "重庆市", directCoord, directDiningName, slotType, 1);
                    if (foundList != null && !foundList.isEmpty()) {
                        var v0 = foundList.get(0);
                        directVenueId = v0.getId();
                        if (v0.getLocation() != null && !v0.getLocation().isBlank()) {
                            directCoord = v0.getLocation();
                        }
                    }
                } catch (Exception ignored) {}
            }
            diningCandidates.add(new AttractionDiningKnowledge.DiningOption(
                    directVenueId,
                    directFullName,
                    "招牌特色风味菜品",
                    requestedFlavor,
                    "人均约 58-88 元",
                    "约 60 分钟",
                    "步行直达",
                    "根据您的调整要求，直接安排指定餐厅：" + directDiningName,
                    "用户指定餐厅，为您精准规划动线",
                    "gold",
                    directCoord
            ));
            var alts = AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                    district, directFullName, requestedFlavor, slotType, anchor, constraints);
            if (alts != null) {
                for (var alt : alts) {
                    if (!alt.name().contains(directDiningName)) {
                        diningCandidates.add(alt);
                        if (diningCandidates.size() >= 3) break;
                    }
                }
            }
            ctx.getAdjustmentDiagnostics().put("replacementMode", "DIRECT");
            ctx.getAdjustmentDiagnostics().put("directReplacement", true);
            ctx.getAdjustmentDiagnostics().put("requestedReplacementName", directDiningName);
        } else {
            diningCandidates.addAll(AttractionDiningKnowledge.resolveDiningReplacementCandidates(
                    district, "", requestedFlavor, slotType, anchor, constraints));
            boolean isTea = "TEA_BREAK".equals(slotType);
            ctx.getAdjustmentDiagnostics().put("replacementMode", isTea ? "TEA_BREAK" : "DINING_ADD");
            ctx.getAdjustmentDiagnostics().put("requestedFlavor", isTea ? "特色老茶馆与盖碗茶歇" : requestedFlavor);
        }

        ctx.getAdjustmentDiagnostics().put("sourceStopName", anchorStop != null ? String.valueOf(anchorStop.getOrDefault("name", "沿途加站")) : "沿途加站");
        ctx.getAdjustmentDiagnostics().put("forceApplicable", true);

        String effectiveStopId = "day" + dayNumber + "-added-dining";

        for (int i = 0; i < diningCandidates.size(); i++) {
            AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
            String optId = "option-" + (i + 1);
            Map<String, Object> optionDraft = deepCopy(sourceTrip, ctx.getObjectMapper());
            Map<String, Object> optDay = findDay(optionDraft, dayNumber);
            if (optDay == null) continue;
            List<Map<String, Object>> optStops = dayStops(optDay);

            int insertIdx = optStops.size();
            for (int si = 0; si < optStops.size(); si++) {
                String stTime = String.valueOf(optStops.get(si).getOrDefault("time", ""));
                int stMinutes = itineraryTimeEngine.parseTimeMinutes(stTime);
                if (stMinutes > targetMinutes) {
                    insertIdx = si;
                    break;
                }
            }

            int actualStartMinute = targetMinutes;
            if (insertIdx > 0 && insertIdx - 1 < optStops.size()) {
                Map<String, Object> prev = optStops.get(insertIdx - 1);
                int prevStart = itineraryTimeEngine.parseTimeMinutes(String.valueOf(prev.getOrDefault("time", "")));
                int prevDuration = itineraryTimeEngine.parseDurationMinutes(String.valueOf(prev.getOrDefault("duration", "")));
                int prevEnd = prevStart + prevDuration;
                if (actualStartMinute < prevEnd) {
                    actualStartMinute = prevEnd;
                }
            }
            String effectiveSlotTime = itineraryTimeEngine.formatTime(actualStartMinute);

            Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(
                    dining, effectiveStopId, effectiveSlotTime, district);
            newDiningStop.put("time", effectiveSlotTime);
            newDiningStop.put("startTime", effectiveSlotTime);

            insertIdx = Math.max(0, Math.min(insertIdx, optStops.size()));
            optStops.add(insertIdx, newDiningStop);

            int transitBuffer = 15;
            int diningDur = itineraryTimeEngine.parseDurationMinutes(dining.duration());
            int currentEndMinute = actualStartMinute + (diningDur > 0 ? diningDur : 50) + transitBuffer;
            for (int si = insertIdx + 1; si < optStops.size(); si++) {
                Map<String, Object> nextStop = optStops.get(si);
                int nextStart = itineraryTimeEngine.parseTimeMinutes(String.valueOf(nextStop.getOrDefault("time", "")));
                int nextDuration = itineraryTimeEngine.parseDurationMinutes(String.valueOf(nextStop.getOrDefault("duration", "")));
                if (nextStart < currentEndMinute) {
                    nextStart = currentEndMinute;
                    String updatedTime = itineraryTimeEngine.formatTime(nextStart);
                    nextStop.put("time", updatedTime);
                    nextStop.put("startTime", updatedTime);
                    String curName = String.valueOf(nextStop.getOrDefault("name", ""));
                    if (nextStart >= 17 * 60 && curName.startsWith("【午餐推荐】")) {
                        nextStop.put("name", curName.replace("【午餐推荐】", "【晚餐推荐】"));
                        if (nextStop.get("displayName") != null) {
                            nextStop.put("displayName", String.valueOf(nextStop.get("displayName")).replace("【午餐推荐】", "【晚餐推荐】"));
                        }
                    }
                }
                currentEndMinute = nextStart + (nextDuration > 0 ? nextDuration : 40) + transitBuffer;
            }

            ctx.getOptionPlans().put(optId, optionDraft);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("optionId", optId);
            cand.put("venueId", dining.id());
            cand.put("name", dining.name());
            cand.put("requestedName", (isDirectDining && i == 0) ? directDiningName : dining.name());
            cand.put("directReplacement", isDirectDining && i == 0);
            cand.put("summary", dining.summary());
            cand.put("specialtyDish", dining.specialtyDish());
            cand.put("costSummary", dining.averageCost());
            cand.put("duration", dining.duration());
            cand.put("district", district);
            cand.put("walkDifficulty", "低");
            cand.put("walk", dining.distanceFromAttraction());
            cand.put("type", "DINING");
            cand.put("isDining", true);
            cand.put("fit", dining.recommendationReason());
            ctx.getCandidateReplacements().add(cand);
        }

        if (ctx.getOptionPlans().isEmpty()) {
            ctx.setFeasible(false);
            ctx.getReasonCodes().add("NO_FEASIBLE_REPLACEMENT");
            ctx.getAlternatives().addAll(List.of("换周边其他风味美食", "保持原行程安排"));
        } else {
            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().add(effectiveStopId);
        }
    }

    private List<Attraction> findCandidates(CandidateRankingEngine.CandidateContext context,
                                            Set<String> used,
                                            TravelConstraints constraints,
                                            PlanAdjustmentIntent intent) {
        List<Attraction> catalog = attractionService.list(null, null);
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
        String raw = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), intent.getRequestedVenueName(), "");
        return raw.matches(".*(餐|饭|馆|烧烤|火锅|小吃|面馆|串串|抄手|烤肉|大排档|吃|喝|甜品|糖水|茶馆|下午茶|咖啡).*");
    }

    private List<Map<String, Object>> resolveAffectedDay(Map<String, Object> trip, Integer dayNumber,
                                                         Map<String, Attraction> byId, TravelConstraints constraints) {
        Map<String, Object> affected = dayNumber == null ? null : findDay(trip, dayNumber);
        if (affected == null) return List.of();
        return routeAwarePlanner.resolveAndVerifyDays(new ArrayList<>(List.of(affected)), byId, constraints);
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

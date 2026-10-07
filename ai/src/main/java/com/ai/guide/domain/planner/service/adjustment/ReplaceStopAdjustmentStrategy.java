package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.DynamicGeoCentroidResolver;
import com.ai.guide.domain.planner.engine.MealGapSolver;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.PlannerService;
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
 * 站点替换调整策略（处理 SUGGEST_REPLACEMENTS 意图）
 *
 * 核心职责：
 * 1. 早餐晨间插入；
 * 2. 餐饮站点替换（去硬编码：通过 DiningCategoryGrounder 区分泛需求与指定商户，结合实景高德动态锁定）；
 * 3. 景点站点替换（直换指定景点 vs 候选空间几何打分与打回保护）。
 */
@Component
public class ReplaceStopAdjustmentStrategy implements AdjustmentStrategy {

    private final AttractionService attractionService;
    private final ItineraryBuilderPort itineraryBuilder;
    private final RouteAwarePlanner routeAwarePlanner;
    private final PlanVerifier planVerifier;
    private final CandidateRankingEngine candidateRankingEngine;
    private final ItineraryTimeEngine itineraryTimeEngine;
    private final DiningCategoryGrounder diningCategoryGrounder;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

    @Autowired
    private DynamicGeoCentroidResolver geoCentroidResolver;

    @Autowired
    private MealGapSolver mealGapSolver;

    @Autowired
    private TaxonomyPort taxonomy;

    @Autowired
    public ReplaceStopAdjustmentStrategy(AttractionService attractionService,
                                         ItineraryBuilderPort itineraryBuilder,
                                         RouteAwarePlanner routeAwarePlanner,
                                         PlanVerifier planVerifier,
                                         CandidateRankingEngine candidateRankingEngine,
                                         ItineraryTimeEngine itineraryTimeEngine,
                                         DiningCategoryGrounder diningCategoryGrounder) {
        this.attractionService = attractionService;
        this.itineraryBuilder = itineraryBuilder;
        this.routeAwarePlanner = routeAwarePlanner;
        this.planVerifier = planVerifier;
        this.candidateRankingEngine = candidateRankingEngine;
        this.itineraryTimeEngine = itineraryTimeEngine;
        this.diningCategoryGrounder = diningCategoryGrounder;
    }

    public void setDiningVenueService(DiningVenueService diningVenueService) {
        this.diningVenueService = diningVenueService;
    }

    public void setDiningScheduling(TaxonomyPort taxonomy, MealGapSolver mealGapSolver, DynamicGeoCentroidResolver geoCentroidResolver) {
        this.taxonomy = taxonomy;
        this.mealGapSolver = mealGapSolver;
        this.geoCentroidResolver = geoCentroidResolver;
    }

    @Override
    public boolean supports(ConversationIntentType intentType) {
        return intentType == ConversationIntentType.SUGGEST_REPLACEMENTS;
    }

    @Override
    public void apply(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        String targetId = intent.getTargetStopId();
        boolean hasStructuredSlot = !safe(intent.getTimeSlot()).isBlank();
        boolean isBreakfastInsertion = (!hasStructuredSlot && "NEW_BREAKFAST_STOP".equals(targetId))
                || ((targetId == null || targetId.isBlank()) && "BREAKFAST".equalsIgnoreCase(intent.getTimeSlot()))
                || (!hasStructuredSlot && (targetId == null || targetId.isBlank()) && (
                        (intent.getReplacementPlaceName() != null && intent.getReplacementPlaceName().contains("早"))
                        || (intent.getRawMessage() != null && intent.getRawMessage().matches(".*(早|早餐|早点).*"))
                ));

        if (isBreakfastInsertion) {
            handleBreakfastInsertion(ctx);
            return;
        }

        StopLocation target = locateStop(draftTrip, targetId);
        if (target == null && targetId != null && !targetId.isBlank()) {
            target = locateStopByVenue(draftTrip, targetId);
        }
        if (target == null && isDiningIntent(intent)) {
            target = locateFallbackDiningTarget(ctx, intent, draftTrip);
        }

        if (target == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("requiresClarification", true);
            body.put("clarificationQuestion", "未在当前行程中找到目标景点，请重新选择卡片。");
            ctx.setFatalError(new PlannerService.ServiceResult(200, body));
            return;
        }

        String effectiveStopId = String.valueOf(target.stop().get("id"));

        if (isDiningStop(target.stop())) {
            handleDiningStopReplacement(ctx, target, effectiveStopId);
        } else {
            handleScenicStopReplacement(ctx, target, effectiveStopId);
        }
    }

    private void handleBreakfastInsertion(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();

        int dayNum = intent.getDayNumber() != null ? intent.getDayNumber()
                : (ctx.getPageContext().getActiveDay() != null ? ctx.getPageContext().getActiveDay() : 1);
        Map<String, Object> day = findDay(draftTrip, dayNum);
        if (day == null) {
            ctx.setFatalError(error(400, "目标日期不存在。", ctx));
            return;
        }
        List<Map<String, Object>> stops = dayStops(day);
        if (stops.isEmpty()) {
            ctx.setFatalError(error(400, "当前日期没有任何景点，无法添加早餐。", ctx));
            return;
        }
        Map<String, Object> firstStop = stops.get(0);
        Attraction anchor = resolveStopAnchor(firstStop, itineraryBuilder);
        String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank())
                ? anchor.getDistrict()
                : extractDistrictFromStop(firstStop);
        String effectiveStopId = "day" + dayNum + "-breakfast";
        String requestedFlavor = diningCategoryGrounder.resolveRequestedFlavor(intent, "特色早点");
        var mealWindow = taxonomy == null ? null : taxonomy.getMealWindow("BREAKFAST");

        List<AttractionDiningKnowledge.DiningOption> diningCandidates =
                diningVenueService == null ? List.of() : diningVenueService.resolveDiningReplacementCandidates(
                        district, "", requestedFlavor, "BREAKFAST", anchor, constraints);

        ctx.getAdjustmentDiagnostics().put("replacementMode", "DINING");
        ctx.getAdjustmentDiagnostics().put("sourceStopName", "晨间早餐");
        ctx.getAdjustmentDiagnostics().put("requestedFlavor", requestedFlavor);
        ctx.getAdjustmentDiagnostics().put("forceApplicable", true);

        for (int i = 0; i < diningCandidates.size(); i++) {
            AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
            String optId = "option-" + (i + 1);
            Map<String, Object> optionDraft = deepCopy(sourceTrip, ctx.getObjectMapper());
            Map<String, Object> optDay = findDay(optionDraft, dayNum);
            if (optDay == null) continue;
            List<Map<String, Object>> optStops = dayStops(optDay);

            Attraction meal = Attraction.builder().id(dining.id()).name(dining.name()).location(dining.location()).district(district).build();
            var placement = mealGapSolver == null ? java.util.Optional.<MealGapSolver.Placement>empty()
                    : mealGapSolver.findGap(optStops, meal, itineraryTimeEngine.parseDurationMinutes(dining.duration()),
                    mealWindow, constraints == null ? null : constraints.getTransportPreference(), itineraryBuilder);
            if (placement.isEmpty()) continue;
            String slotTime = itineraryTimeEngine.formatTime(placement.get().startMinute());
            Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(dining, effectiveStopId, slotTime, district);
            newDiningStop.put("startTime", slotTime);
            newDiningStop.put("district", district);
            optStops.add(placement.get().index(), newDiningStop);
            mealGapSolver.applyRouteMetadata(optStops, placement.get());
            optId = "option-" + (ctx.getOptionPlans().size() + 1);
            ctx.getOptionPlans().put(optId, optionDraft);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("optionId", optId);
            cand.put("venueId", dining.id());
            cand.put("name", dining.name());
            cand.put("requestedName", dining.name());
            cand.put("directReplacement", false);
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
            ctx.getAlternatives().addAll(List.of("换周边其他早点", "保持原行程安排"));
        } else {
            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().add(effectiveStopId);
        }
    }

    private StopLocation locateFallbackDiningTarget(AdjustmentContext ctx, PlanAdjustmentIntent intent, Map<String, Object> draftTrip) {
        int dayNum = intent.getDayNumber() != null ? intent.getDayNumber()
                : (ctx.getPageContext().getActiveDay() != null ? ctx.getPageContext().getActiveDay() : 1);
        Map<String, Object> day = findDay(draftTrip, dayNum);
        if (day == null) return null;
        List<Map<String, Object>> stops = dayStops(day);
        String slotType = diningCategoryGrounder.resolveSlotType(intent);
        boolean wantsLunch = "LUNCH".equals(slotType);
        boolean wantsDinner = "DINNER".equals(slotType) || "NIGHT_SNACK".equals(slotType);
        boolean wantsBreakfast = "BREAKFAST".equals(slotType);
        for (int i = 0; i < stops.size(); i++) {
            Map<String, Object> s = stops.get(i);
            if (isDiningStop(s)) {
                String time = String.valueOf(s.getOrDefault("time", ""));
                String name = String.valueOf(s.getOrDefault("name", ""));
                if (wantsLunch && (itineraryTimeEngine.isEveningSlot(time) || itineraryTimeEngine.isMorningSlot(time) || name.contains("晚餐") || name.contains("早餐"))) {
                    continue;
                }
                if (wantsDinner && (itineraryTimeEngine.isMorningSlot(time) || (!itineraryTimeEngine.isEveningSlot(time) && name.contains("午餐")) || name.contains("早餐"))) {
                    continue;
                }
                if (wantsBreakfast && (itineraryTimeEngine.isEveningSlot(time) || name.contains("晚餐") || name.contains("午餐"))) {
                    continue;
                }
                return new StopLocation(day, stops, i, s);
            }
        }
        return null;
    }

    private void handleDiningStopReplacement(AdjustmentContext ctx, StopLocation target, String effectiveStopId) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        TravelConstraints constraints = ctx.getConstraints();

        String rawMsg = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), "");
        String defaultFlavor = constraints != null && constraints.getDietPreference() != null && !constraints.getDietPreference().isBlank()
                ? constraints.getDietPreference() : "特色美食";
        String requestedFlavor = diningCategoryGrounder.resolveRequestedFlavor(intent, defaultFlavor);

        Attraction anchor = null;
        int targetIdx = target.index();
        for (int offset = 1; offset < target.stops().size(); offset++) {
            int beforeIdx = targetIdx - offset;
            if (beforeIdx >= 0 && !isDiningStop(target.stops().get(beforeIdx))) {
                anchor = resolveStopAnchor(target.stops().get(beforeIdx), itineraryBuilder);
                if (anchor != null) break;
            }
            int afterIdx = targetIdx + offset;
            if (afterIdx < target.stops().size() && !isDiningStop(target.stops().get(afterIdx))) {
                anchor = resolveStopAnchor(target.stops().get(afterIdx), itineraryBuilder);
                if (anchor != null) break;
            }
        }
        if (anchor == null) {
            anchor = resolveStopAnchor(target.stop(), itineraryBuilder);
        }

        Map<String, Object> draftTrip = ctx.getDraftTrip();

        String district = (anchor != null && anchor.getDistrict() != null && !anchor.getDistrict().isBlank() && !"待确认".equals(anchor.getDistrict()))
                ? anchor.getDistrict()
                : extractDistrictFromStop(target.stop());
        String slotTime = safe(target.stop().get("time"));

        String slotType = diningCategoryGrounder.resolveSlotType(intent);
        boolean explicitSlotInRaw = intent.getTimeSlot() != null && !intent.getTimeSlot().isBlank() || rawMsg.matches(".*(早|早餐|早点|午|午餐|中午|晚|晚餐|晚上|夜宵|宵夜).*");
        if (!explicitSlotInRaw && "LUNCH".equals(slotType)) {
            if (itineraryTimeEngine.isEveningSlot(slotTime) || String.valueOf(target.stop().get("name")).contains("晚餐")) {
                slotType = "DINNER";
            } else if (itineraryTimeEngine.isMorningSlot(slotTime) || String.valueOf(target.stop().get("name")).contains("早餐")) {
                slotType = "BREAKFAST";
            }
        }

        String directDiningName = firstNonBlank(intent.getCuisineOrPlace(), intent.getReplacementPlaceName());
        if ((directDiningName == null || directDiningName.isBlank()) && rawMsg != null) {
            Matcher bm = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(rawMsg);
            if (bm.find()) {
                directDiningName = bm.group(1).trim();
            } else {
                Matcher rm = Pattern.compile("(?:改成|换成|替换为|改为|换为|换到|改到)\\s*([^\\s，,。！!？?；;]+)").matcher(rawMsg);
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

        boolean isGenericDiningPhrase = diningCategoryGrounder.isGenericDiningCraving(intent, directDiningName);
        boolean isDirectDining = !isGenericDiningPhrase;

        List<AttractionDiningKnowledge.DiningOption> diningCandidates = new ArrayList<>();
        if (isDirectDining) {
            String city = resolveTripCity(draftTrip, constraints);
            String searchArea = firstNonBlank(district, city);
            String coordinate = anchor != null && isValidCoordinate(anchor.getLocation()) ? anchor.getLocation()
                    : (geoCentroidResolver == null ? resolveDefaultCoordinate(draftTrip, constraints, dayStops(target.day()))
                    : geoCentroidResolver.resolve(draftTrip, constraints, dayStops(target.day())));
            if (diningVenueService != null && searchArea != null && coordinate != null) {
                try {
                    var found = diningVenueService.searchLiveDiningOrTea(searchArea, coordinate, directDiningName, slotType, 1, city);
                    if (found != null && !found.isEmpty() && matchesRequestedDiningVenue(directDiningName, found.get(0).getName())) {
                        var option = verifiedDiningOption(found.get(0), slotType);
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
                    district, String.valueOf(target.stop().get("name")), requestedFlavor, slotType, anchor, constraints));
            ctx.getAdjustmentDiagnostics().put("replacementMode", "DINING");
            ctx.getAdjustmentDiagnostics().put("requestedFlavor", requestedFlavor);
        }

        ctx.getAdjustmentDiagnostics().put("slotType", slotType);
        ctx.getAdjustmentDiagnostics().put("sourceStopName", String.valueOf(target.stop().getOrDefault("name", "当前餐饮")));
        ctx.getAdjustmentDiagnostics().put("forceApplicable", true);

        for (int i = 0; i < diningCandidates.size(); i++) {
            AttractionDiningKnowledge.DiningOption dining = diningCandidates.get(i);
            String optId = "option-" + (i + 1);
            Map<String, Object> optionDraft = deepCopy(sourceTrip, ctx.getObjectMapper());
            StopLocation optTarget = locateStop(optionDraft, effectiveStopId);
            if (optTarget == null) continue;
            if (safe(dining.id()).equals(safe(optTarget.stop().get("venueId")))
                    || safe(dining.name()).replaceAll("^【[^】]+】", "").equals(safe(optTarget.stop().get("name")).replaceAll("^【[^】]+】", ""))) continue;

            // Remove only the target meal before searching; surrounding visits keep their occupied times.
            optTarget.stops().remove(optTarget.index());
            Attraction meal = Attraction.builder().id(dining.id()).name(dining.name()).location(dining.location()).district(district).build();
            var placement = mealGapSolver == null ? java.util.Optional.<MealGapSolver.Placement>empty()
                    : mealGapSolver.findGap(optTarget.stops(), meal, itineraryTimeEngine.parseDurationMinutes(dining.duration()),
                    taxonomy == null ? null : taxonomy.getMealWindow(slotType),
                    constraints == null ? null : constraints.getTransportPreference(), itineraryBuilder);
            if (placement.isEmpty()) continue;
            String effectiveTime = itineraryTimeEngine.formatTime(placement.get().startMinute());
            Map<String, Object> newDiningStop = itineraryBuilder.createDiningStop(dining, effectiveStopId, effectiveTime, district);
            newDiningStop.put("startTime", effectiveTime);
            newDiningStop.put("district", district);
            optTarget.stops().add(placement.get().index(), newDiningStop);
            mealGapSolver.applyRouteMetadata(optTarget.stops(), placement.get());
            optId = "option-" + (ctx.getOptionPlans().size() + 1);
            ctx.getAdjustmentDiagnostics().put("diningRouteDataStatus", "ESTIMATED");
            ctx.getOptionPlans().put(optId, optionDraft);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("optionId", optId);
            cand.put("venueId", dining.id());
            cand.put("name", dining.name());
            cand.put("requestedName", isDirectDining && i == 0 ? directDiningName : dining.name());
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
            ctx.getAlternatives().addAll(List.of("换同片区其他特色餐厅", "调整就餐时间", "保持原行程餐饮安排"));
        } else {
            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().add(effectiveStopId);
        }
    }

    private void handleScenicStopReplacement(AdjustmentContext ctx, StopLocation target, String effectiveStopId) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> sourceTrip = ctx.getSourceTrip();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        Attraction oldAttr = itineraryBuilder.attraction(String.valueOf(target.stop().get("venueId")));
        if (oldAttr == null) {
            oldAttr = Attraction.builder()
                    .id(String.valueOf(target.stop().get("venueId")))
                    .name(String.valueOf(target.stop().get("name")))
                    .district(extractDistrictFromStop(target.stop()))
                    .duration(String.valueOf(target.stop().getOrDefault("duration", "60分钟")))
                    .summary(String.valueOf(target.stop().getOrDefault("summary", "")))
                    .build();
        }
        Set<String> used = usedVenues(draftTrip);
        if (oldAttr != null) used.remove(oldAttr.getId());

        String requestedReplacementId = firstNonBlank(
                intent.getReplacementPlaceId(),
                intent.getTargetAttractionId(),
                resolveAttractionId(intent.getReplacementPlaceName())
        );
        boolean directReplacement = requestedReplacementId != null;
        List<Attraction> candidates;
        if (directReplacement) {
            Attraction requested = itineraryBuilder.attraction(requestedReplacementId);
            String slotTime = String.valueOf(target.stop().getOrDefault("time", ""));
            if (requested == null) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("TARGET_VENUE_NOT_FOUND");
                ctx.getAlternatives().addAll(List.of("从当前重庆核心景点中选择目标地点", "保持原行程"));
                candidates = List.of();
            } else if (oldAttr != null && requested.getId().equals(oldAttr.getId())) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("SAME_VENUE");
                ctx.getAlternatives().addAll(List.of("选择其他未安排的景点", "保持原行程"));
                candidates = List.of(requested);
            } else if (used.contains(requested.getId())) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("DUPLICATE_VENUE");
                ctx.getAlternatives().addAll(List.of("该景点已在行程中，无需重复添加", "选择其他未去过的景点", "保持原行程"));
                candidates = List.of(requested);
            } else if (!candidateRankingEngine.isOpeningCompatible(requested, slotTime, safe(target.day().get("date")), itineraryTimeEngine)) {
                ctx.setFeasible(false);
                ctx.getReasonCodes().add("CLOSED_AT_SLOT_TIME");
                ctx.getAlternatives().addAll(List.of("调整该站点到营业时间内", "选择其他晚间景点", "保持原行程"));
                candidates = List.of(requested);
            } else {
                candidates = List.of(requested);
            }
        } else {
            Attraction prevAttr = target.index() > 0 ? resolveStopAttraction(target.stops().get(target.index() - 1), itineraryBuilder) : null;
            Attraction nextAttr = target.index() + 1 < target.stops().size() ? resolveStopAttraction(target.stops().get(target.index() + 1), itineraryBuilder) : null;
            String startCoord = extractStartCoordinate(draftTrip, constraints);
            CandidateRankingEngine.CandidateContext candContext = new CandidateRankingEngine.CandidateContext(oldAttr, prevAttr, nextAttr, startCoord);
            candidates = findScenicCandidates(candContext, used, constraints, intent, String.valueOf(target.stop().getOrDefault("time", "")), safe(target.day().get("date")));
        }

        if (candidates.isEmpty() && !directReplacement) {
            ctx.setFatalError(error(400, "当前没有可用的替换候选景点。", ctx));
            return;
        }

        if (directReplacement) {
            ctx.getAdjustmentDiagnostics().put("replacementMode", "DIRECT");
            ctx.getAdjustmentDiagnostics().put("sourceStopName", String.valueOf(target.stop().getOrDefault("name", "当前站点")));
            ctx.getAdjustmentDiagnostics().put("requestedReplacementName", firstNonBlank(intent.getReplacementPlaceName(), requestedReplacementId));
            ctx.getAdjustmentDiagnostics().put("forceApplicable", !candidates.isEmpty());
        }

        for (int i = 0; i < candidates.size() && ctx.getOptionPlans().size() < 3; i++) {
            Attraction c = candidates.get(i);
            Map<String, Object> optionDraft = deepCopy(sourceTrip, ctx.getObjectMapper());
            StopLocation optTarget = locateStop(optionDraft, effectiveStopId);
            if (optTarget == null) continue;
            Map<String, Object> newStop = itineraryBuilder.createStop(
                    c, effectiveStopId,
                    String.valueOf(optTarget.stop().getOrDefault("time", "待安排")),
                    c.getDuration(),
                    "根据调整需求替换为" + c.getName() + "（" + PlannerService.stripTrailingPunctuation(c.getSummary()) + "）。",
                    c.getIntro()
            );
            optTarget.stops().set(optTarget.index(), newStop);
            LocalAdjustmentRepair.repair(optionDraft, number(optTarget.day().get("day")), byId, constraints, intent, effectiveStopId, routeAwarePlanner);
            PlanVerifier.VerificationResult optionVerification = planVerifier.verify(days(optionDraft), constraints);
            int targetDayNum = optTarget.day().get("day") instanceof Number n ? n.intValue() : 1;
            boolean affectedDayFeasible = optionVerification.violations().stream()
                    .noneMatch(v -> v.dayNumber() == targetDayNum && v.isHardConstraint());
            if (!affectedDayFeasible && !directReplacement) {
                ctx.getRejectedOptionDiagnostics().add(Map.of(
                        "venueId", c.getId(),
                        "reasonCodes", optionVerification.violations().stream()
                                .filter(v -> v.dayNumber() == targetDayNum)
                                .map(PlanVerifier.PlanViolation::type).distinct().toList()
                ));
                continue;
            }
            String optId = "option-" + (ctx.getOptionPlans().size() + 1);
            ctx.getCandidateReplacements().add(Map.of(
                    "optionId", optId,
                    "venueId", c.getId(),
                    "name", c.getName(),
                    "requestedName", firstNonBlank(intent.getReplacementPlaceName(), c.getName()),
                    "directReplacement", directReplacement,
                    "summary", c.getSummary(),
                    "duration", c.getDuration(),
                    "district", c.getDistrict(),
                    "walkDifficulty", c.getWalkDifficulty(),
                    "fit", c.getFit()
            ));
            ctx.getOptionPlans().put(optId, optionDraft);
        }

        if (ctx.getOptionPlans().isEmpty()) {
            ctx.setFeasible(false);
            ctx.getReasonCodes().add("NO_FEASIBLE_REPLACEMENT");
            ctx.getAlternatives().addAll(List.of("换同片区其他景点", "减少当天一个景点", "调整至其他日期", "保持原行程"));
        } else {
            ctx.setDraftTrip(ctx.getOptionPlans().get("option-1"));
            ctx.getChangedSegments().add(effectiveStopId);
        }
    }

    private List<Attraction> findScenicCandidates(CandidateRankingEngine.CandidateContext context,
                                                  Set<String> used,
                                                  TravelConstraints constraints,
                                                  PlanAdjustmentIntent intent,
                                                  String slotTime, String date) {
        List<Attraction> catalog = attractionService.list(null, null);
        boolean userRequestedSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && (intent.getRawMessage().contains("同片区") || intent.getRawMessage().contains("同区")));

        Attraction oldAttr = context != null ? context.oldAttr() : null;
        boolean oldDistant = oldAttr != null && isDistantSuburban(oldAttr.getDistrict());
        boolean allowDistant = oldDistant || (constraints != null && constraints.getStartPlace() != null && isDistantSuburban(constraints.getStartPlace()));
        String targetDistrict = oldAttr != null && oldAttr.getDistrict() != null && !oldAttr.getDistrict().isBlank()
                ? oldAttr.getDistrict() : "";

        if (userRequestedSameDistrict && !targetDistrict.isBlank() && !"待确认".equals(targetDistrict)) {
            List<Attraction> sameDistrictCandidates = catalog.stream()
                    .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                    .filter(a -> safe(a.getDistrict()).equals(targetDistrict) || targetDistrict.contains(safe(a.getDistrict())) || safe(a.getDistrict()).contains(targetDistrict))
                    .filter(a -> candidateRankingEngine.isOpeningCompatible(a, slotTime, date, itineraryTimeEngine))
                    .filter(a -> allowDistant || !isDistantSuburban(a.getDistrict()))
                    .map(a -> new CandidateRankingEngine.ScoredCandidate(a, candidateRankingEngine.scoreCandidate(a, context, constraints, intent)))
                    .sorted(Comparator.comparingInt(CandidateRankingEngine.ScoredCandidate::score).reversed()
                            .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                    .map(CandidateRankingEngine.ScoredCandidate::attraction)
                    .toList();
            if (!sameDistrictCandidates.isEmpty()) {
                return sameDistrictCandidates;
            }
        }

        return catalog.stream()
                .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                .filter(a -> candidateRankingEngine.isOpeningCompatible(a, slotTime, date, itineraryTimeEngine))
                .filter(a -> allowDistant ? (oldAttr == null || isDistantSuburban(a.getDistrict()) == oldDistant) : !isDistantSuburban(a.getDistrict()))
                .map(a -> new CandidateRankingEngine.ScoredCandidate(a, candidateRankingEngine.scoreCandidate(a, context, constraints, intent)))
                .sorted(Comparator.comparingInt(CandidateRankingEngine.ScoredCandidate::score).reversed()
                        .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                .map(CandidateRankingEngine.ScoredCandidate::attraction)
                .toList();
    }

    private boolean isDistantSuburban(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足") || district.contains("江津");
    }

    private boolean isDiningIntent(PlanAdjustmentIntent intent) {
        if (intent == null) return false;
        if (intent.getGenericDiningCraving() != null || !safe(intent.getCuisineOrPlace()).isBlank()) return true;
        String raw = firstNonBlank(intent.getRawMessage(), intent.getReplacementPlaceName(), intent.getRequestedVenueName(), "");
        return diningCategoryGrounder.hasDiningKeywords(raw);
    }

    private String resolveAttractionId(String name) {
        String reference = safe(name);
        if (reference.isBlank()) return null;
        if (reference.startsWith("amap-")) return reference;
        List<Attraction> catalog = attractionService.list(null, null);
        for (Attraction attraction : catalog) {
            if (reference.equals(safe(attraction.getId()))) return attraction.getId();
            if (matchesAttractionReference(reference, attraction)) return attraction.getId();
        }
        return null;
    }

    private boolean matchesAttractionReference(String reference, Attraction attraction) {
        if (attraction == null) return false;
        for (String candidate : List.of(attraction.getName(), attraction.getDisplayName())) {
            if (matchesText(reference, candidate)) return true;
        }
        Map<String, Object> amapQuery = attraction.getAmapQuery();
        if (amapQuery == null || amapQuery.isEmpty()) return false;
        Object keywords = amapQuery.get("keywords");
        if (keywords != null && matchesText(reference, String.valueOf(keywords))) return true;
        Object matches = amapQuery.get("matches");
        if (matches instanceof Iterable<?> values) {
            for (Object value : values) {
                if (value != null && matchesText(reference, String.valueOf(value))) return true;
            }
        }
        return false;
    }

    private boolean matchesText(String reference, String candidate) {
        String left = normalizeReference(reference);
        String right = normalizeReference(candidate);
        return !left.isBlank() && !right.isBlank()
                && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private String normalizeReference(String value) {
        return safe(value).replaceAll("[\\p{Punct}\\p{Z}\\p{IsPunctuation}]+", "");
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

package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.service.PlannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/**
 * 抽屉内地点与周边问答策略（处理 PLACE_QUESTION 意图）
 */
@Component
public class PlaceQuestionAdjustmentStrategy implements AdjustmentStrategy {

    private final AttractionService attractionService;
    private final DiningCategoryGrounder diningGrounder = new DiningCategoryGrounder();

    @Autowired(required = false)
    private RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired(required = false)
    private DiningVenueService diningVenueService;

    @Autowired
    public PlaceQuestionAdjustmentStrategy(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    public void setRuntimeAttractionDetailService(RuntimeAttractionDetailService runtimeAttractionDetailService) {
        this.runtimeAttractionDetailService = runtimeAttractionDetailService;
    }

    public void setDiningVenueService(DiningVenueService diningVenueService) {
        this.diningVenueService = diningVenueService;
    }

    @Autowired(required = false)
    public void setTaxonomy(PlannerTaxonomyPort taxonomy) {
        diningGrounder.setTaxonomy(taxonomy);
    }

    @Override
    public boolean supports(ConversationIntentType intentType) {
        return intentType == ConversationIntentType.PLACE_QUESTION;
    }

    @Override
    public void apply(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> trip = ctx.getSourceTrip();
        String answer = answerPlaceQuestion(intent, trip);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("type", "PLACE_QUESTION");
        body.put("operation", "QA");
        body.put("answer", answer);
        body.put("message", answer);
        body.put("modified", false);

        ctx.setFatalError(new PlannerService.ServiceResult(200, body));
    }

    public String answerPlaceQuestion(PlanAdjustmentIntent intent, Map<String, Object> trip) {
        String stopId = intent.getTargetStopId();
        String venueId = intent.getTargetAttractionId();
        String rawText = intent.getRawMessage() != null ? intent.getRawMessage() : "";

        boolean isDiningOrTeaInquiry = intent.getCuisineOrPlace() != null
                || (!diningGrounder.isNonDiningPlace(rawText) && diningGrounder.hasDiningKeywords(rawText));
        if (isDiningOrTeaInquiry) {
            return answerDiningOrTeaQuestion(rawText, intent, trip);
        }

        if (stopId != null && !stopId.isBlank()) {
            StopLocation location = locateStop(trip, stopId);
            if (location != null) {
                String type = safe(location.stop().get("type"));
                String name = String.valueOf(location.stop().get("name"));
                String runtimeVenueId = safe(location.stop().get("venueId"));
                boolean isDining = "DINING".equalsIgnoreCase(type)
                        || Boolean.TRUE.equals(location.stop().get("isDining"));
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && runtimeVenueId.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(runtimeVenueId);
                    if (det == null) {
                        return "暂时没有可核验的地点详情，请确认地点名称或稍后重试。";
                    }
                    return runtimeAttractionDetailService.answer(runtimeVenueId);
                }
                String reason = String.valueOf(location.stop().getOrDefault("recommendationReason", ""));
                String summary = String.valueOf(location.stop().getOrDefault("summary", ""));
                String time = String.valueOf(location.stop().getOrDefault("time", ""));
                String aiGuide = String.valueOf(location.stop().getOrDefault("aiGuide", ""));
                String detail = reason.isBlank() ? summary : reason;
                if (!aiGuide.isBlank()) detail += "\n\n✦ 深度游玩导览：" + aiGuide;
                return "【" + name + "】安排在 " + time + "。" + detail;
            }
        }

        for (Map<?, ?> stop : allStops(trip)) {
            String name = String.valueOf(stop.get("name"));
            String venue = String.valueOf(stop.get("venueId"));
            String type = safe(stop.get("type"));
            boolean isDining = "DINING".equalsIgnoreCase(type)
                    || Boolean.TRUE.equals(stop.get("isDining"));
            if (rawText.contains(name) || (venueId != null && venueId.equals(venue))) {
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && venue.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(venue);
                    if (det == null) {
                        return "暂时没有可核验的地点详情，请确认地点名称或稍后重试。";
                    }
                    return runtimeAttractionDetailService.answer(venue);
                }
                String reason = String.valueOf(stop.get("recommendationReason") == null ? "" : stop.get("recommendationReason"));
                String summary = String.valueOf(stop.get("summary") == null ? "" : stop.get("summary"));
                String time = String.valueOf(stop.get("time") == null ? "" : stop.get("time"));
                String aiGuide = String.valueOf(stop.get("aiGuide") == null ? "" : stop.get("aiGuide"));
                String detail = reason.isBlank() ? summary : reason;
                if (!aiGuide.isBlank()) detail += "\n\n✦ 深度游玩导览：" + aiGuide;
                return "【" + name + "】安排在 " + time + "。" + detail;
            }
        }

        for (Attraction a : attractionService.list(null, null)) {
            if (rawText.contains(a.getName()) || (venueId != null && venueId.equals(a.getId()))) {
                return "【" + a.getName() + "】" + a.getSummary() + "（门票：" + a.getTicket() + "，建议游玩：" + a.getDuration() + "）。" + a.getIntro();
            }
        }

        if (runtimeAttractionDetailService != null) {
            String reference = extractPlaceReference(rawText, intent);
            if (!reference.isBlank()) {
                String ans = runtimeAttractionDetailService.answerReference(reference);
                if (ans != null && !ans.contains("暂时没有从高德核验")) {
                    return ans;
                }
                return "暂时没有可核验的地点详情，请确认地点名称或稍后重试。";
            }
        }

        return "关于旅游景点，您可以随时在行程中点击卡片提问，或告诉我具体景点名称。";
    }

    public String answerDiningOrTeaQuestion(String rawText, PlanAdjustmentIntent intent, Map<String, Object> trip) {
        if (diningVenueService == null) return "餐饮查询服务暂不可用，请稍后重试。";
        String city=resolveTripCity(trip, (com.ai.guide.domain.planner.model.TravelConstraints) null);
        Map<String,Object> anchor=null;
        if (intent != null && intent.getTargetStopId() != null) {
            StopLocation selected=locateStop(trip,intent.getTargetStopId());
            if (selected != null) anchor=selected.stop();
        }
        if (anchor == null && trip != null) {
            Map<String,Object> day=findDay(trip,intent != null && intent.getDayNumber() != null ? intent.getDayNumber() : 1);
            if (day != null && !dayStops(day).isEmpty()) anchor=dayStops(day).get(0);
        }
        String district=anchor == null ? "" : safe(anchor.get("district"));
        String coordinate=anchor == null ? null : safe(anchor.get("location"));
        String explicitArea = intent == null ? null : intent.getTargetPlace();
        if (explicitArea == null || explicitArea.isBlank()) {
            // A location phrase supplies a query, never a fixed district or guessed coordinate.
            Matcher location = Pattern.compile("(?:^|[，,。；;\\s])([^，,。！？?；;\\s]+?)(?:附近|周边)").matcher(rawText);
            if (location.find()) explicitArea = location.group(1);
        }
        if (explicitArea != null && !explicitArea.isBlank()) {
            district = explicitArea.trim();
            coordinate = null; // Explicit area overrides an unrelated selected itinerary anchor.
        }
        if (!isValidCoordinate(coordinate) && (city == null || city.isBlank()))
            return "请提供所在城市或行程中的用餐锚点，以便查询附近餐厅。";
        String slot=diningGrounder.resolveSlotType(intent);
        String keyword=intent != null && intent.getCuisineOrPlace() != null ? intent.getCuisineOrPlace()
                : diningGrounder.resolveRequestedFlavor(rawText,"餐厅");
        List<DiningVenue> venues=diningVenueService.searchLiveDiningOrTea(district,coordinate,keyword,slot,3,city);
        if (venues == null || venues.isEmpty()) return "未找到可核验的附近餐饮候选，请确认位置或调整菜系后重试。";
        StringBuilder answer=new StringBuilder("附近餐饮候选（营业状态、菜单和实际路线请确认）：\n\n");
        int index=1;
        for (DiningVenue venue:venues) {
            answer.append(index++).append(". 【").append(venue.getName()).append("】（").append(venue.getDistrict()).append("）\n")
                    .append("   • 菜单：").append(venue.getSpecialtyDish()).append("\n")
                    .append("   • 消费：").append(venue.getAverageCost()).append("\n")
                    .append("   • 来源信息：").append(venue.getSummary()).append("\n\n");
        }
        return answer.toString();
    }

    private String extractPlaceReference(String rawText, PlanAdjustmentIntent intent) {
        String explicit = firstNonBlank(intent == null ? null : intent.getTargetAttractionId(),
                intent == null ? null : intent.getReplacementPlaceName(),
                intent == null ? null : intent.getRequestedVenueName());
        if (explicit != null && !explicit.startsWith("cq-")) return explicit;
        if (rawText == null) return "";
        Matcher bracket = Pattern.compile("【([^】]+)】|[“‘\"']([^”’\"']+)[”’\"']").matcher(rawText);
        if (bracket.find()) return firstNonBlank(bracket.group(1), bracket.group(2));
        return "";
    }
}

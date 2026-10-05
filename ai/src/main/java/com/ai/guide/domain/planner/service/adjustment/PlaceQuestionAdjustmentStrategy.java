package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
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

        boolean isDiningOrTeaInquiry = rawText.matches(".*(茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|原盅茶|甜食|甜品|糖水|冰粉|凉糕|糍粑|火锅|九宫格|老火锅|串串|烧烤|烤肉|烤脑花|夜市|小吃|美食|特产|特色菜|江湖菜|川菜|小面|面馆|面条|抄手|豆花|早餐|早点|午餐|晚餐|宵夜|夜宵|吃|喝|咖啡|下午茶|小憩).*");
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
                        || Boolean.TRUE.equals(location.stop().get("isDining"))
                        || name.matches(".*(餐|饭|馆|烧烤|火锅|小吃|面馆|串串|抄手|烤肉|大排档).*")
                        || runtimeVenueId.matches(".*(dining|restaurant|food).*");
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && runtimeVenueId.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(runtimeVenueId);
                    if (det == null) {
                        return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
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
                    || Boolean.TRUE.equals(stop.get("isDining"))
                    || name.matches(".*(餐|饭|馆|烧烤|火锅|小吃|面馆|串串|抄手|烤肉|大排档).*")
                    || venue.matches(".*(dining|restaurant|food).*");
            if (rawText.contains(name) || (venueId != null && venueId.equals(venue))) {
                if (isDining) {
                    return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
                }
                if (runtimeAttractionDetailService != null && venue.startsWith("amap-")) {
                    Map<String, Object> det = runtimeAttractionDetailService.detail(venue);
                    if (det == null) {
                        return answerDiningOrTeaQuestion(rawText.isBlank() ? name : rawText, intent, trip);
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
                return answerDiningOrTeaQuestion(reference, intent, trip);
            }
        }

        return "关于重庆旅游景点，您可以随时在行程中点击卡片提问，或告诉我具体景点名称。";
    }

    public String answerDiningOrTeaQuestion(String rawText, PlanAdjustmentIntent intent, Map<String, Object> trip) {
        if (diningVenueService == null) {
            diningVenueService = new DiningVenueService();
        }

        String district = null;
        if (rawText.contains("南岸") || rawText.contains("南滨路") || rawText.contains("龙门浩") || rawText.contains("南山") || rawText.contains("弹子石") || rawText.contains("上新街")) {
            district = "南岸区";
        } else if (rawText.contains("渝中") || rawText.contains("解放碑") || rawText.contains("洪崖洞") || rawText.contains("朝天门") || rawText.contains("十八梯") || rawText.contains("戴家巷") || rawText.contains("李子坝") || rawText.contains("鹅岭")) {
            district = "渝中区";
        } else if (rawText.contains("沙坪坝") || rawText.contains("磁器口") || rawText.contains("歌乐山") || rawText.contains("三峡广场")) {
            district = "沙坪坝区";
        } else if (rawText.contains("九龙坡") || rawText.contains("黄桷坪") || rawText.contains("杨家坪")) {
            district = "九龙坡区";
        } else if (rawText.contains("江北") || rawText.contains("观音桥") || rawText.contains("北仓") || rawText.contains("九街") || rawText.contains("大剧院")) {
            district = "江北区";
        } else if (rawText.contains("渝北")) {
            district = "渝北区";
        } else if (rawText.contains("武隆")) {
            district = "武隆区";
        } else if (rawText.contains("大足")) {
            district = "大足区";
        }

        if (district == null && trip != null) {
            for (Map<?, ?> s : allStops(trip)) {
                String d = safe(s.get("district"));
                if (!d.isBlank() && !"待确认".equals(d) && d.endsWith("区")) {
                    district = d;
                    break;
                }
            }
        }
        if (district == null || district.isBlank()) {
            district = "渝中区";
        }

        String coordinate = null;
        if (intent != null && intent.getTargetStopId() != null && !intent.getTargetStopId().isBlank()) {
            StopLocation loc = locateStop(trip, intent.getTargetStopId());
            if (loc != null && loc.stop() != null) {
                coordinate = safe(loc.stop().get("location"));
            }
        }

        boolean isTea = rawText.matches(".*(茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|原盅茶|老茶馆).*");
        boolean isSweet = rawText.matches(".*(甜食|甜品|糖水|冰粉|凉糕|糍粑|甜点).*");
        boolean isHotpot = rawText.matches(".*(火锅|串串|九宫格|老火锅).*");
        boolean isNoodle = rawText.matches(".*(小面|面馆|面条|牛肉面|抄手|早餐|早点).*");

        String category;
        String kw;
        String headerTitle;
        String tipText;

        if (isTea) {
            category = "TEA_BREAK";
            kw = "茶馆";
            headerTitle = "为您推荐【" + district + "】及周边的特色老茶馆与品茗小憩好去处：";
            tipText = "💡 悠悠贴士：如需在动线中安排品茗歇脚，您可以随时告诉我“在路线中增加一处老茶馆停留”或在行程中点击卡片一键添加！";
        } else if (isSweet) {
            category = "SWEET";
            kw = "甜品";
            headerTitle = "重庆有许多温润可口的手工传统特色甜品与糖水铺，清凉解乏、温润暖胃，特别适合游玩间隙小憩：";
            tipText = "💡 悠悠贴士：如需加入行程，您可以随时对我说“路线中加个甜水铺”或“来碗冰粉”，我来为您顺路规划！";
        } else if (isHotpot) {
            category = "HOTPOT";
            kw = "火锅";
            headerTitle = "为您精选【" + district + "】及周边地道品质老火锅推荐：";
            tipText = "💡 悠悠贴士：带长辈可嘱咐店家选择“微辣牛油”或“鸳鸯锅”，配上清凉手工红糖冰粉解辣更舒适。随时可提出换餐或加站！";
        } else if (isNoodle) {
            category = "BREAKFAST";
            kw = "小面";
            headerTitle = "为您推荐【" + district + "】地道好口碑的重庆小面与早点：";
            tipText = "💡 悠悠贴士：晨间赶路可嘱咐店家二两干熘少辣加蛋，配上一杯热唯怡豆奶更暖胃。";
        } else {
            category = "DINING";
            kw = "特色美食";
            headerTitle = "为您检索到【" + district + "】附近的特色餐饮与地道美食推荐：";
            tipText = "💡 悠悠贴士：您可以随时对我说“把午餐/晚餐换成这家”或“在路线中增加此餐”，我会自动优化时间排程！";
        }

        List<DiningVenue> venues = diningVenueService.searchLiveDiningOrTea(district, coordinate, kw, category, 3);
        if (venues == null || venues.isEmpty()) {
            venues = diningVenueService.listAll().stream().limit(3).toList();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(headerTitle).append("\n\n");

        int idx = 1;
        for (DiningVenue v : venues) {
            sb.append(idx++).append(". 【").append(v.getName()).append("】（").append(v.getDistrict()).append("）\n");
            if (isTea) {
                sb.append("   • 招牌茶品：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "特色盖碗茶与茶点").append("\n");
            } else if (isSweet) {
                sb.append("   • 招牌必吃：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "手工红糖冰粉、老红糖凉糕").append("\n");
            } else if (isHotpot) {
                sb.append("   • 招牌必点：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "经典牛油红锅、鲜毛肚").append("\n");
            } else if (isNoodle) {
                sb.append("   • 招牌品类：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "地道重庆小面、红油抄手").append("\n");
            } else {
                sb.append("   • 招牌菜品：").append(v.getSpecialtyDish() != null ? v.getSpecialtyDish() : "店内招牌特色菜").append("\n");
            }
            sb.append("   • 游玩体验：").append(v.getSummary() != null ? v.getSummary() : "交通便捷，环境舒适。")
                    .append("（").append(v.getAverageCost() != null ? v.getAverageCost() : "消费适中")
                    .append("，").append(v.getRecommendationReason() != null ? v.getRecommendationReason() : "长辈适老友好")
                    .append("）\n\n");
        }

        sb.append(tipText);
        return sb.toString();
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

package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detail projection for attractions discovered by the runtime AMap planner.
 * Runtime POIs are not inserted into the core catalog; this service keeps the
 * provider lookup request-scoped and exposes unknown facts explicitly.
 */
@Service
public class RuntimeAttractionDetailService {

    private final GeoRouteGatewayPort routeService;
    private final DynamicShortTripPolicyPort dynamicPolicy;
    private final Map<String, Attraction> runtimeAttractionCache = new ConcurrentHashMap<>();

    @Autowired
    public RuntimeAttractionDetailService(@Autowired(required = false) GeoRouteGatewayPort routeService,
                                          DynamicShortTripPolicyPort dynamicPolicy) {
        this.routeService = routeService;
        this.dynamicPolicy = dynamicPolicy;
    }

    public RuntimeAttractionDetailService(GeoRouteGatewayPort routeService) {
        this(routeService, DynamicShortTripPolicyPort::unconfigured);
    }

    public Map<String, Object> detail(String entityId) {
        String poiId = poiId(entityId);
        if (poiId.isBlank()) return null;
        GeoPoiOutcome outcome = routeService.poiDetail(poiId);
        if (outcome.status() != GeoOutcomeStatus.SUCCESS
                || outcome.candidates() == null || outcome.candidates().isEmpty()) return null;
        com.ai.guide.domain.geo.model.GeoPoiCandidate poi = outcome.candidates().get(0);
        return projection(poi, outcome);
    }

    /** Bridges an AMap runtime entity ID into a full Attraction domain model for planning. */
    public Attraction toAttraction(String entityId) {
        if (entityId == null || entityId.isBlank()) return null;
        Attraction cached = runtimeAttractionCache.get(entityId);
        if (cached != null) return cached;
        String poiId = poiId(entityId);
        if (poiId.isBlank()) return null;
        GeoPoiOutcome outcome = routeService.poiDetail(poiId);
        if (outcome.status() != GeoOutcomeStatus.SUCCESS
                || outcome.candidates() == null || outcome.candidates().isEmpty()) return null;
        com.ai.guide.domain.geo.model.GeoPoiCandidate poi = outcome.candidates().get(0);
        Attraction built = buildAttractionFromPoi(entityId, poi);
        if (built != null) {
            runtimeAttractionCache.put(entityId, built);
        }
        return built;
    }

    public Attraction buildAttractionFromPoi(String entityId, com.ai.guide.domain.geo.model.GeoPoiCandidate poi) {
        if (poi == null) return null;
        String name = text(poi.name());
        String address = text(poi.address());
        String district = extractDistrict(address);
        String location = coordinate(poi.coordinate());
        String photoUrl = text(poi.photoUrl());
        int visitMins = dynamicPolicy.getPolicy().visitDuration(name, text(poi.type()), 60);
        return Attraction.builder()
                .id(entityId)
                .name(name)
                .city(text(poi.cityName()))
                .displayName(name)
                .district(district.isBlank() ? "所在片区待核验" : district)
                .category("探索打卡")
                .tags(List.of("高德实时", "探索打卡", district.isBlank() ? "动态地点" : district))
                .icon("景")
                .tone("dynamic")
                .location(location)
                .summary("高德地图实时周边探索点，位于" + (address.isBlank() ? "地址待核验" : address))
                .intro("该地点由高德地图 POI 实时核验确认，位于" + address + "。具体开放时间与出入管理以现场或官方公告为准。")
                .walk("可使用高德导航直达")
                .walkDifficulty("中")
                .ticket("以现场公告为准")
                .bestTime("开放时间待核验")
                .duration("约 " + visitMins + " 分钟")
                .recommendedVisitMinutes(visitMins)
                .fit("适合自由行探索、摄影打卡")
                .image(photoUrl)
                .imageSource("高德 POI 检索接口")
                .imageStatus(photoUrl.isBlank() ? "未返回" : "已返回")
                .build();
    }

    public void registerDynamicAttraction(Attraction attraction) {
        if (attraction != null && attraction.getId() != null) {
            runtimeAttractionCache.put(attraction.getId(), attraction);

        }
    }

    public void registerDynamicAttraction(Attraction attraction, String city) {
        registerDynamicAttraction(attraction);
        if (attraction != null && attraction.getName() != null && city != null && !city.isBlank()) {
            runtimeAttractionCache.put("query:" + normalizeCity(city) + ":" + attraction.getName(), attraction);
        }
    }

    /**
     * 动态按地点名称调用高德 Web API 搜索 POI 并封装为 Attraction 对象
     */
    public Attraction searchAndBuildAttraction(String query, String city) {
        String cleanQuery = text(query);
        if (cleanQuery.isBlank()) return null;

        // 剔除后缀噪声，如“打卡点”、“打卡”、“景点”、“景区”等
        cleanQuery = cleanQuery.replaceAll("(?:打卡点|打卡|景点|景区|门票|参观|游玩)+$", "").trim();
        if (cleanQuery.isBlank()) cleanQuery = text(query);

        String effectiveCity = text(city);
        String cityQueryKey = "query:" + normalizeCity(effectiveCity) + ":" + cleanQuery;
        Attraction preCached = runtimeAttractionCache.get(cityQueryKey);
        if (preCached != null) return preCached;

        if (routeService == null || !routeService.isConfigured()) return null;

        GeoPoiOutcome search = routeService.searchPoi(cleanQuery, effectiveCity);
        List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates = search.candidates();

        // 若直接搜索未果且包含高校/地标后缀，尝试以核心主体兜底检索
        if ((candidates == null || candidates.isEmpty()) && cleanQuery.length() > 6) {
            String fallbackQuery = cleanQuery;
            if (cleanQuery.contains("大学")) {
                fallbackQuery = cleanQuery.substring(0, cleanQuery.indexOf("大学") + 2);
            } else if (cleanQuery.contains("学院")) {
                fallbackQuery = cleanQuery.substring(0, cleanQuery.indexOf("学院") + 2);
            } else if (cleanQuery.contains("公园")) {
                fallbackQuery = cleanQuery.substring(0, cleanQuery.indexOf("公园") + 2);
            }
            if (!fallbackQuery.equals(cleanQuery)) {
                GeoPoiOutcome retry = routeService.searchPoi(fallbackQuery, effectiveCity);
                if (retry.candidates() != null && !retry.candidates().isEmpty()) {
                    candidates = retry.candidates();
                }
            }
        }

        if (candidates == null || candidates.isEmpty()) return null;

        com.ai.guide.domain.geo.model.GeoPoiCandidate poi = candidates.get(0);
        String entityId = "amap-" + text(poi.poiId());
        if (entityId.equals("amap-")) {
            entityId = "amap-" + Math.abs(cleanQuery.hashCode());
        }

        Attraction built = buildAttractionFromPoi(entityId, poi);
        if (built != null) {
            runtimeAttractionCache.put(entityId, built);
            runtimeAttractionCache.put(cityQueryKey, built);
        }
        return built;
    }

    /** Returns an AMap-backed answer suitable for the planner chat bubble. */
    public String answer(String entityId) {
        Map<String, Object> value = detail(entityId);
        if (value == null) return "暂时没有从高德核验到该景点的详情，请稍后重试或打开高德查看。";
        String name = text(value.get("name"));
        String address = text(value.get("address"));
        String district = text(value.get("district"));
        String type = text(value.get("type"));
        String image = text(value.get("image"));
        String aiGuide = text(value.get("aiGuide"));

        StringBuilder answer = new StringBuilder("【").append(name).append("】\n");
        if (!address.isBlank()) answer.append("地址：").append(address).append(district.isBlank() ? "" : "（" + district + "）").append("\n");
        if (!type.isBlank()) answer.append("类型：").append(type).append("\n");
        if (!aiGuide.isBlank()) {
            answer.append("AI 智囊深度导览：\n").append(aiGuide).append("\n");
        }
        answer.append("开放时间：待核验（高德本次 POI 详情未返回可靠营业时间）\n")
                .append("门票：待核验（以景区现场或官方管理公告为准）\n")
                .append("交通：可使用高德导航，路线需结合实时交通再次计算。");
        if (!image.isBlank()) answer.append("\n图片：已返回高德 POI 图片。");
        return answer.toString();
    }

    /** Searches an arbitrary user-mentioned place first, then answers from its
     * provider detail. The caller is responsible for deciding that the message
     * is a place question; this service never guesses that intent. */
    public String answerReference(String reference) {
        return answerReference(reference, "");
    }

    public String answerReference(String reference, String city) {
        String query = text(reference);
        if (query.isBlank()) return "请提供具体景点名称，我再为你查询高德实时详情。";
        if (routeService == null || !routeService.isConfigured()) return "地点查询暂不可用，请稍后重试。";
        GeoPoiOutcome search = routeService.searchPoi(query, text(city));
        if (search.candidates() == null || search.candidates().isEmpty()) {
            return "高德暂时没有找到“" + query + "”，请检查名称或补充所在区县。";
        }
        com.ai.guide.domain.geo.model.GeoPoiCandidate poi = search.candidates().get(0);
        return answer("amap-" + text(poi.poiId()));
    }

    public String generateAiGuide(String name, String type, String address, String district) {
        return "【地点信息】" + text(name) + "，类型：" + (text(type).isBlank() ? "待核验" : text(type))
                + "，地址：" + (text(address).isBlank() ? "待核验" : text(address))
                + "。\n【出行核验】本次 POI 未提供可靠的开放时间、门票、预约、坡度或无障碍信息，请核实官方公告与现场条件后安排。";
    }

    private Map<String, Object> projection(com.ai.guide.domain.geo.model.GeoPoiCandidate poi,
                                           GeoPoiOutcome outcome) {
        String id = "amap-" + text(poi.poiId());
        String name = text(poi.name());
        String address = text(poi.address());
        String district = extractDistrict(address);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("attractionId", id);
        result.put("entityId", id);
        result.put("name", name);
        result.put("displayName", name);
        result.put("district", district);
        result.put("type", text(poi.type()));
        result.put("address", address);
        result.put("location", coordinate(poi.coordinate()));
        int visitMins = dynamicPolicy.getPolicy().visitDuration(name, text(poi.type()), 180);
        result.put("duration", "约 " + visitMins + " 分钟");
        String ticketAdvice = (address.contains("大学") || address.contains("学院") || name.contains("大学") || name.contains("学院"))
                ? "以现场及校方公告为准（校区管理需遵守参访出入规则）"
                : "以现场及官方公告为准（门票待核验）";
        result.put("ticket", ticketAdvice);
        result.put("bestTime", "开放时间待核验 · 请核实后安排");
        result.put("walk", "高德导航直达 · 路线结合实时交通");
        result.put("fit", "适合自由行、城市风光摄影与周边探索打卡");
        result.put("summary", "高德周边搜索确认的实时打卡点，位于" + address + "。");
        result.put("intro", "该地点由高德地图 POI 实时核验确认（" + text(poi.type()) + "）。开放时间、门票与出入规则以现场及官方管理公告为准，建议使用高德实时导航前往。");
        result.put("aiGuide", generateAiGuide(name, text(poi.type()), address, district));
        result.put("tags", List.of("高德实时", "动态打卡", district));
        result.put("image", text(poi.photoUrl()));
        result.put("imageSource", "高德 POI 详情接口");
        result.put("imageStatus", text(poi.photoUrl()).isBlank() ? "未返回" : "已返回");
        result.put("imageReason", text(poi.photoUrl()).isBlank()
                ? "高德本次没有返回可用图片。" : "图片来自本次高德 POI 详情查询。");
        result.put("sourceMode", "AMAP_RUNTIME");
        result.put("dataStatus", "动态·高德 POI 已核验");
        result.put("facts", facts(poi));
        result.put("citations", List.of(Map.of(
                "title", "高德 POI 详情", "publisher", "高德开放平台",
                "endpoint", "/v5/place/detail", "status", "已核验",
                "fetchedAt", String.valueOf(outcome.fetchedAt()))));
        result.put("actions", Map.of("canAdd", true, "canReplace", true, "canNavigate", true));
        return result;
    }

    public String extractDistrict(String address) {
        if (address == null || address.isBlank()) return "";
        String rest = address.substring(address.lastIndexOf('市') + 1);
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("^([\\p{IsHan}]{2,8}(?:区|县|旗))").matcher(rest);
        return match.find() ? match.group(1) : "";
    }

    private String normalizeCity(String city) {
        return text(city).replaceFirst("市$", "");
    }

    private List<Map<String, Object>> facts(com.ai.guide.domain.geo.model.GeoPoiCandidate poi) {
        List<Map<String, Object>> facts = new ArrayList<>();
        facts.add(fact("地址", text(poi.address()), text(poi.address()).isBlank() ? "未知" : "动态",
                "来自本次高德 POI 详情查询。"));
        facts.add(fact("地点类型", text(poi.type()), text(poi.type()).isBlank() ? "未知" : "动态",
                "来自本次高德 POI 详情查询。"));
        facts.add(fact("开放时间", "以现场及官方公告为准", "待核验",
                "高德基础 POI 详情未返回可靠营业时间，请以官方公告为准。"));
        facts.add(fact("门票与消费", "以现场及官方公告为准", "待核验",
                "高德基础 POI 详情未返回票务信息，请以景区官方公告为准。"));
        facts.add(fact("交通方式", "可使用高德导航直达", "动态",
                "路线需要结合实时出发时间再次查询。"));
        return facts;
    }

    private Map<String, Object> fact(String label, String value, String status, String note) {
        return Map.of("label", label, "value", value == null || value.isBlank() ? "未知·待核验" : value,
                "status", status, "note", note);
    }

    private String poiId(String entityId) {
        String value = text(entityId);
        if (value.startsWith("amap-")) return value.substring("amap-".length());
        if (value.startsWith("runtime-stop-")) {
            int lastDash = value.lastIndexOf('-');
            if (lastDash >= 0 && lastDash < value.length() - 1) {
                String candidate = value.substring(lastDash + 1);
                if (!candidate.isBlank()) return candidate;
            }
        }
        return "";
    }

    private String coordinate(com.ai.guide.domain.geo.model.GeoCoordinate coordinate) {
        return coordinate == null ? "" : coordinate.longitude() + "," + coordinate.latitude();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}


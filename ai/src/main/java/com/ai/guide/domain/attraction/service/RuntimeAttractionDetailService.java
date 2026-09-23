package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.service.AmapRouteService;
import com.ai.guide.domain.planner.service.RuntimeLocationPlanner;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
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

    private final AmapRouteService routeService;
    private final Map<String, Attraction> runtimeAttractionCache = new ConcurrentHashMap<>();

    public RuntimeAttractionDetailService(AmapRouteService routeService) {
        this.routeService = routeService;
    }

    public Map<String, Object> detail(String entityId) {
        String poiId = poiId(entityId);
        if (poiId.isBlank()) return null;
        AmapRouteService.PoiOutcome outcome = routeService.poiDetail(poiId);
        if (outcome.status() != AmapRouteService.OutcomeStatus.SUCCESS
                || outcome.candidates() == null || outcome.candidates().isEmpty()) return null;
        AmapResponseNormalizer.PoiCandidate poi = outcome.candidates().get(0);
        return projection(poi, outcome);
    }

    /** Bridges an AMap runtime entity ID into a full Attraction domain model for planning. */
    public Attraction toAttraction(String entityId) {
        if (entityId == null || entityId.isBlank()) return null;
        Attraction cached = runtimeAttractionCache.get(entityId);
        if (cached != null) return cached;
        String poiId = poiId(entityId);
        if (poiId.isBlank()) return null;
        AmapRouteService.PoiOutcome outcome = routeService.poiDetail(poiId);
        if (outcome.status() != AmapRouteService.OutcomeStatus.SUCCESS
                || outcome.candidates() == null || outcome.candidates().isEmpty()) return null;
        AmapResponseNormalizer.PoiCandidate poi = outcome.candidates().get(0);
        Attraction built = buildAttractionFromPoi(entityId, poi);
        if (built != null) {
            runtimeAttractionCache.put(entityId, built);
        }
        return built;
    }

    public Attraction buildAttractionFromPoi(String entityId, AmapResponseNormalizer.PoiCandidate poi) {
        if (poi == null) return null;
        String name = text(poi.name());
        String address = text(poi.address());
        String district = extractDistrict(address);
        String location = coordinate(poi.coordinate());
        String photoUrl = text(poi.photoUrl());
        int visitMins = RuntimeLocationPlanner.inferVisitMinutes(name, text(poi.type()), 60);
        return Attraction.builder()
                .id(entityId)
                .name(name)
                .displayName(name)
                .district(district.isBlank() ? "重庆核心片区" : district)
                .category("探索打卡")
                .tags(List.of("高德实时", "探索打卡", district.isBlank() ? "重庆" : district))
                .icon("景")
                .tone("dynamic")
                .location(location)
                .summary("高德地图实时周边探索点，位于" + (address.isBlank() ? "重庆市" : address))
                .intro("该地点由高德地图 POI 实时核验确认，位于" + address + "。具体开放时间与出入管理以现场或官方公告为准。")
                .walk("可使用高德导航直达")
                .walkDifficulty("中")
                .ticket("以现场公告为准")
                .bestTime("全天或开放时间段")
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
            if (attraction.getName() != null) {
                runtimeAttractionCache.put("name:" + attraction.getName(), attraction);
            }
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

        Attraction preCached = runtimeAttractionCache.get("name:" + cleanQuery);
        if (preCached != null) return preCached;

        if (routeService == null || !routeService.isConfigured()) return null;

        String effectiveCity = (city == null || city.isBlank()) ? "重庆市" : city;
        AmapRouteService.PoiOutcome search = routeService.searchPoi(cleanQuery, effectiveCity);
        List<AmapResponseNormalizer.PoiCandidate> candidates = search.candidates();

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
                AmapRouteService.PoiOutcome retry = routeService.searchPoi(fallbackQuery, effectiveCity);
                if (retry.candidates() != null && !retry.candidates().isEmpty()) {
                    candidates = retry.candidates();
                }
            }
        }

        if (candidates == null || candidates.isEmpty()) return null;

        AmapResponseNormalizer.PoiCandidate poi = candidates.get(0);
        String entityId = "amap-" + text(poi.poiId());
        if (entityId.equals("amap-")) {
            entityId = "amap-" + Math.abs(cleanQuery.hashCode());
        }

        Attraction built = buildAttractionFromPoi(entityId, poi);
        if (built != null) {
            runtimeAttractionCache.put(entityId, built);
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
        String query = text(reference);
        if (query.isBlank()) return "请提供具体景点名称，我再为你查询高德实时详情。";
        AmapRouteService.PoiOutcome search = routeService.searchPoi(query, "重庆市");
        if (search.candidates() == null || search.candidates().isEmpty()) {
            return "高德暂时没有找到“" + query + "”，请检查名称或补充所在区县。";
        }
        AmapResponseNormalizer.PoiCandidate poi = search.candidates().get(0);
        return answer("amap-" + text(poi.poiId()));
    }

    public String generateAiGuide(String name, String type, String address, String district) {
        String n = text(name);
        String t = text(type);
        String d = (district != null && !district.isBlank()) ? district : extractDistrict(address);
        if (d.isBlank()) d = "重庆核心片区";

        // 1. Memorials / Famous historical persons / Former residences / Revolutionary heritage
        if (n.contains("纪念馆") || n.contains("故居") || n.contains("旧居") || n.contains("生平") || n.contains("陈列馆")
                || n.contains("烈士") || n.contains("革命") || n.contains("抗战") || n.contains("红岩") || n.contains("公馆")
                || n.contains("祠堂") || n.contains("先贤") || n.contains("卢作孚") || n.contains("陶行知")
                || n.contains("郭沫若") || n.contains("徐悲鸿") || n.contains("周恩来") || n.contains("张伯苓")
                || n.contains("纪念亭") || n.contains("旧址") || n.contains("遗址")) {
            return "【人文与历史底蕴】承载着厚重的近现代人文历史、先贤风骨或红色革命记忆，是感悟城市文脉与精神传承的庄重之地。\n" +
                   "【瞻仰与参访建议】建议放慢脚步静心参观，细读生平史料、遗迹展陈与纪念铭文，深入了解先贤报国风骨与历史风云。\n" +
                   "【参访核验与礼仪】场馆多位于历史街区、纪念园区或校区内，通常免费开放；参访瞻仰时请保持庄重肃静，遵守现场秩序。";
        }

        // 2. Museums / Art Galleries / Cultural Exhibitions
        if (n.contains("博物馆") || n.contains("美术馆") || n.contains("科技馆") || n.contains("艺术馆")
                || n.contains("展览馆") || n.contains("规划馆") || n.contains("博览") || n.contains("画廊")
                || t.contains("博物馆") || t.contains("美术馆")) {
            return "【文化艺术与展陈亮点】汇聚特色文博典藏或高水准艺术展览，兼具室内沉浸式美学体验与山城文化特色。\n" +
                   "【观展与体验建议】推荐预留 1-2 小时深度观摩核心展厅或特色特展，配合语音导览或展品注解细细品味。\n" +
                   "【观展提示与预约】室内场馆不受天气影响，是阴雨天或酷暑期的优质参访选择；建议提前关注官方公众号核验是否需实名预约入场。";
        }

        // 3. Historic Old Streets / Ancient Towns / Folk Wharf
        if (n.contains("老街") || n.contains("古镇") || n.contains("街区") || n.contains("码头")
                || n.contains("坊") || n.contains("里") || n.contains("巷") || n.contains("山城步道")
                || n.contains("古村") || t.contains("古镇")) {
            return "【历史风貌与巴渝市井】依山就势分布着传统巴渝吊脚楼建筑、青石板路与历史街巷肌理，洋溢着道地市井烟火气息。\n" +
                   "【漫步与打卡建议】推荐沿青石步道穿街走巷，寻访老茶馆、地道非遗手艺与特色风味小吃，傍晚时分灯笼亮起尤具山城韵味。\n" +
                   "【出行核验与提示】老街步道坡度起伏且多阶梯，建议穿着防滑平底鞋；巷道交错纵横，可结合高德步行导航深度漫游。";
        }

        // 4. City Viewpoints / High Observation Decks / Skylines
        if (n.contains("观景") || n.contains("顶楼") || n.contains("天台") || n.contains("一棵树")
                || n.contains("观景台") || t.contains("观景点")) {
            return "【视觉景观与天际视野】位处" + d + "高位或两江交汇视野开阔机位，具备俯瞰山城层叠建筑、桥都飞跨与江景风貌的绝佳视角。\n" +
                   "【最佳打卡时段】推荐在晴天 17:30 至 20:00 期间前往，可一站式饱览黄昏夕照、蓝调时刻与山城错落万家灯火的立体交融。\n" +
                   "【出行建议与提示】观景高台常有夜风，夜间前往可适当添衣；高峰时段热门机位人流较多，请注意脚下安全与随身物品保管。";
        }

        // 5. Large Nature & Mountain Parks (Only when genuine forest/mountain/wetland)
        if (n.contains("森林公园") || n.contains("湿地公园") || n.contains("大峡谷") || n.contains("自然保护区")
                || n.contains("国家森林") || n.contains("登山步道") || n.contains("健身步道") || n.contains("绿道")) {
            return "【自然生态与负氧离子】属于典型的山城山林生态绿境，林木苍翠、负氧离子充沛，是远离闹市喧嚣、亲近自然的天然氧吧。\n" +
                   "【游览体验建议】沿山势步道或水系慢行，适宜轻户外徒步、自然生态摄影与家庭亲子休闲漫步。\n" +
                   "【出行安全与提醒】山林生态区域面积较大，请沿规定游步道前行；夏季林区建议携带防蚊用品，阴雨天阶梯湿滑需慢行防跌。";
        }

        // 6. Urban City Parks & Waterfront Greenways
        if (n.contains("公园") || n.contains("滨江") || n.contains("湖") || n.contains("池") || n.contains("广场")
                || t.contains("公园广场")) {
            return "【城市公共绿地与市民休闲】作为" + d + "市民亲水沿江或核心开阔绿地空间，园景舒展、视野通透，生动展现山城市民悠闲惬意的生活日常。\n" +
                   "【休闲漫步建议】推荐沿步道或林荫草坪信步闲游，欣赏绿植花卉与城市街景，吹风小憩享受慢节奏的松弛感。\n" +
                   "【出行提示】大多为市政全天开放免费空间，设施完善平缓；建议根据日照选择清晨或傍晚散步，体验更加舒适。";
        }

        // 7. Universities & Academic Heritage
        if (n.contains("大学") || n.contains("学院") || n.contains("校区") || n.contains("校史馆")
                || n.contains("早期建筑") || n.contains("书院") || n.contains("工学院") || n.contains("礼堂")) {
            return "【高校文旅与百年文脉】承载着深厚的学术科研底蕴与百年办学风霜，保留有极具近现代特色风貌的历史建筑群与优雅校园园林。\n" +
                   "【参访漫步建议】推荐漫步品读经典红砖灰瓦老建筑的建筑肌理，在林荫大道与求知长廊中感受静谧求索的学术书香氛围。\n" +
                   "【参访礼仪与须知】校区为正常教学与科研办公场所，非喧闹景区；入校参访时请保持低调安静，遵守校园出入管理与安保秩序。";
        }

        // 8. General Cultural Exploration
        return "【城市微旅行探索】位于" + d + "的特色探索点位，生动呈现重庆独特的地形起伏肌理与本土人文生活画卷。\n" +
               "【游玩建议】推荐结合高德步行导航随心漫游，在走街串巷间捕捉立体山城的别样城市记忆与真实细节。\n" +
               "【核验提示】出行前建议留意最新现场开放指引，顺路结合周边街区综合打卡体验。";
    }

    private Map<String, Object> projection(AmapResponseNormalizer.PoiCandidate poi,
                                           AmapRouteService.PoiOutcome outcome) {
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
        int visitMins = RuntimeLocationPlanner.inferVisitMinutes(name, text(poi.type()), 180);
        result.put("duration", "约 " + visitMins + " 分钟");
        String ticketAdvice = (address.contains("大学") || address.contains("学院") || name.contains("大学") || name.contains("学院"))
                ? "以现场及校方公告为准（校区管理需遵守参访出入规则）"
                : "以现场及官方公告为准（市政公园与公共开放区通常免门票）";
        result.put("ticket", ticketAdvice);
        result.put("bestTime", "全天开放 · 建议日间或傍晚游览");
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

    private static final List<String> CHONGQING_DISTRICTS = List.of(
            "渝中区", "江北区", "南岸区", "沙坪坝区", "九龙坡区", "大渡口区", "渝北区", "巴南区", "北碚区",
            "江津区", "合川区", "永川区", "南川区", "綦江区", "大足区", "璧山区", "铜梁区", "潼南区", "荣昌区",
            "万州区", "涪陵区", "黔江区", "长寿区", "开州区", "梁平区", "武隆区",
            "城口县", "丰都县", "垫江县", "忠县", "云阳县", "奉节县", "巫山县", "巫溪县", "石柱县", "秀山县", "酉阳县", "彭水县"
    );

    public String extractDistrict(String address) {
        if (address == null || address.isBlank()) return "";
        for (String d : CHONGQING_DISTRICTS) {
            if (address.contains(d) || address.contains(d.substring(0, d.length() - 1))) return d;
        }
        if (address.contains("白沙") || address.contains("四面山") || address.contains("聂荣臻") || address.contains("中山古镇") || address.contains("几江")) return "江津区";
        if (address.contains("南山") || address.contains("黄桷垭") || address.contains("弹子石") || address.contains("南坪") || address.contains("龙门浩")) return "南岸区";
        if (address.contains("解放碑") || address.contains("朝天门") || address.contains("大坪") || address.contains("两路口") || address.contains("洪崖洞") || address.contains("十八梯") || address.contains("李子坝")) return "渝中区";
        if (address.contains("观音桥") || address.contains("九街") || address.contains("大石坝") || address.contains("江北嘴") || address.contains("大剧院")) return "江北区";
        if (address.contains("磁器口") || address.contains("大学城") || address.contains("歌乐山")) return "沙坪坝区";
        if (address.contains("杨家坪") || address.contains("谢家湾") || address.contains("黄桷坪") || address.contains("动物园")) return "九龙坡区";
        if (address.contains("照母山") || address.contains("礼嘉") || address.contains("中央公园") || address.contains("仙桃")) return "渝北区";
        if (address.contains("鱼洞") || address.contains("龙洲湾")) return "巴南区";
        if (address.contains("缙云山") || address.contains("自然博物馆")) return "北碚区";
        return "";
    }

    private List<Map<String, Object>> facts(AmapResponseNormalizer.PoiCandidate poi) {
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

    private String coordinate(AmapResponseNormalizer.Coordinate coordinate) {
        return coordinate == null ? "" : coordinate.longitude() + "," + coordinate.latitude();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}

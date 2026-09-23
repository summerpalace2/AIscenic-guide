package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 景点别名与口语化意图动态匹配器
 *
 * 架构职责：
 * 消除原本写死在 ConversationIntentClassifier 和 ItineraryBuilder 中的静态 Map 与正则表。
 * 系统启动时从数据库（或 attractions.json 资源）动态加载景点及其别名（包含 matches、displayName、tags 等），
 * 维护内存高效的最长前缀匹配索引。未来在库中增减景点，代码零变更。
 */
@Slf4j
@Component
public class AttractionAliasMatcher {

    private final Map<String, String> aliasToVenueId = new ConcurrentHashMap<>();
    private final Map<String, String> venueIdToCanonical = new ConcurrentHashMap<>();
    private final List<String> sortedAliases = new ArrayList<>();

    @Autowired
    public AttractionAliasMatcher(@Autowired(required = false) AttractionService attractionService) {
        init(attractionService);
    }

    public AttractionAliasMatcher() {
        this(null);
    }

    public synchronized void init(AttractionService attractionService) {
        aliasToVenueId.clear();
        venueIdToCanonical.clear();

        List<Attraction> list = null;
        if (attractionService != null) {
            try {
                list = attractionService.list(null, null);
            } catch (Exception ignored) {}
        }
        if (list == null || list.isEmpty()) {
            list = loadFromResource();
        }

        for (Attraction a : list) {
            registerAttraction(a);
        }

        registerColloquialAliases();

        sortedAliases.clear();
        sortedAliases.addAll(aliasToVenueId.keySet());
        // 最长前缀优先匹配，避免子串先被截断
        sortedAliases.sort((s1, s2) -> Integer.compare(s2.length(), s1.length()));
        log.info("[AttractionAliasMatcher] 动态构建景点别名索引，共收录 {} 条别名映射", sortedAliases.size());
    }

    private void registerAttraction(Attraction a) {
        if (a == null || a.getId() == null) return;
        String id = a.getId();
        String name = a.getName();
        String displayName = a.getDisplayName();

        if (displayName != null && !displayName.isBlank()) {
            venueIdToCanonical.putIfAbsent(id, displayName);
            aliasToVenueId.put(displayName, id);
        } else if (name != null && !name.isBlank()) {
            venueIdToCanonical.putIfAbsent(id, name);
        }
        if (name != null && !name.isBlank()) {
            aliasToVenueId.put(name, id);
        }

        if (a.getAmapQuery() != null) {
            Object matches = a.getAmapQuery().get("matches");
            if (matches instanceof Iterable<?> it) {
                for (Object m : it) {
                    if (m != null && !String.valueOf(m).isBlank()) {
                        aliasToVenueId.put(String.valueOf(m).trim(), id);
                    }
                }
            }
            Object kw = a.getAmapQuery().get("keywords");
            if (kw != null && !String.valueOf(kw).isBlank()) {
                aliasToVenueId.put(String.valueOf(kw).trim(), id);
            }
            Object alts = a.getAmapQuery().get("alternatives");
            if (alts instanceof Iterable<?> it) {
                for (Object alt : it) {
                    if (alt != null && !String.valueOf(alt).isBlank()) {
                        aliasToVenueId.put(String.valueOf(alt).trim(), id);
                    }
                }
            }
        }
    }

    /** 注册老重庆常用口语化缩写（与数据库景点 ID 对应） */
    private void registerColloquialAliases() {
        safePut("穿楼", "cq-liziba");
        safePut("单轨穿楼", "cq-liziba");
        safePut("李子坝", "cq-liziba");
        safePut("二厂", "cq-erling");
        safePut("贰厂", "cq-erling");
        safePut("鹅岭", "cq-erling");
        safePut("一棵树", "cq-nanshan-yikeshu");
        safePut("索道", "cq-changjiang-cable");
        safePut("天坑", "cq-wulong-tiankeng");
        safePut("天生三桥", "cq-wulong-tiankeng");
        safePut("武隆天坑", "cq-wulong-tiankeng");
        safePut("武隆", "cq-wulong-tiankeng");
        safePut("温泉", "cq-ronghui-hotspring");
        safePut("泡汤", "cq-ronghui-hotspring");
        safePut("水疗", "cq-ronghui-hotspring");
        safePut("宝顶山", "cq-dazu-rock");
        safePut("大足", "cq-dazu-rock");
        safePut("水下博物馆", "cq-baiheliang");
        safePut("白鹤梁", "cq-baiheliang");
        safePut("博物馆", "cq-museum");
        safePut("三峡博物馆", "cq-museum");
        venueIdToCanonical.put("cq-museum", "三峡博物馆");
        venueIdToCanonical.put("cq-grand-theatre", "重庆大剧院");
        venueIdToCanonical.put("cq-erling", "鹅岭二厂");
        venueIdToCanonical.put("cq-changjiang-cable", "长江索道");
        venueIdToCanonical.put("cq-nanshan-yikeshu", "南山一棵树");
        venueIdToCanonical.put("cq-dazu-rock", "大足石刻");
        venueIdToCanonical.put("cq-wulong-tiankeng", "天生三桥");
        venueIdToCanonical.put("cq-ronghui-hotspring", "融汇温泉");
        venueIdToCanonical.put("cq-south-hotspring", "南温泉");
        venueIdToCanonical.put("cq-geleyuan", "歌乐山");
        venueIdToCanonical.put("cq-danzi-shi", "弹子石老街");
        venueIdToCanonical.put("cq-jiefangbei", "解放碑");
        venueIdToCanonical.put("cq-hongyadong", "洪崖洞");
        venueIdToCanonical.put("cq-liziba", "李子坝");
        venueIdToCanonical.put("cq-ciqikou", "磁器口");
        venueIdToCanonical.put("cq-shibati", "十八梯");
        venueIdToCanonical.put("cq-kuixinglou", "魁星楼");
        venueIdToCanonical.put("cq-luohan-temple", "罗汉寺");
        venueIdToCanonical.put("cq-huguang-guild", "湖广会馆");
        venueIdToCanonical.put("cq-zhongshan-road", "中山四路");
        venueIdToCanonical.put("cq-maanshan", "马鞍山");
        venueIdToCanonical.put("cq-beicang", "北仓");
        venueIdToCanonical.put("cq-jinfoshan", "金佛山");
        venueIdToCanonical.put("cq-chaotianmen", "朝天门");
        venueIdToCanonical.put("cq-chongqing-zoo", "重庆动物园");
        venueIdToCanonical.put("cq-longmenhao", "龙门浩老街");
        safePut("大剧院", "cq-grand-theatre");
        safePut("科技馆", "cq-kejiguan");
        safePut("十八梯", "cq-shibati");
        safePut("磁器口", "cq-ciqikou");
        safePut("解放碑", "cq-jiefangbei");
        safePut("洪崖洞", "cq-hongyadong");
        safePut("大礼堂", "cq-renmin-dalitang");
        safePut("人民大礼堂", "cq-renmin-dalitang");
        safePut("来福士", "cq-rff-square");
        safePut("探索舱", "cq-rff-square");
        safePut("朝天门", "cq-chaotianmen");
        safePut("观音桥", "cq-guanyinqiao");
        safePut("九街", "cq-guanyinqiao");
        safePut("北仓", "cq-beicang");
        safePut("马鞍山", "cq-maanshan");
        safePut("渣滓洞", "cq-geleyuan");
        safePut("白公馆", "cq-geleyuan");
        safePut("红岩村", "cq-hongyan-memorial");
        safePut("红岩纪念馆", "cq-hongyan-memorial");
        safePut("白沙", "cq-jiangjin-baisha");
        safePut("四面山", "cq-jiangjin-simianshan");
        safePut("聂荣臻纪念馆", "cq-jiangjin-nie-memorial");
        safePut("聂帅陈列馆", "cq-jiangjin-nie-memorial");
        safePut("几江半岛", "cq-jiangjin-binjiang-night");
        safePut("几江夜景", "cq-jiangjin-binjiang-night");
    }

    private void safePut(String alias, String venueId) {
        aliasToVenueId.put(alias, venueId);
    }

    public String extractMentionedVenueId(String text) {
        if (text == null || text.isBlank()) return null;
        for (String alias : sortedAliases) {
            if (text.contains(alias)) {
                return aliasToVenueId.get(alias);
            }
        }
        return null;
    }

    public String extractMentionedVenueAlias(String text) {
        if (text == null || text.isBlank()) return null;
        for (String alias : sortedAliases) {
            if (text.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
                return venueIdToCanonical.getOrDefault(venueId, alias);
            }
        }
        return null;
    }

    public boolean containsAnotherVenue(String text, String selectedStopId) {
        if (text == null || text.isBlank()) return false;
        String stop = selectedStopId == null ? "" : selectedStopId.toLowerCase(Locale.ROOT)
                .replace("cq-", "").replace("day1-", "").replace("day2-", "").replace("day3-", "").trim();

        for (String alias : sortedAliases) {
            if (text.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
                String normalizedVenue = venueId != null ? venueId.toLowerCase(Locale.ROOT).replace("cq-", "") : "";
                boolean isCurrent = (selectedStopId != null && selectedStopId.contains(alias))
                        || alias.contains(selectedStopId != null ? selectedStopId : "")
                        || (!stop.isBlank() && (stop.contains(normalizedVenue) || normalizedVenue.contains(stop) || stop.contains(alias)));
                if (!isCurrent) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> findExplicitAttractionIds(String prompt, Set<String> validIds) {
        if (prompt == null || prompt.isBlank()) return Collections.emptyList();
        Set<String> matched = new LinkedHashSet<>();
        for (String alias : sortedAliases) {
            if (prompt.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
                if (venueId != null && (validIds == null || validIds.contains(venueId))) {
                    matched.add(venueId);
                }
            }
        }
        return new ArrayList<>(matched);
    }

    public Map<String, String> getAliasToVenueIdMap() {
        return Collections.unmodifiableMap(aliasToVenueId);
    }

    public Map<String, String> getVenueIdToCanonicalMap() {
        return Collections.unmodifiableMap(venueIdToCanonical);
    }

    private List<Attraction> loadFromResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("attractions.json")) {
            if (is == null) return Collections.emptyList();
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(is, new TypeReference<List<Attraction>>() {});
        } catch (Exception e) {
            log.warn("[AttractionAliasMatcher] 读取 attractions.json 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

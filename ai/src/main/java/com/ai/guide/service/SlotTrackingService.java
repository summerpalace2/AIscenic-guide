package com.ai.guide.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户槽位追踪 Service
 * 多账户改造：所有 key 均带 userId 前缀，实现用户隔离
 *
 * 所有槽位均从用户消息中提取，存储策略不同：
 * - interest：滑动窗口 3 条（兴趣可多样，保留多个）
 * - duration / crowd / area：单值覆盖（只保留最新一次）
 */
@Service
public class SlotTrackingService {

    private static final Logger log = LoggerFactory.getLogger(SlotTrackingService.class);

    private static final String SLOTS_KEY_SUFFIX = ":slots";
    private static final String PREFS_KEY_SUFFIX = ":preferences";
    /** interest 槽位滑动窗口上限 */
    private static final int MAX_INTEREST_ITEMS = 3;

    /** 槽位匹配正则：字段名 → {预编译正则 → 标准值} */
    private static final Map<String, Map<Pattern, String>> SLOT_PATTERNS;

    /** 区域名称正则 */
    private static final Pattern AREA_PATTERN =
            Pattern.compile("灵山大佛|梵宫|五印坛城|拈花湾|祥符禅寺|曼飞龙塔|降魔堂|佛手广场|胜境广场|香水海|南门|九龙灌浴");

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        SLOT_PATTERNS = new HashMap<>();

        // 兴趣偏好 → 滑动窗口 3 条（预编译正则）
        Map<Pattern, String> interest = new LinkedHashMap<>();
        interest.put(Pattern.compile("景点|景区|风景|玩的|游览|观赏|观光"), "景点");
        interest.put(Pattern.compile("美食|好吃的|餐厅|吃饭|小吃|素斋|素食"), "美食");
        interest.put(Pattern.compile("文化|历史|故事|渊源|来历|传统|佛教|禅"), "文化");
        interest.put(Pattern.compile("路线|行程|规划|怎么走|游玩顺序|游览路线|推荐路线"), "路线");
        interest.put(Pattern.compile("住宿|酒店|住哪|房间|客栈|民宿"), "住宿");
        SLOT_PATTERNS.put("interest", interest);

        // 游玩时长 → 单值覆盖（预编译正则）
        Map<Pattern, String> duration = new LinkedHashMap<>();
        duration.put(Pattern.compile("半天|上午|下午|半天时间"), "半天");
        duration.put(Pattern.compile("全天|一天|一日|整天|一整天"), "全天");
        duration.put(Pattern.compile("2小时|两小时|2个小时|两个小时"), "2小时");
        duration.put(Pattern.compile("3小时|三小时|3个小时|三个小时"), "3小时");
        duration.put(Pattern.compile("4小时|四小时|4个小时|四个小时"), "4小时");
        duration.put(Pattern.compile("6小时|六小时|6个小时|六个小时"), "6小时");
        SLOT_PATTERNS.put("duration", duration);

        // 游客人群 → 单值覆盖（预编译正则）
        Map<Pattern, String> crowd = new LinkedHashMap<>();
        crowd.put(Pattern.compile("老人|长辈|父母|老年|年长|爸妈|爸妈一起|带着父母"), "老人");
        crowd.put(Pattern.compile("小孩|孩子|儿童|亲子|带娃|宝贝|小朋友"), "小孩");
        crowd.put(Pattern.compile("情侣|对象|男朋友|女朋友|约会|两人|浪漫"), "情侣");
        crowd.put(Pattern.compile("独自|一个人|自己|独行|单独"), "独自");
        crowd.put(Pattern.compile("家庭|一家|全家|一家人|阖家"), "家庭");
        crowd.put(Pattern.compile("朋友|闺蜜|哥们|兄弟|姐妹们|几个朋友"), "朋友");
        SLOT_PATTERNS.put("crowd", crowd);
    }

    public SlotTrackingService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== 核心：每条消息都触发提取 ====================

    /**
     * 从用户消息中提取所有槽位
     * - interest：滑动窗口 3 条（兴趣可多样）
     * - duration / crowd / area：单值覆盖（只保留最新）
     */
    public void extractAndSave(String userId, String message) {
        if (message == null || message.isBlank()) return;

        String key = slotsKey(userId);

        // interest → 滑动窗口 3 条（直接使用预编译正则）
        for (Map.Entry<Pattern, String> entry : SLOT_PATTERNS.get("interest").entrySet()) {
            if (entry.getKey().matcher(message).find()) {
                pushSlotItemLimited(key, "interest", entry.getValue(), MAX_INTEREST_ITEMS);
            }
        }

        // duration → 单值覆盖
        String durationMatch = matchSlot(message, SLOT_PATTERNS.get("duration"));
        if (durationMatch != null) {
            redisTemplate.opsForHash().put(key, "duration", durationMatch);
        }

        // crowd → 单值覆盖
        String crowdMatch = matchSlot(message, SLOT_PATTERNS.get("crowd"));
        if (crowdMatch != null) {
            redisTemplate.opsForHash().put(key, "crowd", crowdMatch);
        }

        // area → 单值覆盖（只保留最新提到的区域）
        Matcher matcher = AREA_PATTERN.matcher(message);
        String lastArea = null;
        while (matcher.find()) {
            lastArea = matcher.group();
        }
        if (lastArea != null) {
            redisTemplate.opsForHash().put(key, "area", lastArea);
        }
    }

    /**
     * 向指定槽位追加一条值（带滑动窗口限制，仅 interest）
     */
    private void pushSlotItemLimited(String redisKey, String slotName, String value, int maxItems) {
        try {
            String raw = (String) redisTemplate.opsForHash().get(redisKey, slotName);
            List<String> items = parseList(raw);
            if (items.contains(value)) return;
            items.add(value);
            if (items.size() > maxItems) {
                items.remove(0);
            }
            redisTemplate.opsForHash().put(redisKey, slotName, objectMapper.writeValueAsString(items));
        } catch (Exception e) {
            redisTemplate.opsForHash().put(redisKey, slotName, value);
        }
    }

    // ==================== 读取 ====================

    /**
     * 获取所有槽位（Map<槽位名, List<值>>）
     * interest 返回 List（滑动窗口），其余返回单元素 List
     */
    public Map<String, List<String>> getSlots(String userId) {
        String key = slotsKey(userId);
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        Map<String, List<String>> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), parseList(v.toString())));
        return result;
    }

    /**
     * 获取格式化后的偏好提示词（供 LLM 使用）
     * 多账户：基于 userId 读取槽位 + 手动偏好
     */
    public String toPromptContext(String userId) {
        Map<String, List<String>> slots = getSlots(userId);
        Map<String, String> manualPrefs = getManualPreferences(userId);

        // 过滤内部标志字段
        manualPrefs.remove("prefs_initialized");

        // 合并：手动偏好覆盖自动槽位
        Map<String, String> merged = new HashMap<>();
        slots.forEach((k, v) -> merged.put(k, String.join("、", v)));
        merged.putAll(manualPrefs);

        if (merged.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("【用户画像】\n");
        merged.forEach((k, v) -> {
            String label = switch (k) {
                case "interest" -> "兴趣偏好";
                case "duration" -> "游玩时长";
                case "crowd" -> "游客人群";
                case "area" -> "关注区域";
                default -> k;
            };
            sb.append(label).append("：").append(v).append("\n");
        });
        return sb.toString().trim();
    }

    // ==================== 管理接口 ====================

    public void clearSlots(String userId) {
        redisTemplate.delete(slotsKey(userId));
    }

    /**
     * 获取用户手动设置偏好（多账户：按 userId 隔离）
     */
    public Map<String, String> getManualPreferences(String userId) {
        String key = prefsKey(userId);
        Map<Object, Object> prefs = redisTemplate.opsForHash().entries(key);
        Map<String, String> result = new HashMap<>();
        prefs.forEach((k, v) -> result.put(k.toString(), v.toString()));
        log.info("[PREF] getManualPreferences userId=" + key + ", raw=" + result);
        return result;
    }

    /**
     * 保存用户手动偏好（多账户：按 userId 隔离）
     */
    public void saveManualPreferences(String userId, Map<String, String> preferences) {
        String key = prefsKey(userId);
        redisTemplate.opsForHash().putAll(key, preferences);
        redisTemplate.opsForHash().put(key, "prefs_initialized", "1");
        log.info("[PREF] saveManualPreferences userId=" + key + " count=" + preferences.size() + " data=" + preferences);
    }

    /**
     * 清除用户手动偏好（多账户：按 userId 隔离）
     */
    public void clearManualPreferences(String userId) {
        String key = prefsKey(userId);
        redisTemplate.delete(key);
        // 保留 flag 防止默认值死灰复燃
        redisTemplate.opsForHash().put(key, "prefs_initialized", "1");
        log.info("[PREF] clearManualPreferences userId=" + key + " (flag preserved)");
    }

    // ==================== 私有工具 ====================

    private String slotsKey(String userId) {
        return "user:" + userId + SLOTS_KEY_SUFFIX;
    }

    private String prefsKey(String userId) {
        return "user:" + userId + PREFS_KEY_SUFFIX;
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 降级：duration/crowd/area 是纯字符串
            List<String> single = new ArrayList<>();
            single.add(json);
            return single;
        }
    }

    private String matchSlot(String message, Map<Pattern, String> patterns) {
        if (patterns == null) return null;
        for (Map.Entry<Pattern, String> entry : patterns.entrySet()) {
            if (entry.getKey().matcher(message).find()) {
                return entry.getValue();
            }
        }
        return null;
    }
}

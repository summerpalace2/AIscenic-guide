package com.ai.guide.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户槽位追踪 Service
 *
 * 所有槽位均从用户消息中提取，存储策略不同：
 * - interest：滑动窗口 3 条（兴趣可多样，保留多个）
 * - duration / crowd / area：单值覆盖（只保留最新一次）
 */
@Service
public class SlotTrackingService {

    private static final String SLOTS_KEY_SUFFIX = ":slots";
    private static final String USER_PREFS_KEY = "user:preferences";
    /** interest 槽位滑动窗口上限 */
    private static final int MAX_INTEREST_ITEMS = 3;

    /** 槽位匹配正则：字段名 → {正则 → 标准值} */
    private static final Map<String, Map<String, String>> SLOT_PATTERNS;

    /** 区域名称正则 */
    private static final Pattern AREA_PATTERN =
            Pattern.compile("灵山大佛|梵宫|五印坛城|拈花湾|祥符禅寺|曼飞龙塔|降魔堂|佛手广场|胜境广场|香水海|南门|九龙灌浴");

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    static {
        SLOT_PATTERNS = new HashMap<>();

        // 兴趣偏好 → 滑动窗口 3 条
        Map<String, String> interest = new LinkedHashMap<>();
        interest.put("景点|景区|风景|玩的|游览|观赏|观光", "景点");
        interest.put("美食|好吃的|餐厅|吃饭|小吃|素斋|素食", "美食");
        interest.put("文化|历史|故事|渊源|来历|传统|佛教|禅", "文化");
        interest.put("路线|行程|规划|怎么走|游玩顺序|游览路线|推荐路线", "路线");
        interest.put("住宿|酒店|住哪|房间|客栈|民宿", "住宿");
        SLOT_PATTERNS.put("interest", interest);

        // 游玩时长 → 单值覆盖
        Map<String, String> duration = new LinkedHashMap<>();
        duration.put("半天|上午|下午|半天时间", "半天");
        duration.put("全天|一天|一日|整天|一整天", "全天");
        duration.put("2小时|两小时|2个小时|两个小时", "2小时");
        duration.put("3小时|三小时|3个小时|三个小时", "3小时");
        duration.put("4小时|四小时|4个小时|四个小时", "4小时");
        duration.put("6小时|六小时|6个小时|六个小时", "6小时");
        SLOT_PATTERNS.put("duration", duration);

        // 游客人群 → 单值覆盖
        Map<String, String> crowd = new LinkedHashMap<>();
        crowd.put("老人|长辈|父母|老年|年长|爸妈|爸妈一起|带着父母", "老人");
        crowd.put("小孩|孩子|儿童|亲子|带娃|宝贝|小朋友", "小孩");
        crowd.put("情侣|对象|男朋友|女朋友|约会|两人|浪漫", "情侣");
        crowd.put("独自|一个人|自己|独行|单独", "独自");
        crowd.put("家庭|一家|全家|一家人|阖家", "家庭");
        crowd.put("朋友|闺蜜|哥们|兄弟|姐妹们|几个朋友", "朋友");
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

        // interest → 滑动窗口 3 条
        for (Map.Entry<String, String> entry : SLOT_PATTERNS.get("interest").entrySet()) {
            if (Pattern.compile(entry.getKey()).matcher(message).find()) {
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
     */
    public String toPromptContext(String userId) {
        Map<String, List<String>> slots = getSlots(userId);
        if (slots.isEmpty()) {
            Map<String, String> fallback = getManualPreferences();
        fallback.remove("prefs_initialized");
            if (fallback.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("【用户画像】\n");
            fallback.forEach((k, v) -> sb.append(k).append("：").append(v).append("\n"));
            return sb.toString().trim();
        }
        return buildSlotContextString(slots);
    }

    /**
     * 构建槽位上下文（内部方法）
     */
    public String buildSlotContextString(Map<String, List<String>> slots) {
        StringBuilder sb = new StringBuilder("【用户画像】\n");
        slots.forEach((k, v) -> {
            String label = switch (k) {
                case "interest" -> "兴趣偏好";
                case "duration" -> "游玩时长";
                case "crowd" -> "游客人群";
                case "area" -> "关注区域";
                default -> k;
            };
            sb.append(label).append("：").append(String.join("、", v)).append("\n");
        });
        return sb.toString().trim();
    }

    // ==================== 管理接口 ====================

    public void clearSlots(String userId) {
        redisTemplate.delete(slotsKey(userId));
    }

    // ==================== 私有工具 ====================

    private String slotsKey(String userId) {
        return "user:" + userId + SLOTS_KEY_SUFFIX;
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

    private Map<String, String> getManualPreferences() {
        Map<Object, Object> prefs = redisTemplate.opsForHash().entries(USER_PREFS_KEY);
        Map<String, String> result = new HashMap<>();
        prefs.forEach((k, v) -> result.put(k.toString(), v.toString()));
        System.out.println("[PREF] getManualPreferences: key=" + USER_PREFS_KEY + ", raw=" + result);
        // Only fill defaults on first access (no flag yet)
        if (result.isEmpty() && !prefs.containsKey("prefs_initialized")) {
            result.put("prefs_initialized", "1");
            result.put("interest", "景点");
            result.put("duration", "全天");
            result.put("crowd", "家庭");
            redisTemplate.opsForHash().putAll(USER_PREFS_KEY, result);
            System.out.println("[PREF] first access, filled defaults: " + result);
        } else if (result.isEmpty()) {
            System.out.println("[PREF] cleared (flag=1), no defaults");
        } else {
            System.out.println("[PREF] existing prefs: " + result);
        }
        return result;
    }

    private String matchSlot(String message, Map<String, String> patterns) {
        if (patterns == null) return null;
        for (Map.Entry<String, String> entry : patterns.entrySet()) {
            if (Pattern.compile(entry.getKey()).matcher(message).find()) {
                return entry.getValue();
            }
        }
        return null;
    }
}
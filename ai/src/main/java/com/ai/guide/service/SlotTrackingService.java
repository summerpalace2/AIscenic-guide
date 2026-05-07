package com.ai.guide.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 槽位追踪服务 —— 从对话中提取用户意图参数，存储到 Redis
 * <p>
 * 槽位定义：
 * <pre>
 * interest : 景点 / 美食 / 文化 / 路线 / 住宿
 * duration : 半天 / 全天 / 2小时 / 3小时 / ...
 * crowd    : 老人 / 小孩 / 情侣 / 独自 / 家庭 / 朋友
 * area     : 灵山大佛 / 梵宫 / 五印坛城 / 拈花湾 / ...
 * budget   : 经济 / 中等 / 不限
 * </pre>
 * <p>
 * Redis Key: chat:session:{id}:slots （Hash 结构）
 */
@Service
public class SlotTrackingService {

    private static final String SLOTS_KEY_SUFFIX = ":slots";

    // 槽位提取规则 —— 关键词 → 槽值映射
    private static final Map<String, Map<String, String>> SLOT_PATTERNS = Map.of(
        "interest", Map.of(
            "景点|景区|风景|玩的|游览|观赏|观光", "景点",
            "美食|好吃的|餐厅|吃饭|小吃|素斋|素食", "美食",
            "文化|历史|故事|渊源|来历|传统|佛教|禅", "文化",
            "路线|行程|规划|怎么走|游玩顺序|游览路线|推荐路线", "路线",
            "住宿|酒店|住哪|房间|客栈|民宿", "住宿"
        ),
        "duration", Map.of(
            "半天|上午|下午|半天时间", "半天",
            "全天|一天|一日|整天|一整天", "全天",
            "2小时|两小时|2个小时|两个小时", "2小时",
            "3小时|三小时|3个小时|三个小时", "3小时",
            "4小时|四小时|4个小时|四个小时", "4小时",
            "6小时|六小时|6个小时|六个小时", "6小时"
        ),
        "crowd", Map.of(
            "老人|长辈|父母|老年|年长|爸妈|爸妈一起|带着父母", "老人",
            "小孩|孩子|儿童|亲子|带娃|宝贝|小朋友", "小孩",
            "情侣|对象|男朋友|女朋友|约会|两人|浪漫", "情侣",
            "独自|一个人|自己|独行|单独", "独自",
            "家庭|一家|全家|一家人|阖家", "家庭",
            "朋友|闺蜜|哥们|兄弟|姐妹们|几个朋友", "朋友"
        )
    );

    // 景区区域名称匹配
    private static final Pattern AREA_PATTERN = Pattern.compile(
        "灵山大佛|梵宫|五印坛城|拈花湾|祥符禅寺|曼飞龙塔|降魔堂|佛手广场|胜境广场|香水海|南门|九龙灌浴"
    );

    private final RedisTemplate<String, String> redisTemplate;

    public SlotTrackingService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private String slotsKey(String sessionId) {
        return "chat:session:" + sessionId + SLOTS_KEY_SUFFIX;
    }

    /**
     * 从用户消息中提取槽位并存储到 Redis
     */
    public void extractAndSave(String sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return;
        String key = slotsKey(sessionId);

        // 兴趣偏好
        String interest = matchSlot(userMessage, SLOT_PATTERNS.get("interest"));
        if (interest != null) redisTemplate.opsForHash().put(key, "interest", interest);

        // 时间
        String duration = matchSlot(userMessage, SLOT_PATTERNS.get("duration"));
        if (duration != null) redisTemplate.opsForHash().put(key, "duration", duration);

        // 人群
        String crowd = matchSlot(userMessage, SLOT_PATTERNS.get("crowd"));
        if (crowd != null) redisTemplate.opsForHash().put(key, "crowd", crowd);

        // 区域
        Matcher areaMatcher = AREA_PATTERN.matcher(userMessage);
        if (areaMatcher.find()) {
            redisTemplate.opsForHash().put(key, "area", areaMatcher.group());
        }
    }

    /**
     * 获取当前会话的完整槽位快照
     */
    public Map<String, String> getSlots(String sessionId) {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(slotsKey(sessionId));
            Map<String, String> slots = new LinkedHashMap<>();
            entries.forEach((k, v) -> slots.put(k.toString(), v.toString()));
            return slots;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 删除会话的槽位数据
     */
    public void clearSlots(String sessionId) {
        try {
            redisTemplate.delete(slotsKey(sessionId));
        } catch (Exception ignored) {}
    }

    /**
     * 将槽位快照 + 用户手动设置的偏好合并，转为 AI 可读的上下文片段
     * <p>
     * 优先级：手动偏好 > 自动提取槽位
     */
    public String toPromptContext(String sessionId) {
        // 合并手动偏好 + 自动槽位（手动优先）
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(getSlots(sessionId));               // 先放自动提取的
        merged.putAll(getManualPreferences());            // 手动设置覆盖自动

        if (merged.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n# 已知用户偏好\n");
        merged.forEach((key, value) -> {
            String label = switch (key) {
                case "interest" -> "兴趣偏好";
                case "duration" -> "可用时间";
                case "crowd"   -> "同行人群";
                case "area"    -> "关注区域";
                case "budget"  -> "预算范围";
                default -> key;
            };
            sb.append("- **").append(label).append("**：").append(value).append("\n");
        });

        // 缺失槽位提醒
        List<String> missing = new ArrayList<>();
        if (!merged.containsKey("interest")) missing.add("兴趣偏好");
        if (!merged.containsKey("duration")) missing.add("可用时间");
        if (!merged.containsKey("crowd"))   missing.add("同行人群");

        if (!missing.isEmpty()) {
            sb.append("\n⚠ 以下信息尚未收集，推荐相关问题时请主动追问：")
              .append(String.join("、", missing)).append("\n");
        }

        return sb.toString();
    }

    // ========== 手动偏好（用户前端弹窗选择，存 Redis 全局键）==========
    // 当前用固定键，未来接入登录后改为 user:{userId}:prefs

    private static final String USER_PREFS_KEY = "user:preferences";

    private Map<String, String> getManualPreferences() {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(USER_PREFS_KEY);
            Map<String, String> prefs = new LinkedHashMap<>();
            entries.forEach((k, v) -> prefs.put(k.toString(), v.toString()));
            return prefs;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ========== 内部方法 ==========

    /** 按正则关键词匹配槽值，返回首个命中 */
    private String matchSlot(String text, Map<String, String> patterns) {
        for (Map.Entry<String, String> entry : patterns.entrySet()) {
            if (Pattern.compile(entry.getKey()).matcher(text).find()) {
                return entry.getValue();
            }
        }
        return null;
    }
}

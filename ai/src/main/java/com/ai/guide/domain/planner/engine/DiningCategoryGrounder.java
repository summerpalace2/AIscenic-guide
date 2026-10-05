package com.ai.guide.domain.planner.engine;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 餐饮泛需求与商户实体动态核验网关（彻底去硬编码）
 *
 * 核心架构哲学（Inverted Default Assumption）：
 * 1. 废除“手写40个食物词汇正则黑名单”的打地鼠反模式；
 * 2. 默认策略倒置：用户在聊天中提及的纯名词短语，100% 默认作为“风味/品类泛需求（Generic Craving）”；
 * 3. 只有具备明确的商户实体特征（分店后缀/商户机构词尾/知名品牌/书名号包裹），才判定为指定门店。
 */
@Component
public class DiningCategoryGrounder {

    /**
     * 知名全国连锁纯餐饮品牌（不含普通食品名称，避免误判）
     */
    private static final Set<String> KNOWN_DINING_BRANDS = Set.of(
            "肯德基", "麦当劳", "汉堡王", "必胜客", "赛百味", "德克士", "华莱士",
            "外婆家", "绿茶餐厅", "海底捞", "西贝莜面村", "眉州东坡", "避风塘",
            "星巴克", "瑞幸咖啡", "喜茶", "奈雪的茶", "茶颜悦色", "霸王茶姬", "一点点", "蜜雪冰城"
    );

    /**
     * 判断某个名称是风味/品类泛需求（true），还是具体明确的商户实体或非餐饮地点（false）
     */
    public boolean isGenericDiningCraving(String name) {
        if (name == null || name.isBlank()) return true;
        String trimmed = name.trim().replaceAll("^[【\\[“\"']|[】\\]”\"']$", "").trim();
        if (trimmed.isBlank()) return true;

        // 1. 排除非餐饮实体（如博物馆、纪念馆、科技馆、大学、电视塔、大剧院、景区、古镇等）
        if (isNonDiningPlace(trimmed)) {
            return false;
        }

        // 2. 检查是否具备餐饮/美食/茶饮相关语义；若完全不含餐饮字样，绝不能作为餐饮泛需求
        if (!hasDiningKeywords(trimmed)) {
            return false;
        }

        // 3. 明确分店标识（如 "较场口店", "(总店)", "黄桷小区店", "时代天街分店"）-> 具体门店
        boolean hasBranchSuffix = trimmed.matches(".*[（(][^）)]*(?:店|分店|总店)[）)]$")
                || trimmed.matches(".+(?:分店|总店|旗舰店)$");
        if (hasBranchSuffix) {
            return false;
        }

        // 4. 知名纯品牌商户（如 "肯德基"、"麦当劳"、"外婆家"）-> 具体门店
        if (KNOWN_DINING_BRANDS.contains(trimmed)) {
            return false;
        }

        // 5. 常见泛化/模糊修饰短语（如 "附近"、"随便"、"推荐"、"吃点"）-> 泛需求
        if (trimmed.matches(".*(附近|周边|同片区|其他|别的|或者|还是|随便|推荐|老字号|中餐|正餐|美食|快餐|吃点|来点|来个|吃个|想吃|想喝|去吃|品尝).*")) {
            return true;
        }
        if (trimmed.contains("或")) {
            return true;
        }

        // 6. 以“的”结尾的修饰动宾短语（如 "点甜食的"、"吃甜的"、"吃辣的"、"清淡的"）-> 泛需求
        if (trimmed.endsWith("的")) {
            return true;
        }

        // 7. 动宾短语（如 "吃火锅"、"喝咖啡"、"来个汉堡"）-> 泛需求
        if (trimmed.matches("^(?:想|要|去|来|选)?(?:吃|喝|尝|品)(?:点|个|下)?.*")) {
            return true;
        }

        // 8. 明确的商户机构专有名词后缀（如 "宏盛餐馆"、"老茶舍"、"川菜大酒楼"），且字数大于等于4 -> 具体门店
        boolean hasEstablishmentSuffix = trimmed.matches(".{2,}(?:餐馆|酒楼|大酒楼|饭店|大饭店|茶舍|茶馆|茶楼|茶室|咖啡馆|咖啡厅|甜品店|糖水铺|糕点铺|大排档|火锅馆|面庄|面馆)$");
        if (hasEstablishmentSuffix) {
            return false;
        }

        // 9. 默认策略倒置（Default Fallback for Food Terms）：
        // 任何包含餐饮词汇、但无分店后缀、无机构名词修饰的单域名词（如 "汉堡"、"火锅"、"披萨"、"甜食"、"炸鸡"、"日料"、"小面"）
        // 一律视为品类/风味泛需求，坚决不凭空捏造商户！
        return true;
    }

    /**
     * 判断是否是明确的非餐饮地名/场馆
     */
    public boolean isNonDiningPlace(String text) {
        if (text == null || text.isBlank()) return false;
        if (text.matches(".*(博物馆|纪念馆|美术馆|图书馆|科技馆|展览馆|体育馆|文化馆|档案馆|天文馆|水族馆|大剧院|大剧场|剧院|剧场|广场|景区|公园|索道|码头|古镇|老街|步道|城门|故居|遗址|大学|学院|校区|教学楼|四教|一教|二教|三教|电视塔|观光塔).*")) {
            // 除非明确有餐饮修饰（例如“三峡博物馆附近吃火锅”）
            return !text.matches(".*(吃|喝|餐|饭|火锅|小吃|美食|茶|咖啡|甜品|糖水).*");
        }
        return false;
    }

    /**
     * 判断是否包含餐饮相关词根
     */
    public boolean hasDiningKeywords(String text) {
        if (text == null || text.isBlank()) return false;
        return text.matches(".*(餐|饭|吃|喝|尝|品|食|肉|菜|面|粉|串|锅|茶|咖啡|奶茶|甜|糖水|点心|糕|烘焙|烤|烧|排档|快餐|酒楼|料理|料|汉堡|披萨|炸鸡|小吃|美食|风味|老字号|海鲜|生鲜|宵夜|夜宵|早点|正餐|餐馆|面馆|饭馆|茶馆|火锅馆|菜馆).*");
    }

    /**
     * 根据上下文文本推断期望时段插槽（slotType: BREAKFAST / LUNCH / DINNER / TEA_BREAK / NIGHT_SNACK）
     */
    public String resolveSlotType(String rawCombined) {
        if (rawCombined == null) return "LUNCH";
        String lower = rawCombined.toLowerCase();
        if (lower.matches(".*(早|早餐|早点|早饭).*")) {
            return "BREAKFAST";
        } else if (lower.matches(".*(夜市|烧烤|烤肉|宵夜|夜宵).*")) {
            return "NIGHT_SNACK";
        } else if (lower.matches(".*(晚|晚餐|晚上|晚饭).*")) {
            return "DINNER";
        } else if (lower.matches(".*(茶|茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|老茶馆|咖啡|下午茶|茶歇|小憩|歇脚|甜|糖水|冰粉|凉糕|糍粑|麻花|点心|蛋糕|烘焙).*")) {
            return "TEA_BREAK";
        }
        return "LUNCH";
    }

    /**
     * 根据自然语言识别风味/菜系标签，用于推荐匹配
     */
    public String resolveRequestedFlavor(String rawCombined, String defaultFlavor) {
        if (rawCombined == null || rawCombined.isBlank()) {
            return defaultFlavor != null ? defaultFlavor : "特色美食";
        }
        if (rawCombined.matches(".*(火锅|九宫格|老火锅|串串).*")) {
            return "重庆火锅";
        } else if (rawCombined.matches(".*(小面|面馆|面条|豌杂|牛肉面|米线|米粉).*")) {
            return "重庆小面";
        } else if (rawCombined.matches(".*(甜|糖水|冰粉|凉糕|糍粑|麻花|点心|蛋糕|烘焙).*")) {
            return "特色甜品糖水";
        } else if (rawCombined.matches(".*(茶|茶馆|茶室|茶楼|盖碗茶|品茗|喝茶|老茶馆|咖啡|下午茶|茶歇|小憩|歇脚).*")) {
            return "特色老茶馆与盖碗茶歇";
        } else if (rawCombined.matches(".*(汉堡|披萨|炸鸡|薯条|快餐|西餐).*")) {
            return "西式快餐轻食";
        } else if (rawCombined.matches(".*(烧烤|烤肉|烤鱼|大排档).*")) {
            return "特色烧烤烤肉";
        } else if (rawCombined.matches(".*(清淡|不辣|汤锅|炖鸡|老鸭汤).*")) {
            return "清淡不辣";
        } else if (rawCombined.matches(".*(江湖菜|川菜|家常菜|中餐|辣子鸡|泉水鸡|毛血旺|酸菜鱼).*")) {
            return "地道江湖菜";
        }

        // 动态提取用户指定的外来菜系或食品名词（如 "日料"、"粤菜"、"螺蛳粉"）
        Matcher m = Pattern.compile("(?:想吃|去吃|吃点|吃个|来点|来个|改成|换成|替换为)\\s*([^\\s，,。！!？?；;]{2,6})").matcher(rawCombined);
        if (m.find()) {
            String extracted = m.group(1).trim();
            if (!isGenericDiningCraving(extracted)) {
                return extracted;
            }
            return "特色" + extracted;
        }

        return defaultFlavor != null ? defaultFlavor : "特色美食";
    }
}

package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.gateway.BrandDictionaryPort;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 餐饮结构化语义解析与兼容性提示
 *
 * 已验证的结构化意图优先；字符串方法仅用于无模型或历史调用方降级，
 * 不保证识别所有菜系、建筑与商户名称。
 */
@Component
public class DiningCategoryGrounder {

    private volatile BrandDictionaryPort brandDictionary;
    private volatile TaxonomyPort taxonomy;

    public DiningCategoryGrounder() {
        this(new TaxonomyProvider());
    }

    @Autowired(required = false)
    public DiningCategoryGrounder(BrandDictionaryPort brandDictionary) {
        this.brandDictionary = brandDictionary != null ? brandDictionary : new TaxonomyProvider();
        this.taxonomy = this.brandDictionary instanceof TaxonomyPort port ? port : new TaxonomyProvider();
    }

    /** Share the production policy source with manually constructed adjustment strategies. */
    public void setTaxonomy(TaxonomyPort taxonomy) {
        if (taxonomy == null) throw new IllegalArgumentException("taxonomy is required");
        this.taxonomy = taxonomy;
        this.brandDictionary = taxonomy;
    }

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
        if (brandDictionary.isKnownBrand(trimmed)) {
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

        // 8. 明确的商户机构专有名词后缀（如 "宏盛餐馆"、"老茶舍"、"川菜大酒楼"、"教工餐厅"），且字数大于等于4 -> 具体门店
        boolean hasEstablishmentSuffix = trimmed.matches(".{2,}(?:餐馆|餐厅|食堂|酒楼|大酒楼|饭店|大饭店|茶舍|茶馆|茶楼|茶室|咖啡馆|咖啡厅|甜品店|糖水铺|糕点铺|大排档|火锅馆|面庄|面馆|小吃店|饮品店|烤肉店|烤鱼店)$");
        if (hasEstablishmentSuffix) {
            return false;
        }

        // 9. 默认策略倒置（Default Fallback for Food Terms）：
        // 任何包含餐饮词汇、但无分店后缀、无机构名词修饰的单域名词（如 "汉堡"、"火锅"、"披萨"、"甜食"、"炸鸡"、"日料"、"小面"）
        // 一律视为品类/风味泛需求，坚决不凭空捏造商户！
        return true;
    }

    // These hints are a compatibility fallback when structured semantic extraction is unavailable.
    // They are deliberately not a claim of nationwide NER coverage.
    public boolean isNonDiningPlace(String text) {
        if (text == null || text.isBlank()) return false;
        if (taxonomy.getHints("nonDining").stream().noneMatch(text::contains)) return false;
        return !text.matches(".*(?:吃|喝|餐|饭|火锅|小吃|美食|茶|咖啡|甜品|糖水).*");
    }

    public boolean hasDiningKeywords(String text) {
        if (text == null || text.isBlank()) return false;
        return brandDictionary.isKnownBrand(text) || taxonomy.getHints("dining").stream().anyMatch(text::contains);
    }

    public boolean isGenericDiningCraving(PlanAdjustmentIntent intent, String name) {
        if (intent != null && intent.getGenericDiningCraving() != null) return intent.getGenericDiningCraving();
        return isGenericDiningCraving(name);
    }

    public String resolveSlotType(PlanAdjustmentIntent intent) {
        if (intent != null && intent.getTimeSlot() != null) {
            String slot=intent.getTimeSlot().toUpperCase(java.util.Locale.ROOT);
            if (java.util.Set.of("BREAKFAST","LUNCH","DINNER","TEA_BREAK","NIGHT_SNACK").contains(slot)) return slot;
        }
        return resolveSlotType(intent == null ? null : intent.getRawMessage());
    }

    public String resolveRequestedFlavor(PlanAdjustmentIntent intent, String defaultFlavor) {
        if (intent != null && intent.getCuisineOrPlace() != null && !intent.getCuisineOrPlace().isBlank())
            return Boolean.TRUE.equals(intent.getGenericDiningCraving())
                    ? normalizeGenericCuisine(intent.getCuisineOrPlace()) : intent.getCuisineOrPlace().trim();
        return resolveRequestedFlavor(intent == null ? null : intent.getRawMessage(),defaultFlavor);
    }

    /** Only normalize conversational grammar for a generic request; never rewrite a named venue. */
    public String normalizeGenericCuisine(String text) {
        // Check the untouched category first: a quantity character can also belong to a food name.
        String original = text == null ? "" : text.trim();
        for (TaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.label().equals(original) || alias.keywords().contains(original)) return alias.label();
        }
        String normalized = stripGenericGrammar(text);
        for (TaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.label().equals(normalized) || alias.keywords().contains(normalized)) return alias.label();
        }
        return normalized;
    }

    /** A conservative consistency check: exact taxonomy categories cannot attest to a specific store. */
    public boolean isGenericCategoryName(String text) {
        if (text == null || text.isBlank() || brandDictionary.isKnownBrand(text.trim())) return false;
        String normalized = normalizeGenericCuisine(text);
        return taxonomy.getFlavorAliases().stream().anyMatch(alias -> alias.label().equals(normalized));
    }

    private String stripGenericGrammar(String text) {
        if (text == null) return "";
        return text.trim()
                .replaceFirst("^(?:我)?(?:想|要)?(?:来|吃|喝|尝|品尝)?(?:一点|一些|点|个|一份)?", "")
                .replaceFirst("[，,。！!？?]+$", "")
                .replaceFirst("(?:的|呀|啊|吧|呢|嘛|啦)+$", "").trim();
    }

    /**
     * 根据上下文文本推断期望时段插槽（slotType: BREAKFAST / LUNCH / DINNER / TEA_BREAK / NIGHT_SNACK）
     */
    public String resolveSlotType(String rawCombined) {
        if (rawCombined == null) return "LUNCH";
        String lower = rawCombined.toLowerCase();
        if (lower.matches(".*(午餐|午饭|中午).*")) return "LUNCH";
        if (lower.matches(".*(晚餐|晚饭).*")) return "DINNER";
        if (lower.matches(".*(早餐|早饭).*")) return "BREAKFAST";
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
        for (TaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.keywords().stream().anyMatch(rawCombined::contains)) return alias.label();
        }

        // 动态提取用户指定的外来菜系或食品名词（如 "日料"、"粤菜"、"螺蛳粉"、"正宗西班牙海鲜炒饭"），无机械字符截断
        Matcher m = Pattern.compile("(?:想吃|去吃|吃点|吃个|来点|来个|改成|换成|替换为)\\s*([^\\s，,。！!？?；;]+)").matcher(rawCombined);
        if (m.find()) {
            String extracted = m.group(1).trim();
            return isGenericDiningCraving(extracted) ? normalizeGenericCuisine(extracted) : extracted;
        }

        return defaultFlavor != null ? defaultFlavor : "特色美食";
    }
}

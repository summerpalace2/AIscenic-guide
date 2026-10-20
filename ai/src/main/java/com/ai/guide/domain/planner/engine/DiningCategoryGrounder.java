package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

/**
 * 餐饮结构化语义解析与兼容性提示
 *
 * 已验证的结构化意图优先；字符串方法仅用于无模型或历史调用方降级，
 * 不保证识别所有菜系、建筑与商户名称。
 */
@Component
public class DiningCategoryGrounder {

    private volatile PlannerTaxonomyPort taxonomy;

    public DiningCategoryGrounder() {
        this(TaxonomyPort.empty());
    }

    @Autowired(required = false)
    public DiningCategoryGrounder(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy != null ? taxonomy : TaxonomyPort.empty();
    }

    /** Share the production policy source with manually constructed adjustment strategies. */
    public void setTaxonomy(PlannerTaxonomyPort taxonomy) {
        if (taxonomy == null) throw new IllegalArgumentException("taxonomy is required");
        this.taxonomy = taxonomy;
    }

    public String defaultDiningDisplayLabel() {
        return taxonomy.getHints("defaultDiningDisplayLabel").stream().findFirst().orElse("");
    }

    public String mealDisplayPrefix(String slotType) {
        String key = switch (slotType == null ? "" : slotType.toUpperCase(java.util.Locale.ROOT)) {
            case "BREAKFAST" -> "mealDisplayPrefixBreakfast";
            case "DINNER" -> "mealDisplayPrefixDinner";
            case "TEA_BREAK", "AFTERNOON_TEA" -> "mealDisplayPrefixTeaBreak";
            case "NIGHT_SNACK" -> "mealDisplayPrefixNightSnack";
            default -> "mealDisplayPrefixLunch";
        };
        return taxonomy.getHints(key).stream().findFirst().orElse("");
    }

    public String mealReferenceLabel(String slotType) {
        String key = switch (slotType == null ? "" : slotType.toUpperCase(java.util.Locale.ROOT)) {
            case "BREAKFAST" -> "slotBreakfast";
            case "LUNCH" -> "slotLunch";
            case "DINNER" -> "slotDinner";
            case "NIGHT_SNACK" -> "slotNightSnack";
            case "TEA_BREAK" -> "slotTeaBreak";
            default -> "";
        };
        return key.isBlank() ? "" : taxonomy.getHints(key).stream().findFirst().orElse("");
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
        if (taxonomy.isKnownBrand(trimmed)) {
            return false;
        }

        // 5. 常见泛化/模糊修饰短语（如 "附近"、"随便"、"推荐"、"吃点"）-> 泛需求
        if (taxonomy.getHints("genericDiningPhrases").stream().anyMatch(trimmed::contains)) {
            return true;
        }
        if (trimmed.contains("或")) {
            return true;
        }

        // 6. Treat a modified dining phrase as a generic craving when no named venue is present.
        if (trimmed.endsWith("的")) {
            return true;
        }

        // 7. 动宾短语（如“吃某类菜”“喝某种饮品”“来份某种小吃”）-> 泛需求
        if (trimmed.matches("^(?:想|要|去|来|选)?(?:吃|喝|尝|品)(?:点|个|下)?.*")) {
            return true;
        }

        // 8. 明确的商户机构专有名词后缀（如 "宏盛餐馆"、"老茶舍"、"川菜大酒楼"、"教工餐厅"），且字数大于等于4 -> 具体门店
        boolean hasEstablishmentSuffix = taxonomy.getHints("establishmentSuffixes").stream()
                .anyMatch(suffix -> trimmed.length() >= suffix.length() + 2 && trimmed.endsWith(suffix));
        if (hasEstablishmentSuffix) {
            return false;
        }

        // 9. 默认策略倒置（Default Fallback for Food Terms）：
        // Classify a single-domain dining noun phrase without a branch or organization suffix as generic.
        // 一律视为品类/风味泛需求，坚决不凭空捏造商户！
        return true;
    }

    // These hints are a compatibility fallback when structured semantic extraction is unavailable.
    // They are deliberately not a claim of nationwide NER coverage.
    public boolean isNonDiningPlace(String text) {
        if (text == null || text.isBlank()) return false;
        if (taxonomy.getHints("nonDining").stream().noneMatch(text::contains)) return false;
        return taxonomy.getHints("nonDiningDiningOverrides").stream().noneMatch(text::contains);
    }

    public boolean hasDiningKeywords(String text) {
        if (text == null || text.isBlank()) return false;
        return taxonomy.isKnownBrand(text) || taxonomy.getHints("dining").stream().anyMatch(text::contains);
    }

    public boolean hasMealSlotReference(String text) {
        return text != null && !text.isBlank()
                && java.util.List.of("slotBreakfast", "slotLunch", "slotDinner", "slotNightSnack", "slotTeaBreak")
                .stream().anyMatch(key -> taxonomy.getHints(key).stream().anyMatch(text::contains));
    }

    public boolean isOpenDiningExploration(String text) {
        return text != null && !isNonDiningPlace(text) && hasDiningKeywords(text)
                && taxonomy.getHints("foodOpenQuestion").stream().anyMatch(text::contains);
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
        for (com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.label().equals(original) || alias.keywords().contains(original)) return alias.label();
        }
        String normalized = stripGenericGrammar(text);
        for (com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.label().equals(normalized) || alias.keywords().contains(normalized)) return alias.label();
        }
        return normalized;
    }

    /** A conservative consistency check: exact taxonomy categories cannot attest to a specific store. */
    public boolean isGenericCategoryName(String text) {
        if (text == null || text.isBlank() || taxonomy.isKnownBrand(text.trim())) return false;
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
        if (rawCombined == null) return defaultMealSlot();
        if (matchesTaxonomyHint(rawCombined, "slotLunch")) return "LUNCH";
        if (matchesTaxonomyHint(rawCombined, "slotBreakfast")) return "BREAKFAST";
        if (matchesTaxonomyHint(rawCombined, "slotNightSnack")) return "NIGHT_SNACK";
        if (matchesTaxonomyHint(rawCombined, "slotDinner")) return "DINNER";
        if (matchesTaxonomyHint(rawCombined, "slotTeaBreak")) return "TEA_BREAK";
        return defaultMealSlot();
    }

    private String defaultMealSlot() {
        return taxonomy.getHints("defaultMealSlot").stream().findFirst()
                .map(String::trim).filter(slot -> java.util.Set.of("BREAKFAST", "LUNCH", "DINNER", "TEA_BREAK", "NIGHT_SNACK")
                        .contains(slot.toUpperCase(java.util.Locale.ROOT)))
                .map(slot -> slot.toUpperCase(java.util.Locale.ROOT)).orElse("");
    }

    public boolean matchesTaxonomyHint(String text, String category) {
        if (text == null || text.isBlank() || category == null || category.isBlank()) return false;
        return taxonomy.getHints(category).stream().anyMatch(text::contains);
    }
    /**
     * 根据自然语言识别风味/菜系标签，用于推荐匹配
     */
    public String resolveRequestedFlavor(String rawCombined, String defaultFlavor) {
        if (rawCombined == null || rawCombined.isBlank()) {
            return defaultFlavor != null ? defaultFlavor : defaultDiningDisplayLabel();
        }
        for (com.ai.guide.domain.taxonomy.gateway.DiningTaxonomyPort.FlavorAlias alias : taxonomy.getFlavorAliases()) {
            if (alias.keywords().stream().anyMatch(rawCombined::contains)) return alias.label();
        }

        // Dynamically retain unlisted cuisine and dish names without mechanical truncation.
        Matcher m = Pattern.compile("(?:想吃|去吃|吃点|吃个|来点|来个|改成|换成|替换为)\\s*([^\\s，,。！!？?；;]+)").matcher(rawCombined);
        if (m.find()) {
            String extracted = m.group(1).trim();
            return isGenericDiningCraving(extracted) ? normalizeGenericCuisine(extracted) : extracted;
        }

        return defaultFlavor != null ? defaultFlavor : defaultDiningDisplayLabel();
    }

    /**
     * Keep the user's selected dish phrase from a narrative plan intact. Taxonomy aliases
     * are useful for ranking, but must not rewrite a specific dish into a broad category.
     */
    public String resolveNarrativeDiningChoice(String rawCombined, String defaultFlavor) {
        if (rawCombined == null || rawCombined.isBlank()) return defaultFlavor;
        int separator = Math.max(rawCombined.lastIndexOf('：'), rawCombined.lastIndexOf(':'));
        if (separator < 0 || separator == rawCombined.length() - 1) {
            return resolveRequestedFlavor(rawCombined, defaultFlavor);
        }
        String narrative = rawCombined.substring(separator + 1).trim();
        String[] clauses = narrative.split("[+＋、，,；;\\r\\n]+", -1);
        String selected = null;
        for (String clause : clauses) {
            String candidate = clause.trim().replaceAll("^[【\\[（(]+|[】\\]）)]+$", "").trim();
            if (!candidate.isBlank() && hasDiningKeywords(candidate)) selected = candidate;
        }
        if (selected == null) return resolveRequestedFlavor(rawCombined, defaultFlavor);
        for (String key : List.of("slotBreakfast", "slotLunch", "slotDinner", "slotNightSnack", "slotTeaBreak")) {
            for (String marker : taxonomy.getHints(key)) {
                if (selected.endsWith(marker) && selected.length() > marker.length()) {
                    selected = selected.substring(0, selected.length() - marker.length()).trim();
                }
            }
        }
        return selected.isBlank() ? defaultFlavor : selected;
    }
}

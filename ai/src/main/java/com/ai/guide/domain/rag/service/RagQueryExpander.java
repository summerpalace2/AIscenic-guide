package com.ai.guide.domain.rag.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps the small, curated synonym layer close to the RAG boundary. It is not
 * a fact source: it only helps a user phrase map to the names and tags already
 * present in the controlled attraction catalogue and knowledge documents.
 */
public final class RagQueryExpander {

    private static final List<AliasGroup> ENTITY_ALIASES = List.of(
            new AliasGroup("三峡博物馆", List.of("三峡博物馆", "重庆中国三峡博物馆", "三峡博物馆主馆")),
            new AliasGroup("科技馆", List.of("科技馆", "重庆科技馆", "江北嘴")),
            new AliasGroup("自然博物馆", List.of("自然博物馆", "重庆自然博物馆", "北碚自然博物馆")),
            new AliasGroup("美术馆", List.of("美术馆", "重庆美术馆", "国泰艺术中心")),
            new AliasGroup("大剧院", List.of("重庆大剧院", "大剧院", "江北嘴")),
            new AliasGroup("朝天门", List.of("朝天门", "朝天门广场", "来福士", "重庆来福士")),
            new AliasGroup("长江索道", List.of("长江索道", "重庆索道", "索道")),
            new AliasGroup("两江游", List.of("两江游", "两江夜游", "朝天门码头", "洪崖洞码头")),
            new AliasGroup("动物园", List.of("重庆动物园", "动物园", "大熊猫馆")),
            new AliasGroup("龙门浩", List.of("龙门浩", "龙门浩老街", "南滨路")),
            new AliasGroup("洪崖洞", List.of("洪崖洞", "洪崖洞民俗风貌区", "沧白路")),
            new AliasGroup("解放碑", List.of("解放碑", "解放碑步行街", "人民解放纪念碑"))
    );

    private static final List<AliasGroup> SCENE_ALIASES = List.of(
            new AliasGroup("室内", List.of("室内", "展馆", "空调", "雨天", "避暑")),
            new AliasGroup("少走路", List.of("少走路", "无障碍", "电梯", "坡道", "休息座椅")),
            new AliasGroup("老人", List.of("老人", "长辈", "父母", "爸妈", "无障碍", "电梯", "平路")),
            new AliasGroup("亲子", List.of("亲子", "儿童", "互动", "科普", "童车")),
            new AliasGroup("夜景", List.of("夜景", "观景", "江景", "亮灯", "拍照")),
            new AliasGroup("雨天", List.of("雨天", "室内", "展馆", "商场"))
    );

    private RagQueryExpander() {
    }

    public static Profile profile(String query) {
        String original = query == null ? "" : query.trim();
        String normalized = original.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> expansionTerms = new LinkedHashSet<>();
        LinkedHashSet<String> rankingTerms = new LinkedHashSet<>();
        collectMatches(normalized, ENTITY_ALIASES, expansionTerms, rankingTerms);
        collectMatches(normalized, SCENE_ALIASES, expansionTerms, rankingTerms);

        String expanded = original;
        if (!expansionTerms.isEmpty()) {
            expanded += "\n检索扩展：" + String.join("、", expansionTerms);
        }
        return new Profile(expanded, List.copyOf(rankingTerms));
    }

    public static int score(String query, String title, String tags, String content) {
        Profile profile = profile(query);
        if (profile.rankingTerms().isEmpty()) return 0;
        String titleText = safe(title);
        String tagsText = safe(tags);
        String contentText = safe(content);
        int score = 0;
        for (String term : profile.rankingTerms()) {
            if (term.length() < 2) continue;
            if (titleText.contains(term)) score += 14;
            if (tagsText.contains(term)) score += 7;
            if (contentText.contains(term)) score += 3;
        }
        return score;
    }

    private static void collectMatches(String normalizedQuery,
                                       List<AliasGroup> groups,
                                       Set<String> expansionTerms,
                                       Set<String> rankingTerms) {
        for (AliasGroup group : groups) {
            boolean matched = group.aliases().stream().anyMatch(alias -> normalizedQuery.contains(alias.toLowerCase(Locale.ROOT)));
            if (!matched) continue;
            expansionTerms.addAll(group.aliases());
            rankingTerms.addAll(group.aliases());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record Profile(String expandedQuery, List<String> rankingTerms) {
    }

    private record AliasGroup(String name, List<String> aliases) {
    }
}

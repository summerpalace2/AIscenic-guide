package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Query expansion vocabulary is provided by the replaceable taxonomy asset. */
@Component
public final class RagQueryExpander {
    private final RagTaxonomyPort taxonomy;

    public RagQueryExpander(RagTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy;
    }

    public com.ai.guide.domain.rag.model.KnowledgeScope queryScope(String city) {
        return com.ai.guide.domain.rag.model.KnowledgeScope.query(city, taxonomy);
    }
    public Profile profile(String query) {
        String original = query == null ? "" : query.trim();
        String normalized = original.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> expansionTerms = new LinkedHashSet<>();
        LinkedHashSet<String> rankingTerms = new LinkedHashSet<>();
        taxonomy.getQueryAliasGroups().forEach(group -> collect(normalized, group.aliases(), expansionTerms, rankingTerms));
        taxonomy.getQuerySceneGroups().forEach(group -> collect(normalized, group.aliases(), expansionTerms, rankingTerms));
        String expanded = expansionTerms.isEmpty() ? original : original + "\n检索扩展：" + String.join("、", expansionTerms);
        return new Profile(expanded, List.copyOf(rankingTerms));
    }

    public int score(String query, String title, String tags, String content) {
        Profile profile = profile(query);
        if (profile.rankingTerms().isEmpty()) return 0;
        String titleText = safe(title), tagsText = safe(tags), contentText = safe(content);
        int score = 0;
        for (String term : profile.rankingTerms()) {
            if (term.length() < 2) continue;
            if (titleText.contains(term)) score += 14;
            if (tagsText.contains(term)) score += 7;
            if (contentText.contains(term)) score += 3;
        }
        return score;
    }

    private static void collect(String query, List<String> aliases, LinkedHashSet<String> expansion, LinkedHashSet<String> ranking) {
        if (aliases.stream().noneMatch(alias -> query.contains(alias.toLowerCase(Locale.ROOT)))) return;
        expansion.addAll(aliases);
        ranking.addAll(aliases);
    }

    private static String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    public record Profile(String expandedQuery, List<String> rankingTerms) {}
}

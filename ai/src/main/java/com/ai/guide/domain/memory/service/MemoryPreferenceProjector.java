package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.taxonomy.gateway.MemoryTaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;

/** Read projection only: equivalent preferences share a key while every source stays editable. */
@Component
public class MemoryPreferenceProjector {
    public static final int PROJECTION_VERSION = 2;
    private final MemoryTaxonomyPort taxonomy;

    public MemoryPreferenceProjector(MemoryTaxonomyPort taxonomy) { this.taxonomy = taxonomy; }

    public TravelMemory project(TravelMemory memory) {
        String key = "TEXT:" + memory.content();
        String summary = memory.content();
        if (taxonomy != null && memory.content() != null) {
            for (TaxonomyPort.MemoryFacet facet : taxonomy.getMemoryFacets()) {
                if (!facet.category().equalsIgnoreCase(memory.category())) continue;
                String rest = Normalizer.normalize(memory.content(), Normalizer.Form.NFKC).replaceAll("\\s+", "");
                boolean matched = false;
                for (String alias : longestFirst(facet.aliases())) {
                    if (rest.contains(alias)) {
                        matched = true;
                        rest = rest.replace(alias, "");
                    }
                }
                if (!matched) continue;
                for (String word : longestFirst(taxonomy.getMemoryPresentationWords())) rest = rest.replace(word, "");
                // Any extra meaning, negation, allergy, cuisine or qualifier keeps
                // its original statement. Sharing a word is not semantic equivalence.
                if (!rest.isBlank()) continue;
                key = facet.key();
                summary = facet.summary();
                break;
            }
        }
        return new TravelMemory(memory.id(), memory.category(), memory.content(), memory.sourceType(), memory.sourceRef(),
                memory.status(), memory.confidence(), memory.createdAt(), memory.updatedAt(), key, summary);
    }

    public TaxonomyPort.MemoryFacet matchingFacet(String category, String content) {
        if (taxonomy == null || category == null || content == null) return null;
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        return taxonomy.getMemoryFacets().stream()
                .filter(facet -> facet.category().equalsIgnoreCase(category))
                .filter(facet -> facet.aliases().stream().anyMatch(normalized::contains))
                .sorted(Comparator.comparingInt((TaxonomyPort.MemoryFacet facet) -> facet.aliases().stream()
                                .mapToInt(normalized::indexOf).filter(index -> index >= 0).min().orElse(Integer.MAX_VALUE))
                        .thenComparing(Comparator.comparingInt((TaxonomyPort.MemoryFacet facet) -> facet.aliases().stream()
                                .filter(normalized::contains).mapToInt(String::length).max().orElse(0)).reversed()))
                .findFirst().orElse(null);
    }

    public List<String> vocabulary(String key) { return taxonomy == null ? List.of() : taxonomy.getHints(key); }

    private List<String> longestFirst(List<String> values) {
        return values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }
    String removePresentationWords(String content) {
        String rest = content;
        if (taxonomy != null) for (String word : longestFirst(taxonomy.getMemoryPresentationWords())) rest = rest.replace(word, "");
        return rest;
    }
}

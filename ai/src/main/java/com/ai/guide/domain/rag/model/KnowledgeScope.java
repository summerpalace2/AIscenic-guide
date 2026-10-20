package com.ai.guide.domain.rag.model;

import com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort;
import java.util.Locale;

/** Explicit knowledge applicability. UNSPECIFIED is preserved for review, never retrieved. */
public record KnowledgeScope(String scope, String city) {
    public KnowledgeScope {
        scope = scope == null || scope.isBlank() ? "UNSPECIFIED" : scope.trim().toUpperCase(Locale.ROOT);
        if (city != null && city.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid city field");
        city = city == null ? "" : city.trim();
        if (city.length() > 128 || city.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid city field");
        if (!java.util.Set.of("CITY", "GLOBAL", "UNSPECIFIED").contains(scope))
            throw new IllegalArgumentException("scope must be CITY, GLOBAL or UNSPECIFIED");
        if ("CITY".equals(scope) && city.isBlank()) throw new IllegalArgumentException("CITY scope requires city");
        if (!"CITY".equals(scope) && !city.isBlank()) throw new IllegalArgumentException("Only CITY scope accepts city");
    }
    public static KnowledgeScope global() { return new KnowledgeScope("GLOBAL", ""); }
    public static KnowledgeScope unspecified() { return new KnowledgeScope("UNSPECIFIED", ""); }
    public static String canonicalCity(String value, RagTaxonomyPort taxonomy) {
        if (value == null || value.isBlank()) return "";
        String canonical = taxonomy.getCityAliases().get(value.trim());
        if (canonical == null || canonical.isBlank()) return value.trim();
        return canonical.endsWith("市") ? canonical : canonical + "市";
    }
    public static KnowledgeScope input(String scope, String city, RagTaxonomyPort taxonomy) {
        var value = new KnowledgeScope(scope, city);
        if (!"CITY".equals(value.scope())) return value;
        String canonical = canonicalCity(value.city(), taxonomy);
        if (canonical.isBlank()) throw new IllegalArgumentException("city is not configured in taxonomy");
        return new KnowledgeScope("CITY", canonical);
    }
    public static KnowledgeScope query(String city, RagTaxonomyPort taxonomy) {
        String canonical = canonicalCity(city, taxonomy);
        return canonical.isBlank() ? global() : new KnowledgeScope("CITY", canonical);
    }
    public boolean permits(KnowledgeScope document) {
        return "GLOBAL".equals(document.scope()) || ("CITY".equals(scope)
                && "CITY".equals(document.scope()) && city.equals(document.city()));
    }
    public String cacheKey() { return scope + ":" + city; }
}

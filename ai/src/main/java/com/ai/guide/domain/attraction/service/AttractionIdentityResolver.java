package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Exact, city-scoped entity identity. Related places and map hints cannot establish identity. */
@Component
public class AttractionIdentityResolver {
    private final AttractionCatalogPort catalog;

    public AttractionIdentityResolver(AttractionCatalogPort catalog) {
        this.catalog = catalog;
    }

    public record Resolution(String status, String id, String canonicalName, List<String> names) {
        public Resolution { names = List.copyOf(names); }
    }

    public Resolution resolve(String city, String mention) {
        String scope = normalizeCity(city), target = normalize(mention);
        if (scope.isEmpty() || target.isEmpty()) return unknown();
        // Read the current catalog snapshot rather than caching aliases across operations refreshes.
        return resolveQualified(entries(scope), scope, target);
    }

    public boolean matches(String city, String subject, String evidenceText) {
        String scope = normalizeCity(city), target = normalize(subject), text = normalize(evidenceText);
        if (scope.isEmpty() || target.isEmpty() || text.isEmpty()) return false;
        List<Attraction> entries = entries(scope);
        Resolution identity = resolveQualified(entries, scope, target);
        if ("AMBIGUOUS".equals(identity.status())) return false;
        if ("UNKNOWN".equals(identity.status())) {
            String localName = withoutCityQualifier(scope, target);
            return text.contains(target) || (!localName.isEmpty() && text.contains(localName));
        }
        // A shared alias in the evidence is not enough to disambiguate either entity.
        return identity.names().stream().map(AttractionIdentityResolver::normalize)
                .filter(name -> !"AMBIGUOUS".equals(resolve(entries, name).status()))
                .anyMatch(text::contains);
    }

    private List<Attraction> entries(String city) {
        List<Attraction> rows = catalog.findByCity(city, null, null);
        if (rows == null) return List.of();
        return rows.stream().filter(row -> row != null && city.equals(normalizeCity(row.getCity())))
                .filter(row -> row.getId() != null && !row.getId().isBlank()).toList();
    }

    private Resolution resolve(List<Attraction> entries, String target) {
        List<Attraction> matched = new ArrayList<>();
        for (Attraction row : entries) {
            if (names(row).stream().anyMatch(name -> target.equals(normalize(name)))) matched.add(row);
        }
        if (matched.isEmpty()) return unknown();
        if (matched.stream().map(Attraction::getId).distinct().count() != 1)
            return new Resolution("AMBIGUOUS", "", "", List.of());
        Attraction row = matched.get(0);
        return new Resolution(target.equals(normalize(row.getName())) ? "EXACT" : "ALIAS",
                row.getId(), row.getName(), names(row));
    }

    private Resolution resolveQualified(List<Attraction> entries, String city, String target) {
        // Full names take precedence: a city's name may legitimately be part of an entity's name.
        Resolution full = resolve(entries, target);
        if (!"UNKNOWN".equals(full.status())) return full;
        String localName = withoutCityQualifier(city, target);
        return localName.equals(target) || localName.isEmpty() ? full : resolve(entries, localName);
    }

    private static String withoutCityQualifier(String city, String target) {
        if (!target.startsWith(city)) return target;
        String remainder = target.substring(city.length());
        return remainder.startsWith("市") ? remainder.substring(1) : remainder;
    }

    private List<String> names(Attraction row) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (row.getName() != null && !row.getName().isBlank()) names.add(row.getName().strip());
        if (row.getAliases() != null) row.getAliases().stream().filter(name -> name != null && !name.isBlank())
                .map(String::strip).forEach(names::add);
        return List.copyOf(names);
    }

    private static Resolution unknown() { return new Resolution("UNKNOWN", "", "", List.of()); }

    private static String normalizeCity(String city) {
        return normalize(city).replaceFirst("市$", "");
    }

    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }
}

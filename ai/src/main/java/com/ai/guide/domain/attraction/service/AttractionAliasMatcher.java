package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.gateway.AttractionAliasTaxonomyPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 景点别名与口语化意图动态匹配器
 *
 * 架构职责：
 * 消除原本写死在 ConversationIntentClassifier 和 ItineraryBuilder 中的静态 Map 与正则表。
 * 系统启动时从数据库（或 attractions.json 资源）动态加载景点及其别名（包含 matches、displayName、tags 等），
 * 维护内存高效的最长前缀匹配索引。未来在库中增减景点，代码零变更。
 */
@Slf4j
@Component
public class AttractionAliasMatcher {
    private record AliasIndex(Map<String, String> aliasToVenueId,
                              Map<String, String> venueIdToCanonical,
                              List<String> sortedAliases,
                              long revision, long catalogRevision) {}
    private volatile AliasIndex index = new AliasIndex(Map.of(), Map.of(), List.of(), Long.MIN_VALUE, Long.MIN_VALUE);

    private final AttractionAliasTaxonomyPort aliasTaxonomy;
    private volatile List<Attraction> catalogSnapshot = List.of();
    private AttractionService attractionService;

    @Autowired
    public AttractionAliasMatcher(AttractionService attractionService,
                                  AttractionAliasTaxonomyPort aliasTaxonomy) {
        this.aliasTaxonomy = aliasTaxonomy;
        init(attractionService);
    }

    /** Test/compatibility constructor; production wiring uses the taxonomy-backed constructor. */
    public AttractionAliasMatcher(AttractionService attractionService) {
        this(attractionService, null);
    }

    public AttractionAliasMatcher() {
        this(null, null);
    }

    public synchronized void init(AttractionService attractionService) {
        long catalogRevision = attractionService == null ? 0 : attractionService.revision();
        List<Attraction> list = attractionService == null ? List.of()
                : Objects.requireNonNull(attractionService.list(null, null), "景点目录加载结果不能为空");
        this.attractionService = attractionService;
        catalogSnapshot = List.copyOf(list);
        index = buildIndex(catalogSnapshot, aliasTaxonomy == null ? 0 : aliasTaxonomy.revision(), catalogRevision);
        log.info("[AttractionAliasMatcher] 动态构建景点别名索引，共收录 {} 条别名映射", index.sortedAliases().size());
    }

    private AliasIndex buildIndex(List<Attraction> catalog, long revision, long catalogRevision) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> canonicalNames = new LinkedHashMap<>();
        for (Attraction attraction : catalog) registerAttraction(attraction, aliases, canonicalNames);
        registerConfiguredAliases(aliases, canonicalNames);
        List<String> sorted = new ArrayList<>(aliases.keySet());
        // 最长别名优先匹配，避免较短别名先截断。
        sorted.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return new AliasIndex(Map.copyOf(aliases), Map.copyOf(canonicalNames), List.copyOf(sorted), revision, catalogRevision);
    }

    private void registerAttraction(Attraction a, Map<String, String> aliasToVenueId,
                                   Map<String, String> venueIdToCanonical) {
        if (a == null || a.getId() == null) return;
        String id = a.getId();
        String name = a.getName();
        String displayName = a.getDisplayName();

        if (displayName != null && !displayName.isBlank()) {
            venueIdToCanonical.putIfAbsent(id, displayName);
            aliasToVenueId.put(displayName, id);
        } else if (name != null && !name.isBlank()) {
            venueIdToCanonical.putIfAbsent(id, name);
        }
        if (name != null && !name.isBlank()) {
            aliasToVenueId.put(name, id);
        }

        if (a.getAmapQuery() != null) {
            Object matches = a.getAmapQuery().get("matches");
            if (matches instanceof Iterable<?> it) {
                for (Object m : it) {
                    if (m != null && !String.valueOf(m).isBlank()) {
                        aliasToVenueId.put(String.valueOf(m).trim(), id);
                    }
                }
            }
            Object kw = a.getAmapQuery().get("keywords");
            if (kw != null && !String.valueOf(kw).isBlank()) {
                aliasToVenueId.put(String.valueOf(kw).trim(), id);
            }
            Object alts = a.getAmapQuery().get("alternatives");
            if (alts instanceof Iterable<?> it) {
                for (Object alt : it) {
                    if (alt != null && !String.valueOf(alt).isBlank()) {
                        aliasToVenueId.put(String.valueOf(alt).trim(), id);
                    }
                }
            }
        }
    }

    /** Load curated aliases only for entities present in the active catalog. */
    private void registerConfiguredAliases(Map<String, String> aliasToVenueId,
                                           Map<String, String> venueIdToCanonical) {
        if (aliasTaxonomy == null) return;
        aliasTaxonomy.aliases().forEach((alias, venueId) -> {
            if (venueIdToCanonical.containsKey(venueId) && alias != null && !alias.isBlank())
                aliasToVenueId.putIfAbsent(alias, venueId);
        });
        aliasTaxonomy.canonicalNames().forEach((venueId, canonical) -> {
            if (venueIdToCanonical.containsKey(venueId) && canonical != null && !canonical.isBlank())
                venueIdToCanonical.put(venueId, canonical);
        });
    }

    private AliasIndex refreshIfTaxonomyChanged() {
        AliasIndex current = index;
        long catalogRevision = attractionService == null ? 0 : attractionService.revision();
        long taxonomyRevision = aliasTaxonomy == null ? 0 : aliasTaxonomy.revision();
        if (taxonomyRevision == current.revision() && catalogRevision == current.catalogRevision()) return current;
        synchronized (this) {
            current = index;
            long revision = aliasTaxonomy == null ? 0 : aliasTaxonomy.revision();
            catalogRevision = attractionService == null ? 0 : attractionService.revision();
            if (revision != current.revision() || catalogRevision != current.catalogRevision()) {
                if (catalogRevision != current.catalogRevision() && attractionService != null)
                    catalogSnapshot = List.copyOf(attractionService.list(null, null));
                current = buildIndex(catalogSnapshot, revision, catalogRevision);
                index = current;
            }
            return current;
        }
    }

    private String sanitizeInput(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
    }

    public String extractMentionedVenueId(String text) {
        AliasIndex current = refreshIfTaxonomyChanged();
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return null;
        for (String alias : current.sortedAliases()) {
            if (clean.contains(alias)) {
                return current.aliasToVenueId().get(alias);
            }
        }
        return null;
    }

    public String extractMentionedVenueAlias(String text) {
        AliasIndex current = refreshIfTaxonomyChanged();
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return null;
        for (String alias : current.sortedAliases()) {
            if (clean.contains(alias)) {
                String venueId = current.aliasToVenueId().get(alias);
                return current.venueIdToCanonical().getOrDefault(venueId, alias);
            }
        }
        return null;
    }

    public boolean containsAnotherVenue(String text, String selectedStopId) {
        AliasIndex current = refreshIfTaxonomyChanged();
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return false;
        String stop = selectedStopId == null ? "" : selectedStopId.toLowerCase(Locale.ROOT)
                .replace("cq-", "").replace("day1-", "").replace("day2-", "").replace("day3-", "").trim();

        for (String alias : current.sortedAliases()) {
            if (clean.contains(alias)) {
                String venueId = current.aliasToVenueId().get(alias);
                String normalizedVenue = venueId != null ? venueId.toLowerCase(Locale.ROOT).replace("cq-", "") : "";
                boolean isCurrent = (selectedStopId != null && selectedStopId.contains(alias))
                        || (selectedStopId != null && !selectedStopId.isBlank() && alias.contains(selectedStopId))
                        || (!stop.isBlank() && (stop.contains(normalizedVenue) || normalizedVenue.contains(stop) || stop.contains(alias)));
                if (!isCurrent) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> findExplicitAttractionIds(String prompt, Set<String> validIds) {
        AliasIndex current = refreshIfTaxonomyChanged();
        String clean = sanitizeInput(prompt);
        if (clean == null || clean.isBlank()) return Collections.emptyList();
        Set<String> matched = new LinkedHashSet<>();
        for (String alias : current.sortedAliases()) {
            if (clean.contains(alias)) {
                String venueId = current.aliasToVenueId().get(alias);
                if (venueId != null && (validIds == null || validIds.contains(venueId))) {
                    matched.add(venueId);
                }
            }
        }
        return new ArrayList<>(matched);
    }

    public List<String> findExplicitAttractionIdsInOrder(String prompt, Set<String> validIds) {
        AliasIndex current = refreshIfTaxonomyChanged();
        String clean = sanitizeInput(prompt);
        if (clean == null || clean.isBlank()) return Collections.emptyList();
        record MatchPos(String venueId, int index) {}
        List<MatchPos> matches = new ArrayList<>();
        Set<String> seenVenues = new HashSet<>();
        for (String alias : current.sortedAliases()) {
            int idx = clean.indexOf(alias);
            if (idx >= 0) {
                String venueId = current.aliasToVenueId().get(alias);
                if (venueId != null && (validIds == null || validIds.contains(venueId))) {
                    if (seenVenues.add(venueId)) {
                        matches.add(new MatchPos(venueId, idx));
                    }
                }
            }
        }
        matches.sort(Comparator.comparingInt(MatchPos::index));
        return matches.stream().map(MatchPos::venueId).toList();
    }

    public Map<String, String> getAliasToVenueIdMap() {
        return refreshIfTaxonomyChanged().aliasToVenueId();
    }

    public Map<String, String> getVenueIdToCanonicalMap() {
        return refreshIfTaxonomyChanged().venueIdToCanonical();
    }

}

package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Map<String, String> aliasToVenueId = new ConcurrentHashMap<>();
    private final Map<String, String> venueIdToCanonical = new ConcurrentHashMap<>();
    private final List<String> sortedAliases = new ArrayList<>();

    @Autowired
    public AttractionAliasMatcher(@Autowired(required = false) AttractionService attractionService) {
        init(attractionService);
    }

    public AttractionAliasMatcher() {
        this(null);
    }

    public synchronized void init(AttractionService attractionService) {
        aliasToVenueId.clear();
        venueIdToCanonical.clear();

        List<Attraction> list = null;
        if (attractionService != null) {
            try {
                list = attractionService.list(null, null);
            } catch (Exception ignored) {}
        }
        if (list == null || list.isEmpty()) {
            list = loadFromResource();
        }

        for (Attraction a : list) {
            registerAttraction(a);
        }

        registerConfiguredAliases();

        sortedAliases.clear();
        sortedAliases.addAll(aliasToVenueId.keySet());
        // 最长前缀优先匹配，避免子串先被截断
        sortedAliases.sort((s1, s2) -> Integer.compare(s2.length(), s1.length()));
        log.info("[AttractionAliasMatcher] 动态构建景点别名索引，共收录 {} 条别名映射", sortedAliases.size());
    }

    private void registerAttraction(Attraction a) {
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
    private void registerConfiguredAliases() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("taxonomy/attraction_aliases.json")) {
            if (is == null) return;
            var root = new ObjectMapper().readTree(is);
            root.path("aliases").fields().forEachRemaining(entry -> {
                String venueId = entry.getValue().asText();
                if (venueIdToCanonical.containsKey(venueId) && !entry.getKey().isBlank()) {
                    aliasToVenueId.putIfAbsent(entry.getKey(), venueId);
                }
            });
            root.path("canonicalNames").fields().forEachRemaining(entry -> {
                if (venueIdToCanonical.containsKey(entry.getKey()) && !entry.getValue().asText().isBlank()) {
                    venueIdToCanonical.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (Exception e) {
            log.warn("[AttractionAliasMatcher] 读取景点别名配置失败: {}", e.getMessage());
        }
    }

    private String sanitizeInput(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
    }

    public String extractMentionedVenueId(String text) {
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return null;
        for (String alias : sortedAliases) {
            if (clean.contains(alias)) {
                return aliasToVenueId.get(alias);
            }
        }
        return null;
    }

    public String extractMentionedVenueAlias(String text) {
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return null;
        for (String alias : sortedAliases) {
            if (clean.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
                return venueIdToCanonical.getOrDefault(venueId, alias);
            }
        }
        return null;
    }

    public boolean containsAnotherVenue(String text, String selectedStopId) {
        String clean = sanitizeInput(text);
        if (clean == null || clean.isBlank()) return false;
        String stop = selectedStopId == null ? "" : selectedStopId.toLowerCase(Locale.ROOT)
                .replace("cq-", "").replace("day1-", "").replace("day2-", "").replace("day3-", "").trim();

        for (String alias : sortedAliases) {
            if (clean.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
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
        String clean = sanitizeInput(prompt);
        if (clean == null || clean.isBlank()) return Collections.emptyList();
        Set<String> matched = new LinkedHashSet<>();
        for (String alias : sortedAliases) {
            if (clean.contains(alias)) {
                String venueId = aliasToVenueId.get(alias);
                if (venueId != null && (validIds == null || validIds.contains(venueId))) {
                    matched.add(venueId);
                }
            }
        }
        return new ArrayList<>(matched);
    }

    public List<String> findExplicitAttractionIdsInOrder(String prompt, Set<String> validIds) {
        String clean = sanitizeInput(prompt);
        if (clean == null || clean.isBlank()) return Collections.emptyList();
        record MatchPos(String venueId, int index) {}
        List<MatchPos> matches = new ArrayList<>();
        Set<String> seenVenues = new HashSet<>();
        for (String alias : sortedAliases) {
            int idx = clean.indexOf(alias);
            if (idx >= 0) {
                String venueId = aliasToVenueId.get(alias);
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
        return Collections.unmodifiableMap(aliasToVenueId);
    }

    public Map<String, String> getVenueIdToCanonicalMap() {
        return Collections.unmodifiableMap(venueIdToCanonical);
    }

    private List<Attraction> loadFromResource() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("attractions.json")) {
            if (is == null) return Collections.emptyList();
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(is, new TypeReference<List<Attraction>>() {});
        } catch (Exception e) {
            log.warn("[AttractionAliasMatcher] 读取 attractions.json 异常: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}

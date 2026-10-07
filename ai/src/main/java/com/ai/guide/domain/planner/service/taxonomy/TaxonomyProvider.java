package com.ai.guide.domain.planner.service.taxonomy;

import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Loads validated assets as one immutable snapshot; failed reloads retain the last good snapshot. */
@Component
@Primary
public class TaxonomyProvider implements TaxonomyPort {
    private static final Logger log = LoggerFactory.getLogger(TaxonomyProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;
    private volatile Snapshot snapshot;
    private volatile long nextCheck;
    private record Snapshot(Set<String> brands, Map<String, MealWindow> windows,
                            Map<String, CityCuisineProfile> cities, Map<String,List<String>> hints,
                            List<FlavorAlias> aliases, Map<String,String> cityAliases,
                            List<MemoryFacet> memoryFacets, List<String> memoryPresentationWords, List<AdjustmentWindow> adjustmentWindows) {}

    public TaxonomyProvider() { this(""); }
    @Autowired
    public TaxonomyProvider(@Value("${planner.taxonomy.directory:}") String directory) {
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory).toAbsolutePath().normalize();
        reload();
    }
    private JsonNode load(String file) throws Exception {
        Path external = directory == null ? null : directory.resolve(file);
        try (InputStream input = external != null && Files.exists(external)
                ? Files.newInputStream(external) : new ClassPathResource("taxonomy/" + file).getInputStream()) {
            return mapper.readTree(input);
        }
    }
    /** Safe operational reload; malformed external configuration never replaces working policy. */
    public synchronized boolean reload() {
        try {
            JsonNode data = load("dining_taxonomy.json");
            Set<String> brands = new LinkedHashSet<>(strings(load("brand_dictionary.json").path("brands")));
            if (brands.isEmpty()) throw new IllegalArgumentException("brands must not be empty");
            Map<String,MealWindow> windows = new LinkedHashMap<>();
            data.path("mealWindows").fields().forEachRemaining(e -> {
                JsonNode v=e.getValue();
                for (String field : List.of("startMinute","endMinute","idealMinute"))
                    if (!v.path(field).isIntegralNumber()) throw new IllegalArgumentException("invalid meal minute");
                int start=v.path("startMinute").asInt(), end=v.path("endMinute").asInt(), ideal=v.path("idealMinute").asInt();
                if (start < 0 || end > 1440 || start >= end || ideal < start || ideal >= end)
                    throw new IllegalArgumentException("invalid meal window: "+e.getKey());
                windows.put(e.getKey(),new MealWindow(start,end,ideal,v.path("label").asText()));
            });
            for (String slot: List.of("BREAKFAST","LUNCH","DINNER","TEA_BREAK","NIGHT_SNACK"))
                if (!windows.containsKey(slot)) throw new IllegalArgumentException("missing window: "+slot);
            Map<String,CityCuisineProfile> cities=new LinkedHashMap<>();
            data.path("cityCuisineProfiles").fields().forEachRemaining(e -> cities.put(e.getKey(),
                    new CityCuisineProfile(e.getKey(),e.getValue().path("defaultBreakfast").asText(),
                            strings(e.getValue().path("signatureDishes")),strings(e.getValue().path("breakfastKeywords")))));
            if (!cities.containsKey("DEFAULT")) throw new IllegalArgumentException("missing DEFAULT city profile");
            Map<String,List<String>> hints = new LinkedHashMap<>();
            data.path("legacyHints").fields().forEachRemaining(e -> hints.put(e.getKey(),strings(e.getValue())));
            List<FlavorAlias> aliases = new ArrayList<>();
            for (JsonNode v : data.path("flavorAliases")) {
                String searchKeyword = v.has("searchKeyword") ? v.path("searchKeyword").asText("").trim() : null;
                if (searchKeyword != null && (!v.path("searchKeyword").isTextual() || searchKeyword.isBlank()))
                    throw new IllegalArgumentException("invalid flavor search keyword");
                aliases.add(new FlavorAlias(v.path("label").asText(), strings(v.path("keywords")), searchKeyword,
                        v.has("poiTypeKeywords") ? strings(v.path("poiTypeKeywords")) : List.of()));
            }
            Map<String,String> cityAliases = new LinkedHashMap<>();
            for (String city : strings(load("city_names.json").path("cities"))) {
                String shortName = city.replaceAll("市$", "");
                if (!city.endsWith("市") || city.length() < 2) throw new IllegalArgumentException("invalid city name");
                String canonical = shortName.length() >= 2 ? shortName : city;
                cityAliases.put(city, canonical);
                if (shortName.length() >= 2) cityAliases.put(shortName, canonical);
            }
            List<MemoryFacet> memoryFacets = new ArrayList<>();
            Set<String> facetKeys = new HashSet<>();
            for (JsonNode facet : data.path("memoryProjection").path("facets")) {
                String category = facet.path("category").asText("").trim();
                String key = facet.path("key").asText("").trim();
                String summary = facet.path("summary").asText("").trim();
                List<String> facetAliases = strings(facet.path("aliases"));
                if (category.isBlank() || key.isBlank() || summary.isBlank() || facetAliases.isEmpty()
                        || !facetKeys.add(category + ":" + key)) throw new IllegalArgumentException("invalid memory facet");
                memoryFacets.add(new MemoryFacet(category, key, summary, facetAliases));
            }
            List<String> memoryWords = data.path("memoryProjection").has("presentationWords")
                    ? strings(data.path("memoryProjection").path("presentationWords")) : List.of();
            List<AdjustmentWindow> adjustmentWindows = new ArrayList<>();
            for (JsonNode window : data.path("adjustmentWindows")) {
                int start = window.path("startMinute").asInt(-1), end = window.path("endMinute").asInt(-1);
                String key = window.path("key").asText("");
                List<String> names = strings(window.path("aliases"));
                if (!window.path("startMinute").isIntegralNumber() || !window.path("endMinute").isIntegralNumber()
                        || start < 0 || end > 1440 || start >= end || key.isBlank() || names.isEmpty())
                    throw new IllegalArgumentException("invalid adjustment window");
                adjustmentWindows.add(new AdjustmentWindow(key, start, end, names));
            }
            snapshot = new Snapshot(Set.copyOf(brands),Map.copyOf(windows),Map.copyOf(cities),Map.copyOf(hints),List.copyOf(aliases),Map.copyOf(cityAliases),List.copyOf(memoryFacets),memoryWords,List.copyOf(adjustmentWindows));
            return true;
        } catch (Exception e) {
            if (snapshot == null) throw new IllegalStateException("Cannot initialize taxonomy assets",e);
            log.warn("Taxonomy reload rejected; keeping previous snapshot: {}",e.getMessage());
            return false;
        }
    }
    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Expected string array");
        List<String> values=new ArrayList<>();
        for (JsonNode value: array) {
            if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Expected nonblank string");
            values.add(value.asText().trim());
        }
        return List.copyOf(values);
    }
    private Snapshot current() {
        if (directory != null && System.currentTimeMillis() >= nextCheck) {
            synchronized(this) {
                if (System.currentTimeMillis() >= nextCheck) {
                    nextCheck=System.currentTimeMillis()+5000;
                    reload();
                }
            }
        }
        return snapshot;
    }
    @Override public boolean isKnownBrand(String name) { return name != null && current().brands().contains(name.trim()); }
    @Override public Set<String> getBrands() { return current().brands(); }
    @Override public MealWindow getMealWindow(String slot) {
        Snapshot data=current();
        return data.windows().getOrDefault(slot == null ? "LUNCH" : slot.toUpperCase(Locale.ROOT),data.windows().get("LUNCH"));
    }
    @Override public CityCuisineProfile getCityProfile(String city) {
        Snapshot data=current();
        String normalized=city == null ? "" : city.trim().replaceAll("市$","");
        return data.cities().entrySet().stream().filter(e -> !e.getKey().equals("DEFAULT")
                && e.getKey().replaceAll("市$","").equals(normalized)).map(Map.Entry::getValue)
                .findFirst().orElse(data.cities().get("DEFAULT"));
    }
    @Override public List<String> getHints(String category) { return current().hints().getOrDefault(category,List.of()); }
    @Override public List<FlavorAlias> getFlavorAliases() { return current().aliases(); }
    @Override public Map<String,String> getCityAliases() { return current().cityAliases(); }
    @Override public List<AdjustmentWindow> getAdjustmentWindows() { return current().adjustmentWindows(); }
    @Override public List<MemoryFacet> getMemoryFacets() { return current().memoryFacets(); }
    @Override public List<String> getMemoryPresentationWords() { return current().memoryPresentationWords(); }
}

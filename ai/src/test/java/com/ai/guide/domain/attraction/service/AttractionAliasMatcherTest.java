package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.gateway.AttractionAliasTaxonomyPort;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AttractionAliasMatcherTest {
    @Test
    void initialCatalogFailureCannotPublishAnEmptySuccessfulIndex() {
        AttractionService service = org.mockito.Mockito.mock(AttractionService.class);
        org.mockito.Mockito.when(service.revision()).thenReturn(5L);
        org.mockito.Mockito.when(service.list(null, null)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> new AttractionAliasMatcher(service));
    }

    @Test
    void externalAliasesCannotIntroduceEntitiesAbsentFromActiveCatalog() {
        Attraction place = Attraction.builder().id("beijing-museum").name("本地展馆")
                .amapQuery(Map.of("matches", List.of("地方文博馆"))).build();
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            @Override public List<Attraction> find(String district, String category) { return List.of(place); }
            @Override public Attraction findById(String id) { return "beijing-museum".equals(id) ? place : null; }
        });
        AttractionAliasMatcher matcher = new AttractionAliasMatcher(catalog);
        assertEquals("beijing-museum", matcher.extractMentionedVenueId("去地方文博馆"));
        assertNull(matcher.extractMentionedVenueId("洪崖洞"));
        assertNull(matcher.extractMentionedVenueId("参观博物馆"));
        assertTrue(matcher.containsAnotherVenue("去地方文博馆", null));
    }

    @Test
    void preservesCuratedColloquialAliasForAnExistingEntity() {
        Attraction place = Attraction.builder().id("cq-liziba").name("李子坝").build();
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            @Override public List<Attraction> find(String district, String category) { return List.of(place); }
            @Override public Attraction findById(String id) { return "cq-liziba".equals(id) ? place : null; }
        });
        AttractionAliasTaxonomyPort aliases = new AttractionAliasTaxonomyPort() {
            @Override public Map<String, String> aliases() { return Map.of("单轨穿楼", "cq-liziba"); }
            @Override public Map<String, String> canonicalNames() { return Map.of("cq-liziba", "李子坝"); }
            @Override public long revision() { return 1; }
        };
        AttractionAliasMatcher matcher = new AttractionAliasMatcher(catalog, aliases);
        assertEquals("cq-liziba", matcher.extractMentionedVenueId("想看单轨穿楼"));
        assertFalse(matcher.containsAnotherVenue("去李子坝", "day1-liziba"));
    }

    @Test
    void configuredAliasSnapshotRefreshesAfterTaxonomyReload() {
        Attraction place = Attraction.builder().id("venue").name("展馆").build();
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            @Override public List<Attraction> find(String district, String category) { return List.of(place); }
            @Override public Attraction findById(String id) { return "venue".equals(id) ? place : null; }
        });
        class MutableAliases implements AttractionAliasTaxonomyPort {
            private volatile long revision = 1;
            private volatile Map<String, String> aliases = Map.of("旧别名", "venue");
            @Override public Map<String, String> aliases() { return aliases; }
            @Override public Map<String, String> canonicalNames() { return Map.of(); }
            @Override public long revision() { return revision; }
        }
        MutableAliases aliases = new MutableAliases();
        AttractionAliasMatcher matcher = new AttractionAliasMatcher(catalog, aliases);
        assertEquals("venue", matcher.extractMentionedVenueId("旧别名"));
        aliases.aliases = Map.of("新别名", "venue");
        aliases.revision++;
        assertNull(matcher.extractMentionedVenueId("旧别名"));
        assertEquals("venue", matcher.extractMentionedVenueId("新别名"));
    }

    @Test
    void concurrentReloadPublishesOnlyCompleteAliasIndexes() throws Exception {
        Attraction place = Attraction.builder().id("venue").name("展馆").build();
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            @Override public List<Attraction> find(String district, String category) { return List.of(place); }
            @Override public Attraction findById(String id) { return "venue".equals(id) ? place : null; }
        });
        class MutableAliases implements AttractionAliasTaxonomyPort {
            private volatile long revision = 1;
            private volatile Map<String, String> aliases = Map.of("别名甲", "venue");
            @Override public Map<String, String> aliases() { return aliases; }
            @Override public Map<String, String> canonicalNames() { return Map.of("venue", "展馆"); }
            @Override public long revision() { return revision; }
            void replace(int n) { aliases = Map.of("别名" + n, "venue"); revision++; }
        }
        MutableAliases source = new MutableAliases();
        AttractionAliasMatcher matcher = new AttractionAliasMatcher(catalog, source);
        var pool = Executors.newFixedThreadPool(5);
        try {
            var writer = pool.submit(() -> {
                for (int i = 0; i < 500; i++) source.replace(i);
            });
            var readers = java.util.stream.IntStream.range(0, 4).mapToObj(index -> pool.submit(() -> {
                for (int i = 0; i < 1000; i++) {
                    var aliases = matcher.getAliasToVenueIdMap();
                    assertTrue(aliases.values().stream().allMatch("venue"::equals));
                    assertTrue(matcher.findExplicitAttractionIds("别名" + (i % 500), java.util.Set.of("venue")).stream()
                            .allMatch("venue"::equals));
                }
            })).toList();
            writer.get(5, TimeUnit.SECONDS);
            for (var reader : readers) reader.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}

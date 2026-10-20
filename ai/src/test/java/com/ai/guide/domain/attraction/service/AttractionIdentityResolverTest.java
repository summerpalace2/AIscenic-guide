package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttractionIdentityResolverTest {
    private Attraction row(String id, String name, String... aliases) {
        return Attraction.builder().id(id).city("测试城").name(name).aliases(List.of(aliases)).build();
    }

    @Test
    void resolvesOnlyExactNamesAndCuratedAliasesInRequestedCity() {
        var catalog = mock(AttractionCatalogPort.class);
        var first = row("one", "第一博物馆", "一博");
        first.setAmapQuery(Map.of("alternatives", List.of("附近广场"), "matches", List.of("搜索别称")));
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(first));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("EXACT", resolver.resolve("测试城市", "第一博物馆").status());
        assertEquals("ALIAS", resolver.resolve("测试城", " 一 博 ").status());
        assertEquals("UNKNOWN", resolver.resolve("测试城", "第一博物馆附近").status());
        assertEquals("UNKNOWN", resolver.resolve("测试城", "附近广场").status());
        assertEquals("UNKNOWN", resolver.resolve("测试城", "搜索别称").status());
        assertEquals("UNKNOWN", resolver.resolve("另一城", "一博").status());
        assertEquals("UNKNOWN", resolver.resolve("", "一博").status());
        assertTrue(resolver.matches("测试城", "一博", "第一博物馆开放至下午。"));
        assertFalse(resolver.matches("测试城", "一博", "附近广场开放至下午。"));
        assertTrue(resolver.matches("测试城", "未收录的馆", "未收录的馆具体开放时间待核验。"));
    }

    @Test
    void sharedAliasDoesNotEstablishIdentityEvenWhenOneCanonicalNameCollides() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(
                row("one", "第一博物馆", "市博"), row("two", "第二博物馆", "市博")));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("AMBIGUOUS", resolver.resolve("测试城", "市博").status());
        assertFalse(resolver.matches("测试城", "市博", "第一博物馆欢迎参观。"));
        assertFalse(resolver.matches("测试城", "第一博物馆", "市博欢迎参观。"));
        assertTrue(resolver.matches("测试城", "第一博物馆", "第一博物馆欢迎参观。"));
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(
                row("one", "第一博物馆", "第二博物馆"), row("two", "第二博物馆")));
        assertEquals("AMBIGUOUS", resolver.resolve("测试城", "第二博物馆").status());
    }

    @Test
    void refreshedAliasesAndIncorrectAdapterCityCannotLeak() {
        var catalog = mock(AttractionCatalogPort.class);
        var leaked = row("other", "外城馆", "外馆");
        leaked.setCity("另一城");
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(row("one", "本城馆", "旧简称"), leaked));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("ALIAS", resolver.resolve("测试城", "旧简称").status());
        assertEquals("UNKNOWN", resolver.resolve("测试城", "外馆").status());
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(row("one", "本城馆", "新简称")));
        assertEquals("UNKNOWN", resolver.resolve("测试城", "旧简称").status());
        assertEquals("ALIAS", resolver.resolve("测试城", "新简称").status());
    }

    @Test
    void resolvesCityQualifiedAliasesButPreservesFullCanonicalNamePriority() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(
                row("one", "第一博物馆", "一博"), row("two", "测试城第一博物馆")));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("ALIAS", resolver.resolve("测试城", "测试城一博").status());
        assertEquals("ALIAS", resolver.resolve("测试城市", "测试城市一博").status());
        assertEquals("two", resolver.resolve("测试城", "测试城第一博物馆").id());
        assertEquals("EXACT", resolver.resolve("测试城", "测试城第一博物馆").status());
        assertTrue(resolver.matches("测试城", "测试城市一博", "第一博物馆欢迎参观。"));
    }

    @Test
    void cityQualifierCanBeRemovedFromUnknownSubjectOnlyForLiteralEvidenceMatching() {
        var catalog = mock(AttractionCatalogPort.class);
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("UNKNOWN", resolver.resolve("测试城", "测试城微澜阅读园").status());
        assertTrue(resolver.matches("测试城", "测试城微澜阅读园", "微澜阅读园提供室内阅读空间。"));
        assertTrue(resolver.matches("测试城市", "测试城市微澜阅读园", "微澜阅读园提供室内阅读空间。"));
        assertFalse(resolver.matches("测试城", "测试城市", "任意不相关资料"));
    }

    @Test
    void otherCityQualifierIsNeverStripped() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity("测试城", null, null)).thenReturn(List.of(row("one", "第一博物馆", "一博")));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("UNKNOWN", resolver.resolve("测试城", "另一城一博").status());
        assertFalse(resolver.matches("测试城", "另一城一博", "第一博物馆欢迎参观。"));
        assertFalse(resolver.matches("测试城", "另一城微澜阅读园", "微澜阅读园提供室内阅读空间。"));
    }
}

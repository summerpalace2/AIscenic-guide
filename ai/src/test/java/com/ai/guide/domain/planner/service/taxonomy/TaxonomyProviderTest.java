package com.ai.guide.domain.planner.service.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TaxonomyProviderTest {
    @TempDir Path directory;
    @Test void cityProfilesMatchExactlyRatherThanAnySubstring() {
        var provider=new TaxonomyProvider();
        assertEquals("西安市",provider.getCityProfile("西安").cityName());
        assertEquals("DEFAULT",provider.getCityProfile("安").cityName());
        assertEquals("DEFAULT",provider.getCityProfile("武汉市").cityName());
    }
    @Test void externalReloadIsAtomicAndRejectsBrokenWindows() throws Exception {
        var provider=new TaxonomyProvider(directory.toString());
        var old=provider.getMealWindow("LUNCH");
        var mapper=new ObjectMapper();
        var tree=mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mealWindows").path("LUNCH")).put("startMinute",750).put("idealMinute",760);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertTrue(provider.reload());
        assertEquals(750,provider.getMealWindow("LUNCH").startMinute());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("mealWindows").path("LUNCH")).put("endMinute",700);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertFalse(provider.reload());
        assertEquals(750,provider.getMealWindow("LUNCH").startMinute());
        assertNotEquals(old,provider.getMealWindow("LUNCH"));
    }
    @Test void flavorSearchPolicyReloadsAtomicallyAndRejectsMalformedKeyword() throws Exception {
        var provider=new TaxonomyProvider(directory.toString());
        var mapper=new ObjectMapper();
        var tree=mapper.readTree(getClass().getResourceAsStream("/taxonomy/dining_taxonomy.json"));
        com.fasterxml.jackson.databind.node.ObjectNode dessert=null;
        for (var alias:tree.path("flavorAliases")) {
            if (alias.path("label").asText().equals("特色甜品糖水")) dessert=(com.fasterxml.jackson.databind.node.ObjectNode)alias;
        }
        assertNotNull(dessert);
        dessert.put("searchKeyword","糖水");
        dessert.putArray("poiTypeKeywords").add("甜品店");
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertTrue(provider.reload());
        var policy=provider.getFlavorAliases().stream().filter(alias->alias.label().equals("特色甜品糖水")).findFirst().orElseThrow();
        assertEquals("糖水",policy.searchKeyword());
        assertEquals(java.util.List.of("甜品店"),policy.poiTypeKeywords());
        assertThrows(UnsupportedOperationException.class,()->policy.poiTypeKeywords().clear());
        dessert.put("searchKeyword",42);
        Files.writeString(directory.resolve("dining_taxonomy.json"),tree.toString());
        assertFalse(provider.reload());
        assertEquals(policy,provider.getFlavorAliases().stream().filter(alias->alias.label().equals("特色甜品糖水")).findFirst().orElseThrow());
    }

    @Test void brokenBrandAssetDoesNotPartiallyInstallMealChange() throws Exception {
        var provider=new TaxonomyProvider(directory.toString());
        var window=provider.getMealWindow("LUNCH");
        Files.writeString(directory.resolve("brand_dictionary.json"),"{\"brands\":[42]}");
        assertFalse(provider.reload());
        assertEquals(window,provider.getMealWindow("LUNCH"));
        assertTrue(provider.isKnownBrand("肯德基"));
        assertThrows(UnsupportedOperationException.class,()->provider.getBrands().clear());
    }
}

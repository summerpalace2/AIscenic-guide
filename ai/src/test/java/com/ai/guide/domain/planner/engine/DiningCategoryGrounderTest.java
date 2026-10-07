package com.ai.guide.domain.planner.engine;

import org.junit.jupiter.api.Test;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiningCategoryGrounderTest {

    private final DiningCategoryGrounder grounder = new DiningCategoryGrounder();

    @Test
    void sharedTaxonomyUpdatesAreVisibleWithoutRecreatingGrounder() {
        TaxonomyPort taxonomy = mock(TaxonomyPort.class);
        AtomicBoolean activeBrand = new AtomicBoolean(false);
        when(taxonomy.isKnownBrand("星河拾味")).thenAnswer(invocation -> activeBrand.get());
        grounder.setTaxonomy(taxonomy);
        assertFalse(grounder.hasDiningKeywords("星河拾味"));
        activeBrand.set(true);
        assertTrue(grounder.hasDiningKeywords("星河拾味"));
        assertFalse(grounder.isGenericDiningCraving("星河拾味"));
    }

    @Test
    void testGenericFoodCravingsAreIdentifiedWithoutHardcoding() {
        // 无分店、无机构后缀的常见食品品类一律判定为泛需求（isGeneric = true）
        assertTrue(grounder.isGenericDiningCraving("汉堡"), "汉堡必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("披萨"), "披萨必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("炸鸡"), "炸鸡必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("火锅"), "火锅必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("点甜食的"), "点甜食的必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("甜食"), "甜食必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("烧烤"), "烧烤必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("烤肉"), "烤肉必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("日料"), "日料必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("粤菜"), "粤菜必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("螺蛳粉"), "螺蛳粉必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("吃点清淡的"), "吃点清淡的必须判定为风味泛需求");
        assertTrue(grounder.isGenericDiningCraving("随便吃点"), "随便吃点必须判定为风味泛需求");
    }

    @Test
    void testExplicitStoresAreRecognized() {
        // 具有分店后缀或知名连锁品牌的一律判定为具体商户（isGeneric = false）
        assertFalse(grounder.isGenericDiningCraving("肯德基"), "肯德基必须判定为具体品牌商户");
        assertFalse(grounder.isGenericDiningCraving("麦当劳"), "麦当劳必须判定为具体品牌商户");
        assertFalse(grounder.isGenericDiningCraving("汉堡王"), "汉堡王必须判定为具体品牌商户");
        assertFalse(grounder.isGenericDiningCraving("外婆家"), "外婆家必须判定为具体品牌商户");
        assertFalse(grounder.isGenericDiningCraving("陈胖子火锅(总店)"), "带分店后缀必须判定为具体商户");
        assertFalse(grounder.isGenericDiningCraving("佩姐老火锅(较场口店)"), "带分店后缀必须判定为具体商户");
        assertFalse(grounder.isGenericDiningCraving("宏盛餐馆(黄桷小区店)"), "带分店后缀必须判定为具体商户");
        assertFalse(grounder.isGenericDiningCraving("临江门老酒楼"), "带机构后缀必须判定为具体商户");
    }

    @Test
    void testFlavorAndSlotResolution() {
        assertEquals("西式快餐轻食", grounder.resolveRequestedFlavor("我想吃汉堡", "特色美食"));
        assertEquals("西式快餐轻食", grounder.resolveRequestedFlavor("把午餐改成披萨", "特色美食"));
        assertEquals("特色甜品糖水", grounder.resolveRequestedFlavor("我想吃点甜食的", "特色美食"));
        assertEquals("火锅", grounder.resolveRequestedFlavor("我想吃火锅", "特色美食"));
        assertEquals("特色烧烤烤肉", grounder.resolveRequestedFlavor("晚上吃点烧烤", "特色美食"));

        assertEquals("TEA_BREAK", grounder.resolveSlotType("我想吃点甜食的"));
        assertEquals("NIGHT_SNACK", grounder.resolveSlotType("晚上吃点烧烤"));
        assertEquals("BREAKFAST", grounder.resolveSlotType("早上吃小面"));
        assertEquals("DINNER", grounder.resolveSlotType("晚餐吃火锅"));
    }
}

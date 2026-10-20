package com.ai.guide.domain.attraction.service;


import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AttractionServiceTest {

    @Autowired
    private AttractionService attractionService;

    @Test
    void testAttractionsSeededAndPropertiesValid() {
        List<Attraction> list = attractionService.list(null, null);

        // 默认受控目录已从 24 条扩展到 36 条；未来管理员新增记录不应让该回归失效。
        assertTrue(list.size() >= 36, "景点目录至少应包含 36 条受控景点");

        Set<String> ids = new HashSet<>();
        for (Attraction attr : list) {
            // 2. 验证 ID 唯一
            assertTrue(ids.add(attr.getId()), "ID 应该唯一: " + attr.getId());

            // Existing coordinate-backed assets retain their contract; new name-only assets await provider resolution.
            assertNotNull(attr.getLocation(), "坐标不能为空: " + attr.getId());
            assertNotNull(attr.getCity(), "城市不能为空: " + attr.getId());
            assertFalse(attr.getCity().isBlank(), "城市不能为空: " + attr.getId());
            if ("重庆".equals(attr.getCity())) assertFalse(attr.getLocation().isBlank(), "重庆既有坐标资产不可退化: " + attr.getId());
            if (attr.getLocation().isBlank()) {
                assertNotNull(attr.getAmapQuery().get("keywords"), "待解析景点必须保留检索关键词: " + attr.getId());
                assertFalse(String.valueOf(attr.getAmapQuery().get("keywords")).isBlank());
            } else {
            String[] coord = attr.getLocation().split(",");
            assertEquals(2, coord.length, "坐标格式应该是 lng,lat: " + attr.getLocation());
            double lng = Double.parseDouble(coord[0]);
            double lat = Double.parseDouble(coord[1]);
            assertTrue(Double.isFinite(lng) && lng >= -180.0 && lng <= 180.0, "经度非法: " + lng);
            assertTrue(Double.isFinite(lat) && lat >= -90.0 && lat <= 90.0, "纬度非法: " + lat);
            if ("重庆".equals(attr.getCity())) {
                assertTrue(lng >= 105.0 && lng <= 110.0, "经度应在重庆范围内: " + lng);
                assertTrue(lat >= 28.0 && lat <= 31.0, "纬度应在重庆范围内: " + lat);
            }
            }

            // 4. 验证字段完整
            assertNotNull(attr.getName(), "name 不能为空: " + attr.getId());
            assertNotNull(attr.getDisplayName(), "displayName 不能为空: " + attr.getId());
            assertNotNull(attr.getDistrict(), "district 不能为空: " + attr.getId());
            assertNotNull(attr.getCategory(), "category 不能为空: " + attr.getId());
            assertNotNull(attr.getSummary(), "summary 不能为空: " + attr.getId());
            assertNotNull(attr.getWalk(), "walk 不能为空: " + attr.getId());
            assertNotNull(attr.getDuration(), "duration 不能为空: " + attr.getId());
            assertNotNull(attr.getWalkDifficulty(), "walkDifficulty 不能为空: " + attr.getId());
            assertNotNull(attr.getTicket(), "ticket 不能为空: " + attr.getId());
            assertNotNull(attr.getBestTime(), "bestTime 不能为空: " + attr.getId());
            assertNotNull(attr.getIntro(), "intro 不能为空: " + attr.getId());
            assertNotNull(attr.getFit(), "fit 不能为空: " + attr.getId());

            // 5. 验证标签完整
            assertNotNull(attr.getTags(), "tags 不能为空: " + attr.getId());
            assertFalse(attr.getTags().isEmpty(), "tags 列表不能为空: " + attr.getId());

            // 6. 验证 amapQuery 完整
            assertNotNull(attr.getAmapQuery(), "amapQuery 不能为空: " + attr.getId());
            assertFalse(attr.getAmapQuery().isEmpty(), "amapQuery 不能为空 map: " + attr.getId());
        }

        assertNotNull(attractionService.get("cq-kejiguan"), "扩展的重庆科技馆应可被查询");
        assertNotNull(attractionService.get("cq-ziran-museum"), "扩展的重庆自然博物馆应可被查询");
        assertNotNull(attractionService.get("cq-liangjiang-you"), "扩展的两江游应可被查询");
    }

    @Test
    void testDistrictAndCategoryFiltering() {
        // 过滤渝中区
        List<Attraction> yuzhong = attractionService.list("渝中区", null);
        assertFalse(yuzhong.isEmpty());
        for (Attraction attr : yuzhong) {
            assertEquals("渝中区", attr.getDistrict());
        }

        // 过滤夜景分类
        List<Attraction> night = attractionService.list(null, "夜景");
        assertFalse(night.isEmpty());
        for (Attraction attr : night) {
            assertEquals("夜景", attr.getCategory());
        }

        // 同时过滤渝中区和夜景
        List<Attraction> yuzhongNight = attractionService.list("渝中区", "夜景");
        assertFalse(yuzhongNight.isEmpty());
        for (Attraction attr : yuzhongNight) {
            assertEquals("渝中区", attr.getDistrict());
            assertEquals("夜景", attr.getCategory());
        }
    }

    @Test
    void testGetSingleAttraction() {
        Attraction attr = attractionService.get("cq-hongyadong");
        assertNotNull(attr);
        assertEquals("cq-hongyadong", attr.getId());
        assertEquals("洪崖洞", attr.getName());

        // 不存在的 ID 应返回 null
        assertNull(attractionService.get("non-existent-id"));
    }
}

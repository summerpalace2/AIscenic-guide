package com.ai.guide.adapter.attraction.web;

import com.ai.guide.application.attraction.AttractionApplicationService;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.model.Attraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AttractionController 适配层端点测试")
class AttractionControllerTest {

    private final AttractionApplicationService applicationService = mock(AttractionApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AttractionController(applicationService)).build();

    @Test
    @DisplayName("GET /ai/attractions - 成功获取景点列表")
    void testListAttractions() throws Exception {
        Attraction attraction = new Attraction();
        attraction.setId("cq-hongyadong");
        attraction.setName("洪崖洞");
        attraction.setDistrict("渝中区");
        attraction.setCategory("核心地标");

        when(applicationService.list(isNull(String.class), eq("渝中区"), eq("核心地标")))
                .thenReturn(Result.success("查询成功", List.of(attraction)));

        mockMvc.perform(get("/ai/attractions")
                        .param("district", "渝中区")
                        .param("category", "核心地标"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("查询成功"))
                .andExpect(jsonPath("$.data[0].id").value("cq-hongyadong"))
                .andExpect(jsonPath("$.data[0].name").value("洪崖洞"));
    }

    @Test
    @DisplayName("GET /ai/attractions?city - 将显式城市范围传到应用层")
    void testListAttractionsByCity() throws Exception {
        Attraction attraction = Attraction.builder().id("cq-hongyadong").name("洪崖洞").city("重庆").build();
        when(applicationService.list(eq("重庆市"), eq("渝中区"), eq("夜景")))
                .thenReturn(Result.success("查询成功", List.of(attraction)));

        mockMvc.perform(get("/ai/attractions")
                        .param("city", "重庆市")
                        .param("district", "渝中区")
                        .param("category", "夜景"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].city").value("重庆"));
    }

    @Test
    @DisplayName("GET /ai/attractions/{id} - 景点不存在返回 404")
    void testGetAttractionNotFound() throws Exception {
        when(applicationService.get("unknown-id"))
                .thenReturn(Result.error(404, "景点不存在"));

        mockMvc.perform(get("/ai/attractions/unknown-id"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("景点不存在"));
    }

    @Test
    @DisplayName("GET /ai/attractions/{id}/facts - 成功获取核验事实")
    void testGetFactsSuccess() throws Exception {
        Map<String, Object> facts = Map.of(
                "venueId", "cq-jiefangbei",
                "openingHours", "全天开放",
                "ticket", "免费"
        );
        when(applicationService.getFacts("cq-jiefangbei"))
                .thenReturn(Result.success("查询成功", facts));

        mockMvc.perform(get("/ai/attractions/cq-jiefangbei/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("查询成功"))
                .andExpect(jsonPath("$.data.venueId").value("cq-jiefangbei"));
    }
}

package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceRetrievalTest {

    @Test
    void localFallbackPrioritizesKnownAliasAndSceneMatches() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", "science", "title", "重庆科技馆亲子指南", "content", "江北嘴互动科技展馆", "tags", "[\"亲子\",\"科普\"]", "source_name", "science"),
                Map.of("id", "art", "title", "重庆美术馆无障碍参观", "content", "国泰艺术中心室内展厅有直梯与休息座椅", "tags", "[\"室内\",\"无障碍\",\"艺术\"]", "source_name", "art")
        ));
        KnowledgeDocumentService service = new KnowledgeDocumentService(jdbcTemplate, null);

        List<Map<String, Object>> result = service.searchLocalKnowledge("国泰艺术中心下雨天带父母去哪里？", 2);

        assertEquals("art", result.get(0).get("id"));
    }
}

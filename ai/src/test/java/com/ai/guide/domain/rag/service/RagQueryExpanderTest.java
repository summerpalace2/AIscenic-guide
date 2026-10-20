package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryExpanderTest {
    private final RagQueryExpander expander = new RagQueryExpander(new JsonTaxonomyAdapter());

    @Test
    void expandsAliasAndSceneWithoutChangingTheUserQuestion() {
        RagQueryExpander.Profile profile = expander.profile("国泰艺术中心下雨天带父母适合去吗？");

        assertTrue(profile.expandedQuery().startsWith("国泰艺术中心下雨天带父母适合去吗？"));
        assertTrue(profile.expandedQuery().contains("重庆美术馆"));
        assertTrue(profile.expandedQuery().contains("室内"));
        assertTrue(profile.expandedQuery().contains("无障碍"));
    }

    @Test
    void givesExactAliasDocumentsAUsefulRankingBoost() {
        int museumScore = expander.score(
                "国泰艺术中心有什么展览？", "重庆美术馆（国泰艺术中心）", "艺术展览,室内", "解放碑商圈艺术空间");
        int unrelatedScore = expander.score(
                "国泰艺术中心有什么展览？", "重庆科技馆", "亲子,科普", "江北嘴互动展馆");

        assertTrue(museumScore > unrelatedScore);
    }
}

package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;

/** Query expansion and destination resolution capabilities owned by RAG/search. */
public interface RagTaxonomyPort {
    static RagTaxonomyPort empty() {
        return new RagTaxonomyPort() {
            @Override public String getDefaultCity() { return ""; }
            @Override public java.util.Map<String, String> getCityAliases() { return java.util.Map.of(); }
            @Override public List<TaxonomyPort.QueryAliasGroup> getQueryAliasGroups() { return List.of(); }
            @Override public List<TaxonomyPort.QuerySceneGroup> getQuerySceneGroups() { return List.of(); }
        };
    }
    String getDefaultCity();
    java.util.Map<String, String> getCityAliases();
    List<TaxonomyPort.QueryAliasGroup> getQueryAliasGroups();
    List<TaxonomyPort.QuerySceneGroup> getQuerySceneGroups();
    default List<String> getRagStopwords() { return List.of(); }
}

package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionIdentityResolver;
import com.ai.guide.domain.rag.gateway.*;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.model.SemanticEvidence.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Controlled response contracts, not a model accuracy benchmark. */
class ClaimEvidenceV6Test {
    final ScenicKnowledgePort provider = mock(ScenicKnowledgePort.class);
    final SemanticEvidencePort semantic = mock(SemanticEvidencePort.class);
    final KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
    final String city = "测试城";
    final String text = "白庭馆常设展在二层。资料未列讲解语言。不提供寄存。";
    final Requirement regular = new Requirement("r", "白庭馆", "所问属性", "STATIC", "REGULAR");

    RagRetrievalService setup(Analysis analysis, Verdict verdict) {
        when(provider.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(new KnowledgeFragment("doc", "fixture", text, new KnowledgeScope("CITY", city))));
        when(semantic.analyze(anyString(), anyString())).thenReturn(analysis);
        when(semantic.assess(anyString(), any(), anyList())).thenReturn(verdict);
        return new RagRetrievalService(provider, local, new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()), semantic, null);
    }
    Support support(String id, String quote, String state, String shape, List<Condition> conditions) {
        return new Support(id, "SUPPORTED", "e0", quote, text.indexOf(quote), text.indexOf(quote) + quote.length(), null, true, conditions, state, shape);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> claims(Map<String,Object> r) { return (List<Map<String,Object>>) r.get("claims"); }

    @Test void notStatedCannotApproveEvenIfTheModelSaysSupported() {
        var s = setup(new Analysis(List.of(regular)), new Verdict(true, List.of(support("r", "资料未列讲解语言。", "NOT_STATED", "UNKNOWN", List.of()))));
        var r = s.retrieve("白庭馆提供几种讲解语言", city, Map.of(), false);
        assertEquals("UNKNOWN", r.get("retrievalStatus"));
        assertEquals("VALUE_NOT_STATED", claims(r).get(0).get("reasonCode"));
    }
    @Test void explicitNegativeIsAnswerableInsteadOfBeingTreatedAsMissing() {
        var s = setup(new Analysis(List.of(regular)), new Verdict(true, List.of(support("r", "不提供寄存。", "STATED_NEGATIVE", "UNKNOWN", List.of()))));
        assertEquals("RELEVANT", s.retrieve("白庭馆提供寄存吗", city, Map.of(), false).get("retrievalStatus"));
    }
    @Test void missingNewFieldsInLegacyPortRemainUnknown() {
        var s = setup(new Analysis(List.of(regular)), new Verdict(true, List.of(new Support("r", "SUPPORTED", "e0", text, 0, text.length()))));
        assertEquals("UNKNOWN", s.retrieve("白庭馆常设展在哪", city, Map.of(), false).get("retrievalStatus"));
    }
    @Test void alternativesNeverBecomeEligibleEvenWithAllPresentedConditionsSatisfied() {
        var rs = List.of(new Requirement("rule", "白庭馆", "资格规则", "STATIC", "RULE"), new Requirement("person", "白庭馆", "个人资格", "STATIC", "ELIGIBILITY"));
        var a = new Analysis("FACTUAL", rs, List.of(new UserFact("u", "我已报名")), List.of());
        var person = support("person", text, "SUPPORTED_VALUE", "ALTERNATIVES", List.of(new Condition("c", "e0", "白庭馆", "SATISFIED", "u")));
        var s = setup(a, new Verdict(true, List.of(support("rule", text, "SUPPORTED_VALUE", "UNKNOWN", List.of()), person)));
        var r = s.retrieve("我已报名，白庭馆资格够吗", city, Map.of(), false);
        assertEquals("PARTIAL", r.get("retrievalStatus"));
        assertEquals("ALTERNATIVE_POLICY_UNSUPPORTED", claims(r).get(1).get("reasonCode"));
    }
    @Test void verifiedCatalogFlowsToAssessmentAndOnlySupportsIdentity() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity(anyString(), isNull(), isNull())).thenReturn(List.of(
                Attraction.builder().id("known").city(city).name("白庭馆").aliases(List.of("庭馆")).build()));
        var resolver = new AttractionIdentityResolver(catalog);
        var a = new Analysis(List.of(new Requirement("identity", "庭馆", "是否为白庭馆", "STATIC", "IDENTITY"), regular));
        when(semantic.analyze(anyString(), anyString())).thenReturn(a);
        when(provider.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(new KnowledgeFragment("doc", "fixture", text, new KnowledgeScope("CITY", city))));
        when(semantic.assess(anyString(), any(), anyList(), anyList())).thenAnswer(call -> {
            List<CatalogIdentity> ids = call.getArgument(3); assertEquals("known", ids.get(0).catalogId());
            List<Evidence> es = call.getArgument(2); var identity = es.stream().filter(e -> e.id().startsWith("catalog-identity-")).findFirst().orElseThrow();
            return new Verdict(true, List.of(new Support("identity", "SUPPORTED", identity.id(), identity.text(), 0, identity.text().length(), null, false, List.of(), "SUPPORTED_VALUE", "UNKNOWN"), support("r", text, "SUPPORTED_VALUE", "UNKNOWN", List.of())));
        });
        var s = new RagRetrievalService(provider, local, new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()), semantic, resolver);
        assertEquals("RELEVANT", s.retrieve("庭馆是白庭馆吗，白庭馆常设展在哪", city, Map.of(), false).get("retrievalStatus"));
        verify(semantic).assess(anyString(), any(), anyList(), anyList());
    }
    @Test void sharedAliasAndOtherCitiesDoNotEstablishTrustedIdentity() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity(anyString(), isNull(), isNull())).thenReturn(List.of(
                Attraction.builder().id("a").city(city).name("甲庭").aliases(List.of("庭馆")).build(),
                Attraction.builder().id("b").city(city).name("乙庭").aliases(List.of("庭馆")).build(),
                Attraction.builder().id("foreign").city("另一城").name("外庭").aliases(List.of("外馆")).build()));
        var resolver = new AttractionIdentityResolver(catalog);
        assertEquals("AMBIGUOUS", resolver.resolve(city, "庭馆").status());
        assertEquals("UNKNOWN", resolver.resolve(city, "外馆").status());
        assertEquals("UNKNOWN", resolver.resolve(city, "另一城庭馆").status());
    }
    @Test void identityEvidenceCannotBecomeAQualificationOrMissingRequirement() {
        var catalog = mock(AttractionCatalogPort.class);
        when(catalog.findByCity(anyString(), isNull(), isNull())).thenReturn(List.of(Attraction.builder().id("known").city(city).name("白庭馆").aliases(List.of("庭馆")).build()));
        var person = new Requirement("p", "白庭馆", "个人资格", "STATIC", "ELIGIBILITY");
        when(semantic.analyze(anyString(), anyString())).thenReturn(new Analysis("FACTUAL", List.of(person), List.of(new UserFact("u", "我有学生证")), List.of()));
        when(provider.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(new KnowledgeFragment("doc", "fixture", text, new KnowledgeScope("CITY", city))));
        when(semantic.assess(anyString(), any(), anyList(), anyList())).thenAnswer(call -> {
            List<Evidence> es = call.getArgument(2); var e = es.stream().filter(x -> x.id().startsWith("catalog-identity-")).findFirst().orElseThrow();
            return new Verdict(true, List.of(new Support("p", "SUPPORTED", e.id(), e.text(), 0, e.text().length(), null, true,
                    List.of(new Condition("c", e.id(), "庭馆", "UNKNOWN", null)), "SUPPORTED_VALUE", "CONJUNCTION")));
        });
        var s = new RagRetrievalService(provider, local, new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()), semantic, new AttractionIdentityResolver(catalog));
        var r = s.retrieve("庭馆是白庭馆吧，我有学生证，我有个人资格吗", city, Map.of(), false);
        assertEquals("UNKNOWN", r.get("retrievalStatus"));
        assertEquals("IDENTITY_ONLY_EVIDENCE", claims(r).get(0).get("reasonCode"));
        assertFalse(claims(r).get(0).containsKey("missingConditions"));
        doAnswer(call -> {
            List<Evidence> es = call.getArgument(2); var e = es.stream().filter(x -> x.id().startsWith("catalog-identity-")).findFirst().orElseThrow();
            return new Verdict(true, List.of(new Support("p", "SUPPORTED", "e0", text, 0, text.length(), null, true,
                    List.of(new Condition("c", e.id(), "庭馆", "SATISFIED", "u")), "SUPPORTED_VALUE", "CONJUNCTION")));
        }).when(semantic).assess(anyString(), any(), anyList(), anyList());
        var nested = s.retrieve("庭馆是白庭馆吧，我有学生证，我有个人资格吗", city, Map.of(), false);
        assertEquals("UNKNOWN", nested.get("retrievalStatus"));
        assertEquals("QUALIFICATION_PREMISES_MISSING", claims(nested).get(0).get("reasonCode"));
        assertFalse(claims(nested).get(0).containsKey("qualificationDecision"));
    }
}

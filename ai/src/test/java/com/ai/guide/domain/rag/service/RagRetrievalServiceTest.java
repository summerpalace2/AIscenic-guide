package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.*;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.model.SemanticEvidence.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Protocol tests with scripted model responses; never an AI quality benchmark. */
class RagRetrievalServiceTest {
    private final ScenicKnowledgePort scenic = mock(ScenicKnowledgePort.class);
    private final KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
    private final SemanticEvidencePort model = mock(SemanticEvidencePort.class);
    private final Analysis analysis = new Analysis(List.of(new Requirement("r1", "青岚馆", "开放时间", "STATIC", "REGULAR")));
    private RagRetrievalService service() {
        return new RagRetrievalService(scenic, local,
                new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()), model, null);
    }
    private void evidence() {
        when(scenic.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(new KnowledgeFragment("fragment", "fixture", "青岚馆9点开放", new KnowledgeScope("CITY", "北京"), "https://example.org/source")));
        when(model.analyze(anyString(), anyString())).thenReturn(analysis);
        when(model.assess(anyString(), any(), anyList())).thenReturn(new Verdict(true, List.of(new Support("r1", "SUPPORTED", "e0", "青岚馆9点开放", 0, 7,null,false,List.of(),"SUPPORTED_VALUE","UNKNOWN"))));
    }
    @Test void supportedQuotationRetainsReferenceWithoutEditorialVerification() {
        evidence(); var response = service().retrieve("青岚馆开放时间", "北京", Map.of(), false);
        assertEquals("RELEVANT", response.get("retrievalStatus")); assertEquals(false, response.get("verified"));
        assertEquals("https://example.org/source", ((Map<?,?>)((List<?>)response.get("citations")).get(0)).get("url"));
        assertEquals("青岚馆9点开放", ((Map<?,?>)((List<?>)response.get("facts")).get(0)).get("value"));
    }
    @Test void noModelRetainsBackgroundButNeverApproves() {
        evidence(); var response = new RagRetrievalService(scenic,local).retrieve("青岚馆开放时间", "北京", Map.of(), false);
        assertEquals("UNKNOWN", response.get("retrievalStatus")); assertTrue(((List<?>)response.get("facts")).isEmpty());
        assertEquals(1, ((List<?>)response.get("backgroundFacts")).size());
        assertFalse(response.get("backgroundFacts").toString().contains("9点开放"));
        assertFalse(response.get("backgroundCitations").toString().contains("9点开放"));
        assertEquals("QDRANT", response.get("backgroundSource"));
    }
    @Test void unavailableSemanticRetrievalUsesScopedLocalBackground() {
        evidence(); when(scenic.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenThrow(new IllegalStateException());
        when(local.searchLocalKnowledge(anyString(),anyInt(),any(KnowledgeScope.class))).thenReturn(List.of(Map.of("title","青岚馆","content","青岚馆9点开放")));
        when(model.analyze(anyString(),anyString())).thenThrow(new SemanticEvidencePort.Failure("unavailable"));
        var response = service().retrieve("青岚馆开放时间","北京",Map.of(),false);
        assertEquals("UNKNOWN",response.get("retrievalStatus")); assertEquals("LOCAL_FALLBACK",response.get("backgroundSource"));
        assertEquals(1,((List<?>)response.get("backgroundFacts")).size());
    }
    @Test void schemaFailureAndFabricatedQuotesCannotGrantPermission() {
        evidence(); when(model.assess(anyString(),any(),anyList())).thenReturn(new Verdict(true, List.of(new Support("r1","SUPPORTED","e0","允许带狗",0,4,null,false,List.of(),"SUPPORTED_VALUE","UNKNOWN"))));
        assertEquals("UNKNOWN",service().retrieve("青岚馆开放时间","北京",Map.of(),false).get("retrievalStatus"));
        when(model.analyze(anyString(),anyString())).thenReturn(new Analysis(List.of(new Requirement("r1","其他馆","开放","STATIC","REGULAR"))));
        assertEquals("UNKNOWN",service().retrieve("青岚馆开放时间","北京",Map.of(),false).get("retrievalStatus"));
    }
    @Test void everyRequirementMustBeCovered() {
        evidence(); when(model.analyze(anyString(),anyString())).thenReturn(new Analysis(List.of(analysis.requirements().get(0), new Requirement("r2","青岚馆","票价","STATIC","REGULAR"))));
        var response=service().retrieve("青岚馆开放和票价","北京",Map.of(),false);
        assertEquals("PARTIAL",response.get("retrievalStatus"));
        assertEquals(1,((List<?>)response.get("facts")).size());
        assertTrue(response.get("claims").toString().contains("UNKNOWN"));
    }
    @Test void currentDatedAndGuaranteeNeverUseStaticTextAsProof() {
        evidence();
        for(String scope:List.of("CURRENT","DATED")) {
            when(model.analyze(anyString(),anyString())).thenReturn(new Analysis(List.of(new Requirement("r1","青岚馆","活动",scope,"ACTUAL"))));
            var response=service().retrieve("青岚馆活动","北京",Map.of(),false);
            assertEquals("UNKNOWN",response.get("retrievalStatus")); assertFalse(((List<?>)response.get("backgroundFacts")).isEmpty());
        }
        when(model.analyze(anyString(),anyString())).thenReturn(new Analysis(List.of(new Requirement("r1","青岚馆","活动","STATIC","GUARANTEE"))));
        assertEquals("UNKNOWN",service().retrieve("青岚馆活动","北京",Map.of(),false).get("retrievalStatus"));
        verify(model,never()).assess(anyString(),any(),anyList());
    }
    @Test void otherCityFragmentsAreExcludedBeforeJudgement() {
        evidence(); when(scenic.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(List.of(new KnowledgeFragment("x","fixture","青岚馆9点开放",new KnowledgeScope("CITY","武汉"))));
        assertEquals("UNKNOWN",service().retrieve("青岚馆开放时间","北京",Map.of(),false).get("retrievalStatus"));
        verify(model,never()).assess(anyString(),any(),anyList());
    }
    @Test void unsafeSourceUrlsNeverBecomeContactLinks() {
        evidence(); when(scenic.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(List.of(new KnowledgeFragment("f","fixture","青岚馆9点开放",new KnowledgeScope("CITY","北京"),"javascript:alert(1)")));
        var r=service().retrieve("青岚馆开放时间","北京",Map.of(),false);
        assertFalse(((Map<?,?>)((List<?>)r.get("citations")).get(0)).containsKey("url"));
    }
    @Test void originalQuestionDoesNotContainExpansionOrPreferences() {
        evidence(); service().retrieve("青岚馆开放时间","北京",Map.of("偏好","今晚烟花"),false);
        verify(model).analyze("青岚馆开放时间","北京");
    }
}

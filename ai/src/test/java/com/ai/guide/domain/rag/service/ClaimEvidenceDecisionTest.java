package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.*;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.model.SemanticEvidence.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Java decisions with controlled semantic responses, not a language model accuracy test. */
class ClaimEvidenceDecisionTest {
    private final ScenicKnowledgePort provider=mock(ScenicKnowledgePort.class);
    private final KnowledgeDocumentService local=mock(KnowledgeDocumentService.class);
    private final SemanticEvidencePort semantic=mock(SemanticEvidencePort.class,CALLS_REAL_METHODS);
    private final String rule="雪纹馆免票需同时满足：有效学生证、本地户籍、在校高中生。";
    private final String notice="雪纹馆10月17日公告：当晚安排灯光展览，具体运行可能调整。";
    private final List<Requirement> personal=List.of(req("rule","RULE"),req("person","ELIGIBILITY"));
    private Requirement req(String id,String type){return new Requirement(id,"雪纹馆","所问结论","STATIC",type);}
    private RagRetrievalService setup(String content,Analysis analysis,Verdict verdict){
        when(provider.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any()))
                .thenReturn(List.of(new KnowledgeFragment("fixture","fixture",content,new KnowledgeScope("CITY","北京"),"https://example.org/source")));
        when(semantic.analyze(anyString(),anyString())).thenReturn(analysis);
        when(semantic.assess(anyString(),any(),anyList())).thenReturn(verdict);
        return new RagRetrievalService(provider,local,new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()),semantic,null);
    }
    private Support support(String id,String text){return new Support(id,"SUPPORTED","e0",text,0,text.length(),null,false,List.of(),"SUPPORTED_VALUE","UNKNOWN");}
    private Condition condition(String id,String quote,String state,String fact){return new Condition(id,"e0",quote,state,fact);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> claims(Map<String,Object> response){return (List<Map<String,Object>>)response.get("claims");}

    @Test void ruleDoesNotProvePersonalQualificationWhenTwoPremisesMissing(){
        var a=new Analysis("FACTUAL",personal,List.of(new UserFact("student","我有学生证")),List.of());
        var eligibility=new Support("person","SUPPORTED","e0",rule,0,rule.length(),null,true,List.of(
                condition("c1","有效学生证","SATISFIED","student"),condition("c2","本地户籍","UNKNOWN",null),condition("c3","在校高中生","UNKNOWN",null)),"SUPPORTED_VALUE","CONJUNCTION");
        var service=setup(rule,a,new Verdict(true,List.of(support("rule",rule),eligibility)));
        var response=service.retrieve("我有学生证，雪纹馆能免票吗","北京",Map.of(),false);
        assertEquals("PARTIAL",response.get("retrievalStatus"));assertEquals("RELEVANT",claims(response).get(0).get("decision"));
        assertEquals("UNKNOWN",claims(response).get(1).get("decision"));
        assertEquals(List.of("本地户籍","在校高中生"),claims(response).get(1).get("missingConditions"));
    }
    @Test void completeUserBindingsAllowQualificationButNeverGuaranteeEntry(){
        var facts=List.of(new UserFact("f1","我有学生证"),new UserFact("f2","我是本地户籍"),new UserFact("f3","我是在校高中生"));
        var eligibility=new Support("person","SUPPORTED","e0",rule,0,rule.length(),null,true,List.of(
                condition("c1","有效学生证","SATISFIED","f1"),condition("c2","本地户籍","SATISFIED","f2"),condition("c3","在校高中生","SATISFIED","f3")),"SUPPORTED_VALUE","CONJUNCTION");
        var service=setup(rule,new Analysis("FACTUAL",personal,facts,List.of()),new Verdict(true,List.of(support("rule",rule),eligibility)));
        var response=service.retrieve("我有学生证，我是本地户籍，我是在校高中生，雪纹馆能免票吗","北京",Map.of(),false);
        assertEquals("RELEVANT",response.get("retrievalStatus"));assertEquals("ELIGIBLE",claims(response).get(1).get("qualificationDecision"));
        assertEquals(false,response.get("verified"));
    }
    @Test void absentFactIdCannotCompletePremises(){
        var eligibility=new Support("person","SUPPORTED","e0",rule,0,rule.length(),null,true,List.of(condition("c1","有效学生证","SATISFIED","invented")),"SUPPORTED_VALUE","CONJUNCTION");
        var service=setup(rule,new Analysis(personal),new Verdict(true,List.of(support("rule",rule),eligibility)));
        var response=service.retrieve("雪纹馆能免票吗","北京",Map.of(),false);
        assertEquals("UNKNOWN",claims(response).get(1).get("decision"));assertEquals("PARTIAL",response.get("retrievalStatus"));
    }
    @Test void statedViolationOfNecessaryConditionCanSupportNegativeDecision(){
        var eligibility=new Support("person","SUPPORTED","e0",rule,0,rule.length(),null,true,List.of(
                condition("c1","有效学生证","UNKNOWN",null),condition("c2","本地户籍","CONTRADICTED","notLocal")),"SUPPORTED_VALUE","CONJUNCTION");
        var service=setup(rule,new Analysis("FACTUAL",personal,List.of(new UserFact("notLocal","我不是本地户籍")),List.of()),new Verdict(true,List.of(support("rule",rule),eligibility)));
        var response=service.retrieve("我不是本地户籍，雪纹馆能免票吗","北京",Map.of(),false);
        assertEquals("INELIGIBLE",claims(response).get(1).get("qualificationDecision"));
    }
    @Test void datedAnnouncementDoesNotProveActualOccurrenceOrGuarantee(){
        var rs=List.of(req("announcement","ANNOUNCEMENT"),req("live","ACTUAL"),req("guarantee","GUARANTEE"));
        var published=new Support("announcement","SUPPORTED","e0",notice,0,notice.length(),"10月17日",false,List.of(),"SUPPORTED_VALUE","UNKNOWN");
        var service=setup(notice,new Analysis(rs),new Verdict(true,List.of(published,support("live",notice),support("guarantee",notice))));
        var response=service.retrieve("雪纹馆今晚一定有展览吗","北京",Map.of(),false);
        assertEquals("PARTIAL",response.get("retrievalStatus"));
        assertEquals(List.of("RELEVANT","UNKNOWN","UNKNOWN"),claims(response).stream().map(c->c.get("decision")).toList());
        assertEquals("10月17日",claims(response).get(0).get("announcementDateQuote"));
    }
    @Test void announcementDateMustBePartOfActualCitation(){
        var bad=new Support("announcement","SUPPORTED","e0",notice,0,notice.length(),"10月18日",false,List.of(),"SUPPORTED_VALUE","UNKNOWN");
        var response=setup(notice,new Analysis(List.of(req("announcement","ANNOUNCEMENT"))),new Verdict(true,List.of(bad)))
                .retrieve("雪纹馆公告是什么","北京",Map.of(),false);
        assertEquals("UNKNOWN",response.get("retrievalStatus"));
    }
    @Test void nonFactualIntentWithNoCityDoesNotQueryKnowledge(){
        when(semantic.analyze(anyString(),anyString())).thenReturn(new Analysis("NON_FACTUAL",List.of(),List.of(),List.of()));
        var service=new RagRetrievalService(provider,local,new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()),semantic,null);
        var response=service.retrieve("今天走得好累啊，想坐下来聊聊","",Map.of(),false);
        assertEquals("NON_FACTUAL",response.get("retrievalStatus"));assertEquals(response.get("reason"),response.get("message"));assertEquals("NOT_QUERIED",response.get("retrievalSource"));
        verifyNoInteractions(provider,local);verify(semantic,never()).assess(anyString(),any(),anyList());
    }
    @Test void coverageFailureKeepsOnlyDocumentedPartAndExplicitUnknown(){
        var response=setup(rule,new Analysis(List.of(req("rule","RULE"))),new Verdict(false,List.of(support("rule",rule))))
                .retrieve("雪纹馆规则与其他问题","北京",Map.of(),false);
        assertEquals("PARTIAL",response.get("retrievalStatus"));assertEquals(2,claims(response).size());
        assertEquals("UNRESOLVED",claims(response).get(1).get("type"));
    }
    @Test void ambiguousAliasWithholdsOnlyItsOwnConclusion(){
        String clear="甲园9点开门",unclear="青馆设有寄存柜";
        var requirements=List.of(new Requirement("clear","甲园","开门时间","STATIC","REGULAR"),new Requirement("ambiguous","市博","寄存设施","STATIC","REGULAR"));
        when(semantic.analyze(anyString(),anyString())).thenReturn(new Analysis(requirements));
        when(semantic.assess(anyString(),any(),anyList())).thenReturn(new Verdict(true,List.of(
                support("clear",clear),new Support("ambiguous","SUPPORTED","e1",unclear,0,unclear.length(),null,false,List.of(),"SUPPORTED_VALUE","UNKNOWN"))));
        when(provider.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(List.of(
                new KnowledgeFragment("clear","fixture",clear,new KnowledgeScope("CITY","北京")),new KnowledgeFragment("ambiguous","fixture",unclear,new KnowledgeScope("CITY","北京"))));
        var catalog=mock(com.ai.guide.domain.attraction.gateway.AttractionCatalogPort.class);
        when(catalog.findByCity(anyString(),isNull(),isNull())).thenReturn(List.of(
                com.ai.guide.domain.attraction.model.Attraction.builder().id("a").name("甲园").city("北京").aliases(List.of()).build(),
                com.ai.guide.domain.attraction.model.Attraction.builder().id("b").name("青馆").city("北京").aliases(List.of("市博")).build(),
                com.ai.guide.domain.attraction.model.Attraction.builder().id("c").name("白馆").city("北京").aliases(List.of("市博")).build()));
        var service=new RagRetrievalService(provider,local,new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()),semantic,new com.ai.guide.domain.attraction.service.AttractionIdentityResolver(catalog));
        var response=service.retrieve("甲园开门时间和市博寄存设施","北京",Map.of(),false);
        assertEquals("PARTIAL",response.get("retrievalStatus"));
        assertEquals(List.of("RELEVANT","UNKNOWN"),claims(response).stream().map(c->c.get("decision")).toList());
    }
}

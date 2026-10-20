package com.ai.guide.evaluation.retrieval;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClaimGateScoringTest {
    @Test void catalogFixtureKeepsExplicitCitiesAndCrossCityAliasesDoNotBecomeLocalAmbiguity(){
        var local=ClaimGateLiveEvaluationTest.catalogAttraction(json.valueToTree(Map.of(
                "id","local","name","本城馆","city","北京","aliases",List.of("共有简称"))),"北京");
        var remote=ClaimGateLiveEvaluationTest.catalogAttraction(json.valueToTree(Map.of(
                "id","remote","name","异城馆","city","杭州","aliases",List.of("共有简称"))),"北京");
        var fallback=ClaimGateLiveEvaluationTest.catalogAttraction(json.valueToTree(Map.of(
                "id","fallback","name","未标城市馆","aliases",List.of())),"北京");
        assertEquals("杭州",remote.getCity());assertEquals("北京",fallback.getCity());
        var catalog=org.mockito.Mockito.mock(com.ai.guide.domain.attraction.gateway.AttractionCatalogPort.class);
        org.mockito.Mockito.when(catalog.findByCity(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of(local,remote,fallback));
        var resolver=new com.ai.guide.domain.attraction.service.AttractionIdentityResolver(catalog);
        assertEquals("UNKNOWN",resolver.resolve("北京","异城馆").status());
        var localAlias=resolver.resolve("北京","共有简称");
        assertEquals("ALIAS",localAlias.status());assertEquals("local",localAlias.id());
        assertEquals("remote",resolver.resolve("杭州","共有简称").id());
        assertEquals("fallback",resolver.resolve("北京","未标城市馆").id());
    }
    private final ObjectMapper json=new ObjectMapper();
    private Map<String,Object> gold(String key,String type,String expected){return Map.of("key",key,"subject","甲馆","type",type,"expectedDecision",expected);}
    private Map<String,Object> claim(String type,String decision){return Map.of("subject","甲馆","type",type,"decision",decision);}
    private Map<String,Object> score(List<Map<String,Object>> gold,List<Map<String,Object>> actual,String status,String intent){
        return ClaimGateLiveEvaluationTest.score(json.valueToTree(Map.of("id","controlled","city","北京","goldClaims",gold,"expectedOverall","UNKNOWN")),Map.of("claims",actual,"retrievalStatus",status,"intent",intent));
    }
    @Test void relabelledLiveFactCannotHideAsUnknownAndUnexpectedRegularSupport(){
        var result=score(List.of(gold("live","ACTUAL","UNKNOWN")),List.of(claim("REGULAR","RELEVANT")),"RELEVANT","FACTUAL");
        assertEquals(true,result.get("falseAllow"));assertEquals(1,result.get("unexpectedSupportedClaims"));
    }
    @Test void factualMessageEscapingIntoConversationIsNotAValidUnknownDecision(){
        var result=score(List.of(gold("live","ACTUAL","UNKNOWN")),List.of(),"NON_FACTUAL","NON_FACTUAL");
        assertEquals(true,result.get("factualIntentError"));assertEquals(false,result.get("overallMatch"));assertEquals(1,result.get("missingGoldClaims"));
    }
    @Test void sameTypeSubjectMultiplePropertiesRequireAdjudicationRatherThanGreedyMatching(){
        var result=score(List.of(gold("price","REGULAR","RELEVANT"),gold("hours","REGULAR","UNKNOWN")),List.of(claim("REGULAR","RELEVANT"),claim("REGULAR","UNKNOWN")),"PARTIAL","FACTUAL");
        assertEquals(true,result.get("adjudicationRequired"));assertEquals(2,result.get("missingGoldClaims"));assertEquals(true,result.get("falseAllow"));
    }
    @Test void supportedRuleWithUnknownPersonalQualificationIsNotAnError(){
        var result=score(List.of(gold("rule","RULE","RELEVANT"),gold("person","ELIGIBILITY","UNKNOWN")),List.of(claim("RULE","RELEVANT"),claim("ELIGIBILITY","UNKNOWN")),"PARTIAL","FACTUAL");
        assertEquals(false,result.get("falseAllow"));assertEquals(false,result.get("falseReject"));assertEquals(0,result.get("missingGoldClaims"));
    }
    @Test void optionalReviewModePreservesRawCountsAndQueuesAmbiguousAlignment(){
        var c=json.valueToTree(Map.of("id","ambiguous","city","北京","query","两个属性","goldClaims",
                List.of(gold("price","REGULAR","RELEVANT"),gold("hours","REGULAR","UNKNOWN")),"expectedOverall","PARTIAL"));
        Map<String,Object> response=Map.of("claims",List.of(claim("REGULAR","RELEVANT"),claim("REGULAR","UNKNOWN")),"intent","FACTUAL","retrievalStatus","PARTIAL");
        var old=ClaimGateLiveEvaluationTest.score(c,response);
        var explicitOld=ClaimGateLiveEvaluationTest.score(c,response,false);
        assertEquals(old,explicitOld);assertFalse(old.containsKey("reviewRequired"));
        var review=ClaimGateLiveEvaluationTest.score(c,response,true);
        old.forEach((key,value)->assertEquals(value,review.get(key),"Raw field changed: "+key));
        assertEquals(true,review.get("reviewRequired"));assertEquals("PENDING_HUMAN",review.get("reviewStatus"));
        var item=(Map<?,?>)review.get("reviewItem");assertEquals("",item.get("humanOpinion"));assertEquals("",item.get("humanDecision"));
        assertTrue(((List<?>)item.get("reasons")).contains("AMBIGUOUS_TYPE_SUBJECT_ALIGNMENT"));
    }
    @Test void extraTypeMissingSlotAndSummaryMismatchEnterReviewQueue(){
        var c=json.valueToTree(Map.of("id","wrong-type","city","北京","query","现在开吗","goldClaims",List.of(gold("live","ACTUAL","UNKNOWN")),"expectedOverall","UNKNOWN"));
        var response=Map.<String,Object>of("claims",List.of(claim("REGULAR","RELEVANT")),"intent","FACTUAL","retrievalStatus","RELEVANT");
        var review=ClaimGateLiveEvaluationTest.score(c,response,true);
        assertEquals(true,review.get("falseAllow"));
        var reasons=(List<?>)((Map<?,?>)review.get("reviewItem")).get("reasons");
        assertTrue(reasons.containsAll(List.of("UNMATCHED_SUPPORTED_CLAIM_OR_TYPE","MISSING_GOLD_SLOT","OVERALL_STATUS_MISMATCH")));
    }
    @Test void unambiguousCasesCanMeetThresholdButPendingHumanCasesCannot(){
        var rows=new ArrayList<Map<String,Object>>();var cases=new ArrayList<Map<String,Object>>();
        for(int i=0;i<50;i++) {
            Map<String,Object> c=Map.of("id","fact"+i,"city","北京","query","规则","bucket","FACTUAL",
                    "goldClaims",List.of(gold("rule","RULE","RELEVANT")),"expectedOverall","RELEVANT");cases.add(c);
            var row=ClaimGateLiveEvaluationTest.score(json.valueToTree(c),Map.of("claims",List.of(claim("RULE","RELEVANT")),"intent","FACTUAL","retrievalStatus","RELEVANT"),true);
            row.put("latencyMs",1.0);rows.add(row);
        }
        for(int i=0;i<10;i++) {
            var row=ClaimGateLiveEvaluationTest.score(json.valueToTree(Map.of("id","dialogue"+i,"expectedIntent","NON_FACTUAL")),
                    Map.of("claims",List.of(),"intent","NON_FACTUAL","retrievalStatus","NON_FACTUAL"),true);
            row.put("latencyMs",1.0);rows.add(row);
        }
        var dataset=json.valueToTree(Map.of("cases",cases));
        var accepted=ClaimGateLiveEvaluationTest.summarize(rows,dataset,1,"controlled",true);
        assertEquals(true,accepted.get("thresholdMet"));assertEquals("ACCEPTED",accepted.get("acceptanceStatus"));
        rows.get(0).put("reviewRequired",true);
        var pending=ClaimGateLiveEvaluationTest.summarize(rows,dataset,1,"controlled",true);
        assertEquals(false,pending.get("thresholdMet"));assertEquals("PENDING_HUMAN",pending.get("acceptanceStatus"));
        assertEquals(accepted.get("falseAllowCases"),pending.get("falseAllowCases"));
        var legacy=ClaimGateLiveEvaluationTest.summarize(rows,dataset,1,"controlled",false);
        assertEquals(true,legacy.get("thresholdMet"));assertFalse(legacy.containsKey("acceptanceStatus"));
        rows.get(0).put("reviewRequired",false);rows.get(0).put("falseReject",true);rows.get(1).put("falseReject",true);rows.get(2).put("falseReject",true);
        assertEquals("NOT_ACCEPTED",ClaimGateLiveEvaluationTest.summarize(rows,dataset,1,"controlled",true).get("acceptanceStatus"));
    }
    @Test void consolidatedQueueRetainsAllThreeAnswersAndBlankHumanOpinion() throws Exception {
        var c=json.valueToTree(Map.of("id","queued","query","规则与现场","city","北京","goldClaims",List.of(gold("live","ACTUAL","UNKNOWN")),"expectedOverall","UNKNOWN"));
        var runs=new ArrayList<Map<String,Object>>();
        for(int run=1;run<=3;run++) {
            var response=Map.<String,Object>of("claims",run==2?List.of():List.of(claim("REGULAR","RELEVANT")),"intent","FACTUAL","retrievalStatus",run==2?"UNKNOWN":"RELEVANT");
            var row=ClaimGateLiveEvaluationTest.score(c,response,true);row.put("response",response);
            runs.add(Map.of("summary",Map.of("run",run),"cases",List.of(row)));
        }
        var consolidated=ClaimGateLiveEvaluationTest.consolidateReview(runs);
        assertEquals("PENDING_HUMAN",consolidated.get("status"));assertEquals(true,consolidated.get("threeRunsComplete"));
        var item=(Map<?,?>)((List<?>)consolidated.get("cases")).get(0);
        assertEquals(3,((List<?>)item.get("observations")).size());assertEquals(false,item.get("actualClaimsStable"));
        assertEquals("",item.get("humanOpinion"));assertEquals("",item.get("humanDecision"));
        var markdown=ClaimGateLiveEvaluationTest.reviewMarkdown(consolidated);
        assertTrue(markdown.contains("PENDING_HUMAN"));assertTrue(markdown.contains("not human-confirmed"));
    }
}

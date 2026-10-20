package com.ai.guide.evaluation.retrieval;

import com.ai.guide.ScenicGuideApplication;
import com.ai.guide.common.config.ThreadPoolConfig;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.*;
import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionIdentityResolver;
import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.service.*;
import com.ai.guide.infrastructure.chat.model.SpringAiChatModelAdapter;
import com.ai.guide.infrastructure.chat.model.MeasuredThinkingRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import com.ai.guide.infrastructure.rag.semantic.LlmSemanticEvidenceAdapter;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.reactivestreams.Publisher;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real measured model, preselected synthetic evidence. Quality scores do not control JUnit success. */
@EnabledIfSystemProperty(named="claim.live.enabled",matches="true")
class ClaimGateLiveEvaluationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @Test void frozenRepeatedMeasurement() throws Exception {
        Path input=Path.of(System.getProperty("claim.live.input")),out=Path.of(System.getProperty("claim.live.output"));
        Path project=Path.of(System.getProperty("claim.live.project",".."));
        String expected=System.getProperty("claim.live.sha256");byte[] bytes=Files.readAllBytes(input);
        assertEquals(expected,hash(bytes));JsonNode dataset=JSON.readTree(bytes);
        var ids=new HashSet<String>();
        for(String group:List.of("cases","dialogueCases"))for(var c:dataset.path(group))
            assertTrue(c.path("id").asText().matches("[A-Za-z0-9_-]{1,80}")&&ids.add(c.path("id").asText()),"Unsafe or duplicate case ID");
        Files.createDirectories(out);Files.write(out.resolve("frozen-input.json"),bytes,StandardOpenOption.CREATE_NEW);
        ScenicGuideApplication.loadDotEnv();
        boolean reviewMode=Boolean.getBoolean("claim.live.review-mode");
        boolean disableThinking=Boolean.parseBoolean(System.getProperty("rag.semantic.disable-thinking",config("RAG_SEMANTIC_DISABLE_THINKING","true")));
        var sources=sourceHashes(project);write(out.resolve("freeze.json"),Map.of("datasetSha256",expected,"sourceHashes",sources,"requestedTemperature",0.0,"thinkingDisabledRequested",disableThinking,"reviewMode",reviewMode));
        for(String relative:sources.keySet()) {Path target=out.resolve("frozen-source").resolve(relative);Files.createDirectories(target.getParent());Files.copy(project.resolve(relative),target);}
        String key=config("DEEPSEEK_API_KEY","");assertFalse(key.isBlank(),"Configured model key unavailable");
        String modelName=config("DEEPSEEK_MODEL","deepseek-v4-flash");
        String baseUrl=config("DEEPSEEK_BASE_URL","https://api.deepseek.com");
        String completionsPath=System.getProperty("spring.ai.openai.chat.completions-path","/v1/chat/completions");
        var rest=RestClient.builder();
        if(disableThinking) rest.requestInterceptor(new MeasuredThinkingRequestInterceptor(baseUrl,completionsPath));
        var client=new SpringAiChatModelAdapter(ChatClient.builder(new OpenAiChatModel(
                new OpenAiApi(baseUrl,key,completionsPath,"/v1/embeddings",rest,WebClient.builder(),org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER),
                OpenAiChatOptions.builder().withModel(modelName).withTemperature(0.0).withMaxTokens(8192).build())),disableThinking);
        int passes=Integer.getInteger("claim.live.passes",3),limit=Integer.getInteger("claim.live.limit",Integer.MAX_VALUE);
        if(reviewMode) assertEquals(3,passes,"Review mode requires three frozen passes");
        var all=new ArrayList<Map<String,Object>>();
        for(int run=1;run<=passes;run++) {
            assertEquals(sources,sourceHashes(project));var calls=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
            AtomicReference<String> caseId=new AtomicReference<>("");
            LanguageModelPort journal=new LanguageModelPort(){
                public Publisher<String> stream(List<LanguageModelMessage> m){return client.stream(m);}
                public <T>T complete(List<LanguageModelMessage> m,Class<T> c){return client.complete(m,c);}
                public String completeText(List<LanguageModelMessage> m){return client.completeText(m);}
                public LanguageModelCompletion completeMeasured(List<LanguageModelMessage> m,double temperature){
                    var event=new LinkedHashMap<String,Object>();event.put("caseId",caseId.get());event.put("messages",m);event.put("requestedTemperature",temperature);event.put("thinkingDisabledRequested",disableThinking);
                    long started=System.nanoTime();try{var result=client.completeMeasured(m,temperature);event.put("completion",result);return result;}
                    catch(RuntimeException e){event.put("errorClass",e.getClass().getSimpleName());throw e;}
                    finally{event.put("wallMs",(System.nanoTime()-started)/1_000_000.0);calls.add(event);}
                }
            };
            var executor=new ThreadPoolConfig().plannerLlmExecutor(2,4,16);
            var semantic=new LlmSemanticEvidenceAdapter(journal,JSON,executor,true,15000,8000);
            Path runDir=out.resolve("run-"+run);Files.createDirectories(runDir);var rows=new ArrayList<Map<String,Object>>();
            try {
                var cases=new ArrayList<JsonNode>();dataset.path("cases").forEach(cases::add);dataset.path("dialogueCases").forEach(cases::add);
                for(JsonNode c:cases) {
                    if(rows.size()>=limit) break;
                    caseId.set(c.path("id").asText());String city=c.path("city").asText("");
                    var provider=mock(ScenicKnowledgePort.class);var local=mock(KnowledgeDocumentService.class);var catalog=mock(AttractionCatalogPort.class);
                    var attractions=new ArrayList<Attraction>();
                    for(var item:c.path("catalog")) attractions.add(catalogAttraction(item,city));
                    when(catalog.findByCity(anyString(),isNull(),isNull())).thenReturn(attractions);
                    var fragments=new ArrayList<KnowledgeFragment>();
                    for(var e:c.path("evidence")) fragments.add(new KnowledgeFragment(e.path("id").asText(),"CONTROLLED_SYNTHETIC",
                            e.path("title").asText()+"\n"+e.path("content").asText(),new KnowledgeScope("CITY",city),e.path("sourceUrl").asText("")));
                    when(provider.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(fragments);
                    var retrieval=new RagRetrievalService(provider,local,new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()),semantic,new AttractionIdentityResolver(catalog));
                    long started=System.nanoTime();var response=retrieval.retrieve(c.path("query").asText(),city,Map.of(),false);
                    var row=score(c,response,reviewMode);row.put("response",response);row.put("latencyMs",(System.nanoTime()-started)/1_000_000.0);
                    rows.add(row);write(runDir.resolve(c.path("id").asText()+".json"),row);
                    System.out.println("CLAIM_EVAL run="+run+" id="+c.path("id").asText()+" status="+response.get("retrievalStatus")+" falseAllow="+row.get("falseAllow")+" falseReject="+row.get("falseReject"));
                }
            } finally {executor.shutdownNow();}
            assertEquals(expected,hash(Files.readAllBytes(input)));assertEquals(sources,sourceHashes(project));
            var summary=summarize(rows,dataset,run,modelName,reviewMode);write(runDir.resolve("results.json"),Map.of("summary",summary,"cases",rows,"modelCalls",List.copyOf(calls)));
            write(runDir.resolve("summary.json"),summary);all.add(Map.of("summary",summary,"cases",rows));
            if(reviewMode) write(runDir.resolve("review-queue.json"),Map.of("run",run,"status",reviewStatus(rows),
                    "cases",rows.stream().filter(r->Boolean.TRUE.equals(r.get("reviewRequired"))).map(r->r.get("reviewItem")).toList()));
        }
        var stability=new ArrayList<Map<String,Object>>();
        if(!all.isEmpty()) for(var first:rowsOf(all.get(0))) {
            var observations=new ArrayList<Object>();var shapes=new ArrayList<Object>();
            for(var run:all) for(var row:rowsOf(run)) if(row.get("id").equals(first.get("id"))) {observations.add(row.get("goldDecisions"));shapes.add(((Map<?,?>)row.get("response")).get("retrievalStatus"));}
            stability.add(Map.of("id",first.get("id"),"goldDecisions",observations,"overallStatuses",shapes,"decisionStable",new HashSet<>(observations).size()==1,"shapeStable",new HashSet<>(shapes).size()==1));
        }
        write(out.resolve("stability.json"),Map.of("runs",all.size(),"cases",stability));
        boolean accepted=passes==3&&all.stream().allMatch(r->Boolean.TRUE.equals(((Map<?,?>)r.get("summary")).get("thresholdMet")));
        var overall=new LinkedHashMap<String,Object>();overall.put("datasetSha256",expected);overall.put("runs",all.stream().map(r->r.get("summary")).toList());overall.put("allThreeThresholdMet",accepted);
        overall.put("stableGoldDecisionCases",stability.stream().filter(s->Boolean.TRUE.equals(s.get("decisionStable"))).count());overall.put("boundary","REAL_MODEL_PRESELECTED_SYNTHETIC_EVIDENCE_NOT_HTTP_STREAMING_OR_FINAL_ANSWER_EVALUATION");
        if(reviewMode) {
            var review=consolidateReview(all);write(out.resolve("review-queue.json"),review);
            Files.writeString(out.resolve("review-queue.md"),reviewMarkdown(review),StandardOpenOption.CREATE_NEW);
            boolean pending="PENDING_HUMAN".equals(review.get("status"));
            overall.put("reviewMode",true);overall.put("acceptanceStatus",pending?"PENDING_HUMAN":accepted?"ACCEPTED":"NOT_ACCEPTED");
            if(pending)overall.put("allThreeThresholdMet",false);
        }
        write(out.resolve("summary.json"),overall);
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rowsOf(Map<String,Object> r){return (List<Map<String,Object>>)r.get("cases");}
    static Attraction catalogAttraction(JsonNode item,String fallbackCity) {
        var aliases=new ArrayList<String>();item.path("aliases").forEach(a->aliases.add(a.asText()));
        return Attraction.builder().id(item.path("id").asText()).name(item.path("name").asText())
                .city(item.path("city").asText(fallbackCity)).aliases(aliases).build();
    }
    static Map<String,Object> score(JsonNode c,Map<String,Object> response){
        var row=new LinkedHashMap<String,Object>();row.put("id",c.path("id").asText());row.put("bucket",c.path("bucket").asText("DIALOGUE"));row.put("query",c.path("query").asText());
        var decisions=new LinkedHashMap<String,String>();boolean allow=false,reject=false,ambiguous=false;int missing=0;
        JsonNode claims=JSON.valueToTree(response.getOrDefault("claims",List.of()));var used=new HashSet<Integer>();
        for(var gold:c.path("goldClaims")) {
            String actual="UNKNOWN";boolean found=false;
            int goldSlots=0,actualSlots=0;
            for(var other:c.path("goldClaims"))if(other.path("type").asText().equals(gold.path("type").asText())&&normalize(other.path("subject").asText()).equals(normalize(gold.path("subject").asText())))goldSlots++;
            for(var candidate:claims)if(gold.path("type").asText().equals(candidate.path("type").asText())&&sameSubject(c,gold,candidate.path("subject").asText()))actualSlots++;
            boolean unique=goldSlots==1&&actualSlots<=1;
            if(!unique)ambiguous=true;
            for(int i=0;i<claims.size();i++) if(!used.contains(i)&&gold.path("type").asText().equals(claims.get(i).path("type").asText())&&sameSubject(c,gold,claims.get(i).path("subject").asText())) {
                if(!unique)break;
                used.add(i);actual=claims.get(i).path("decision").asText("UNKNOWN");found=true;break;
            }
            if(!found) missing++;String wanted=gold.path("expectedDecision").asText();
            allow|=wanted.equals("UNKNOWN")&&actual.equals("RELEVANT");reject|=wanted.equals("RELEVANT")&&!actual.equals("RELEVANT");
            decisions.put(gold.path("key").asText(),actual);
        }
        // A live-state/guarantee claim must never be supported, including unexpected extra claims.
        for(var claim:claims) if(List.of("ACTUAL","GUARANTEE").contains(claim.path("type").asText())&&claim.path("decision").asText().equals("RELEVANT"))allow=true;
        boolean dialogueMatch=!c.has("expectedIntent")||c.path("expectedIntent").asText().equals(response.get("intent"));
        if(c.has("expectedIntent"))decisions.put("intent",String.valueOf(response.get("intent")));
        int unexpected=0;
        for(int i=0;i<claims.size();i++)if(!used.contains(i)&&claims.get(i).path("decision").asText().equals("RELEVANT")){
            unexpected++;allow=true;
        }
        row.put("unexpectedSupportedClaims",unexpected);
        row.put("adjudicationRequired",ambiguous);
        row.put("factualIntentError",c.has("goldClaims")&&!"FACTUAL".equals(response.get("intent")));
        row.put("falseAllow",allow);row.put("falseReject",reject);row.put("goldDecisions",decisions);row.put("missingGoldClaims",missing);row.put("dialogueMatch",dialogueMatch);
        row.put("overallMatch",!c.has("expectedOverall")||c.path("expectedOverall").asText().equals(response.get("retrievalStatus")));return row;
    }
    static Map<String,Object> score(JsonNode c,Map<String,Object> response,boolean reviewMode) {
        var row=score(c,response);
        if(!reviewMode)return row;
        var reasons=new ArrayList<String>();
        if(Boolean.TRUE.equals(row.get("adjudicationRequired")))reasons.add("AMBIGUOUS_TYPE_SUBJECT_ALIGNMENT");
        if(((Number)row.get("unexpectedSupportedClaims")).intValue()>0)reasons.add("UNMATCHED_SUPPORTED_CLAIM_OR_TYPE");
        if(!Boolean.TRUE.equals(row.get("overallMatch")))reasons.add("OVERALL_STATUS_MISMATCH");
        if(((Number)row.get("missingGoldClaims")).intValue()>0)reasons.add("MISSING_GOLD_SLOT");
        if(Boolean.TRUE.equals(row.get("factualIntentError")))reasons.add("FACTUAL_INTENT_MISMATCH");
        if(!Boolean.TRUE.equals(row.get("dialogueMatch")))reasons.add("DIALOGUE_INTENT_MISMATCH");
        row.put("reviewRequired",!reasons.isEmpty());row.put("reviewStatus",reasons.isEmpty()?"AUTOMATIC_UNAMBIGUOUS":"PENDING_HUMAN");
        if(!reasons.isEmpty()) {
            var item=new LinkedHashMap<String,Object>();item.put("id",row.get("id"));item.put("query",row.get("query"));
            item.put("goldClaims",c.path("goldClaims"));item.put("expectedOverall",c.path("expectedOverall").asText(""));
            item.put("actualClaims",response.getOrDefault("claims",List.of()));item.put("actualOverall",response.getOrDefault("retrievalStatus",""));
            item.put("reasons",reasons);item.put("status","PENDING_HUMAN");item.put("humanOpinion","");item.put("humanDecision","");
            row.put("reviewItem",item);
        }
        return row;
    }
    private static boolean sameSubject(JsonNode c,JsonNode gold,String actual){
        String target=normalize(actual),wanted=normalize(gold.path("subject").asText());if(target.equals(wanted))return true;
        String city=normalize(c.path("city").asText()).replaceFirst("市$","");if(!city.isEmpty()&&target.startsWith(city))target=target.substring(city.length()).replaceFirst("^市","");
        if(target.equals(wanted))return true;for(var alias:gold.path("subjectAliases"))if(target.equals(normalize(alias.asText())))return true;
        for(var catalog:c.path("catalog")) if(normalize(catalog.path("name").asText()).equals(wanted))for(var alias:catalog.path("aliases"))if(target.equals(normalize(alias.asText())))return true;
        return false;
    }
    private static Map<String,Object> summarize(List<Map<String,Object>> rows,JsonNode dataset,int run,String model){
        var factual=rows.stream().filter(r->!r.get("bucket").equals("DIALOGUE")).toList();var dialogue=rows.stream().filter(r->r.get("bucket").equals("DIALOGUE")).toList();
        long falseAllow=factual.stream().filter(r->Boolean.TRUE.equals(r.get("falseAllow"))).count(),falseReject=factual.stream().filter(r->Boolean.TRUE.equals(r.get("falseReject"))).count();
        long unknownCases=0,supportedCases=0;for(var c:dataset.path("cases")){boolean u=false,s=false;for(var g:c.path("goldClaims")){u|=g.path("expectedDecision").asText().equals("UNKNOWN");s|=g.path("expectedDecision").asText().equals("RELEVANT");}if(u)unknownCases++;if(s)supportedCases++;}
        var metrics=JSON.valueToTree(rows);long attempts=0,retries=0,knownTokens=0,unknownUsage=0,recoveredStages=0,firstFailedStages=0;
        for(var row:metrics) {var m=row.path("response").path("semanticMetrics");attempts+=m.path("attemptCount").asLong();retries+=m.path("retryCount").asLong();
            var failed=new HashSet<String>();for(var event:m.path("modelAttempts")){if(event.path("totalTokens").isNumber())knownTokens+=event.path("totalTokens").asLong();else unknownUsage++;
                if(event.path("attempt").asInt()==1&&!event.path("outcome").asText().equals("SUCCESS")){failed.add(event.path("stage").asText());firstFailedStages++;}
                if(event.path("attempt").asInt()>1&&event.path("outcome").asText().equals("SUCCESS")&&failed.contains(event.path("stage").asText()))recoveredStages++;}}
        var result=new LinkedHashMap<String,Object>();result.put("run",run);result.put("model",model);result.put("requestedTemperature",0.0);result.put("factualCases",factual.size());
        result.put("unsupportedConclusionBearingCases",unknownCases);result.put("supportedConclusionBearingCases",supportedCases);result.put("falseAllowCases",falseAllow);result.put("falseRejectCases",falseReject);
        result.put("missingGoldClaims",factual.stream().mapToInt(r->(Integer)r.get("missingGoldClaims")).sum());result.put("overallMismatchCases",factual.stream().filter(r->!Boolean.TRUE.equals(r.get("overallMatch"))).count());
        long routeErrors=factual.stream().filter(r->Boolean.TRUE.equals(r.get("factualIntentError"))).count(),adjudications=factual.stream().filter(r->Boolean.TRUE.equals(r.get("adjudicationRequired"))).count();
        result.put("factualIntentErrors",routeErrors);result.put("adjudicationRequiredCases",adjudications);
        result.put("dialogueCases",dialogue.size());result.put("dialogueErrors",dialogue.stream().filter(r->!Boolean.TRUE.equals(r.get("dialogueMatch"))).count());
        result.put("requestP50Ms",percentile(factual,0.5));result.put("requestP95Ms",percentile(factual,0.95));result.put("logicalModelAttempts",attempts);result.put("logicalRetries",retries);
        result.put("knownResponseTokens",knownTokens);result.put("attemptsWithUnknownTokenUsage",unknownUsage);result.put("firstAttemptFailedStages",firstFailedStages);result.put("recoveredStages",recoveredStages);
        result.put("billingCost","NOT_COMPUTED_PROVIDER_FAILURE_USAGE_AND_PRICING_UNKNOWN");
        result.put("thresholdMet",factual.size()==50&&dialogue.size()==10&&falseAllow<=2&&falseReject<=2&&routeErrors==0&&adjudications==0
                &&((Number)result.get("overallMismatchCases")).longValue()<=2&&((Number)result.get("missingGoldClaims")).longValue()<=2
                &&dialogue.stream().allMatch(r->Boolean.TRUE.equals(r.get("dialogueMatch"))));return result;
    }
    static Map<String,Object> summarize(List<Map<String,Object>> rows,JsonNode dataset,int run,String model,boolean reviewMode) {
        var result=summarize(rows,dataset,run,model);
        if(!reviewMode)return result;
        long pending=rows.stream().filter(r->Boolean.TRUE.equals(r.get("reviewRequired"))).count();
        result.put("reviewMode",true);result.put("reviewQueueCases",pending);
        result.put("automaticUnambiguousFalseAllowCases",rows.stream().filter(r->!Boolean.TRUE.equals(r.get("reviewRequired"))&&Boolean.TRUE.equals(r.get("falseAllow"))).count());
        result.put("automaticUnambiguousFalseRejectCases",rows.stream().filter(r->!Boolean.TRUE.equals(r.get("reviewRequired"))&&Boolean.TRUE.equals(r.get("falseReject"))).count());
        result.put("automaticSubsetBoundary","TRIAGE_ONLY_EXCLUDES_REVIEW_QUEUE_NOT_A_HUMAN_ADJUDICATED_QUALITY_SCORE");
        result.put("acceptanceStatus",pending>0?"PENDING_HUMAN":Boolean.TRUE.equals(result.get("thresholdMet"))?"ACCEPTED":"NOT_ACCEPTED");
        if(pending>0)result.put("thresholdMet",false);
        return result;
    }
    private static String reviewStatus(List<Map<String,Object>> rows) {
        return rows.stream().anyMatch(r->Boolean.TRUE.equals(r.get("reviewRequired")))?"PENDING_HUMAN":"NO_AUTOMATIC_AMBIGUITY";
    }
    static Map<String,Object> consolidateReview(List<Map<String,Object>> runs) {
        var queued=new LinkedHashSet<String>();
        for(var run:runs)for(var row:rowsOf(run))if(Boolean.TRUE.equals(row.get("reviewRequired")))queued.add(String.valueOf(row.get("id")));
        var items=new ArrayList<Map<String,Object>>();
        for(String id:queued) {
            var observations=new ArrayList<Map<String,Object>>();var claimShapes=new HashSet<String>();
            Map<String,Object> original=null;
            for(var run:runs)for(var row:rowsOf(run))if(id.equals(String.valueOf(row.get("id")))) {
                if(original==null&&row.get("reviewItem") instanceof Map<?,?> item) {
                    original=new LinkedHashMap<>();for(var entry:item.entrySet())original.put(String.valueOf(entry.getKey()),entry.getValue());
                }
                var response=JSON.valueToTree(row.get("response"));var observation=new LinkedHashMap<String,Object>();
                observation.put("run",((Map<?,?>)run.get("summary")).get("run"));observation.put("actualClaims",response.path("claims"));
                observation.put("actualOverall",response.path("retrievalStatus").asText());observation.put("actualIntent",response.path("intent").asText());
                observation.put("rawFalseAllow",row.get("falseAllow"));observation.put("rawFalseReject",row.get("falseReject"));
                observation.put("rawAdjudicationRequired",row.get("adjudicationRequired"));
                observation.put("reviewReasons",row.get("reviewItem") instanceof Map<?,?> item?item.get("reasons"):List.of());
                observations.add(observation);claimShapes.add(response.path("claims").toString());
            }
            if(original==null)throw new IllegalStateException("Queued case has no review data");
            original.put("observations",observations);original.put("actualClaimsStable",claimShapes.size()==1);
            original.put("humanOpinion","");original.put("humanDecision","");items.add(original);
        }
        return Map.of("status",items.isEmpty()?"NO_AUTOMATIC_AMBIGUITY":"PENDING_HUMAN","runs",runs.size(),
                "threeRunsComplete",runs.size()==3,"cases",items,"boundary","AUTOMATIC_REVIEW_QUEUE_ONLY_NO_HUMAN_ADJUDICATION_HAS_OCCURRED");
    }
    static String reviewMarkdown(Map<String,Object> review) throws Exception {
        var text=new StringBuilder("# Conclusion gate human review queue\n\nStatus: ").append(review.get("status"))
                .append("\n\nRaw automatic FA/FR counts remain unchanged. Queued results are not human-confirmed; the automatic unambiguous subset is triage only.\n");
        for(var item:JSON.valueToTree(review.get("cases"))) {
            text.append("\n## ").append(item.path("id").asText()).append("\n\n");
            String body=JSON.writerWithDefaultPrettyPrinter().writeValueAsString(item);
            String fence="```";while(body.contains(fence))fence+="`";
            text.append(fence).append("json\n").append(body).append("\n").append(fence)
                    .append("\n\nHuman opinion: \n\nHuman decision: \n");
        }
        return text.toString();
    }
    private static double percentile(List<Map<String,Object>> rows,double p){double[] values=rows.stream().mapToDouble(r->((Number)r.get("latencyMs")).doubleValue()).sorted().toArray();return values.length==0?0:values[Math.max(0,(int)Math.ceil(values.length*p)-1)];}
    private static Map<String,String> sourceHashes(Path root)throws Exception{var hashes=new TreeMap<String,String>();try(var files=Files.walk(root.resolve("ai/src/main/java"))){for(var p:files.filter(f->f.toString().endsWith(".java")).toList())hashes.put(root.relativize(p).toString(),hash(Files.readAllBytes(p)));}
        for(String name:List.of("ai/src/main/resources/application.yml","ai/src/test/java/com/ai/guide/evaluation/retrieval/ClaimGateLiveEvaluationTest.java"))hashes.put(name,hash(Files.readAllBytes(root.resolve(name))));return hashes;}
    private static String config(String name,String fallback){String env=System.getenv(name);return env!=null?env:System.getProperty(name,fallback);}
    private static String normalize(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC).replaceAll("[\\s\\p{Z}]+","").toLowerCase(Locale.ROOT);}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void write(Path p,Object value)throws Exception{Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);}
}

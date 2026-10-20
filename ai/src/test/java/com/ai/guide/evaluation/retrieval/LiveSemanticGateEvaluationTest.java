package com.ai.guide.evaluation.retrieval;

import com.ai.guide.ScenicGuideApplication;
import com.ai.guide.common.config.ThreadPoolConfig;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.*;
import com.ai.guide.domain.rag.service.*;
import com.ai.guide.infrastructure.chat.model.SpringAiChatModelAdapter;
import com.ai.guide.infrastructure.rag.semantic.LlmSemanticEvidenceAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.reactivestreams.Publisher;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Legacy question-level evaluator; use ClaimGateLiveEvaluationTest for conclusion-level acceptance. */
@EnabledIfSystemProperty(named="semantic.live.enabled",matches="true")
class LiveSemanticGateEvaluationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @Test void measure() throws Exception {
        Path input=Path.of(System.getProperty("semantic.live.input"));
        Path out=Path.of(System.getProperty("semantic.live.output"));
        String expectedHash=System.getProperty("semantic.live.sha256");
        byte[] bytes=Files.readAllBytes(input); assertEquals(expectedHash,hash(bytes));
        var dataset=JSON.readTree(bytes);
        ScenicGuideApplication.loadDotEnv();
        String key=config("DEEPSEEK_API_KEY",""); assertFalse(key.isBlank(),"Configured model key unavailable");
        String base=config("DEEPSEEK_BASE_URL","https://api.deepseek.com");
        String modelName=config("DEEPSEEK_MODEL","deepseek-v4-flash");
        var client=new SpringAiChatModelAdapter(ChatClient.builder(new OpenAiChatModel(new OpenAiApi(base,key),
                OpenAiChatOptions.builder().withModel(modelName).withTemperature(0.0).withMaxTokens(8192).build())));
        List<Map<String,Object>> calls=Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> caseId=new AtomicReference<>("");
        LanguageModelPort journal=new LanguageModelPort() {
            public Publisher<String> stream(List<LanguageModelMessage> m){return client.stream(m);}
            public <T>T complete(List<LanguageModelMessage> m,Class<T> c){return client.complete(m,c);}
            public String completeText(List<LanguageModelMessage> m){
                String id=caseId.get(); long start=System.nanoTime(); var event=new LinkedHashMap<String,Object>();
                event.put("caseId",id);
                event.put("messages",m);
                try {String response=client.completeText(m);event.put("response",response);return response;}
                catch(RuntimeException failure){event.put("errorClass",failure.getClass().getSimpleName());throw failure;}
                finally {event.put("latencyMs",(System.nanoTime()-start)/1_000_000.0);calls.add(event);}
            }
            public com.ai.guide.domain.ai.model.LanguageModelCompletion completeMeasured(List<LanguageModelMessage> m,double temperature){
                var event=new LinkedHashMap<String,Object>();event.put("caseId",caseId.get());event.put("messages",m);event.put("requestedTemperature",temperature);
                long start=System.nanoTime();try{var result=client.completeMeasured(m,temperature);event.put("completion",result);return result;}
                catch(RuntimeException failure){event.put("errorClass",failure.getClass().getSimpleName());throw failure;}
                finally{event.put("latencyMs",(System.nanoTime()-start)/1_000_000.0);calls.add(event);}
            }
        };
        var executor=new ThreadPoolConfig().plannerLlmExecutor(2,4,16);
        var semantic=new LlmSemanticEvidenceAdapter(journal,JSON,executor,true,15000,8000);
        var rows=new ArrayList<Map<String,Object>>();
        var before=sourceHashes(); Files.createDirectories(out);
        write(out.resolve("freeze.json"),Map.of("datasetSha256",expectedHash,"productionHashes",before,"model",modelName));
        Path project=Path.of(System.getProperty("semantic.live.project",".."));
        for(String relative:before.keySet()) {
            Path target=out.resolve("frozen-source").resolve(relative);Files.createDirectories(target.getParent());
            Files.copy(project.resolve(relative),target);
        }
        int limit=Integer.getInteger("semantic.live.limit",dataset.path("cases").size());
        try {
            for(var c:dataset.path("cases")) {
                if(rows.size()>=limit) break;
                String id=c.path("id").asText(),city=c.path("city").asText();caseId.set(id);
                var fragments=new ArrayList<KnowledgeFragment>();
                for(var e:c.path("evidence")) fragments.add(new KnowledgeFragment(e.path("id").asText(),"CONTROLLED_SYNTHETIC",
                        e.path("title").asText()+"\n"+e.path("content").asText(),new KnowledgeScope("CITY",city)));
                var provider=mock(ScenicKnowledgePort.class);var local=mock(KnowledgeDocumentService.class);
                when(provider.searchScoped(anyString(),anyInt(),anyInt(),anyBoolean(),any())).thenReturn(fragments);
                var catalog=mock(com.ai.guide.domain.attraction.gateway.AttractionCatalogPort.class);
                when(catalog.findByCity(anyString(),isNull(),isNull())).thenReturn(List.of());
                var retrieval=new RagRetrievalService(provider,local,new RagQueryExpander(com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort.empty()),semantic,
                        new com.ai.guide.domain.attraction.service.AttractionIdentityResolver(catalog));
                long start=System.nanoTime();var response=retrieval.retrieve(c.path("query").asText(),city,Map.of(),false);
                var row=new LinkedHashMap<String,Object>(); row.put("id",id);row.put("category",c.path("category").asText());
                row.put("query",c.path("query").asText());row.put("expectedStatus",c.path("expectedStatus").asText());
                row.put("response",response);row.put("matched",c.path("expectedStatus").asText().equals(response.get("retrievalStatus")));
                row.put("latencyMs",(System.nanoTime()-start)/1_000_000.0);rows.add(row);
                System.out.println("SEMANTIC_EVAL "+id+" "+response.get("retrievalStatus")+" "+response.get("reasonCode"));
                write(out.resolve(id+".json"),row);
            }
        } finally {executor.shutdownNow();}
        assertEquals(expectedHash,hash(Files.readAllBytes(input))); assertEquals(before,sourceHashes());
        long unknown=rows.stream().filter(r->"UNKNOWN".equals(r.get("expectedStatus"))).count();
        long falseAllow=rows.stream().filter(r->"UNKNOWN".equals(r.get("expectedStatus"))&&!Boolean.TRUE.equals(r.get("matched"))).count();
        long falseReject=rows.stream().filter(r->"RELEVANT".equals(r.get("expectedStatus"))&&!Boolean.TRUE.equals(r.get("matched"))).count();
        var summary=new LinkedHashMap<String,Object>();summary.put("datasetSha256",expectedHash);summary.put("count",rows.size());
        summary.put("expectedUnknown",unknown);summary.put("falseAllow",falseAllow);summary.put("expectedAnswerable",rows.size()-unknown);summary.put("falseReject",falseReject);
        summary.put("model",modelName);summary.put("temperature",0.0);summary.put("recordedModelCalls",calls.size());
        summary.put("successfulModelResponses",calls.stream().filter(c->c.containsKey("response")||c.containsKey("completion")).count());
        summary.put("failedModelCalls",calls.stream().filter(c->c.containsKey("errorClass")).count());
        summary.put("tokens","PER_CALL_COMPLETION_METADATA_NOT_AGGREGATED_BY_LEGACY_HARNESS");
        summary.put("boundary","REAL_MODEL_PRESELECTED_SYNTHETIC_EVIDENCE_NOT_RETRIEVAL_OR_FINAL_ANSWER_BENCHMARK");
        summary.put("productionHashes",before);summary.put("completeDataset",rows.size()==dataset.path("cases").size());
        summary.put("thresholdMet",rows.size()==50&&unknown==25&&falseAllow<=2&&falseReject<=2);
        write(out.resolve("results.json"),Map.of("summary",summary,"cases",rows,"modelCalls",List.copyOf(calls)));
        write(out.resolve("summary.json"),summary);
    }
    static String config(String name,String fallback){String env=System.getenv(name);return env!=null?env:System.getProperty(name,fallback);}
    static Map<String,String> sourceHashes() throws Exception {
        Path root=Path.of(System.getProperty("semantic.live.project",".."));var result=new TreeMap<String,String>();
        try(var files=Files.walk(root.resolve("ai/src/main/java"))){for(Path p:files.filter(f->f.toString().endsWith(".java")).toList())result.put(root.relativize(p).toString(),hash(Files.readAllBytes(p)));}
        Path harness=root.resolve("ai/src/test/java/com/ai/guide/evaluation/retrieval/LiveSemanticGateEvaluationTest.java");
        result.put(root.relativize(harness).toString(),hash(Files.readAllBytes(harness)));
        return result;
    }
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static void write(Path p,Object value)throws Exception{Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardOpenOption.CREATE_NEW);}
}

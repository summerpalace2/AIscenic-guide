package com.ai.guide.evaluation;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.ai.model.*;
import com.ai.guide.infrastructure.chat.model.SpringAiChatModelAdapter;
import com.ai.guide.infrastructure.chat.model.MeasuredThinkingRequestInterceptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.reactivestreams.Publisher;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.*;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.gateway.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.planner.service.*;
import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Frozen independent author input; translation/scoring is explicit and never edits that input. */
@EnabledIfSystemProperty(named="continuation.holdout.enabled", matches="true")
class ContinuationHoldoutEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INPUT_SHA="8658bcad6334644074696fcba5ff150ba2edcdd23b204898a749fa33bc6e6699";
    private static final Set<String> MUTATORS=Set.of("create","update","claimAnonymous","linkFormalTrip",
            "deleteUnchangedUserSession","activatePendingFormalOpen","deleteExpiredPendingFormalOpens");
    private final List<Map<String,Object>> modelEvents=Collections.synchronizedList(new ArrayList<>());
    private final ThreadLocal<Integer> submittedRound=new ThreadLocal<>();
    private LanguageModelPort liveClient;
    private String providerKey;
    private int roundIndex;
    private String currentMode,currentId;
    private JsonNode input;

    @Test void measureFrozenIndependentCases() throws Exception {
        Path source=Path.of(System.getProperty("continuation.holdout.input","../docs/refactor/evidence/continuation-holdout-20261017/author-cases.json"));
        byte[] bytes=Files.readAllBytes(source);assertEquals(INPUT_SHA,sha(bytes));input=JSON.readTree(bytes);
        assertEquals(30,input.path("cases").size());
        String runId=System.getProperty("continuation.holdout.runId","holdout-"+UUID.randomUUID());
        assertTrue(runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"));
        Path out=Path.of("../docs/refactor/evidence/continuation-holdout-20261017",runId).toAbsolutePath().normalize();
        Files.createDirectory(out);
        String modesText=System.getProperty("continuation.holdout.modes","DISABLED,THROW,INVALID,TIMEOUT,LIVE");
        List<String> modes=Arrays.stream(modesText.split(",")).map(String::trim).toList();
        assertTrue(!modes.isEmpty()&&new HashSet<>(modes).size()==modes.size());
        assertTrue(Set.of("DISABLED","THROW","INVALID","TIMEOUT","LIVE").containsAll(modes));
        int passes=Integer.getInteger("continuation.holdout.passes",3);assertTrue(passes>=1&&passes<=3);
        if(modes.contains("LIVE"))initializeProvider();
        var before=sourceIdentity(bytes);writeNew(out.resolve("freeze.json"),before);
        var summaries=new ArrayList<Map<String,Object>>();
        for(String mode:modes)for(int pass=1;pass<=passes;pass++) {
            currentMode=mode;Path folder=out.resolve(mode.toLowerCase(Locale.ROOT)+"-"+pass);Files.createDirectory(folder);
            var rows=new ArrayList<Map<String,Object>>();int eventsBefore=modelEvents.size();
            for(JsonNode c:input.path("cases")) {
                currentId=c.path("id").asText();Map<String,Object> row;
                try {row=evaluate(c);}catch(Exception error) {row=new LinkedHashMap<>();row.put("id",currentId);row.put("exceptionClass",error.getClass().getName());row.put("exceptionStackHead",Arrays.stream(error.getStackTrace()).limit(8).map(StackTraceElement::toString).toList());row.put("strictSemanticPass",false);row.put("annotationStatus",c.path("expected").path("status").asText());}
                rows.add(row);writeNew(folder.resolve(currentId+".json"),row);
                System.out.println("CONTINUATION_HOLDOUT mode="+mode+" pass="+pass+" id="+currentId+" strict="+row.get("strictSemanticPass"));
            }
            var summary=summarize(mode,pass,rows,eventsBefore);summaries.add(summary);
            writeNew(folder.resolve("results.json"),Map.of("summary",summary,"cases",rows));
        }
        var after=sourceIdentity(Files.readAllBytes(source));writeNew(out.resolve("source-after.json"),after);
        writeNew(out.resolve("summary.json"),Map.of("datasetSha256",INPUT_SHA,"runs",summaries,"sourceStable",before.equals(after),
                "boundary","REAL_PREVIEW_NO_APPLY_SYNTHETIC_FIXTURE_AI_AUTHORED_NOT_HUMAN_ADJUDICATED","acceptanceStatus","PENDING_REVIEW"));
        assertEquals(before,after,"Sources changed during frozen measurement");
        for(var summary:summaries)assertEquals(true,summary.get("modeExercised"),"Mode was not actually exercised");
    }

    private Map<String,Object> evaluate(JsonNode c) throws Exception {
        var fixture=fixture(c);String original=JSON.writeValueAsString(fixture.trip());
        var authorPending=c.path("context").path("active_pending");String pendingId=null;
        Map<String,Object> translation=new LinkedHashMap<>();
        if(authorPending.isObject()) {
            var intent=translatePending(authorPending);translation.put("beforeRemember",intent.asMap());
            var pending=fixture.continuations().remember(fixture.sessionId(),authorPending.path("plan_version").asInt(8),intent);
            pendingId=pending.intentId();translation.put("afterRemember",pending.intent().asMap());
            translation.put("authorPendingId",authorPending.path("id").asText());translation.put("runtimePendingId",pendingId);
        }
        var binding=c.path("context").path("request_binding");
        if(binding.has("pending_id")&&!"pending-live".equals(binding.path("pending_id").asText()))pendingId=binding.path("pending_id").asText();
        var row=new LinkedHashMap<String,Object>();row.put("id",c.path("id").asText());row.put("query",c.path("query").asText());
        row.put("annotationStatus",c.path("expected").path("status").asText());row.put("sourceCase",c);row.put("translation",translation);
        Map<String,Object> body;int status;long started=System.nanoTime();int callStart=modelEvents.size();roundIndex=0;
        UserContext.set("continuation-holdout-owner","USER");
        try {
            List<String> requestPins=new ArrayList<>();if(binding.path("pins").isArray())binding.path("pins").forEach(p->requestPins.add(p.asText()));
            var page=PlanPageContext.builder().activeDay(null).pendingIntentId(pendingId).pinnedStopIds(requestPins).build();
            var result=fixture.service().preview(fixture.sessionId(),PlanConversationRequestDto.builder().message(c.path("query").asText())
                    .baseRevision(binding.path("plan_version").asInt(8)).context(page).build());
            body=result.body();status=result.status();
            row.put("actual",body);row.put("status",status);row.put("canApply",body.get("canApply"));row.put("feasible",body.get("feasible"));row.put("latencyMs",(System.nanoTime()-started)/1_000_000.0);
            var pending=fixture.continuations().pending(fixture.sessionId());row.put("pendingAfter",pending==null?Map.of():pending.intent().asMap());
            row.put("pendingIdAfter",pending==null?"":pending.intentId());
            var facts=new Facts(body,status,fixture.trip(),pending);
            List<Map<String,Object>> checks=new ArrayList<>();
            for(var check:c.path("expected").path("checks")) {
                String id=check.path("id").asText();Boolean resultCheck;
                if(id.equals("CH-30-A2")) resultCheck=Boolean.valueOf(original.equals(JSON.writeValueAsString(fixture.trip()))&&mutationCalls(fixture.repo())==0);
                else resultCheck=atomicCheck(id,facts);
                checks.add(check(id,check.path("assertion").asText(),resultCheck));
            }
            checks.add(check("NEXT_ACTION",c.path("expected").path("next_action").asText(),actionMatches(c.path("expected").path("next_action").asText(),facts)));
            var safety=new ArrayList<Map<String,Object>>();
            safety.add(check("NO_PERSISTENCE","Authoritative snapshot and version unchanged; no repository mutation",original.equals(JSON.writeValueAsString(fixture.trip()))&&mutationCalls(fixture.repo())==0));
            boolean explicitUnpin=Set.of("CH-20","CH-21").contains(c.path("id").asText());
            safety.add(check("SERVER_PIN_RETAINED","Stored pin remains; proposed pin and venue remain unless explicitly unpinned",explicitUnpin||facts.pinRetained()));
            Boolean bindingSafe=switch(c.path("id").asText()) {
                case "CH-13","CH-14" -> !facts.executableChange();
                case "CH-15","CH-29" -> null; // No public request history channel exists; do not pretend it was excited.
                default -> true;
            };
            safety.add(check("TASK_BINDING","No old plan/wrong pending/version bound to current task",bindingSafe));
            row.put("checks",checks);row.put("safety",safety);
            row.put("strictSemanticPass",checks.stream().allMatch(x->"PASS".equals(x.get("result"))));
            row.put("semanticFailCount",checks.stream().filter(x->"FAIL".equals(x.get("result"))).count());
            row.put("notObservableCount",checks.stream().filter(x->"NOT_OBSERVABLE".equals(x.get("result"))).count());
            row.put("repositoryMutationCalls",mutationCalls(fixture.repo()));
        } finally {UserContext.clear();fixture.close();}
        row.put("actualModelCallsThisCase",modelEvents.size()-callStart);
        row.put("executorCleaned",fixture.executor().isTerminated());
        return row;
    }
    private record Facts(Map<String,Object> body,int status,Map<String,Object> original,PendingIntentContinuations.Pending pending) {
        Map<String,Object> intent(){return map(body.get("intent"));}
        String operation(){return switch(text(intent().get("operation"))){
            case "REDUCE_DAY_DENSITY" -> "REDUCE_DENSITY";case "SUGGEST_REPLACEMENTS" -> "REPLACE_STOP";
            case "REPLAN_DAY_FOR_CONDITION" -> "REPLAN_DAY";default -> text(intent().get("operation"));};}
        int day(){return number(intent().get("dayNumber"),-1);}
        boolean clarify(){return Boolean.TRUE.equals(body.get("requiresClarification"));}
        boolean preview(){return body.get("proposedTrip") instanceof Map<?,?>;}
        Map<String,Object> proposed(){return map(body.get("proposedTrip"));}
        boolean changePreview(){return preview()&&(!changes().isEmpty()||!PlannerPins.read(original).equals(PlannerPins.read(proposed())));}
        boolean executableChange(){return changePreview()&&Boolean.TRUE.equals(body.get("canApply"));}
        List<Map<String,Object>> changes(){return maps(body.get("stopChanges"));}
        boolean unchangedOtherThan(Set<String> changed) {
            if(!preview())return false;
            var before=PlannerPins.stops(original);var after=PlannerPins.stops(proposed());
            for(var e:before.entrySet())if(!changed.contains(e.getKey())) {
                var next=after.get(e.getKey());if(next==null||!Objects.equals(e.getValue().get("venueId"),next.get("venueId")))return false;
            }
            return true;
        }
        boolean removedOnly(String id) {return preview()&&!PlannerPins.stops(proposed()).containsKey(id)&&unchangedOtherThan(Set.of(id));}
        boolean pinRetained(){return !preview()||PlannerPins.retained(original,proposed())&&PlannerPins.read(proposed()).contains("s-museum-d2");}
        boolean hasVenue(String name,int day) {if(!preview())return false;for(var d:maps(proposed().get("days")))if(number(d.get("day"),-1)==day)for(var s:maps(d.get("stops")))if(name.equals(s.get("name")))return true;return false;}
        int dayCount(int day,boolean after){for(var d:maps((after?proposed():original).get("days")))if(number(d.get("day"),-1)==day)return maps(d.get("stops")).size();return -1;}
        boolean pendingMissing(){return pending!=null&&pending.intent().isRequiresClarification();}
    }
    private static Boolean actionMatches(String expected,Facts f) {
        return switch(expected) {
            case "PREVIEW" -> f.preview();
            case "CLARIFY" -> f.clarify();
            case "REJECT_OR_CLARIFY" -> f.status()>=400||f.clarify();
            case "NO_MUTATION" -> !f.changePreview();
            case "CANCEL" -> f.pending()==null&&!f.executableChange()&&Boolean.TRUE.equals(f.body().get("cancelled"));
            case "CLARIFY_OR_NO_ACTION" -> !f.executableChange()&&!"REMOVE_STOP".equals(f.operation());
            default -> null;
        };
    }
    private static Boolean atomicCheck(String id,Facts f) {
        return switch(id) {
            case "CH-01-A1" -> f.operation().equals("REMOVE_STOP")&&f.day()==1;
            case "CH-01-A2","CH-04-A2","CH-16-A1","CH-30-A1" -> f.removedOnly("s-garden-d1");
            case "CH-02-A1","CH-27-A1" -> f.operation().equals("ADD_STOP")&&f.day()==3&&f.hasVenue("雾桥花房",3);
            case "CH-02-A2" -> !f.operation().equals("REPLAN_DAY")&&f.unchangedOtherThan(Set.of());
            case "CH-03-A1" -> f.operation().equals("REDUCE_DENSITY")&&f.day()==3&&number(f.intent().get("reduceCount"),-1)==2;
            case "CH-03-A2" -> f.preview()&&f.dayCount(3,false)-f.dayCount(3,true)==2;
            case "CH-04-A1" -> f.operation().equals("REMOVE_STOP")&&text(f.intent().get("targetStopReference")).contains("月影园");
            case "CH-05-A1","CH-10-A1" -> f.operation().equals("REPLACE_STOP")&&f.day()==3&&f.hasVenue("雾桥花房",3)&&!f.hasVenue("星湾湖",3);
            case "CH-05-A2" -> f.day()==3&&!f.operation().equals("ADD_STOP")&&f.preview()&&!f.hasVenue("星湾湖",3);
            case "CH-06-A1" -> f.pending()==null&&!f.executableChange();
            case "CH-06-A2","CH-07-A1","CH-15-A1" -> !f.executableChange()&&!f.operation().equals("REMOVE_STOP");
            case "CH-08-A1" -> !f.executableChange();
            case "CH-08-A2","CH-09-A2" -> f.pendingMissing();
            case "CH-09-A1" -> !f.executableChange()&&number(f.intent().get("reduceCount"),-1)!=2;
            case "CH-10-A2" -> f.preview()&&f.unchangedOtherThan(Set.of("s-lake-d3"));
            case "CH-11-A1" -> f.operation().equals("ADD_STOP")&&f.day()==1&&f.hasVenue("雾桥花房",1);
            case "CH-11-A2" -> f.preview()&&PlannerPins.stops(f.proposed()).containsKey("s-garden-d1");
            case "CH-12-A1" -> null; // Silent dropping a second intent has no deterministic public predicate here.
            case "CH-13-A1","CH-13-A2","CH-14-A1","CH-14-A2" -> !f.executableChange();
            case "CH-16-A2" -> !f.clarify();
            case "CH-17-A1" -> f.clarify()&&f.day()==2;
            case "CH-17-A2" -> !f.executableChange();
            case "CH-18-A1" -> f.preview()&&f.pinRetained();
            case "CH-18-A2","CH-19-A2" -> f.pinRetained();
            case "CH-19-A1" -> f.operation().equals("REDUCE_DENSITY")&&f.day()==2&&number(f.intent().get("reduceCount"),-1)==1;
            case "CH-20-A1" -> f.preview()&&!PlannerPins.read(f.proposed()).contains("s-museum-d2");
            case "CH-20-A2" -> f.preview()&&Objects.equals(f.original().get("days"),f.proposed().get("days"));
            case "CH-21-A1","CH-21-A2" -> f.preview()&&!PlannerPins.read(f.proposed()).contains("s-museum-d2")&&!PlannerPins.stops(f.proposed()).containsKey("s-museum-d2");
            case "CH-22-A1","CH-23-A1","CH-24-A2" -> f.status()>=400||f.clarify();
            case "CH-22-A2","CH-24-A1","CH-25-A1" -> !f.changePreview();
            case "CH-23-A2" -> (f.operation().equals("ADD_STOP")||f.pending()!=null&&f.pending().intent().getOperation().equals("ADD_STOP"))&&f.day()!=3;
            case "CH-26-A1" -> f.clarify()&&f.day()!=2&&f.day()!=3;
            case "CH-26-A2","CH-28-A2" -> !f.executableChange();
            case "CH-27-A2" -> !f.clarify()&&f.preview();
            case "CH-28-A1" -> f.clarify();
            case "CH-29-A1" -> f.operation().equals("REDUCE_DENSITY")&&f.day()==3&&number(f.intent().get("reduceCount"),-1)==1;
            case "CH-29-A2" -> null; // Old history has no public DTO channel; absence of injected history is not isolation proof.
            case "CH-30-A2" -> null; // Bound directly to measured snapshot/repository predicates in evaluate. Never hard-code PASS.
            default -> null;
        };
    }
    private PlanAdjustmentIntent translatePending(JsonNode p) {
        String op=switch(p.path("operation").asText()){case "REMOVE"->"REMOVE_STOP";case "ADD"->"ADD_STOP";case "REPLACE"->"REPLACE_STOP";case "REDUCE"->"REDUCE_DENSITY";default->"REPLAN_DAY";};
        var type=switch(op){case "REMOVE_STOP"->ConversationIntentType.REMOVE_STOP;case "ADD_STOP"->ConversationIntentType.ADD_STOP;case "REPLACE_STOP"->ConversationIntentType.SUGGEST_REPLACEMENTS;case "REDUCE_DENSITY"->ConversationIntentType.REDUCE_DAY_DENSITY;default->ConversationIntentType.REPLAN_DAY;};
        var missing=new ArrayList<String>();for(var m:p.path("missing"))missing.add(switch(m.asText()){case "date","valid_date"->"DAY_NUMBER";case "instance"->"TARGET_STOP";case "quantity"->"REDUCE_COUNT";case "target"->op.equals("ADD_STOP")?"TARGET_PLACE":"TARGET_STOP";default->m.asText();});
        Integer day=p.path("date").isTextual()?dateDay(p.path("date").asText()):null;
        String target=p.path("target").isTextual()?p.path("target").asText():null;
        return PlanAdjustmentIntent.builder().type(type).operation(op).scope(Set.of("ADD_STOP","REDUCE_DENSITY","REPLAN_DAY").contains(op)?"DAY":"STOP")
                .dayNumber(day).targetDayReference(p.path("date").isTextual()?p.path("date").asText():null)
                .targetStopReference(op.equals("ADD_STOP")?null:target).requestedVenueName(op.equals("ADD_STOP")?target:null)
                .replacementPlaceName(p.path("replacement").isTextual()?p.path("replacement").asText():null)
                .reduceCount(p.path("quantity").isNumber()?p.path("quantity").asInt():null).requiresClarification(true)
                .missingFields(missing).rawMessage(op+" "+(target==null?"":target)).build();
    }
    private Integer dateDay(String date){var dates=input.path("fixtures").path("three-day-plan").path("dates");for(int i=0;i<dates.size();i++)if(dates.get(i).asText().equals(date))return i+1;return null;}
    private record Fixture(PlanAdjustmentService service,PlannerSessionRepositoryPort repo,Map<String,Object> trip,String sessionId,
                           PendingIntentContinuations continuations,ExecutorService executor,CountDownLatch release) implements AutoCloseable {
        public void close(){if(release!=null)release.countDown();executor.shutdownNow();try{if(!executor.awaitTermination(15,TimeUnit.SECONDS))throw new IllegalStateException("Fixture executor did not stop");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    }
    private Fixture fixture(JsonNode c) {
        var author=input.path("fixtures").path("three-day-plan");String city="测试市";
        var places=new LinkedHashMap<String,Attraction>();int ordinal=0;
        var names=new LinkedHashSet<String>();author.path("stops").forEach(s->names.add(s.path("name").asText()));input.path("fixtures").path("fictional_places").forEach(n->names.add(n.asText()));
        for(String name:names){ordinal++;String key="fictional-"+ordinal;places.put(name,Attraction.builder().id(key).name(name).displayName(name).city(city).district("测试区")
                .category("公园").tags(List.of()).location("0.0,"+(ordinal*0.001)).summary("独立题集虚构地点").intro("隔离测试数据")
                .duration("45分钟").recommendedVisitMinutes(45).openingHours("00:00-23:59").build());}
        List<Attraction> catalogRows=new ArrayList<>(places.values());
        var catalog=new AttractionService(new AttractionCatalogPort(){
            public List<Attraction> find(String district,String category){return catalogRows;}
            public Attraction findById(String id){return catalogRows.stream().filter(a->a.getId().equals(id)).findFirst().orElse(null);}
            public List<Attraction> findByCity(String requested,String district,String category){return city.equals(requested)?catalogRows:List.of();}
        });
        var taxonomy=new JsonTaxonomyAdapter();var aliases=new AttractionAliasMatcher(catalog,taxonomy);var classifier=new ConversationIntentClassifier(catalog,aliases);
        classifier.setTaxonomy(taxonomy);classifier.setDiningCategoryGrounder(new DiningCategoryGrounder(taxonomy));
        var verifier=new PlanVerifier(taxonomy);var repairer=new LocalPlanRepairer(new PoiCategoryPolicyRegistry(taxonomy));repairer.setTaxonomy(taxonomy);
        var costs=new RouteCostProvider(null,taxonomy);var routes=new RouteAwarePlanner(costs,verifier,repairer,null,null,taxonomy);
        var builder=new ItineraryBuilder(catalog,routes,verifier,repairer,new PlanNarrativeService(taxonomy),aliases);
        var days=new ArrayList<Map<String,Object>>();var pins=new ArrayList<String>();
        for(int day=1;day<=3;day++){
            var stops=new ArrayList<Map<String,Object>>();String date=author.path("dates").get(day-1).asText();
            for(var s:author.path("stops"))if(date.equals(s.path("date").asText())){
                stops.add(builder.createStop(places.get(s.path("name").asText()),s.path("id").asText(),List.of("09:00","12:00","15:00").get(stops.size()),"45分钟",null,null));
                if(s.path("fixed").asBoolean())pins.add(s.path("id").asText());
            }
            days.add(new LinkedHashMap<>(Map.of("day",day,"date",date,"stops",stops)));
        }
        var trip=new LinkedHashMap<String,Object>(Map.of("id",author.path("id").asText(),"city",city,"version",8,"days",days,"pinnedStopIds",pins));
        String sessionId="plan-current-"+currentMode+"-"+c.path("id").asText();
        var constraints=TravelConstraints.builder().destination(city).durationDays(3).build().asMap();
        var stored=new PlannerSessionRepositoryPort.StoredSession(sessionId,"USER","continuation-holdout-owner","","","",constraints,AppliedPreferencesSnapshot.empty(),trip,8,8,"DRAFT",1,1);
        PlannerSessionRepositoryPort repo=mock(PlannerSessionRepositoryPort.class,call->{if(MUTATORS.contains(call.getMethod().getName()))throw new IllegalStateException("Preview wrote repository");return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);});
        when(repo.find(sessionId,"USER","continuation-holdout-owner","")).thenReturn(stored);
        CountDownLatch release="TIMEOUT".equals(currentMode)?new CountDownLatch(1):null;
        String mode=currentMode,id=currentId;
        LanguageModelPort model=new LanguageModelPort(){
            public Publisher<String> stream(List<LanguageModelMessage> m){throw new UnsupportedOperationException();}
            public <T>T complete(List<LanguageModelMessage> m,Class<T> t){throw new UnsupportedOperationException();}
            public String completeText(List<LanguageModelMessage> messages){
                var event=new LinkedHashMap<String,Object>();event.put("caseId",id);event.put("mode",mode);modelEvents.add(event);long start=System.nanoTime();
                try {
                    if(mode.equals("THROW"))throw new IllegalStateException("Controlled provider failure");
                    if(mode.equals("INVALID"))return "not structured JSON";
                    if(mode.equals("TIMEOUT")){try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Controlled interruption");}return "cleanup";}
                    if(!mode.equals("LIVE"))throw new IllegalStateException("Disabled model unexpectedly called");
                    var completion=liveClient.completeMeasured(messages,0.0);event.put("completion",completion);return completion.text();
                }catch(RuntimeException e){event.put("errorClass",e.getClass().getName());throw e;}finally{event.put("wallMs",(System.nanoTime()-start)/1_000_000.0);}
            }
        };
        var executor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"continuation-holdout-model");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        var extractor=new LlmIntentExtractor(model,JSON,classifier,!mode.equals("DISABLED"),0.75,mode.equals("TIMEOUT")?500:3500,mode.equals("LIVE")?providerKey:"controlled-test-placeholder",executor);
        var service=new PlanAdjustmentService(repo,builder,catalog,costs,verifier,routes,new CaffeineRedisProposalRepository(),extractor,classifier,JSON,new PlannerMetricsLogger());
        service.setDiningScheduling(taxonomy,new MealGapSolver(costs,new ItineraryTimeEngine()),null);
        var state=(PlannerConversationStatePort)ReflectionTestUtils.getField(service,"conversationState");assertNotNull(state);
        var continuations=new PendingIntentContinuations(classifier,System::currentTimeMillis,state);ReflectionTestUtils.setField(service,"continuations",continuations);
        return new Fixture(service,repo,trip,sessionId,continuations,executor,release);
    }
    private Map<String,Object> summarize(String mode,int pass,List<Map<String,Object>> rows,int eventsBefore) {
        var ready=rows.stream().filter(r->"READY".equals(r.get("annotationStatus"))).toList();
        List<Map<String,Object>> events; synchronized(modelEvents){events=List.copyOf(modelEvents.subList(eventsBefore,modelEvents.size()));}
        var reasons=new TreeMap<String,Long>();for(var r:rows){var b=map(r.get("actual"));String reason=text(b.getOrDefault("fallbackReason",b.getOrDefault("llmFallbackReason","NOT_RETURNED")));reasons.merge(reason,1L,Long::sum);}
        String reason=switch(mode){case "DISABLED"->"FEATURE_DISABLED";case "THROW"->"PROVIDER_ERROR";case "INVALID"->"INVALID_OUTPUT";case "TIMEOUT"->"TIMEOUT";default->"";};
        boolean exercised=mode.equals("DISABLED")?events.isEmpty():mode.equals("LIVE")?events.stream().anyMatch(e->e.containsKey("completion")):!events.isEmpty()&&reasons.getOrDefault(reason,0L)>0;
        var summary=new LinkedHashMap<String,Object>();summary.put("mode",mode);summary.put("pass",pass);summary.put("caseCount",rows.size());summary.put("readyCount",ready.size());
        summary.put("strictSemanticPasses",ready.stream().filter(r->Boolean.TRUE.equals(r.get("strictSemanticPass"))).count());
        summary.put("semanticFailureCases",ready.stream().filter(r->number(r.get("semanticFailCount"),0)>0).count());
        summary.put("notObservableCases",ready.stream().filter(r->number(r.get("notObservableCount"),0)>0).count());
        summary.put("safetyFailureCases",rows.stream().filter(r->maps(r.get("safety")).stream().anyMatch(s->"FAIL".equals(s.get("result")))).count());
        summary.put("safetyNotObservableCases",rows.stream().filter(r->maps(r.get("safety")).stream().anyMatch(s->"NOT_OBSERVABLE".equals(s.get("result")))).count());
        summary.put("exceptionCases",rows.stream().filter(r->r.containsKey("exceptionClass")).count());summary.put("fallbackReasons",reasons);summary.put("modelEvents",events);summary.put("actualLogicalModelCalls",events.size());summary.put("modeExercised",exercised);
        summary.put("sdkTotalTokens",events.stream().map(e->e.get("completion")).filter(LanguageModelCompletion.class::isInstance).map(LanguageModelCompletion.class::cast).map(LanguageModelCompletion::usage).map(LanguageModelUsage::totalTokens).filter(Objects::nonNull).mapToLong(Long::longValue).sum());
        return summary;
    }
    private Map<String,Object> sourceIdentity(byte[] bytes)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");for(Path dir:List.of(Path.of("src/main/java"),Path.of("src/main/resources")))try(var paths=Files.walk(dir)){
            for(var p:paths.filter(Files::isRegularFile).sorted().toList()){digest.update(p.toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(p));digest.update((byte)0);}}
        return Map.of("datasetSha256",sha(bytes),"productionTreeSha256",HexFormat.of().formatHex(digest.digest()),"harnessSha256",sha(Files.readAllBytes(Path.of("src/test/java/com/ai/guide/evaluation/ContinuationHoldoutEvaluationTest.java"))));
    }
    private static Map<String,Object> check(String id,String assertion,Boolean value){return Map.of("id",id,"assertion",assertion,"result",value==null?"NOT_OBSERVABLE":value?"PASS":"FAIL");}
    private static long mutationCalls(PlannerSessionRepositoryPort r){return mockingDetails(r).getInvocations().stream().filter(i->MUTATORS.contains(i.getMethod().getName())).count();}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static void writeNew(Path p,Object value)throws Exception{Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object x){return x instanceof Map<?,?>?(Map<String,Object>)x:Map.of();}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> maps(Object x){return x instanceof List<?>?(List<Map<String,Object>>)x:List.of();}
    private static int number(Object x,int fallback){return x instanceof Number n?n.intValue():fallback;}
    private static String text(Object x){return x==null?"":x.toString();}
    private void initializeProvider() throws Exception {
        // Read only supplier keys; never load arbitrary .env settings into global JVM properties.
        Map<String,String> supplier = new HashMap<>();
        Path env = Path.of(System.getProperty("continuation.live.env", ".env"));
        if (Files.exists(env)) for (String raw : Files.readAllLines(env,StandardCharsets.UTF_8)) {
            String line = raw.replace("﻿", "").trim();
            int equals = line.indexOf('='); if(equals <= 0 || line.startsWith("#")) continue;
            String key = line.substring(0,equals).trim();
            if(!Set.of("DEEPSEEK_API_KEY","DEEPSEEK_MODEL","DEEPSEEK_BASE_URL").contains(key)) continue;
            String value = line.substring(equals+1).trim();
            if(value.length()>=2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) value=value.substring(1,value.length()-1);
            else {int comment=value.indexOf(" #");if(comment>=0)value=value.substring(0,comment).trim();}
            supplier.put(key,value);
        }
        providerKey = config("DEEPSEEK_API_KEY",supplier.getOrDefault("DEEPSEEK_API_KEY",""));
        assertFalse(providerKey.isBlank(),"LIVE supplier credentials unavailable");
        String model = config("DEEPSEEK_MODEL",supplier.getOrDefault("DEEPSEEK_MODEL","deepseek-v4-flash"));
        String base = config("DEEPSEEK_BASE_URL",supplier.getOrDefault("DEEPSEEK_BASE_URL","https://api.deepseek.com"));
        String completionPath = System.getProperty("spring.ai.openai.chat.completions-path","/v1/chat/completions");
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
        factory.setReadTimeout(java.time.Duration.ofSeconds(10));
        var rest = RestClient.builder().requestFactory(factory).requestInterceptor(new MeasuredThinkingRequestInterceptor(base,completionPath));
        liveClient = new SpringAiChatModelAdapter(ChatClient.builder(new OpenAiChatModel(
                new OpenAiApi(base,providerKey,completionPath,"/v1/embeddings",rest,WebClient.builder(),org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER),
                OpenAiChatOptions.builder().withModel(model).withTemperature(0.0).withMaxTokens(4096).build())),true);
    }
    private static String config(String key,String fallback) {
        String env=System.getenv(key);return env!=null?env:System.getProperty(key,fallback);
    }
}

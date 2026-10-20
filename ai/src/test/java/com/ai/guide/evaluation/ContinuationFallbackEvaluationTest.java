package com.ai.guide.evaluation;

import com.ai.guide.common.context.UserContext;
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

/** Opt-in measurement: quality mismatches are retained; JUnit success is not product acceptance. */
@EnabledIfSystemProperty(named="continuation.evaluation.enabled", matches="true")
class ContinuationFallbackEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RESOURCE = "/ai-evaluation/continuation-fallback-cases-v2.json";
    private static final Set<String> MUTATORS = Set.of("create", "update", "claimAnonymous", "linkFormalTrip",
            "deleteUnchangedUserSession", "activatePendingFormalOpen", "deleteExpiredPendingFormalOpens");
    private static final Set<String> EXPECTED_FIELDS = Set.of("operation", "dayNumber", "requiresClarification",
            "missingFields", "route", "targetStopId", "replacementPlaceNameContains", "requestedVenueNameContains",
            "targetAttractionId", "reduceCount", "targetStartMinute", "targetEndMinute", "serverPendingOperationNot", "extractionSourceNot", "status");
    private final String modelMode = System.getProperty("continuation.evaluation.modelMode", "DISABLED")
            .toUpperCase(Locale.ROOT);

    @Test void measureThirtyRealPreviewConversationsWithControlledModelMode() throws Exception {
        assertTrue(Set.of("DISABLED", "THROW", "INVALID", "TIMEOUT").contains(modelMode), "invalid controlled model mode");
        String runId = System.getProperty("continuation.evaluation.runId", "baseline-" + UUID.randomUUID());
        assertTrue(runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "invalid runId");
        Path output = Path.of("..", "docs", "refactor", "evidence", "continuation-fallback-20261017", runId)
                .toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.createDirectory(output); // Never overwrite a prior run.
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(in); bytes = in.readAllBytes();
        }
        Map<String,Object> dataset = JSON.readValue(bytes, new TypeReference<>() {});
        assertEquals("continuation-fallback-v2", dataset.get("schemaVersion"));
        List<Map<String,Object>> rows = maps(dataset.get("cases"));
        assertEquals(30, rows.size());
        assertEquals(30, rows.stream().map(r -> r.get("id")).distinct().count());
        Map<String,Long> groups = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                r -> text(r.get("group")), TreeMap::new, java.util.stream.Collectors.counting()));
        assertEquals(6, groups.size()); assertTrue(groups.values().stream().allMatch(n -> n == 5));
        Map<String,Object> before = sourceIdentity(bytes);
        writeNew(output.resolve("source-before.json"), before);
        List<Map<String,Object>> results = new ArrayList<>();
        for (Map<String,Object> row : rows) {
            assertTrue(Set.of("AGENT_PROVISIONAL", "PENDING_LABEL").contains(text(row.get("labelStatus"))));
            List<Map<String,Object>> steps = maps(row.get("turns"));
            assertTrue(steps.size() >= 2 && steps.size() <= 3);
            for (var step : steps) {
                Map<String,Object> expected = map(step.get("expected"));
                assertFalse(text(expected.get("operation")).isBlank());
                assertTrue(EXPECTED_FIELDS.containsAll(expected.keySet()), "unrecognized label field");
                assertFalse(expected.containsValue(null), "null expected value");
            }
            Map<String,Object> result;
            try { result = evaluate(row); }
            catch (Exception error) {
                result = new LinkedHashMap<>(); result.put("id", row.get("id")); result.put("input", row);
                result.put("status", "FIXTURE_OR_COMPONENT_EXCEPTION"); result.put("intentContractMatch", false);
                result.put("error", errorMap(error)); result.put("labelStatus", row.get("labelStatus"));
            }
            results.add(result); writeNew(output.resolve(text(row.get("id")) + ".json"), result);
        }
        Map<String,Object> after = sourceIdentity(bytes);
        writeNew(output.resolve("source-after.json"), after);
        List<Map<String,Object>> labeled = results.stream().filter(r -> !"PENDING_LABEL".equals(r.get("labelStatus"))).toList();
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "continuation-fallback-results-v2"); report.put("runId", runId);
        report.put("generatedAt", Instant.now().toString()); report.put("datasetSha256", sha(bytes));
        report.put("sourceStable", before.equals(after)); report.put("llmEnabled", !modelMode.equals("DISABLED"));
        report.put("modelMode", modelMode); report.put("controlledProviderOnly", true);
        report.put("modelCallAssertion", "Zero calls required only in DISABLED; controlled failure modes record actual calls without asserting positive counts");
        report.put("measurement", "Actual PlanAdjustmentService.preview, 2 or 3 rounds; disabled or controlled failing mock provider; bundled catalog; no apply/save/commit");
        report.put("primaryMetric", "Intent-field and preview-safety contract; not business execution success");
        report.put("businessExecutionSuccess", "NOT_SCORED_NO_APPLY");
        report.put("limitations", List.of("Agent provisional labels; no human adjudication", "No browser, HTTP, Redis or multi-instance coverage",
                "Pending labels excluded from accuracy, never counted as passes", "Provider faults are controlled mocks, never real supplier availability",
                "TIMEOUT uses the existing minimum 500ms extractor budget and a releasable latch; cleanup does not prove provider-side cancellation or billing termination",
                "Controllable TTL clock injected into production continuation component; no wall-clock waiting",
                "Catalog fixture preserves source coordinates; route costs are estimates, not supplier verified"));
        report.put("caseCount", results.size()); report.put("labeledCount", labeled.size());
        report.put("pendingLabelCount", results.size() - labeled.size());
        report.put("exceptionCaseCount", results.stream().filter(r -> r.containsKey("error") || maps(r.get("rounds")).stream()
                .anyMatch(round -> map(round.get("actual")).containsKey("error"))).count());
        report.put("intentContractMatches", labeled.stream().filter(r -> Boolean.TRUE.equals(r.get("intentContractMatch"))).count());
        report.put("initialOperationMatches", labeled.stream().filter(r -> Boolean.TRUE.equals(r.get("initialOperationMatch"))).count());
        report.put("continuationMatches", labeled.stream().filter(r -> Boolean.TRUE.equals(r.get("continuationMatch"))).count());
        var recognizedInitial = labeled.stream().filter(r -> Boolean.TRUE.equals(r.get("initialOperationMatch"))).toList();
        report.put("continuationDenominatorAfterRecognizedInitial", recognizedInitial.size());
        report.put("continuationMatchesAfterRecognizedInitial", recognizedInitial.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("continuationMatch"))).count());
        report.put("actualChangePreviewCount", results.stream().flatMap(r -> maps(r.get("rounds")).stream())
                .filter(r -> "ACTUAL_CHANGE".equals(map(r.get("actual")).get("previewChangeState"))).count());
        report.put("noChangePreviewCount", results.stream().flatMap(r -> maps(r.get("rounds")).stream())
                .filter(r -> "NO_CHANGE".equals(map(r.get("actual")).get("previewChangeState"))).count());
        long actualModelCalls = results.stream().mapToLong(r -> maps(r.get("rounds")).stream()
                .mapToLong(round -> number(map(round.get("actual")).get("modelCalls"), 0)).max().orElse(0)).sum();
        Map<String,Long> fallbackReasons = results.stream().flatMap(r -> maps(r.get("rounds")).stream())
                .collect(java.util.stream.Collectors.groupingBy(round -> text(map(round.get("actual"))
                        .getOrDefault("fallbackReason", "NOT_RETURNED")), TreeMap::new, java.util.stream.Collectors.counting()));
        String expectedReason = switch (modelMode) {
            case "THROW" -> "PROVIDER_ERROR"; case "INVALID" -> "INVALID_OUTPUT"; case "TIMEOUT" -> "TIMEOUT";
            default -> "FEATURE_DISABLED";
        };
        boolean exercised = modelMode.equals("DISABLED") ? actualModelCalls == 0
                : actualModelCalls > 0 && fallbackReasons.getOrDefault(expectedReason, 0L) > 0;
        report.put("actualModelCalls", actualModelCalls); report.put("fallbackReasonCounts", fallbackReasons);
        report.put("expectedControlledFallbackReason", expectedReason); report.put("modelModeExercised", exercised);
        report.put("cases", results); writeNew(output.resolve("results.json"), report);
        assertEquals(before, after, "Sources changed while measuring; preserve this run but do not use as a stable baseline");
        assertTrue(exercised, "Model mode was not actually exercised; the mode label alone is not fault-injection evidence");
    }

    private Map<String,Object> evaluate(Map<String,Object> row) throws Exception {
        var fixture = fixture(text(row.get("city")), text(row.get("id")));
        List<Map<String,Object>> rounds = new ArrayList<>(); String pendingId = null;
        String before = JSON.writeValueAsString(fixture.trip());
        UserContext.set("continuation-evaluation-owner", "USER");
        try {
            int index = 0;
            for (Map<String,Object> step : maps(row.get("turns"))) {
                long started = System.nanoTime();
                int callsBefore = mockingDetails(fixture.model()).getInvocations().size();
                fixture.clock().addAndGet(number(step.get("advanceClockMs"), 0));
                String message = substitute(text(step.get("message")), fixture.names());
                String suppliedPending = "WRONG".equals(step.get("pendingIdMode")) ? "wrong-pending-id" : pendingId;
                Map<String,Object> actual = new LinkedHashMap<>();
                try {
                    var page = PlanPageContext.builder().activeDay(1).pendingIntentId(suppliedPending)
                            .pinnedStopIds(index == 0 ? List.of("d1-s3") : List.of()).build();
                    var response = fixture.service().preview(fixture.sessionId(), PlanConversationRequestDto.builder()
                            .message(message).baseRevision((int) number(step.get("baseRevision"), 1)).context(page).build());
                    Map<String,Object> body = response.body(), intent = map(body.get("intent"));
                    actual.put("status", response.status()); actual.put("operation", intent.isEmpty() ? "NO_INTENT" : intent.get("operation"));
                    actual.put("dayNumber", intent.get("dayNumber")); actual.put("requiresClarification", body.getOrDefault("requiresClarification", false));
                    actual.put("missingFields", intent.getOrDefault("missingFields", List.of()));
                    actual.putAll(intent); actual.put("extractionSource", body.getOrDefault("intentExtractionSource", "NOT_RETURNED"));
                    actual.put("fallbackReason", body.getOrDefault("fallbackReason", body.getOrDefault("llmFallbackReason", "NOT_RETURNED")));
                    actual.put("route", response.status() >= 400 ? "ERROR" : Boolean.TRUE.equals(body.get("requiresClarification"))
                            ? "CLARIFICATION" : body.containsKey("proposedTrip") ? "PREVIEW" : "QA".equals(intent.get("operation")) ? "ANSWER" : "OTHER");
                    actual.put("body", body); pendingId = nullableText(map(body.get("pendingIntent")).get("intentId"));
                    boolean hasPreview = body.containsKey("proposedTrip");
                    boolean changeMeasured = hasPreview && body.get("stopChanges") instanceof Collection<?>;
                    Boolean actualChange = changeMeasured ? !((Collection<?>) body.get("stopChanges")).isEmpty() : null;
                    actual.put("actualChange", actualChange);
                    actual.put("previewChangeState", !hasPreview ? "NOT_PREVIEW" : !changeMeasured ? "UNMEASURED"
                            : Boolean.TRUE.equals(actualChange) ? "ACTUAL_CHANGE" : "NO_CHANGE");
                    actual.put("previewChangeMeasurement", changeMeasured ? "PUBLIC_STOP_CHANGES" : "NOT_MEASURED");
                    actual.put("previewCanApply", body.get("canApply")); actual.put("previewFeasible", body.get("feasible"));
                    var pending = fixture.continuations().pending(fixture.sessionId());
                    actual.put("serverPendingOperation", pending == null ? "NONE" : pending.intent().getOperation());
                    actual.put("serverPendingMissingFields", pending == null ? List.of() : pending.intent().getMissingFields());
                    Map<String,Object> protectedParameters = new LinkedHashMap<>();
                    for (String field : List.of("pinnedStopIds", "preserveReferences", "preferences", "conditions", "targetStartMinute", "targetEndMinute"))
                        protectedParameters.put(field, intent.get(field));
                    actual.put("protectedParameters", protectedParameters);
                    actual.put("proposedPinnedStopRetained", !body.containsKey("proposedTrip") || allStopIds(map(body.get("proposedTrip"))).contains("d1-s3"));
                } catch (Exception error) {
                    actual.put("operation", "EXCEPTION"); actual.put("error", errorMap(error)); actual.put("route", "EXCEPTION");
                }
                actual.put("authoritativeTripUnchanged", before.equals(JSON.writeValueAsString(fixture.trip())));
                actual.put("repositoryMutationCalls", mutationCalls(fixture.repo()));
                actual.put("modelCalls", mockingDetails(fixture.model()).getInvocations().size());
                actual.put("modelCallsThisRound", mockingDetails(fixture.model()).getInvocations().size() - callsBefore);
                actual.put("modelMode", modelMode); actual.put("controlledProviderOnly", true);
                Map<String,Object> expected = substituteMap(map(step.get("expected")), fixture.names());
                Map<String,Object> checks = check(expected, actual);
                addCheck(checks, "authoritativeTripUnchanged", true, actual.get("authoritativeTripUnchanged"));
                addCheck(checks, "repositoryMutationCalls", 0L, actual.get("repositoryMutationCalls"));
                if (modelMode.equals("DISABLED")) addCheck(checks, "modelCalls", 0, actual.get("modelCalls"));
                addCheck(checks, "proposedPinnedStopRetained", true, actual.getOrDefault("proposedPinnedStopRetained", false));
                if ("PENDING_INTENT".equals(actual.get("extractionSource")))
                    actual.put("publicIntentPinReportedDiagnostic",
                            actual.get("pinnedStopIds") instanceof Collection<?> pins && pins.contains("d1-s3"));
                Map<String,Object> round = new LinkedHashMap<>(); round.put("message", message); round.put("expected", expected);
                round.put("actual", actual); round.put("checks", checks); round.put("matched", matched(checks));
                round.put("latencyMs", (System.nanoTime() - started) / 1_000_000.0); rounds.add(round); index++;
            }
        } finally { UserContext.clear(); fixture.close(); }
        Map<String,Object> result = new LinkedHashMap<>(); result.put("id", row.get("id")); result.put("group", row.get("group"));
        result.put("input", row); result.put("labelStatus", row.get("labelStatus")); result.put("rounds", rounds);
        result.put("controlledExecutorCleaned", fixture.modelExecutor() == null || fixture.modelExecutor().isTerminated());
        result.put("initialOperationMatch", Objects.equals(map(rounds.get(0).get("expected")).get("operation"), map(rounds.get(0).get("actual")).get("operation")));
        result.put("continuationMatch", rounds.subList(1, rounds.size()).stream().allMatch(r -> Boolean.TRUE.equals(r.get("matched"))));
        result.put("intentContractMatch", rounds.stream().allMatch(r -> Boolean.TRUE.equals(r.get("matched"))));
        result.put("status", "PENDING_LABEL".equals(row.get("labelStatus")) ? "PENDING_LABEL"
                : Boolean.TRUE.equals(result.get("intentContractMatch")) ? "INTENT_CONTRACT_MATCH" : "INTENT_CONTRACT_MISMATCH");
        return result;
    }

    private record Fixture(PlanAdjustmentService service, PlannerSessionRepositoryPort repo, Map<String,Object> trip,
                           String sessionId, Map<String,String> names, AtomicLong clock, PendingIntentContinuations continuations,
                           LanguageModelPort model, ExecutorService modelExecutor, CountDownLatch release) implements AutoCloseable {
        @Override public void close() {
            if (release != null) release.countDown();
            if (modelExecutor != null) {
                modelExecutor.shutdownNow();
                try {
                    if (!modelExecutor.awaitTermination(1, TimeUnit.SECONDS)) throw new IllegalStateException("Controlled model executor did not stop");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("Controlled model cleanup interrupted", error);
                }
            }
        }
    }
    private Fixture fixture(String city, String id) throws Exception {
        List<Attraction> places;
        try (InputStream input = getClass().getResourceAsStream("/attractions.json")) {
            assertNotNull(input);
            places = JSON.readValue(input, new TypeReference<List<Attraction>>() {});
        }
        places.forEach(a -> { if (a.getCity() == null || a.getCity().isBlank()) a.setCity("重庆"); });
        List<Attraction> selected = places.stream().filter(a -> city.equals(a.getCity())).limit(4).toList();
        assertEquals(4, selected.size(), "fixture requires four unchanged catalog rows");
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            public List<Attraction> find(String district, String category) { return places.stream().filter(a -> district == null || district.equals(a.getDistrict())).filter(a -> category == null || category.equals(a.getCategory())).toList(); }
            public Attraction findById(String venueId) { return places.stream().filter(a -> Objects.equals(venueId, a.getId())).findFirst().orElse(null); }
            public List<Attraction> findByCity(String c, String district, String category) { return find(district, category).stream().filter(a -> Objects.equals(c, a.getCity())).toList(); }
        });
        var taxonomy = new JsonTaxonomyAdapter();
        var aliases = new AttractionAliasMatcher(catalog, taxonomy);
        var classifier = new ConversationIntentClassifier(catalog, aliases);
        classifier.setTaxonomy(taxonomy); classifier.setDiningCategoryGrounder(new DiningCategoryGrounder(taxonomy));
        var verifier = new PlanVerifier(taxonomy); var repairer = new LocalPlanRepairer(new PoiCategoryPolicyRegistry(taxonomy)); repairer.setTaxonomy(taxonomy);
        var costs = new RouteCostProvider(null, taxonomy);
        var routes = new RouteAwarePlanner(costs, verifier, repairer, null, null, taxonomy);
        var builder = new ItineraryBuilder(catalog, routes, verifier, repairer, new PlanNarrativeService(taxonomy), aliases);
        List<Map<String,Object>> days = new ArrayList<>();
        for (int day = 1; day <= 3; day++) {
            List<Map<String,Object>> stops = new ArrayList<>();
            for (int n = 1; n <= 3; n++) stops.add(builder.createStop(selected.get(n-1), "d"+day+"-s"+n,
                    List.of("10:00", "13:00", "16:00").get(n-1), "60分钟", null, null));
            days.add(new LinkedHashMap<>(Map.of("day", day, "date", "2026-10-"+(19+day), "stops", stops)));
        }
        Map<String,Object> trip = new LinkedHashMap<>(Map.of("city", city, "version", 1, "days", days));
        var constraints = TravelConstraints.builder().destination(city).durationDays(3).build().asMap();
        String sessionId = "continuation-evaluation-"+id;
        var session = new PlannerSessionRepositoryPort.StoredSession(sessionId, "USER", "continuation-evaluation-owner", "", "", "",
                constraints, AppliedPreferencesSnapshot.empty(), trip, 1, 1, "DRAFT", 1, 1);
        PlannerSessionRepositoryPort repo = mock(PlannerSessionRepositoryPort.class, call -> {
            if (MUTATORS.contains(call.getMethod().getName())) throw new IllegalStateException("Evaluation forbids repository mutation: "+call.getMethod().getName());
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        when(repo.find(sessionId, "USER", "continuation-evaluation-owner", "")).thenReturn(session);
        LanguageModelPort model = mock(LanguageModelPort.class);
        ExecutorService modelExecutor = null;
        CountDownLatch release = modelMode.equals("TIMEOUT") ? new CountDownLatch(1) : null;
        if (modelMode.equals("THROW"))
            when(model.completeText(anyList())).thenThrow(new IllegalStateException("Controlled provider failure"));
        else if (modelMode.equals("INVALID")) when(model.completeText(anyList())).thenReturn("not valid structured JSON");
        else if (modelMode.equals("TIMEOUT")) {
            when(model.completeText(anyList())).thenAnswer(call -> {
                release.await(); return "controlled timeout task released during cleanup";
            });
            modelExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "continuation-evaluation-timeout"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
        Executor executor = modelExecutor == null ? Runnable::run : modelExecutor;
        boolean enabled = !modelMode.equals("DISABLED");
        var extractor = new LlmIntentExtractor(model, JSON, classifier, enabled, 0.75,
                modelMode.equals("TIMEOUT") ? 500 : 3500, enabled ? "isolated-test-placeholder" : "", executor);
        var service = new PlanAdjustmentService(repo, builder, catalog, costs, verifier, routes,
                new CaffeineRedisProposalRepository(), extractor, classifier, JSON, new PlannerMetricsLogger());
        service.setDiningScheduling(taxonomy, new MealGapSolver(costs, new ItineraryTimeEngine()), null);
        AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        var state = (PlannerConversationStatePort) ReflectionTestUtils.getField(service, "conversationState");
        assertNotNull(state);
        var continuations = new PendingIntentContinuations(classifier, clock::get, state);
        ReflectionTestUtils.setField(service, "continuations", continuations);
        return new Fixture(service, repo, trip, sessionId, Map.of("STOP1", selected.get(0).getName(), "STOP2", selected.get(1).getName(),
                "STOP3", selected.get(2).getName(), "NEW_STOP", selected.get(3).getName(),
                "NEW_STOP_ID", selected.get(3).getId()), clock, continuations, model, modelExecutor, release);
    }

    private static long mutationCalls(PlannerSessionRepositoryPort repo) { return mockingDetails(repo).getInvocations().stream().filter(i -> MUTATORS.contains(i.getMethod().getName())).count(); }
    private static Set<String> allStopIds(Map<String,Object> trip) {
        Set<String> result = new HashSet<>(); for (var day : maps(trip.get("days"))) for (var stop : maps(day.get("stops"))) result.add(text(stop.get("id"))); return result;
    }
    private static Map<String,Object> check(Map<String,Object> expected, Map<String,Object> actual) {
        Map<String,Object> result = new LinkedHashMap<>();
        expected.forEach((key, value) -> {
            if (key.endsWith("Contains")) {
                Object found = actual.get(key.substring(0, key.length()-8));
                boolean match = found instanceof Collection<?> c ? c.contains(value) : found != null && text(found).contains(text(value));
                addCheck(result,key,true,match);
            } else if (key.endsWith("Not")) {
                Object found = actual.get(key.substring(0,key.length()-3));
                addCheck(result,key,true,found != null && !Objects.equals(value,found));
            } else addCheck(result,key,value,actual.get(key));
        }); return result;
    }
    private static void addCheck(Map<String,Object> checks,String key,Object expected,Object actual) {
        boolean equal = Objects.equals(expected,actual) || expected instanceof Number e && actual instanceof Number a && Double.compare(e.doubleValue(),a.doubleValue())==0;
        Map<String,Object> row=new LinkedHashMap<>();row.put("expected",expected);row.put("actual",actual);row.put("correct",equal);checks.put(key,row);
    }
    private static boolean matched(Map<String,Object> checks) { return checks.values().stream().allMatch(v -> Boolean.TRUE.equals(map(v).get("correct"))); }
    private static Map<String,Object> errorMap(Exception error) { return Map.of("type",error.getClass().getName(),"message",text(error.getMessage())); }
    private static String substitute(String value,Map<String,String> names) { for(var e:names.entrySet())value=value.replace("{{"+e.getKey()+"}}",e.getValue());return value; }
    private static Map<String,Object> substituteMap(Map<String,Object> input,Map<String,String> names) throws Exception {
        return JSON.readValue(substitute(JSON.writeValueAsString(input),names),new TypeReference<>(){});
    }
    private Map<String,Object> sourceIdentity(byte[] dataset) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Path base:List.of(Path.of("src/main/java"),Path.of("src/main/resources")))try(var paths=Files.walk(base)){
            for(Path p:paths.filter(Files::isRegularFile).sorted().toList()){
                digest.update(p.toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(p));digest.update((byte)0);
            }
        }
        return Map.of("productionTreeSha256",HexFormat.of().formatHex(digest.digest()),"datasetSha256",sha(dataset),
                "harnessSha256",sha(Files.readAllBytes(Path.of("src/test/java/com/ai/guide/evaluation/ContinuationFallbackEvaluationTest.java"))),
                "javaVersion",System.getProperty("java.version"), "modelMode", modelMode);
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void writeNew(Path path,Object data) throws Exception { Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return value instanceof Map<?,?> ? (Map<String,Object>)value : Map.of(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> ? (List<Map<String,Object>>)value : List.of(); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static String nullableText(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private static long number(Object value,long fallback) { return value instanceof Number n ? n.longValue() : fallback; }
}

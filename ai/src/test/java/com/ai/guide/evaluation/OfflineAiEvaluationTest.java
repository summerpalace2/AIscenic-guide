package com.ai.guide.evaluation;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.*;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.planner.service.*;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.common.context.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in measurement, not a regression gate. Incorrect model/component outputs are retained as
 * measured misses; only malformed data or a broken harness fails JUnit. No Spring context, model,
 * network client or database is created. This measures the deterministic components, not the LLM.
 */
@EnabledIfSystemProperty(named = "ai.evaluation.enabled", matches = "true")
class OfflineAiEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CITIES = Set.of("重庆", "北京", "杭州", "西安", "武汉");
    private static final Set<String> MUTATIONS = Set.of("ADD_STOP", "REMOVE_STOP", "REPLACE_STOP",
            "REPLAN_DAY", "REDUCE_DENSITY", "APPLY_REPLACEMENT", "PLAN");
    private final String runId = System.getProperty("ai.evaluation.runId", UUID.randomUUID().toString());
    private final JsonTaxonomyAdapter taxonomy = new JsonTaxonomyAdapter();
    private final List<Map<String, Object>> catalogMetadata = new ArrayList<>();

    @Test
    void measureIntentAndPlanningWithoutSuppliers() throws Exception {
        List<Attraction> places = loadCatalog();
        AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
            @Override public List<Attraction> find(String district, String category) {
                return places.stream().filter(a -> district == null || district.isBlank() || district.equals(a.getDistrict()))
                        .filter(a -> category == null || category.isBlank() || category.equals(a.getCategory())).toList();
            }
            @Override public Attraction findById(String id) {
                return places.stream().filter(a -> Objects.equals(id, a.getId())).findFirst().orElse(null);
            }
            @Override public List<Attraction> findByCity(String city, String district, String category) {
                return find(district, category).stream().filter(a -> Objects.equals(city, a.getCity())).toList();
            }
        });
        ConversationIntentClassifier classifier = new ConversationIntentClassifier(catalog,
                new AttractionAliasMatcher(catalog, taxonomy));
        classifier.setTaxonomy(taxonomy);
        classifier.setDiningCategoryGrounder(new DiningCategoryGrounder(taxonomy));
        measureIntent(classifier, catalog);
        measurePlanning(catalog, places);
    }

    private void measureIntent(ConversationIntentClassifier classifier, AttractionService catalog) throws Exception {
        Dataset data = dataset("intent");
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : data.cases()) {
            long start = System.nanoTime();
            Map<String, Object> context = map(row.get("context"));
            PlanPageContext page = PlanPageContext.builder().activeDay(number(context.get("activeDay")))
                    .selectedStopId((String) context.get("selectedStopId")).build();
            Map<String, Object> actual;
            if (context.get("followUp") != null) {
                actual = twoTurnPreview(row, page, classifier, catalog);
            } else actual = new LinkedHashMap<>(classifier.classify(text(row.get("query")), page).asMap());
            Map<String, Object> checks = checks(map(row.get("expected")), actual);
            boolean exact = allCorrect(checks);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("exactMatch", exact);
            // Null means no operation label, not an implicitly correct operation.
            metrics.put("operationCorrect", checks.containsKey("operation")
                    ? map(checks.get("operation")).get("correct") : null);
            metrics.put("operationCorrectReason", checks.containsKey("operation") ? "ANNOTATED" : "NOT_ANNOTATED");
            metrics.put("fieldChecks", checks);
            metrics.put("measurementScope", context.get("followUp") == null
                    ? "DETERMINISTIC_CLASSIFIER_COMPONENT" : "PLAN_ADJUSTMENT_SERVICE_TWO_TURN_LLM_DISABLED");
            metrics.put("evaluationPath", context.get("followUp") == null
                    ? "SINGLE_TURN_CLASSIFIER" : "TWO_TURN_PREVIEW_DISABLED_LLM");
            results.add(result(row, actual, metrics, exact ? "MATCH" : "MISMATCH", start));
        }
        write(data, results, Map.of("llmExecuted", false, "continuationStore", "production in-memory store",
                "scope", "45 deterministic classifier cases; 5 real PlanAdjustmentService two-turn previews with LLM disabled and mocked repository; no apply or real persistence"));
    }

    private record Components(ItineraryBuilder builder, RouteCostProvider costs,
                              PlanVerifier verifier, RouteAwarePlanner routes) {}

    private Components components(AttractionService catalog) {
        PlanVerifier verifier = new PlanVerifier(taxonomy);
        LocalPlanRepairer repairer = new LocalPlanRepairer(new PoiCategoryPolicyRegistry(taxonomy));
        repairer.setTaxonomy(taxonomy);
        RouteCostProvider costs = new RouteCostProvider(null, taxonomy);
        RouteAwarePlanner routes = new RouteAwarePlanner(costs, verifier, repairer, null, null, taxonomy);
        ItineraryBuilder builder = new ItineraryBuilder(catalog, routes, verifier, repairer,
                new PlanNarrativeService(taxonomy), new AttractionAliasMatcher(catalog, taxonomy));
        return new Components(builder, costs, verifier, routes);
    }

    private Map<String, Object> twoTurnPreview(Map<String, Object> row, PlanPageContext page,
                                              ConversationIntentClassifier classifier,
                                              AttractionService catalog) throws Exception {
        Components graph = components(catalog);
        List<Attraction> cityPlaces = catalog.list(text(row.get("city")), null, null);
        assertFalse(cityPlaces.isEmpty(), "two-turn fixture needs actual city catalog");
        List<Map<String, Object>> days = new ArrayList<>();
        // Neutral existing-trip input: first three catalog entries, no expected labels involved.
        // No geographic claims are invented for catalog entries whose coordinates are unknown.
        for (int day = 1; day <= 3; day++) {
            Attraction place = cityPlaces.get((day - 1) % cityPlaces.size());
            var stop = graph.builder().createStop(place, "existing-" + day, "10:00", "60分钟", null, null);
            days.add(new LinkedHashMap<>(Map.of("day", day, "date", "2026-10-" + (19 + day),
                    "stops", new ArrayList<>(List.of(stop)))));
        }
        Map<String, Object> trip = new LinkedHashMap<>(Map.of("city", row.get("city"), "version", 1, "days", days));
        Map<String, Object> constraints = TravelConstraints.builder().destination(text(row.get("city")))
                .durationDays(3).build().asMap();
        String sessionId = "evaluation-" + row.get("id");
        var session = new PlannerSessionRepositoryPort.StoredSession(sessionId, "USER", "evaluation-owner",
                "", "", "", constraints, AppliedPreferencesSnapshot.empty(), trip, 1, 1, "DRAFT", 1, 1);
        PlannerSessionRepositoryPort repository = mock(PlannerSessionRepositoryPort.class);
        when(repository.find(sessionId, "USER", "evaluation-owner", "")).thenReturn(session);
        PlanAdjustmentService service = new PlanAdjustmentService(repository, graph.builder(), catalog,
                graph.costs(), graph.verifier(), graph.routes(), new CaffeineRedisProposalRepository(), classifier, JSON);
        service.setDiningScheduling(taxonomy, new MealGapSolver(graph.costs(), new ItineraryTimeEngine()), null);
        String before = JSON.writeValueAsString(trip);
        UserContext.set("evaluation-owner", "USER");
        try {
            PlanConversationRequestDto firstRequest = PlanConversationRequestDto.builder().message(text(row.get("query")))
                    .baseRevision(1).context(page).build();
            var first = service.preview(sessionId, firstRequest);
            String pendingId = text(map(first.body().get("pendingIntent")).get("intentId"));
            String followUp = text(map(row.get("context")).get("followUp"));
            PlanPageContext secondPage = PlanPageContext.builder().activeDay(page.getActiveDay())
                    .selectedStopId(page.getSelectedStopId()).pendingIntentId(pendingId).build();
            var second = service.preview(sessionId, PlanConversationRequestDto.builder().message(followUp)
                    .baseRevision(1).context(secondPage).build());
            // Never recycle the first intent when the real routing chain returns another result.
            Map<String, Object> actual = new LinkedHashMap<>(map(second.body().get("intent")));
            actual.put("rounds", List.of(round(text(row.get("query")), first), round(followUp, second)));
            actual.put("firstRoundCreatedPending", !pendingId.isBlank());
            actual.put("finalIntentPresent", second.body().get("intent") instanceof Map<?, ?>);
            actual.put("finalIntentStatus", second.body().get("intent") instanceof Map<?, ?>
                    ? "RETURNED" : "FINAL_INTENT_NOT_RETURNED");
            actual.put("authoritativeTripUnchanged", before.equals(JSON.writeValueAsString(trip)));
            actual.put("repositoryMutationCalls", mockingDetails(repository).getInvocations().stream()
                    .filter(call -> "update".equals(call.getMethod().getName())).count());
            return actual;
        } finally { UserContext.clear(); }
    }

    private static Map<String, Object> round(String query, PlannerService.ServiceResult response) {
        Map<String, Object> body = response.body();
        String decision = Boolean.TRUE.equals(body.get("requiresClarification")) ? "CLARIFICATION"
                : body.containsKey("proposedTrip") ? "PREVIEW" : "OTHER_RESPONSE";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query); result.put("status", response.status());
        result.put("decision", decision); result.put("body", body);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void measurePlanning(AttractionService catalog, List<Attraction> places) throws Exception {
        Dataset data = dataset("planning");
        TravelConstraintParser parser = new TravelConstraintParser(new ConstraintConflictDetector(),
                new LlmShadowPreferenceExtractor(taxonomy));
        parser.setTaxonomy(taxonomy);
        // Null gateway deliberately prevents map HTTP calls. Production coordinate estimates remain
        // estimates. Missing catalog coordinates remain missing, never replaced by synthetic facts.
        Components graph = components(catalog);
        PlanVerifier verifier = graph.verifier();
        ItineraryBuilder builder = graph.builder();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> row : data.cases()) {
            long start = System.nanoTime();
            TravelConstraints constraints = parser.parse(text(row.get("query")));
            Map<String, Object> actual = new LinkedHashMap<>(constraints.asMap());
            Map<String, Object> annotatedFields = new LinkedHashMap<>(map(row.get("expected")));
            annotatedFields.remove("outcome");
            Map<String, Object> fieldChecks = checks(annotatedFields, actual);
            Map<String, Object> requirements = map(row.get("requirements"));
            String city = constraints.getDestination();
            long cityCatalogCount = places.stream().filter(a -> Objects.equals(city, a.getCity())).count();
            long coordinatesCount = places.stream().filter(a -> Objects.equals(city, a.getCity()))
                    .filter(a -> com.ai.guide.domain.geo.model.GeoCoordinates.parse(a.getLocation()) != null).count();
            List<Map<String, Object>> days = List.of();
            String status;
            boolean planningEligible = "PLAN".equals(map(row.get("expected")).get("outcome"));
            String observedOutcome;
            // Branch only on production output, never on expected labels.
            if (constraints.isNeedsClarification()) {
                status = "CLARIFICATION";
                observedOutcome = "CLARIFICATION";
            } else if (constraints.getDurationDays() <= 0) {
                status = "NO_CONFIRMED_DURATION";
                observedOutcome = "REJECTED";
            } else {
                try {
                    Map<String, Object> trip = builder.build(1, constraints);
                    actual.put("trip", trip);
                    days = (List<Map<String, Object>>) trip.getOrDefault("days", List.of());
                    status = cityCatalogCount == 0 ? "NO_CANDIDATES" : "GENERATED";
                    observedOutcome = "PLAN";
                } catch (RuntimeException failure) {
                    // A production component error is a measured failure, not a missing case.
                    actual.put("componentError", Map.of("type", failure.getClass().getName(),
                            "message", text(failure.getMessage())));
                    status = "COMPONENT_ERROR";
                    observedOutcome = "REJECTED";
                }
            }
            actual.put("outcome", observedOutcome);
            fieldChecks.putAll(checks(Map.of("outcome", map(row.get("expected")).get("outcome")), actual));
            List<Map<String, Object>> stops = days.stream().flatMap(d -> ((List<Map<String, Object>>) d
                    .getOrDefault("stops", List.of())).stream()).toList();
            var verification = verifier.verify(days, constraints);
            boolean nonEmpty = !stops.isEmpty();
            List<Attraction> selectedCatalogEntries = stops.stream()
                    .map(s -> catalog.get(text(s.get("venueId")))).toList();
            boolean allSelectedIdentitiesKnown = nonEmpty && selectedCatalogEntries.stream().allMatch(Objects::nonNull);
            Map<String, Object> requirementChecks = new LinkedHashMap<>();
            for (Object name : list(requirements.get("requiredVenueNames"))) {
                addCheck(requirementChecks, "requiredVenue:" + name, true,
                        stops.stream().anyMatch(s -> text(s.get("name")).contains(text(name))));
            }
            for (Object category : list(requirements.get("forbiddenCategories"))) {
                // Stop DTO has no category field. Absence of that field cannot prove compliance.
                addCheck(requirementChecks, "forbiddenCategory:" + category, false,
                        allSelectedIdentitiesKnown ? selectedCatalogEntries.stream()
                                .anyMatch(a -> text(a.getCategory()).contains(text(category))) : null);
            }
            if (requirements.containsKey("maxWalkingDifficulty")) {
                // Unknown metadata cannot prove a low-walking constraint was honored.
                boolean low = nonEmpty && stops.stream().allMatch(s -> "低".equals(s.get("walkDifficulty"))
                        || "SUPPORTED".equals(s.get("accessibility")));
                addCheck(requirementChecks, "lowWalkingEvidence", true, low);
            }
            if (planningEligible) {
                addCheck(requirementChecks, "requestedDayCount",
                        map(row.get("expected")).get("durationDays"), days.size());
                addCheck(requirementChecks, "allRequestedDaysPopulated", true,
                        !days.isEmpty() && days.stream().allMatch(d -> !list(d.get("stops")).isEmpty()));
                addCheck(requirementChecks, "allSelectedInRequestedCity", true,
                        allSelectedIdentitiesKnown && selectedCatalogEntries.stream()
                                .allMatch(a -> Objects.equals(row.get("city"), a.getCity())));
            }
            boolean satisfied = allCorrect(fieldChecks) && allCorrect(requirementChecks);
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("fieldChecks", fieldChecks);
            metrics.put("requirementChecks", requirementChecks);
            metrics.put("nonEmpty", nonEmpty);
            metrics.put("feasible", planningEligible ? nonEmpty && verification.feasible() : null);
            metrics.put("feasibleReason", !planningEligible ? "NOT_A_PLANNING_SAMPLE"
                    : nonEmpty ? "STATIC_ESTIMATED_SCHEDULE_ONLY" : "EMPTY_PLAN_NOT_COUNTED_AS_FEASIBLE");
            metrics.put("constraintsSatisfied", satisfied);
            metrics.put("successful", nonEmpty && verification.feasible() && satisfied);
            metrics.put("eligibleForPlanning", planningEligible);
            metrics.put("outcomeCorrect", Objects.equals(map(row.get("expected")).get("outcome"), observedOutcome));
            metrics.put("requirementsComplete", false);
            metrics.put("requirementsScope", "Only annotated fields/venue/days/category/walking evidence; not all natural-language preferences or safety guarantees");
            metrics.put("runtimePlannerExecuted", false);
            metrics.put("catalogCount", cityCatalogCount);
            metrics.put("catalogCoordinatesCount", coordinatesCount);
            metrics.put("realWorldReachabilityVerified", false);
            metrics.put("verification", verification.asMap());
            actual.put("plannedStopCount", stops.size());
            if (!nonEmpty && "GENERATED".equals(status)) status = "EMPTY_PLAN";
            results.add(result(row, actual, metrics, status, start));
        }
        write(data, results, Map.of("llmExecuted", false, "mapCalls", 0,
                "routes", "production coordinate estimates or unavailable; no synthetic VERIFIED routes",
                "catalogs", "unmodified bundled city catalogs; some metadata/coordinates unknown",
                "scope", "parser + catalog ItineraryBuilder + PlanVerifier; runtime start-location branch not executed"));
    }

    private record Dataset(String suite, byte[] bytes, List<Map<String, Object>> cases, Map<String, Object> document) {}
    private Dataset dataset(String suite) throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/ai-evaluation/" + suite + "-cases.json")) {
            assertNotNull(input, "missing dataset");
            bytes = input.readAllBytes();
        }
        Map<String, Object> data = JSON.readValue(bytes, new TypeReference<>() {});
        assertEquals("ai-evaluation-v1", data.get("schemaVersion"));
        assertEquals(suite, data.get("suite"));
        assertNotNull(data.get("sourcePolicy"));
        List<Map<String, Object>> rows = JSON.convertValue(data.get("cases"), new TypeReference<>() {});
        assertEquals(50, rows.size());
        assertEquals(50L, rows.stream().map(r -> r.get("id")).distinct().count(), "duplicate IDs");
        assertEquals(50L, rows.stream().map(r -> r.get("query")).distinct().count(), "duplicate inputs");
        assertEquals(CITIES, rows.stream().map(r -> text(r.get("city"))).collect(java.util.stream.Collectors.toSet()));
        for (Map<String, Object> row : rows) {
            assertFalse(text(row.get("query")).isBlank());
            assertFalse(map(row.get("expected")).isEmpty(), "unlabeled case " + row.get("id"));
            assertFalse(text(row.get("familyId")).isBlank(), "missing family " + row.get("id"));
            Set<String> supported = "intent".equals(suite)
                    ? Set.of("operation", "dayNumber", "requestedVenueNameContains", "targetStopId",
                            "replacementPlaceNameContains", "reduceCount", "cuisineOrPlaceContains",
                            "mutationAllowed", "requiresClarification")
                    : Set.of("destination", "durationDays", "interestsContains", "walkingTolerance",
                            "mustVisitContains", "interestsNotContains", "transportPreferenceNotContains",
                            "budgetContains", "needsClarification", "timeBudgetMinutes", "outcome");
            map(row.get("expected")).forEach((key, value) -> {
                assertTrue(supported.contains(key), "unknown expected field " + key);
                assertNotNull(value, "null label " + key);
            });
            if ("planning".equals(suite)) assertTrue(Set.of("PLAN", "CLARIFICATION", "REJECTED")
                    .contains(text(map(row.get("expected")).get("outcome"))), "missing/invalid outcome");
        }
        return new Dataset(suite, bytes, rows, data);
    }

    private List<Attraction> loadCatalog() throws Exception {
        List<Attraction> result = new ArrayList<>();
        for (String path : List.of("attractions.json", "taxonomy/cities/beijing.json",
                "taxonomy/cities/hangzhou.json", "taxonomy/cities/xian.json", "taxonomy/cities/wuhan.json")) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
                assertNotNull(in, "missing catalog " + path);
                List<Map<String, Object>> rawRows = JSON.readValue(in, new TypeReference<>() {});
                List<Attraction> rows = new ArrayList<>();
                for (Map<String, Object> raw : rawRows) {
                    Map<String, Object> entityFields = new LinkedHashMap<>(raw);
                    // The real importer persists its explicit FIELDS list; locationStatus is
                    // catalog bookkeeping, not an Attraction property or proof of coordinates.
                    // Keep it in the evaluation artifact rather than silently treating it as a fact.
                    Object locationStatus = entityFields.remove("locationStatus");
                    if (locationStatus != null) catalogMetadata.add(Map.of("resource", path,
                            "id", text(raw.get("id")), "locationStatus", locationStatus));
                    rows.add(JSON.convertValue(entityFields, Attraction.class));
                }
                if ("attractions.json".equals(path)) rows.forEach(a -> {
                    if (a.getCity() == null || a.getCity().isBlank()) a.setCity("重庆");
                });
                result.addAll(rows);
            }
        }
        return result;
    }

    private Map<String, Object> result(Map<String, Object> row, Map<String, Object> actual,
                                        Map<String, Object> metrics, String status, long start) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id"));
        result.put("traceId", runId + ":" + row.get("id"));
        result.put("query", row.get("query"));
        result.put("city", row.get("city"));
        result.put("input", row);
        result.put("expected", row.get("expected"));
        result.put("actual", actual);
        result.put("metrics", metrics);
        result.put("status", status);
        result.put("latencyMs", (System.nanoTime() - start) / 1_000_000.0);
        return result;
    }

    private Map<String, Object> checks(Map<String, Object> expected, Map<String, Object> actual) {
        Map<String, Object> checks = new LinkedHashMap<>();
        for (var entry : expected.entrySet()) {
            String key = entry.getKey();
            Object target = entry.getValue();
            boolean contains = key.endsWith("Contains");
            boolean notContains = key.endsWith("NotContains");
            String field = notContains ? key.substring(0, key.length() - 11)
                    : contains ? key.substring(0, key.length() - 8) : key;
            Object value = actual.get(field);
            boolean correct;
            if ("mutationAllowed".equals(key)) {
                value = MUTATIONS.contains(text(actual.get("operation")));
                correct = Objects.equals(target, value);
            } else if (contains || notContains) {
                boolean found = value instanceof Collection<?> items
                        ? items.stream().anyMatch(item -> text(item).contains(text(target)))
                        : value != null && text(value).contains(text(target));
                // A missing value is not evidence of successful negation handling.
                correct = value != null && (notContains ? !found : found);
            } else {
                correct = Objects.equals(target, value) || target instanceof Number && value instanceof Number
                        && Double.compare(((Number) target).doubleValue(), ((Number) value).doubleValue()) == 0;
            }
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("expected", target); check.put("actual", value); check.put("correct", correct);
            check.put("comparator", notContains ? "NOT_CONTAINS" : contains ? "CONTAINS" : "EQUALS");
            checks.put(key, check);
        }
        return checks;
    }

    private static void addCheck(Map<String, Object> checks, String key, Object expected, Object actual) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("expected", expected); check.put("actual", actual);
        check.put("correct", Objects.equals(expected, actual));
        checks.put(key, check);
    }
    private static boolean allCorrect(Map<String, Object> checks) {
        return checks.values().stream().allMatch(c -> Boolean.TRUE.equals(map(c).get("correct")));
    }

    private void write(Dataset data, List<Map<String, Object>> results, Map<String, Object> limitations) throws Exception {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "ai-evaluation-v1"); output.put("suite", data.suite());
        output.put("protocolVersion", "v2");
        output.put("runId", runId); output.put("generatedAt", Instant.now().toString());
        output.put("executionMode", "OFFLINE_PRODUCTION_DETERMINISTIC_COMPONENTS");
        output.put("datasetVersion", data.document().get("datasetVersion"));
        output.put("datasetSha256", sha(data.bytes())); output.put("sourcePolicy", data.document().get("sourcePolicy"));
        output.put("source", sourceIdentity()); output.put("limitations", limitations);
        output.put("catalogAuxiliaryMetadata", catalogMetadata);
        output.put("cases", results);
        Map<String, Map<String, Integer>> denominators = new TreeMap<>();
        for (var row : results) map(map(row.get("metrics")).get("fieldChecks")).forEach((field, raw) -> {
            Map<String, Integer> counter = denominators.computeIfAbsent(field,
                    ignored -> new LinkedHashMap<>(Map.of("evaluated", 0, "correct", 0)));
            counter.merge("evaluated", 1, Integer::sum);
            if (Boolean.TRUE.equals(map(raw).get("correct"))) counter.merge("correct", 1, Integer::sum);
        });
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("caseCount", results.size()); summary.put("fieldMetrics", denominators);
        summary.put("familyCount", data.cases().stream().map(r -> r.get("familyId")).distinct().count());
        summary.put("annotationStatus", "AGENT_AUTHORED_PEER_REVIEWED_PROVISIONAL_NOT_HUMAN_GROUND_TRUTH");
        if ("planning".equals(data.suite())) {
            summary.put("eligiblePlanningCount", results.stream().filter(r -> Boolean.TRUE.equals(
                    map(r.get("metrics")).get("eligibleForPlanning"))).count());
            summary.put("clarificationExpectedCount", results.stream().filter(r -> "CLARIFICATION".equals(
                    map(r.get("expected")).get("outcome"))).count());
        }
        output.put("summary", summary);
        Path directory = Path.of("target", "ai-evaluation"); Files.createDirectories(directory);
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(data.suite() + "-results.json").toFile(), output);
    }

    private Map<String, Object> sourceIdentity() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path base : List.of(Path.of("src/main/java"), Path.of("src/main/resources"))) {
            assertTrue(Files.isDirectory(base), "run Maven from ai module directory");
            try (var paths = Files.walk(base)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    digest.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0); digest.update(Files.readAllBytes(path)); digest.update((byte) 0);
                }
            }
        }
        return Map.of("gitHead", git("rev-parse", "HEAD"), "gitWorkingTreeStatus",
                git("status", "--porcelain", "--", "src/main", "src/test/java/com/ai/guide/evaluation", "src/test/resources/ai-evaluation"),
                "productionTreeSha256", HexFormat.of().formatHex(digest.digest()),
                "harnessSha256", sha(Files.readAllBytes(Path.of("src/test/java/com/ai/guide/evaluation/OfflineAiEvaluationTest.java"))),
                "javaVersion", System.getProperty("java.version"), "osName", System.getProperty("os.name"));
    }
    private static String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return "UNAVAILABLE_TIMEOUT"; }
        return process.exitValue() == 0 ? new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()
                : "UNAVAILABLE_EXIT_" + process.exitValue();
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Integer number(Object value) { return value instanceof Number number ? number.intValue() : null; }
}

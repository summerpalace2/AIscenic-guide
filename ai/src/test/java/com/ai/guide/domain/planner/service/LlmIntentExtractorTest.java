package com.ai.guide.domain.planner.service;



import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmIntentExtractorTest {

    @Test
    void longCompositeDiningAdditionKeepsStructuredOperationWhenQuestionAndPlaceWordsAppear() {
        var result = extractor((system, user) -> """
                {"operation":"ADD_STOP","scope":"DAY","cuisineOrPlace":"螺蛳粉","genericDiningCraving":true,"timeSlot":"LUNCH","confidence":0.96}
                """, true, true, 0.75, 500).extract(
                "三峡博物馆几点开门我之后自己确认，午餐帮我加一顿清淡一点的螺蛳粉，吃完再去洪崖洞看看",
                PlanPageContext.builder().activeDay(2).selectedStopId("museum-stop").pinnedStopIds(List.of("pinned-stop")).build());
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals(ConversationIntentType.ADD_STOP, result.intent().getType());
        assertEquals("ADD_STOP", result.intent().getOperation());
        assertEquals("DAY", result.intent().getScope());
        assertEquals("螺蛳粉", result.intent().getCuisineOrPlace());
        assertEquals("LUNCH", result.intent().getTimeSlot());
        assertEquals(List.of("pinned-stop"), result.intent().getPinnedStopIds());
    }

    @Test
    void structuredDiningOperationBeatsConflictingLegacyQaOrReplacementWithoutLosingTargetContext() {
        for (ConversationIntentType legacyType : List.of(ConversationIntentType.PLACE_QUESTION, ConversationIntentType.SUGGEST_REPLACEMENTS)) {
            ConversationIntentClassifier classifier = mock(ConversationIntentClassifier.class);
            when(classifier.classify(any(), any())).thenReturn(PlanAdjustmentIntent.builder()
                    .type(legacyType).scope("STOP").dayNumber(2).targetStopId("selected-lunch")
                    .targetStopReference("午餐").replacementPlaceId("stale-catalog-id").preferences(List.of("FOOD")).build());
            for (String operation : List.of("ADD_STOP", "REPLACE_STOP")) {
                var extractor = new LlmIntentExtractor((system, user) -> "{\"operation\":\"" + operation
                        + "\",\"scope\":\"DAY\",\"cuisineOrPlace\":\"驴肉火烧\",\"genericDiningCraving\":true,\"timeSlot\":\"LUNCH\",\"confidence\":0.96}",
                        objectMapper, classifier, true, true, 0.75, 500);
                var result = extractor.extract("景点几点开门我自己查询，按刚才商量的午餐安排吃驴肉火烧", PlanPageContext.builder()
                        .activeDay(2).selectedStopId("selected-lunch").activeProposalId("proposal-1").pinnedStopIds(List.of("protected-1")).build());
                assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
                assertEquals(operation, result.intent().getOperation(), legacyType.name());
                assertEquals("ADD_STOP".equals(operation) ? ConversationIntentType.ADD_STOP : ConversationIntentType.SUGGEST_REPLACEMENTS,
                        result.intent().getType(), legacyType.name());
                assertEquals("selected-lunch", result.intent().getTargetStopId());
                assertEquals(2, result.intent().getDayNumber());
                assertEquals("proposal-1", result.intent().getProposalId());
                assertEquals(List.of("protected-1"), result.intent().getPinnedStopIds());
                assertEquals(null, result.intent().getReplacementPlaceId());
            }
        }
    }

    @Test
    void structuredDiningFieldsSurviveMergeAndKeepExplicitLunchWithBarbecue() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"operation":"ADD_STOP","cuisineOrPlace":"驴肉火烧","genericDiningCraving":true,"timeSlot":"LUNCH","confidence":0.95}
                """, true, true, 0.75, 500);
        var result = extractor.extract("午餐想吃驴肉火烧，之后再看有没有烧烤", PlanPageContext.builder().activeDay(1).build());
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals("驴肉火烧", result.intent().getCuisineOrPlace());
        assertEquals(Boolean.TRUE, result.intent().getGenericDiningCraving());
        assertEquals("LUNCH", result.intent().getTimeSlot());
        assertEquals("驴肉火烧", result.intent().asMap().get("cuisineOrPlace"));
        assertEquals(true, result.intent().asMap().get("genericDiningCraving"));
    }

    @Test
    void modelExtractedSpecificVenueBeatsRegexSwallowingLongSentence() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"operation":"REPLACE_STOP","cuisineOrPlace":"宏盛餐馆(黄桷小区店)","genericDiningCraving":false,"timeSlot":"DINNER","confidence":0.96}
                """, true, true, 0.75, 500);
        var result = extractor.extract("把晚餐换成宏盛餐馆(黄桷小区店)，希望清淡不辣", PlanPageContext.builder().activeDay(1).build());
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals("宏盛餐馆(黄桷小区店)", result.intent().getReplacementPlaceName());
        assertEquals(Boolean.FALSE, result.intent().getGenericDiningCraving());
    }

    @Test
    void missingGenericDiningFlagUsesInjectedTaxonomyInsteadOfEmptyStaticFallback() {
        var classifier = org.mockito.Mockito.mock(ConversationIntentClassifier.class);
        org.mockito.Mockito.when(classifier.classify(any(), any())).thenReturn(PlanAdjustmentIntent.builder()
                .type(ConversationIntentType.ADD_STOP).operation("ADD_STOP")
                .requestedVenueName("螺蛳粉").build());
        var extractor = new LlmIntentExtractor((system, user) ->
                "{\"operation\":\"ADD_STOP\",\"cuisineOrPlace\":\"螺蛳粉\",\"confidence\":0.96}",
                objectMapper, classifier, true, true, 0.75, 500);
        var taxonomy = new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
        extractor.setDiningCategoryGrounder(new com.ai.guide.domain.planner.engine.DiningCategoryGrounder(taxonomy));

        var result = extractor.extract("午餐想吃螺蛳粉", PlanPageContext.builder().activeDay(1).build());

        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals(null, result.intent().getRequestedVenueName());
    }

    @Test
    void snakeCaseDiningContractIsAcceptedAndNonDiningNullDoesNotInventFood() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"operation":"ADD_STOP","cuisine_or_place":"柳州螺蛳粉","generic_dining_craving":true,"time_window":"lunch","confidence":0.94}
                """, true, true, 0.75, 500);
        var result = extractor.extract("午饭吃柳州螺蛳粉", PlanPageContext.builder().activeDay(1).build());
        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals("柳州螺蛳粉", result.intent().getCuisineOrPlace());
        assertEquals("LUNCH", result.intent().getTimeSlot());

        var nonDining = extractor((system, user) -> """
                {"operation":"REMOVE_STOP","cuisineOrPlace":null,"genericDiningCraving":null,"timeSlot":null,"confidence":0.94}
                """, true, true, 0.75, 500).extract("删除这个景点", PlanPageContext.builder().selectedStopId("stop-1").build());
        assertEquals("LLM_STRUCTURED", nonDining.intentExtractionSource());
        assertEquals(null, nonDining.intent().getGenericDiningCraving());
        assertFalse(nonDining.intent().asMap().containsKey("cuisineOrPlace"));
    }

    @Test
    void userGuidanceGenericCravingAliasAcceptsOnlyBooleanOrNullAndCanonicalWins() {
        for (String value : List.of("true", "false", "null")) {
            var result = extractor((system, user) -> "{\"operation\":\"ADD_STOP\",\"cuisine_or_place\":\"驴肉火烧\","
                    + "\"is_generic_craving\":" + value + ",\"time_window\":\"LUNCH\",\"confidence\":0.96}",
                    true, true, 0.75, 500).extract("午餐吃驴肉火烧", PlanPageContext.builder().activeDay(1).build());
            assertEquals("LLM_STRUCTURED", result.intentExtractionSource(), value);
            assertEquals("null".equals(value) ? null : Boolean.valueOf(value), result.intent().getGenericDiningCraving());
        }
        var canonical = extractor((system, user) -> """
                {"operation":"ADD_STOP","cuisineOrPlace":"驴肉火烧","genericDiningCraving":true,"is_generic_craving":false,"confidence":0.96}
                """, true, true, 0.75, 500).extract("午餐吃驴肉火烧", PlanPageContext.builder().activeDay(1).build());
        assertEquals(Boolean.TRUE, canonical.intent().getGenericDiningCraving());
    }

    @Test
    void malformedStructuredDiningFieldsFallBackRatherThanCoerceOrTruncate() {
        for (String fields : List.of(
                "\"genericDiningCraving\":\"false\"",
                "\"genericDiningCraving\":1",
                "\"is_generic_craving\":\"false\"",
                "\"is_generic_craving\":0",
                "\"is_generic_craving\":[]",
                "\"cuisineOrPlace\":[\"粤菜\"]",
                "\"cuisineOrPlace\":\"" + "菜".repeat(121) + "\"",
                "\"timeSlot\":\"MIDNIGHT\"",
                "\"timeSlot\":12")) {
            LlmIntentExtractor extractor = extractor((system, user) ->
                    "{\"operation\":\"ADD_STOP\",\"confidence\":0.99," + fields + "}", true, true, 0.75, 500);
            var result = extractor.extract("第二天加上北仓", PlanPageContext.builder().activeDay(1).build());
            assertEquals("INVALID_OUTPUT", result.llmFallbackReason(), fields);
            assertEquals("DETERMINISTIC_FALLBACK", result.intentExtractionSource(), fields);
            assertEquals(ConversationIntentType.ADD_STOP, result.intent().getType());
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationIntentClassifier rules = com.ai.guide.testing.PlannerTaxonomyFixtures.classifier();

    private LlmIntentExtractor extractor(LlmIntentExtractor.IntentModelClient client,
                                         boolean enabled,
                                         boolean credentialsAvailable,
                                         double minimumConfidence,
                                         long timeoutMs) {
        return new LlmIntentExtractor(client, objectMapper, rules, enabled,
                credentialsAvailable, minimumConfidence, timeoutMs);
    }

    @Test
    void validStructuredOutputMapsToExistingIntentAndSource() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"intentType":"REPLAN_DAY_FOR_CONDITION","targetDay":2,"condition":"RAIN","confidence":0.93}
                """, true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "外面一直淅淅沥沥的，我想尽量待在有屋顶的地方。",
                PlanPageContext.builder().activeDay(2).build());

        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals(ConversationIntentType.REPLAN_DAY_FOR_CONDITION, result.intent().getType());
        assertEquals(2, result.intent().getDayNumber());
        assertEquals("RAIN", result.intent().getCondition());
        assertEquals("HIGH", result.confidenceBand());
        assertTrue(result.metadata().containsKey("intentExtractionSource"));
    }

    @Test
    void commonNaturalLanguageAdjustmentsProduceSanitizedStructuredMetadata() {
        LlmIntentExtractor extractor = extractor((system, user) -> {
            if (user.contains("改第二天景点")) return "{\"intentType\":\"REPLAN_DAY\",\"targetDay\":2,\"confidence\":0.92}";
            if (user.contains("安排得有点累")) return "{\"intentType\":\"REDUCE_DAY_DENSITY\",\"targetDay\":2,\"requestedReductionCount\":1,\"confidence\":0.91}";
            if (user.contains("下雨")) return "{\"intentType\":\"REPLAN_DAY_FOR_CONDITION\",\"targetDay\":2,\"condition\":\"RAIN\",\"confidence\":0.94}";
            return "{\"intentType\":\"SUGGEST_REPLACEMENTS\",\"targetStopId\":\"day2-stop-1\",\"confidence\":0.90}";
        }, true, true, 0.75, 500);

        List<LlmIntentExtractor.LlmIntentExtractionResult> results = List.of(
                extractor.extract("改第二天景点", PlanPageContext.builder().activeDay(1).build()),
                extractor.extract("第二天安排得有点累，帮我松一点", PlanPageContext.builder().activeDay(1).build()),
                extractor.extract("明天估计会下雨，尽量别一直在外面", PlanPageContext.builder().activeDay(2).build()),
                extractor.extract("这个地方不太适合我，附近有没有省脚力的替代项", PlanPageContext.builder().activeDay(2).selectedStopId("day2-stop-1").build())
        );

        assertEquals(ConversationIntentType.REPLAN_DAY, results.get(0).intent().getType());
        assertEquals(ConversationIntentType.REDUCE_DAY_DENSITY, results.get(1).intent().getType());
        assertEquals(ConversationIntentType.REPLAN_DAY_FOR_CONDITION, results.get(2).intent().getType());
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, results.get(3).intent().getType());
        for (LlmIntentExtractor.LlmIntentExtractionResult result : results) {
            assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
            assertTrue(result.metadata().keySet().containsAll(List.of(
                    "intentType", "intentExtractionSource", "fallbackReason", "confidenceBand", "latencyMs")));
            assertFalse(result.metadata().containsKey("prompt"));
            assertFalse(result.metadata().containsKey("rawModelResponse"));
        }
    }

    @Test
    void mergesTemporaryLlmPreferencesAndNormalizesRainConditionWithoutPersistingThem() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"operation":"REPLAN_DAY","targetDay":2,"conditions":["RAIN"],"preferences":["LOW_WALKING","INDOOR"],"confidence":0.92}
                """, true, true, 0.75, 500);
        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "明天可能一直淅淅沥沥，尽量待在有屋顶的地方。", PlanPageContext.builder().activeDay(1).build());

        assertEquals(ConversationIntentType.REPLAN_DAY_FOR_CONDITION, result.intent().getType());
        assertEquals(2, result.intent().getDayNumber());
        assertTrue(result.intent().getConditions().contains("RAIN"));
        assertTrue(result.intent().getPreferences().contains("LOW_WALKING"));
        assertTrue(result.intent().getPreferences().contains("INDOOR"));
    }

    @Test
    void selectedStopAndActiveProposalOverrideModelGuessAndExplicitSecondKeepsOptionTwo() {
        LlmIntentExtractor extractor = extractor((system, user) -> """
                {"intentType":"APPLY_REPLACEMENT","targetStopReference":"model-stop","candidateOrdinal":1,"confidence":0.91}
                """, true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "就用刚才的第二个。",
                PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId("selected-stop-42")
                        .activeProposalId("prop-123")
                        .pinnedStopIds(java.util.List.of("pinned-1"))
                        .build());

        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals(ConversationIntentType.APPLY_REPLACEMENT, result.intent().getType());
        assertEquals("selected-stop-42", result.intent().getTargetStopId());
        assertEquals("prop-123", result.intent().getProposalId());
        assertEquals(2, result.intent().getCandidateIndex());
        assertEquals("option-2", result.intent().getOptionId());
        assertEquals(java.util.List.of("pinned-1"), result.intent().getPinnedStopIds());
    }

    @Test
    void llmReplacementNameIsRetainedAlongsideDeterministicCatalogId() {
        LlmIntentExtractor extractor = extractor((system, user) ->
                "{\"operation\":\"APPLY_REPLACEMENT\",\"replacementPlaceName\":\"重庆大剧院外围广场\",\"confidence\":0.92}",
                true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "把最后一个晚上行程改成重庆大剧院外围广场",
                PlanPageContext.builder().activeDay(2).build());

        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, result.intent().getType());
        assertEquals("cq-grand-theatre", result.intent().getReplacementPlaceId());
        assertEquals("cq-grand-theatre", result.intent().getTargetAttractionId());
    }

    @Test
    void selectedQuickReplacementCannotBeDowngradedToClarificationByModel() {
        LlmIntentExtractor extractor = extractor((system, user) ->
                "{\"intentType\":\"CLARIFICATION\",\"requiresClarification\":true,\"clarificationQuestion\":\"请先选中景点\",\"confidence\":0.99}",
                true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "请推荐【南温泉风景区】的同片区可替换景点",
                PlanPageContext.builder().activeDay(2).selectedStopId("day2-south-hot-spring").build());

        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, result.intent().getType());
        assertEquals("day2-south-hot-spring", result.intent().getTargetStopId());
        assertFalse(result.intent().isRequiresClarification());
    }

    @Test
    void providerTimeoutFallsBackWithoutEscaping() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmIntentExtractor extractor = new LlmIntentExtractor((system, user) -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return "{}";
            }, objectMapper, rules, true, true, 0.75, 5, executor);

            LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                    "今天下雨，尽量室内。",
                    PlanPageContext.builder().activeDay(1).build());

            assertEquals("DETERMINISTIC_FALLBACK", result.intentExtractionSource());
            assertEquals("TIMEOUT", result.llmFallbackReason());
            assertEquals(ConversationIntentType.REPLAN_DAY_FOR_CONDITION, result.intent().getType());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerFailureFallsBackToRules() {
        LlmIntentExtractor extractor = extractor((system, user) -> {
            throw new IllegalStateException("provider unavailable");
        }, true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "第二天加上北仓",
                PlanPageContext.builder().activeDay(1).build());

        assertEquals("DETERMINISTIC_FALLBACK", result.intentExtractionSource());
        assertEquals("PROVIDER_ERROR", result.llmFallbackReason());
        assertEquals(ConversationIntentType.ADD_STOP, result.intent().getType());
        assertEquals(2, result.intent().getDayNumber());
        assertEquals("cq-beicang", result.intent().getTargetAttractionId());
    }

    @Test
    void invalidJsonAndUnknownIntentFallBackToRules() {
        LlmIntentExtractor invalidJson = extractor((system, user) -> "not-json", true, true, 0.75, 500);
        LlmIntentExtractor.LlmIntentExtractionResult invalid = invalidJson.extract(
                "今天安排太满了，少去两个。", PlanPageContext.builder().activeDay(1).build());
        assertEquals("INVALID_OUTPUT", invalid.llmFallbackReason());
        assertEquals(ConversationIntentType.REDUCE_DAY_DENSITY, invalid.intent().getType());
        assertEquals(2, invalid.intent().getReduceCount());

        LlmIntentExtractor unknownIntent = extractor((system, user) ->
                "{\"intentType\":\"MAKE_UP_A_TRIP\",\"confidence\":0.99}", true, true, 0.75, 500);
        LlmIntentExtractor.LlmIntentExtractionResult unknown = unknownIntent.extract(
                "删除这个景点", PlanPageContext.builder().activeDay(1).selectedStopId("stop-1").build());
        assertEquals("INVALID_OUTPUT", unknown.llmFallbackReason());
        assertEquals(ConversationIntentType.REMOVE_STOP, unknown.intent().getType());
    }

    @Test
    void lowConfidenceFallsBackToDeterministicClassifier() {
        LlmIntentExtractor extractor = extractor((system, user) ->
                "{\"intentType\":\"PLACE_QUESTION\",\"confidence\":0.30}", true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "这个地方适合老人吗？",
                PlanPageContext.builder().activeDay(1).selectedStopId("stop-elder").build());

        assertEquals("LOW_CONFIDENCE", result.llmFallbackReason());
        assertEquals("DETERMINISTIC_FALLBACK", result.intentExtractionSource());
        assertEquals(ConversationIntentType.PLACE_QUESTION, result.intent().getType());
    }

    @Test
    void featureFlagDisabledMakesZeroModelCallsAndUsesFallback() {
        AtomicInteger calls = new AtomicInteger();
        LlmIntentExtractor extractor = extractor((system, user) -> {
            calls.incrementAndGet();
            return "{\"intentType\":\"PLACE_QUESTION\",\"confidence\":0.99}";
        }, false, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "删除这个景点", PlanPageContext.builder().activeDay(1).selectedStopId("stop-1").build());

        assertEquals(0, calls.get());
        assertEquals("FEATURE_DISABLED", result.llmFallbackReason());
        assertEquals(ConversationIntentType.REMOVE_STOP, result.intent().getType());
    }

    @Test
    void missingCredentialsMakesZeroModelCalls() {
        AtomicInteger calls = new AtomicInteger();
        LlmIntentExtractor extractor = extractor((system, user) -> {
            calls.incrementAndGet();
            return "{}";
        }, true, false, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "今天下雨，尽量室内。", PlanPageContext.builder().activeDay(1).build());

        assertEquals(0, calls.get());
        assertEquals("CREDENTIAL_UNAVAILABLE", result.llmFallbackReason());
        assertEquals("DETERMINISTIC_FALLBACK", result.intentExtractionSource());
    }

    @Test
    void promptContainsOnlyMinimalContextAndNoSecretsOrTripPayload() {
        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userPrompt = new AtomicReference<>();
        LlmIntentExtractor extractor = extractor((system, user) -> {
            systemPrompt.set(system);
            userPrompt.set(user);
            return "{\"intentType\":\"REDUCE_DAY_DENSITY\",\"requestedReductionCount\":2,\"confidence\":0.88}";
        }, true, true, 0.75, 500);

        LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                "今天安排太满了，少去两个。", PlanPageContext.builder().activeDay(2).build());

        assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
        assertNotNull(systemPrompt.get());
        assertNotNull(userPrompt.get());
        assertFalse(userPrompt.get().contains("sessionAccessToken"));
        assertFalse(userPrompt.get().contains("optionPlans"));
        assertFalse(userPrompt.get().contains("proposedTrip"));
        assertFalse(userPrompt.get().contains("api-key"));
        assertTrue(userPrompt.get().contains("activeDay=2"));
    }

    @Test
    void modelCallUsesDedicatedExecutorBoundary() {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-planner-llm");
            thread.setDaemon(true);
            return thread;
        });
        try {
            LlmIntentExtractor extractor = new LlmIntentExtractor(
                    (system, user) -> {
                        threadName.set(Thread.currentThread().getName());
                        return "{\"intentType\":\"PLACE_QUESTION\",\"confidence\":0.95}";
                    }, objectMapper, rules, true, true, 0.75, 500, executor);

            LlmIntentExtractor.LlmIntentExtractionResult result = extractor.extract(
                    "这个景点适合老人吗？",
                    PlanPageContext.builder().activeDay(1).selectedStopId("stop-elder").build());

            assertEquals("LLM_STRUCTURED", result.intentExtractionSource());
            assertEquals("test-planner-llm", threadName.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void testExtractBalancedJsonObjectWithMarkdownAndTrailingBraces() {
        String markdownOutput = """
                好的，已为您分析：
                ```json
                {
                  "intentType": "ADD_STOP",
                  "requestedVenueName": "洪崖洞",
                  "confidence": 0.95
                }
                ```
                说明：{这里是额外补充说明}
                """;
        String extracted = LlmIntentExtractor.extractBalancedJsonObject(markdownOutput);
        assertNotNull(extracted);
        assertTrue(extracted.contains("\"intentType\": \"ADD_STOP\""));
        assertFalse(extracted.contains("额外补充说明"));
    }

    @Test
    void testExtractBalancedJsonObjectDirectJsonWithTrailingText() {
        String raw = "{\"intentType\":\"QA\",\"confidence\":0.9} 附带备注: {xyz}";
        String extracted = LlmIntentExtractor.extractBalancedJsonObject(raw);
        assertEquals("{\"intentType\":\"QA\",\"confidence\":0.9}", extracted);
    }
}

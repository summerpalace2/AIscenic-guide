package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.engine.DiningCategoryGrounder;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DisplayName("【智能体 B】红方联合极限实弹攻击战役套件（战区 3、4、5 实弹压测）")
public class AgentBRedTeamAssaultCampaignTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private PlannerSessionRepositoryPort sessionRepositoryPort;

    @Autowired
    private ProposalStore proposalStore;

    @Autowired
    private DiningCategoryGrounder diningCategoryGrounder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        proposalStore.clear();
    }

    private static Path resolveTaxonomyDir() {
        if (Files.exists(Path.of("src/main/resources/taxonomy"))) {
            return Path.of("src/main/resources/taxonomy");
        }
        return Path.of("ai/src/main/resources/taxonomy");
    }

    // =========================================================================
    // 【战区 3：超长自然语言定语与复合否定语义轰炸（Context Poison & Semantic Confusion）】
    // =========================================================================
    @Nested
    @DisplayName("战区 3：超长自然语言定语与复合否定语义轰炸")
    class Warzone3ContextPoisonAndSemanticConfusionAssault {

        @Test
        @DisplayName("实弹 3A：73字多重否定错位长句轰炸，杜绝关键词误触发'重庆火锅'与'特色盖碗茶'")
        void assault_ultraLongSentenceWithMultipleNegationsAndMisplacedModifiers() {
            String bombSentence = "我们不想在解放碑吃火锅也不要在南山喝茶，但是如果路过沙坪坝附近有没有非辣且适合痛风患者吃的老字号清汤养生馆或者随便来个别的快餐都可以只要不是重辣就行";
            assertTrue(bombSentence.length() >= 50, "必须满足50+字超长句子轰炸要求，实际长度: " + bombSentence.length());

            // 1. 测试 Grounder 在复杂长句下的关键词与非餐饮感知
            assertTrue(diningCategoryGrounder.hasDiningKeywords("快餐"));
            assertTrue(diningCategoryGrounder.hasDiningKeywords("特色清汤羊肉馆"));
            assertTrue(diningCategoryGrounder.isNonDiningPlace("解放碑广场"));
            assertTrue(diningCategoryGrounder.isNonDiningPlace("南山公园"));
            assertTrue(diningCategoryGrounder.isNonDiningPlace("沙坪坝公园"));
            assertTrue(diningCategoryGrounder.isNonDiningPlace("重庆三峡博物馆"));

            // 2. 模拟 LLM 结构化提取出的意图对象
            PlanAdjustmentIntent intent = PlanAdjustmentIntent.builder()
                    .rawMessage(bombSentence)
                    .operation("ADD_STOP")
                    .type(ConversationIntentType.ADD_STOP)
                    .cuisineOrPlace("老字号清汤养生馆")
                    .genericDiningCraving(true)
                    .timeSlot("LUNCH")
                    .preferences(List.of("FOOD", "LOW_SPICE"))
                    .build();

            // 3. 验证 Grounder 解析核心意图
            assertTrue(diningCategoryGrounder.isGenericDiningCraving(intent, "老字号清汤养生馆"),
                    "老字号清汤养生馆应被判定为泛需求(true)，而非具体商户门店！");
            String resolvedFlavor = diningCategoryGrounder.resolveRequestedFlavor(intent, "特色美食");
            assertEquals("老字号清汤养生馆", resolvedFlavor,
                    "长定语轰炸下，系统必须精准提取'老字号清汤养生馆'，严禁误提取为'重庆火锅'或'盖碗茶'！");
            assertFalse(resolvedFlavor.contains("火锅"), "严禁将否定上下文中的'火锅'误作为用户需求！");
            assertFalse(resolvedFlavor.contains("茶"), "严禁将否定上下文中的'喝茶'误作为用户需求！");
        }

        @Test
        @DisplayName("实弹 3B：73字双重否定与错位修饰词长句，验证时段解析与精准风味锁定")
        void assault_doubleNegationWithMisplacedTimeAndLocation() throws Exception {
            String bombSentence = "明天中午我们绝对不要吃麻辣小面也不想去任何网红火锅店排队受罪，能不能在磁器口附近给我换成一家人均80以内适合带老人小孩慢慢吃的清淡粤菜馆？";
            assertTrue(bombSentence.length() >= 50, "必须满足50+字超长句子轰炸要求，实际长度: " + bombSentence.length());

            // 模拟大模型端提取结构化意图
            String modelOutput = objectMapper.writeValueAsString(Map.of(
                    "operation", "REPLACE_STOP",
                    "intentType", "SUGGEST_REPLACEMENTS",
                    "scope", "STOP",
                    "cuisineOrPlace", "清淡粤菜馆",
                    "genericDiningCraving", true,
                    "timeSlot", "LUNCH",
                    "targetPlace", "磁器口",
                    "preferences", List.of("FOOD", "ELDER_FRIENDLY"),
                    "confidence", 0.97
            ));

            LlmIntentExtractor extractor = new LlmIntentExtractor(
                    (system, user) -> modelOutput,
                    objectMapper,
                    new ConversationIntentClassifier(),
                    true, true, 0.75, 500
            );

            var extracted = extractor.extract(bombSentence, PlanPageContext.builder().activeDay(2).selectedStopId("day2-lunch").build());
            assertEquals("LLM_STRUCTURED", extracted.intentExtractionSource());
            PlanAdjustmentIntent intent = extracted.intent();

            assertEquals("LUNCH", diningCategoryGrounder.resolveSlotType(intent), "中午时段必须精准解析为 LUNCH！");
            assertEquals("清淡粤菜馆", diningCategoryGrounder.resolveRequestedFlavor(intent, "特色美食"));
            assertTrue(diningCategoryGrounder.isGenericDiningCraving(intent, "清淡粤菜馆"));
        }

        @Test
        @DisplayName("实弹 3C：110+字极限恶意长句（多重转折、反问修饰），系统优雅收敛无崩溃")
        void assault_extreme109CharAdversarialBombardment() {
            String extremeBomb = "我们不想在解放碑挤着排队吃重辣九宫格火锅，也坚决不要在南山一棵树喝又贵又难喝的盖碗茶，如果下午三点多路过大学城或者沙坪坝附近，有没有那种完全不辣、适合老年人痛风病人的百年老字号清汤瓦罐养生汤馆，实在不行随便来个清淡快餐也行？";
            assertTrue(extremeBomb.length() >= 100, "验证极限 100+ 字长度，实际长度: " + extremeBomb.length());

            PlanAdjustmentIntent intent = PlanAdjustmentIntent.builder()
                    .rawMessage(extremeBomb)
                    .cuisineOrPlace("百年老字号清汤瓦罐养生汤馆")
                    .genericDiningCraving(true)
                    .timeSlot("TEA_BREAK")
                    .build();

            assertTrue(diningCategoryGrounder.isGenericDiningCraving(intent, "百年老字号清汤瓦罐养生汤馆"));
            assertEquals("百年老字号清汤瓦罐养生汤馆", diningCategoryGrounder.resolveRequestedFlavor(intent, "特色美食"));
            assertEquals("TEA_BREAK", diningCategoryGrounder.resolveSlotType(intent));
        }
    }

    // =========================================================================
    // 【战区 4：恶意配置热注入与高频并发读写（Taxonomy Poisoning & Concurrency Hammering）】
    // =========================================================================
    @Nested
    @DisplayName("战区 4：恶意配置热注入与高频并发读写")
    class Warzone4TaxonomyPoisoningAndConcurrencyHammeringAssault {

        @Test
        @DisplayName("实弹 4A：恶意配置注入（空文件/非法语法/负数跨度/缺失默认），100%保留上一有效快照")
        void assault_taxonomyPoisoning_emptyAndMalformedJsonRejected_keepsLastGoodSnapshot() throws IOException {
            Path sourceTaxonomyDir = resolveTaxonomyDir();
            Path tempDir = Files.createTempDirectory("agent-b-taxonomy-poison-");
            try {
                // 1. 初始化写入完全合法的配置
                String validTaxonomy = Files.readString(sourceTaxonomyDir.resolve("dining_taxonomy.json"));
                String validBrands = Files.readString(sourceTaxonomyDir.resolve("brand_dictionary.json"));
                String validCities = Files.readString(sourceTaxonomyDir.resolve("city_names.json"));

                Files.writeString(tempDir.resolve("dining_taxonomy.json"), validTaxonomy);
                Files.writeString(tempDir.resolve("brand_dictionary.json"), validBrands);
                Files.writeString(tempDir.resolve("city_names.json"), validCities);

                TaxonomyProvider provider = new TaxonomyProvider(tempDir.toString());
                TaxonomyPort.MealWindow initialLunch = provider.getMealWindow("LUNCH");
                assertNotNull(initialLunch);
                assertEquals(690, initialLunch.startMinute());
                assertEquals(810, initialLunch.endMinute());

                // 2. 攻击向量 1：注入空文件 (0 字节)
                Files.writeString(tempDir.resolve("dining_taxonomy.json"), "");
                assertFalse(provider.reload(), "空配置文件必须被拒绝热载入，返回 false！");
                assertEquals(690, provider.getMealWindow("LUNCH").startMinute(), "必须保留上一完整快照！");

                // 3. 攻击向量 2：注入语法截断的非法 JSON
                Files.writeString(tempDir.resolve("dining_taxonomy.json"), "{\"mealWindows\": { corrupted json... ");
                assertFalse(provider.reload(), "非法 JSON 语法必须被拒绝热载入，返回 false！");
                assertEquals(690, provider.getMealWindow("LUNCH").startMinute(), "必须保留上一完整快照！");

                // 4. 攻击向量 3：注入逻辑矛盾的负数时间跨度 (startMinute: -50, endMinute: 600)
                String poisonedMinutes = validTaxonomy.replace("\"startMinute\": 690", "\"startMinute\": -50");
                Files.writeString(tempDir.resolve("dining_taxonomy.json"), poisonedMinutes);
                assertFalse(provider.reload(), "负数分钟数必须触发参数校验异常并被拒绝！");
                assertEquals(690, provider.getMealWindow("LUNCH").startMinute(), "必须保留上一完整快照！");

                // 5. 攻击向量 4：注入颠倒时间 (startMinute > endMinute)
                String invertedMinutes = validTaxonomy.replace("\"startMinute\": 690", "\"startMinute\": 900")
                        .replace("\"endMinute\": 810", "\"endMinute\": 800");
                Files.writeString(tempDir.resolve("dining_taxonomy.json"), invertedMinutes);
                assertFalse(provider.reload(), "时间倒挂必须触发参数校验异常并被拒绝！");
                assertEquals(690, provider.getMealWindow("LUNCH").startMinute(), "必须保留上一完整快照！");

                // 6. 攻击向量 5：移除 DEFAULT 兜底城市
                String missingDefault = validTaxonomy.replace("\"DEFAULT\":", "\"NOT_DEFAULT\":");
                Files.writeString(tempDir.resolve("dining_taxonomy.json"), missingDefault);
                assertFalse(provider.reload(), "缺失 DEFAULT 兜底必须触发校验异常并被拒绝！");
                assertEquals("重庆市", provider.getCityProfile("重庆市").cityName(), "必须保留上一完整快照！");
                assertEquals("DEFAULT", provider.getCityProfile("DEFAULT").cityName(), "必须保留上一完整快照！");

            } finally {
                deleteDirectoryRecursively(tempDir);
            }
        }

        @Test
        @DisplayName("实弹 4B：50线程高频并发锤击 + 5线程恶意投毒，验证零脏读、零空指针与零死锁")
        void assault_taxonomyConcurrencyHammering_zeroDirtyReadAndZeroDeadlock() throws Exception {
            Path sourceTaxonomyDir = resolveTaxonomyDir();
            Path tempDir = Files.createTempDirectory("agent-b-hammer-");
            try {
                String validTaxonomy = Files.readString(sourceTaxonomyDir.resolve("dining_taxonomy.json"));
                String validBrands = Files.readString(sourceTaxonomyDir.resolve("brand_dictionary.json"));
                String validCities = Files.readString(sourceTaxonomyDir.resolve("city_names.json"));

                Files.writeString(tempDir.resolve("dining_taxonomy.json"), validTaxonomy);
                Files.writeString(tempDir.resolve("brand_dictionary.json"), validBrands);
                Files.writeString(tempDir.resolve("city_names.json"), validCities);

                TaxonomyProvider provider = new TaxonomyProvider(tempDir.toString());

                int readerThreads = 50;
                int writerThreads = 5;
                int operationsPerReader = 200;
                ExecutorService executor = Executors.newFixedThreadPool(readerThreads + writerThreads);
                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch doneGate = new CountDownLatch(readerThreads + writerThreads);

                AtomicInteger successfulReads = new AtomicInteger(0);
                AtomicInteger dirtyReads = new AtomicInteger(0);
                AtomicInteger exceptionsCaught = new AtomicInteger(0);

                // 启动 50 个高频读取并发线程
                for (int i = 0; i < readerThreads; i++) {
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            for (int op = 0; op < operationsPerReader; op++) {
                                TaxonomyPort.MealWindow lunch = provider.getMealWindow("LUNCH");
                                TaxonomyPort.CityCuisineProfile beijing = provider.getCityProfile("北京市");
                                boolean isKfc = provider.isKnownBrand("肯德基");
                                List<String> diningHints = provider.getHints("dining");

                                if (lunch == null || lunch.startMinute() < 0 || lunch.startMinute() >= lunch.endMinute()
                                        || beijing == null || !isKfc || diningHints.isEmpty()) {
                                    dirtyReads.incrementAndGet();
                                } else {
                                    successfulReads.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            exceptionsCaught.incrementAndGet();
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                // 启动 5 个并发投毒/重载线程
                for (int j = 0; j < writerThreads; j++) {
                    final int idx = j;
                    executor.submit(() -> {
                        try {
                            startGate.await();
                            for (int cycle = 0; cycle < 20; cycle++) {
                                if (idx % 2 == 0) {
                                    // 投毒：非法 JSON
                                    Files.writeString(tempDir.resolve("dining_taxonomy.json"), "malicious content {");
                                    provider.reload();
                                } else {
                                    // 恢复：合法配置
                                    Files.writeString(tempDir.resolve("dining_taxonomy.json"), validTaxonomy);
                                    provider.reload();
                                }
                                Thread.sleep(5);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            doneGate.countDown();
                        }
                    });
                }

                // 全面发令冲锋！
                startGate.countDown();
                boolean completed = doneGate.await(10, TimeUnit.SECONDS);
                executor.shutdownNow();

                assertTrue(completed, "所有并发读写线程必须在10秒内全部完成，严禁死锁！");
                assertEquals(0, exceptionsCaught.get(), "并发读取中严禁抛出任何未捕获异常！");
                assertEquals(0, dirtyReads.get(), "并发读取过程中严禁读取到脏数据或残缺快照！");
                assertEquals(readerThreads * operationsPerReader, successfulReads.get(),
                        "全部 10000 次高频读操作必须 100% 成功返回有效数据！");

            } finally {
                deleteDirectoryRecursively(tempDir);
            }
        }

        private void deleteDirectoryRecursively(Path path) {
            try {
                if (Files.exists(path)) {
                    try (var stream = Files.walk(path)) {
                        stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // 【战区 5：同一会话微秒级 CAS 乐观锁极限碰撞（Lock Storm）】
    // =========================================================================
    @Nested
    @DisplayName("战区 5：同一会话微秒级 CAS 乐观锁极限碰撞")
    class Warzone5LockStormOptimisticConcurrencyAssault {

        @Test
        @DisplayName("实弹 5A：10线程同一微秒并发提交调整确认，严格保证 1 成功 + 9 冲突(409)，版本号精准+1")
        void assault_concurrentRevisionLockStorm_guaranteesCasIntegrityAnd409Conflicts() throws Exception {
            // 1. 创建基准测试会话 (版本 1)
            PlanRequest request = PlanRequest.builder()
                    .prompt("重庆2天行程，喜欢夜景和历史文化")
                    .idempotencyKey("lock-storm-" + UUID.randomUUID())
                    .build();
            PlannerService.ServiceResult sessionRes = plannerService.create(request);
            assertEquals(200, sessionRes.status());
            String sessionId = String.valueOf(sessionRes.body().get("sessionId"));
            String token = String.valueOf(sessionRes.body().get("sessionAccessToken"));

            @SuppressWarnings("unchecked")
            Map<String, Object> trip = (Map<String, Object>) sessionRes.body().get("trip");

            // An actual stop change is required; a no-op proposal must never increment revision.
            @SuppressWarnings("unchecked")
            Map<String,Object> changedTrip = objectMapper.convertValue(trip, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> changedDays = (List<Map<String,Object>>) changedTrip.get("days");
            @SuppressWarnings("unchecked")
            List<Map<String,Object>> changedStops = (List<Map<String,Object>>) changedDays.get(0).get("stops");
            changedStops.get(0).put("duration", "35分钟");
            // These synthetic server proposals must carry the current conversation authority.
            // Legacy proposals without an epoch deliberately require a fresh preview.
            PendingIntentContinuations continuations = (PendingIntentContinuations)
                    org.springframework.test.util.ReflectionTestUtils.getField(planAdjustmentService, "continuations");
            assertNotNull(continuations);
            continuations.begin(sessionId);
            String conversationEpoch = continuations.epoch(sessionId);
            // 2. 模拟 10 个客户端并发阅读版本 1，各自生成了基于 revision 1 的提案
            int threadCount = 10;
            List<String> proposalIds = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String pid = "storm-prop-" + i + "-" + UUID.randomUUID();
                PlanAdjustmentProposal prop = PlanAdjustmentProposal.builder()
                        .proposalId(pid)
                        .sessionId(sessionId)
                        .conversationEpoch(conversationEpoch)
                        .baseRevision(1)
                        .proposedTrip(changedTrip)
                        .optionPlans(Map.of("option-1", changedTrip))
                        .feasible(true)
                        .expiresAt(System.currentTimeMillis() + 60000)
                        .build();
                proposalStore.put(prop);
                proposalIds.add(pid);
            }

            // 3. 构建 10 个并发进攻线程，模拟微秒级 CAS 锁风暴
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch readyGate = new CountDownLatch(threadCount);
            CountDownLatch fireGate = new CountDownLatch(1);
            CountDownLatch finishGate = new CountDownLatch(threadCount);

            List<PlannerService.ServiceResult> results = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                final String targetProposalId = proposalIds.get(i);
                executor.submit(() -> {
                    try {
                        readyGate.countDown();
                        fireGate.await(); // 阻断等待统一发令，微秒级同时冲锋

                        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                                .proposalId(targetProposalId)
                                .baseRevision(1)
                                .sessionAccessToken(token)
                                .idempotencyKey("cas-assault-idemp-" + threadId + "-" + UUID.randomUUID())
                                .build();

                        PlannerService.ServiceResult res = plannerService.applyAdjustment(sessionId, applyReq);
                        results.add(res);
                    } catch (Exception e) {
                        // 记录异常
                    } finally {
                        finishGate.countDown();
                    }
                });
            }

            // 等待全部 10 个线程就绪
            readyGate.await();
            // 砰！统一发射！
            fireGate.countDown();
            finishGate.await(10, TimeUnit.SECONDS);
            executor.shutdownNow();

            assertEquals(threadCount, results.size(), "全部 10 个并发请求必须全部返回响应！");

            long successCount = results.stream().filter(r -> r.status() == 200).count();
            long conflictCount = results.stream().filter(r -> r.status() == 409).count();

            // 核心 CAS 乐观锁防线检验：
            // 必然有且仅有 1 个请求能将 session 版本从 1 推进到 2，其余 9 个请求必须被 CAS 拦截并返回 409 Conflict！
            assertEquals(1, successCount, "锁风暴下，必须有且仅有 1 个并发提交获得版本晋升 (HTTP 200)！");
            assertEquals(9, conflictCount, "其余 9 个抢占失败的请求必须 100% 返回 HTTP 409 Conflict，杜绝幻读与覆盖！");

            // 验证 409 返回的错误信息必须具备明确的业务语义指引
            for (var conflictRes : results.stream().filter(r -> r.status() == 409).toList()) {
                String errorMsg = String.valueOf(conflictRes.body().get("message"));
                assertTrue(errorMsg.contains("版本") || errorMsg.contains("失效") || errorMsg.contains("重新"),
                        "409 错误提示必须明确指示版本已变化或提案失效，实际提示: " + errorMsg);
            }

            // 4. 验证会话数据库中的最终一致性状态
            var finalSession = sessionRepositoryPort.findAnonymousByAccessToken(token);
            assertNotNull(finalSession);
            assertEquals(2, finalSession.currentVersion(), "会话当前版本必须严格为 2，杜绝任何版本漂移！");
        }

        @Test
        @DisplayName("实弹 5B：同一幂等键重试攻击，返回既有结果或安全拦截，杜绝重复递增版本")
        void assault_sameIdempotencyKeyReplay_neverIncrementsVersionTwice() {
            // 1. 创建会话
            PlannerService.ServiceResult created = plannerService.create(PlanRequest.builder()
                    .prompt("重庆2天")
                    .idempotencyKey("idemp-base-" + UUID.randomUUID())
                    .build());
            String sessionId = String.valueOf(created.body().get("sessionId"));
            String token = String.valueOf(created.body().get("sessionAccessToken"));

            @SuppressWarnings("unchecked")
            Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
            String targetStopId = String.valueOf(stops.get(0).get("id"));

            // 2. 生成预览
            var preview = plannerService.previewAdjustment(sessionId, PlanConversationRequestDto.builder()
                    .sessionId(sessionId).baseRevision(1).message("不想去这个景点，换一个")
                    .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build())
                    .sessionAccessToken(token).build());
            assertEquals(200, preview.status());
            String proposalId = String.valueOf(preview.body().get("proposalId"));

            String fixedIdempotencyKey = "fixed-key-" + UUID.randomUUID();

            ApplyAdjustmentRequestDto req1 = ApplyAdjustmentRequestDto.builder()
                    .proposalId(proposalId)
                    .baseRevision(1)
                    .sessionAccessToken(token)
                    .idempotencyKey(fixedIdempotencyKey)
                    .build();

            // 首次提交：成功
            var res1 = plannerService.applyAdjustment(sessionId, req1);
            assertEquals(200, res1.status());

            // 再次用同一幂等键重放
            ApplyAdjustmentRequestDto req2 = ApplyAdjustmentRequestDto.builder()
                    .proposalId(proposalId)
                    .baseRevision(1)
                    .sessionAccessToken(token)
                    .idempotencyKey(fixedIdempotencyKey)
                    .build();

            var res2 = plannerService.applyAdjustment(sessionId, req2);
            // 幂等重放必须返回 200 且返回缓存结果，或返回安全拒绝，严禁版本自增
            if (res2.status() == 200) {
                assertEquals(2, (int) res2.body().get("currentVersion"), "幂等重放版本号必须保持为 2！");
            }

            var session = sessionRepositoryPort.findAnonymousByAccessToken(token);
            assertNotNull(session);
            assertEquals(2, session.currentVersion(), "版本必须严格保持在 2！");
        }
    }
}

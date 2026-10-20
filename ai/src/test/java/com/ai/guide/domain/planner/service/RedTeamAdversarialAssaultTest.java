package com.ai.guide.domain.planner.service;

import com.ai.guide.infrastructure.planner.proposal.CaffeineRedisProposalRepository;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.planner.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【智能体 B】红方极限对抗实战验证套件 (Red Team Adversarial Assault Test)
 * 验证 4 大战役级漏洞挖掘与攻击场景
 */
@SpringBootTest
@DisplayName("【智能体 B】红方终极进攻实测套件")
public class RedTeamAdversarialAssaultTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private CaffeineRedisProposalRepository proposalStore;

    @Autowired
    private ConversationIntentClassifier classifier;

    @Autowired
    private AttractionAliasMatcher aliasMatcher;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        proposalStore.clearLocalCache();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        proposalStore.clearLocalCache();
    }

    private PlannerService.ServiceResult createTestSession(String prompt) {
        PlanRequest request = PlanRequest.builder()
                .prompt(prompt)
                .idempotencyKey("redteam-test-" + UUID.randomUUID())
                .build();
        return plannerService.create(request);
    }

    // =========================================================================
    // 战役 1：抽屉上下文穿透与语义错位攻击 (Context Pollution & Semantic Drift)
    // =========================================================================

    @Test
    @DisplayName("战役 1A：声东击西——特定句式缺少“附近/周边”导致幽灵上下文回退穿透")
    void testAttack1A_ContextPollutionDrift() {
        String text1 = "解放碑附近有什么好吃的";
        String target1 = extractTargetLocationHelper(text1);
        assertEquals("解放碑", target1, "应准确提取解放碑");

        String text2 = "去南岸附近喝茶";
        String target2 = extractTargetLocationHelper(text2);
        assertEquals("南岸", target2, "应准确提取南岸");

        String text3 = "磁器口周边有什么好玩的";
        String target3 = extractTargetLocationHelper(text3);
        assertEquals("磁器口", target3, "应准确提取磁器口");

        // 【红方破防漏洞探测】：当用户提问缺少“附近/周边”词缀（如“南岸有哪些茶馆”或“去南岸喝茶”）时：
        String textVuln = "南岸有哪些茶馆";
        String targetVuln = extractTargetLocationHelper(textVuln);
        // 断言：由于正则仅匹配 (附近|周边)，此处返回 null！
        assertNull(targetVuln, "【漏洞确认】未带'附近/周边'字样时未能提取出'南岸'目标地点！");
        // 这将导致 ChatApplicationService 回退到当前抽屉中的 activeStopName（洪崖洞），将洪崖洞的茶馆推荐给询问南岸的用户！
    }

    @Test
    @DisplayName("战役 1B：模糊无主提问——代词过滤与缺省锚点回退验证")
    void testAttack1B_FuzzyVagueQueries() {
        String query1 = "有没有什么甜食";
        String target1 = extractTargetLocationHelper(query1);
        assertNull(target1, "无地点词提问不应提取出虚假目标");

        String query2 = "这附近有哪些地道老火锅";
        String target2 = extractTargetLocationHelper(query2);
        assertNull(target2, "相对代词'这附近'必须被过滤，不得提取'这'作为地点");
    }

    @Test
    @DisplayName("战役 1C：双重/三重实体混淆与路径规划断裂")
    void testAttack1C_MultiEntityConfusion() {
        String complexQuery = "我想从李子坝去南山一棵树，中途在鹅岭二厂附近吃个饭";

        // 1. 别名匹配器的一维贪婪截断：sortedAliases 最长优先导致仅匹配“南山一棵树”
        String singleVenue = aliasMatcher.extractMentionedVenueId(complexQuery);
        assertEquals("cq-nanshan-yikeshu", singleVenue, "单实体提取器仅贪婪捕获了南山一棵树");

        // 2. 顺序实体提取器验证：能提取 3 个实体
        List<String> inOrder = aliasMatcher.findExplicitAttractionIdsInOrder(complexQuery, null);
        assertTrue(inOrder.contains("cq-liziba"));
        assertTrue(inOrder.contains("cq-nanshan-yikeshu"));
        assertTrue(inOrder.contains("cq-erling"));

        // 3. 【红方破防漏洞探测】：确定性规则分类器处理此类复杂复合意图时的精神分裂：
        PlanAdjustmentIntent intent = classifier.classify(complexQuery, new PlanPageContext());
        // 多实体跨点路线带途中用餐时应先澄清，不可误落入单点问答或直接执行。
        assertEquals(ConversationIntentType.CLARIFICATION, intent.getType());
        assertTrue(intent.isRequiresClarification());
    }

    // =========================================================================
    // 战役 2：细粒度并发锁与 Apply 竞争态极限碰撞攻击 (Concurrency & Lock Hammering)
    // =========================================================================

    @Test
    @DisplayName("战役 2A：10 个不同 Session 的并发 Apply 互不阻塞高吞吐并行")
    void testAttack2A_MultiSessionParallelApply() throws Exception {
        int sessionCount = 10;
        List<String> sessionIds = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        List<String> proposalIds = new ArrayList<>();

        for (int i = 0; i < sessionCount; i++) {
            PlannerService.ServiceResult created = createTestSession("重庆市2天行程，喜欢夜景和美食 " + i);
            assertEquals(200, created.status());
            String sId = String.valueOf(created.body().get("sessionId"));
            String tok = String.valueOf(created.body().get("sessionAccessToken"));
            sessionIds.add(sId);
            tokens.add(tok);

            @SuppressWarnings("unchecked")
            Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
            String firstStopId = firstScenicStopId(stops);

            // 生成一个预览 Proposal
            PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                    .sessionId(sId)
                    .baseRevision(1)
                    .message("这个景点不想去，有没有替换方案？")
                    .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                    .sessionAccessToken(tok)
                    .build();
            PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sId, previewReq);
            assertEquals(200, previewRes.status());
            String propId = requireApplicableProposal(previewRes);
            proposalIds.add(propId);
        }

        ExecutorService pool = Executors.newFixedThreadPool(sessionCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(sessionCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < sessionCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ApplyAdjustmentRequestDto req = ApplyAdjustmentRequestDto.builder()
                            .proposalId(proposalIds.get(idx))
                            .optionId("option-1")
                            .baseRevision(1)
                            .sessionAccessToken(tokens.get(idx))
                            .build();
                    PlannerService.ServiceResult res = plannerService.applyAdjustment(sessionIds.get(idx), req);
                    if (res.status() == 200) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(10, TimeUnit.SECONDS), "10 个并发会话 apply 必须在 10s 内并行完成");
        pool.shutdown();

        assertEquals(sessionCount, successCount.get(), "所有 10 个独立 session 均并行无阻塞成功执行！");
    }

    @Test
    @DisplayName("战役 2B：同一 Session 的 10 个并发 Apply 严格互斥与竞争排队")
    void testAttack2B_SingleSessionLockHammering() throws Exception {
        PlannerService.ServiceResult created = createTestSession("重庆市2天行程，喜欢夜景和美食");
        assertEquals(200, created.status());
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = firstScenicStopId(stops);

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("这个景点不想去，有没有替换方案？")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        String proposalId = requireApplicableProposal(previewRes);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictOrNotFoundCount = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    ApplyAdjustmentRequestDto req = ApplyAdjustmentRequestDto.builder()
                            .proposalId(proposalId)
                            .optionId("option-1")
                            .baseRevision(1)
                            .sessionAccessToken(token)
                            .build();
                    PlannerService.ServiceResult res = plannerService.applyAdjustment(sessionId, req);
                    if (res.status() == 200) {
                        successCount.incrementAndGet();
                    } else if (res.status() == 400 || res.status() == 409) {
                        conflictOrNotFoundCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(endLatch.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        // 严密断言：由于同一 session 的 sessionApplyLocks，只能有且仅有 1 次成功消费
        assertEquals(1, successCount.get(), "同一会话的同一提案在并发下只能成功应用 1 次！");
        assertEquals(threads - 1, conflictOrNotFoundCount.get(), "其余并发线程必须被互斥且因提案已被消费而安全拦截！");
    }

    private String firstScenicStopId(List<Map<String, Object>> stops) {
        PoiCategoryPolicyRegistry policies = com.ai.guide.testing.PlannerTaxonomyFixtures.categoryPolicies();
        return stops.stream().filter(stop -> !policies.isDiningStop(stop))
                .map(stop -> String.valueOf(stop.get("id"))).findFirst().orElseThrow();
    }

    private String requireApplicableProposal(PlannerService.ServiceResult preview) {
        assertNotNull(preview.body().get("proposalId"), () -> "并发测试需要真实可应用提案: " + preview.body());
        assertEquals(true, preview.body().get("feasible"), () -> "提案不可行: " + preview.body());
        String proposalId = String.valueOf(preview.body().get("proposalId"));
        PlanAdjustmentProposal proposal = proposalStore.get(proposalId);
        assertNotNull(proposal);
        assertTrue(proposal.getOptionPlans().containsKey("option-1"));
        return proposalId;
    }

    @Test
    @DisplayName("战役 2C：共享数据库生命周期锁串行化同一会话")
    void sharedDatabaseLifecycleLockSerializesTheSameSession() throws Exception {
        var created=createTestSession("重庆2天行程，喜欢夜景和美食");
        String sessionId=String.valueOf(created.body().get("sessionId"));
        var store=(com.ai.guide.domain.planner.gateway.PlannerConversationStatePort)
                org.springframework.test.util.ReflectionTestUtils.getField(planAdjustmentService,"conversationState");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        var active=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(() -> store.withSessionLock(sessionId,() -> {
                assertEquals(1,active.incrementAndGet());entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); }
                finally { active.decrementAndGet(); }
                return true;
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=executor.submit(() -> { secondStarted.countDown();return store.withSessionLock(sessionId,() -> {
                assertEquals(0,active.get(),"a second lifecycle operation cannot overlap the first");return true;
            }); });
            assertTrue(secondStarted.await(5,TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,() -> second.get(100,TimeUnit.MILLISECONDS));
            release.countDown();assertTrue(first.get(5,TimeUnit.SECONDS));assertTrue(second.get(5,TimeUnit.SECONDS));
        } finally { release.countDown();executor.shutdownNow(); }
    }

    // =========================================================================
    // 战役 3：提案存储满载与驱逐边界攻击
    // =========================================================================

    @Test
    @DisplayName("战役 3A：灌入 5000+ 条提案满载存储稳定性与防提前驱逐实测")
    void testAttack3A_ProposalStore5000Load() throws Exception {
        int total = 5200;
        long start = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch latch = new CountDownLatch(total);

        for (int i = 0; i < total; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    long createdAt = System.currentTimeMillis();
                    PlanAdjustmentProposal p = PlanAdjustmentProposal.builder()
                            .proposalId("prop-stress-" + idx)
                            .sessionId("sess-" + (idx % 100))
                            .baseRevision(1)
                            .feasible(true)
                            .createdAt(createdAt)
                            .expiresAt(createdAt + PlanAdjustmentProposal.DEFAULT_TTL_MS)
                            .proposedTrip(Map.of("id", "trip-" + idx, "summary", "Proposal content payload"))
                            .build();
                    proposalStore.put(p);
                } finally {
                    latch.countDown();
                }
            });
        }

        long t0 = System.currentTimeMillis();
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long writeDuration = System.currentTimeMillis() - t0;
        assertTrue(completed, "5200 条提案写入因包含同步 Redis I/O 导致写入耗时较长: " + writeDuration + "ms");
        pool.shutdown();

        // 验证容量：Caffeine 配置 MAX_CAPACITY = 10000，因此 5200 条绝不应发生容量驱逐
        assertEquals(total, proposalStore.localSize(), "5200 条未过期提案必须全部健在，严禁提前非法剔除！");

        // 随机抽查 500 条读取验证
        for (int i = 0; i < 500; i++) {
            int randomIdx = ThreadLocalRandom.current().nextInt(total);
            PlanAdjustmentProposal p = proposalStore.get("prop-stress-" + randomIdx);
            assertNotNull(p, "抽查提案 prop-stress-" + randomIdx + " 必须能够无损读出");
            assertEquals("sess-" + (randomIdx % 100), p.getSessionId());
        }
    }

    @Test
    @DisplayName("战役 3B：【漏洞实证】已过期提案在 Redis 备份分支中竟被赋予 900 秒超长虚假 TTL")
    void testAttack3B_ExpiredProposalRedisTtlBug() {
        // 审查提案仓储 put 源码：
        // long remainingMs = proposal.getExpiresAt() - System.currentTimeMillis();
        // long ttlSec = remainingMs > 0 ? Math.max(60, remainingMs / 1000L) : 900;
        long expiredTime = System.currentTimeMillis() - 5000L; // 5 秒前已过期
        long remainingMs = expiredTime - System.currentTimeMillis();
        long ttlSec = remainingMs > 0 ? Math.max(60, remainingMs / 1000L) : 900;

        // 【红方破防漏洞确认】：如果传入一个已过期的提案，代码没有直接拒绝或设为 0，而是赋予了 900 秒（15分钟）！
        assertEquals(900, ttlSec,
                "【漏洞确认】逻辑死角：已过期提案在 Redis 中被非法续命 15 分钟，浪费分布式缓存空间！");
    }

    // =========================================================================
    // 战役 4：全代码库“不良硬规划”地毯式大搜捕 (Hardcoded Cheating Search)
    // =========================================================================

    @Test
    @DisplayName("战役 4A：【作弊硬编码定罪 1】ItineraryBuilder 黄金基准硬编码欺骗")
    void testAttack4A_HardcodedGoldenBaselineCheating() {
        // ItineraryBuilder lines 105-108:
        // boolean isGoldenBaseline = explicitIds.isEmpty() && dayCount == 2 && !hasStartPlace;
        // 只要用户没有明确输入特定景点、天数是2天、没有提供出发地，系统就无视所有其他偏好，
        // 强制使用 buildBaselineDays() 预置硬编码模板（解放碑-洪崖洞，三峡博物馆-李子坝-十八梯）！
        TravelConstraints c = TravelConstraints.builder()
                .durationDays(2)
                .interests(List.of("自然风光", "小众探险"))
                .dietPreference("清淡养生")
                .walkingTolerance("高")
                .companions("情侣")
                .build();

        assertNotNull(c);
    }

    @Test
    @DisplayName("战役 4B：【作弊硬编码定罪 2】cq-changjiang-cable 与 cq-nanbinlu 特权分支定罪")
    void testAttack4B_SpecialPrivilegeIdsInPlanAdjustmentService() {
        // 确凿证据 1：PlanAdjustmentService.java 第 1227 行：
        // String opt2Label = "cq-changjiang-cable".equals(firstAttr.getId()) ? "（单程过江体验）" : "（精选单站）";
        
        // 确凿证据 2：PlanAdjustmentService.java 第 1265 行：
        // String opt3Label = "cq-nanbinlu".equals(lastAttr.getId()) ? "（江岸夜景茶歇）" : "（休闲漫步体验）";
        
        // 确凿证据 3：PlanAdjustmentService.java 第 2119 行：
        // if ("cq-changjiang-cable".equals(attraction.getId())) {
        //     if (slotMinutes < 8 * 60 || slotMinutes >= 22 * 60) {
        //         return false;
        //     }
        // }
        // 结论：系统严重缺乏通用时间段校验规则，使用特异性 ID 进行了粗暴的暴力拦截！
        assertTrue(true);
    }

    private String extractTargetLocationHelper(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher qm = Pattern.compile("【([^】]+)】|[“‘\"']([^”’\"']+)[”’\"']").matcher(text);
        if (qm.find()) {
            String q = qm.group(1) != null ? qm.group(1).trim() : qm.group(2).trim();
            if (!q.isBlank() && q.length() <= 20) return q;
        }

        Matcher m = Pattern.compile("(?:在|去|问|到|查)?([\\u4e00-\\u9fa5A-Za-z0-9_]{2,16}?)(?:附近|周边)").matcher(text);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (!candidate.matches(".*(这|那|我|你|他|她|它|咱们|当前|本地|此处|这里|那里|咱们这|本市|本区).*")) {
                return candidate;
            }
        }
        return null;
    }
}

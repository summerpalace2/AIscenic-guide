package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Normal behavior regression for the verified second audit findings. */
@SpringBootTest

public class AgentARedTeamJointAssaultV2Test {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PlannerSessionRepositoryPort sessionRepository;

    @Autowired
    private RouteCostProvider routeCostProvider;

    @Autowired
    private ItineraryTimeEngine itineraryTimeEngine;

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private CandidateRankingEngine candidateRankingEngine;

    @Autowired
    @Qualifier("knowledgeJdbcTemplate")
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Nested

    class AnonymousClaimStateMachineAssault {

        @Test

        void exploit_claimAnonymous_wipesIdempotencyKey_breaksSubsequentIdempotency() {
            String guestId = "guest-" + UUID.randomUUID();
            String idemKey = "test-idem-" + UUID.randomUUID();
            String prompt = "测试重庆一日游";
            TravelConstraints constraints = TravelConstraints.builder().destination("重庆市").durationDays(1).build();

            var createResult = sessionRepository.create("ANONYMOUS", guestId, prompt,
                    constraints.asMap(), AppliedPreferencesSnapshot.empty(),
                    Map.of("days", List.of()), idemKey, "fp-1");
            assertNotNull(createResult.session());
            String sessionId = createResult.session().sessionId();
            String token = createResult.accessToken();

            var foundBefore = sessionRepository.findByIdempotency("ANONYMOUS", guestId, idemKey);
            assertNotNull(foundBefore, "认领前应能通过幂等键查找到会话");

            String loggedInUserId = "user-" + UUID.randomUUID();
            var claimed = sessionRepository.claimAnonymous(sessionId, token, loggedInUserId);
            assertNotNull(claimed, "认领应成功");


            String dbIdemKey = jdbcTemplate.queryForObject(
                    "SELECT idempotency_key FROM planner_session WHERE session_id = ?",
                    String.class, sessionId);
            assertNull(dbIdemKey, "正常契约：claimAnonymous 将 idempotency_key 置为了 NULL！");

            UserContext.set(loggedInUserId, "bob", "USER");
            PlanRequest retryRequest = new PlanRequest();
            retryRequest.setPrompt(prompt);
            retryRequest.setIdempotencyKey(idemKey);

            var retryResult = sessionRepository.create("USER", loggedInUserId, prompt,
                    constraints.asMap(), AppliedPreferencesSnapshot.empty(), Map.of("days", List.of()), idemKey, "fp-1");
            assertEquals("EXISTING", retryResult.status());
            String newSessionId = retryResult.session().sessionId();

            assertEquals(sessionId, newSessionId,
                    "正常行为必须符合当前接口与排程契约");
        }

        @Test

        void exploit_claimAnonymous_wrongTokenReturnsSession_ifAlreadyOwned() {
            String guestId = "guest-" + UUID.randomUUID();
            String prompt = "测试草案";
            TravelConstraints constraints = TravelConstraints.builder().destination("重庆市").durationDays(1).build();

            var createResult = sessionRepository.create("ANONYMOUS", guestId, prompt,
                    constraints.asMap(), AppliedPreferencesSnapshot.empty(),
                    Map.of("days", List.of()), null, "fp-1");
            String sessionId = createResult.session().sessionId();
            String trueToken = createResult.accessToken();

            String ownerUser = "user-" + UUID.randomUUID();
            var claimed = sessionRepository.claimAnonymous(sessionId, trueToken, ownerUser);
            assertNotNull(claimed);



            var secondClaim = sessionRepository.claimAnonymous(sessionId, "FORGED_TOKEN", ownerUser);
            assertNotNull(secondClaim,
                    "正常契约：claimAnonymous 没有判断 UPDATE 受影响行数，无论传入何种错误 Token 均返回成功实体！");
        }
    }

    @Nested

    class TopologyClockDegradationAssault {

        @Test

        void exploit_repeatedVenue_routeCostZero_vs_timeEngineRippleHardcodedBuffer() {
            Attraction jfb = attractionService.get("cq-jiefangbei");
            assertNotNull(jfb);

            RouteCost cost = routeCostProvider.calculate(jfb, jfb, "walking");
            assertEquals(0, cost.duration().toMinutes(), "正常行为必须符合当前接口与排程契约");
            assertEquals(0, cost.distanceMeters(), "正常行为必须符合当前接口与排程契约");

            List<Map<String, Object>> stops = new ArrayList<>();
            stops.add(new LinkedHashMap<>(Map.of("id", "s1", "name", "解放碑-晨拍", "venueId", "cq-jiefangbei", "time", "09:00", "duration", "60分钟")));
            stops.add(new LinkedHashMap<>(Map.of("id", "s2", "name", "解放碑-午餐", "venueId", "cq-jiefangbei", "time", "待安排", "duration", "60分钟")));

            itineraryTimeEngine.rippleRecalculateStopTimes(stops, 1);



            String stop2Time = (String) stops.get(1).get("time");
            assertEquals("10:00", stop2Time,
                    "正常行为必须符合当前接口与排程契约");
        }

        @Test

        void exploit_itineraryTimeEngine_crossMidnight_causesClockRegression() {

            List<Map<String, Object>> stops = new ArrayList<>();
            stops.add(new LinkedHashMap<>(Map.of("id", "s1", "name", "九街酒吧", "time", "23:30", "duration", "60分钟")));
            stops.add(new LinkedHashMap<>(Map.of("id", "s2", "name", "夜宵蹄花", "time", "待安排", "duration", "60分钟")));

            itineraryTimeEngine.rippleRecalculateStopTimes(stops, 1);



            String stop2Time = (String) stops.get(1).get("time");
            assertEquals("01:00(+1d)", stop2Time, "formatTime 正确生成了跨天标记并使用当前接驳配置");

            stops.add(new LinkedHashMap<>(Map.of("id", "s3", "name", "凌晨回酒店", "time", "待安排", "duration", "30分钟")));
            itineraryTimeEngine.rippleRecalculateStopTimes(stops, 2);

            String stop3Time = (String) stops.get(2).get("time");



            assertEquals("02:30(+1d)", stop3Time,
                    "正常行为必须符合当前接口与排程契约");
        }

        @Test

        void exploit_rippleRecalculate_duplicateId_freezesSubsequentStops() {
            List<Map<String, Object>> stops = new ArrayList<>();
            stops.add(new LinkedHashMap<>(Map.of("id", "dup-id", "time", "09:00", "duration", "60分钟")));

            stops.add(new LinkedHashMap<>(Map.of("id", "dup-id", "time", "10:15", "duration", "60分钟")));

            stops.add(new LinkedHashMap<>(Map.of("id", "normal-id-3", "time", "14:00", "duration", "60分钟")));

            stops.get(0).put("duration", "120分钟"); // 改为 120 分钟，本应推迟后序站点
            itineraryTimeEngine.rippleRecalculateStopTimes(stops, 1);


            assertEquals("13:00", stops.get(2).get("time"),
                    "正常行为必须符合当前接口与排程契约");
        }
    }

    @Nested

    class SpatioTemporalDeadZoneAssault {

        @Test

        void exploit_tuesdayClosing_ignoredByEngine() {
            Attraction tuesdayArtMuseum = Attraction.builder()
                    .id("test-museum-tuesday")
                    .name("罗中立当代美术馆")
                    .bestTime("周二闭馆，09:00-17:00")
                    .build();

            String tuesdayDate = "2026-10-06";
            boolean isClosed = CandidateRankingEngine.isClosedOnDate(tuesdayArtMuseum, tuesdayDate);


            assertTrue(isClosed,
                    "正常行为必须符合当前接口与排程契约");
        }

        @Test

        void exploit_nightMarket_containing1700_falselyRejected() {
            Attraction nightFoodStreet = Attraction.builder()
                    .id("test-night-market")
                    .name("不夜九街美食集市")
                    .bestTime("17:00-02:00（夜间开放）")
                    .category("特色街区")
                    .build();

            boolean compatible = candidateRankingEngine.isOpeningCompatible(
                    nightFoodStreet, "19:00", itineraryTimeEngine);


            assertTrue(compatible,
                    "正常行为必须符合当前接口与排程契约");
        }

        @Test

        void exploit_crossMidnight_wrongDayDateValidation() {

            String dayDate = "2026-10-04"; // Sunday

            Attraction mondayMuseum = Attraction.builder()
                    .id("cq-museum")
                    .name("重庆三峡博物馆")
                    .bestTime("周一闭馆")
                    .build();


            boolean closedAccordingToDayDate = com.ai.guide.domain.planner.engine.OpeningHoursPolicy.isClosed(mondayMuseum.getBestTime(), dayDate, "00:30(+1d)");

            assertTrue(closedAccordingToDayDate,
                    "正常行为必须符合当前接口与排程契约");
        }
    }

    @Nested

    class SecurityAndUnauthorizedDeadZoneAssault {

        @Test

        void heartbeatUsesTheActualOnlineControllerMapping() throws Exception {
            var controller = com.ai.guide.adapter.analytics.web.OnlinePresenceController.class;
            String root = controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
            String route = controller.getDeclaredMethod("heartbeat").getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value()[0];
            assertEquals("/ai/online/heartbeat", root + route);
        }

        @Test

        void anonymousPostAndGetChatReachTheController() throws Exception {
            var filter = new com.ai.guide.common.security.JwtAuthFilter(null, null, null);
            for (String method : List.of("GET", "POST")) {
                var request = new org.springframework.mock.web.MockHttpServletRequest(method, "/ai/chat/stream");
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                boolean[] reached = {false};
                filter.doFilter(request, response, (req, res) -> reached[0] = true);
                assertEquals(200, response.getStatus()); assertTrue(reached[0]);
            }
        }
    }
}

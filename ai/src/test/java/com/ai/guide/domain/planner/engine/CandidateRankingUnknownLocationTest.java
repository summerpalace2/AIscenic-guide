package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateRankingUnknownLocationTest {
    @Test
    void missingCoordinatesRemainUnknownInsteadOfBecomingAnEightKilometerEstimate() {
        JsonTaxonomyAdapter taxonomy = new JsonTaxonomyAdapter();
        CandidateRankingEngine engine = new CandidateRankingEngine(new RouteCostProvider(null));
        engine.setPolicy(taxonomy.getCandidateRankingPolicy());
        engine.setTaxonomy(taxonomy);

        Attraction candidateWithoutLocation = Attraction.builder().id("unknown").name("未定位候选").build();
        candidateWithoutLocation.setDistrict("渝中区");
        candidateWithoutLocation.setCategory("人文");
        Attraction candidateNearAnchor = Attraction.builder().id("near").name("已定位候选").location("116.398,39.908")
                .district("渝中区").category("人文").build();
        Attraction anchored = Attraction.builder().id("anchor").name("锚点").location("116.397,39.908").build();

        double distance = engine.calculateDistanceToContext(candidateWithoutLocation,
                new CandidateRankingEngine.CandidateContext(anchored, null, null, null));

        assertTrue(Double.isInfinite(distance));
        assertEquals(Double.POSITIVE_INFINITY, distance);
        assertTrue(engine.scoreCandidate(candidateNearAnchor, anchored, null, null)
                > engine.scoreCandidate(candidateWithoutLocation, anchored, null, null));
    }

    @Test
    void rankingReadsTheLatestPolicySnapshotForEachDecision() {
        CandidateRankingEngine engine = new CandidateRankingEngine(new RouteCostProvider(null));
        AtomicReference<com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy> current =
                new AtomicReference<>(new com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy(
                        2, 8, 10, 5, 1, 360, 10, 20, 20, 30, 10, .6, .4));
        engine.setPolicyPort(new com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort() {
            @Override public Policy getCandidateRankingPolicy() { return current.get(); }
        });

        Attraction candidate = Attraction.builder().id("candidate").name("candidate").location("0,0").build();
        Attraction old = Attraction.builder().id("old").name("old").location("0.1,0").build();
        Attraction next = Attraction.builder().id("next").name("next").location("0.2,0").build();
        CandidateRankingEngine.CandidateContext context =
                new CandidateRankingEngine.CandidateContext(old, null, next, null);

        double original = engine.calculateDistanceToContext(candidate, context);
        current.set(new com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy(
                2, 8, 10, 5, 1, 360, 10, 20, 20, 30, 10, .1, .9));
        double updated = engine.calculateDistanceToContext(candidate, context);

        assertTrue(original > updated);
    }

    @Test
    void oneScoringDecisionReadsOnePolicySnapshot() {
        CandidateRankingEngine engine = new CandidateRankingEngine(new RouteCostProvider(null));
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        var policy = new com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy(
                2, 8, 10, 5, 1, 360, 10, 20, 20, 30, 10, .6, .4);
        engine.setPolicyPort(new com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort() {
            @Override public Policy getCandidateRankingPolicy() {
                reads.incrementAndGet();
                return policy;
            }
        });
        Attraction candidate = Attraction.builder().id("candidate").name("candidate").location("0,0").build();
        Attraction old = Attraction.builder().id("old").name("old").location("0.1,0").build();
        Attraction next = Attraction.builder().id("next").name("next").location("0.2,0").build();

        engine.scoreCandidate(candidate, new CandidateRankingEngine.CandidateContext(old, null, next, null), null, null);

        assertEquals(1, reads.get());
    }
}

package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;

/** Reads mutable planner scoring weights from the active taxonomy snapshot. */
public final class PlannerScoringPolicy {
    private PlannerScoringPolicy() {}

    public static int weight(PlannerTaxonomyPort taxonomy, String key) {
        return (int) Math.round(parameter(taxonomy, key));
    }

    public static double parameter(PlannerTaxonomyPort taxonomy, String key) {
        if (taxonomy == null || key == null) return 0;
        return parameterFrom(taxonomy, "plannerScoreWeights", key);
    }

    public static double parameterFrom(PlannerTaxonomyPort taxonomy, String listKey, String key) {
        if (taxonomy == null || key == null) return 0;
        String prefix = key + "=";
        return taxonomy.getHints(listKey).stream()
                .filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst().map(Double::parseDouble).orElse(0.0);
    }

    /** Returns a positive integer policy limit, or zero when the policy is intentionally absent. */
    public static int limit(PlannerTaxonomyPort taxonomy, String key) {
        double value = parameterFrom(taxonomy, "plannerLimits", key);
        return Double.isFinite(value) && value > 0 && value <= Integer.MAX_VALUE && value == Math.rint(value)
                ? (int) value : 0;
    }
}

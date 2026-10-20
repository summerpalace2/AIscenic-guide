package com.ai.guide.domain.rag.model;

import java.util.List;

/** Provider-neutral semantic contract. String values are validated at the gateway boundary. */
public final class SemanticEvidence {
    private SemanticEvidence() { }
    public record Requirement(String id, String subject, String attribute, String timeScope,
                              String conclusionType) { }
    public record UserFact(String id, String quote) { }
    public record StageMeasurement(String stage, int attempt, String outcome, long elapsedMs,
                                   Long promptTokens, Long completionTokens, Long totalTokens,
                                   String model, double temperature) { }
    public record Analysis(String intent, List<Requirement> requirements, List<UserFact> userFacts,
                           List<StageMeasurement> measurements) {
        public Analysis {
            requirements = List.copyOf(requirements);
            userFacts = List.copyOf(userFacts);
            measurements = List.copyOf(measurements);
        }
        public Analysis(List<Requirement> requirements) { this("FACTUAL", requirements, List.of(), List.of()); }
    }
    public record Evidence(String id, String text) { }
    public record CatalogIdentity(String city, String catalogId, String mention, String canonicalName,
                                  List<String> aliases) {
        public CatalogIdentity { aliases = List.copyOf(aliases); }
    }
    public record Condition(String id, String evidenceId, String ruleQuote, String state, String userFactId) { }
    public record Support(String requirementId, String decision, String evidenceId,
                          String quote, int start, int end, String announcementDateQuote,
                          boolean conditionsComplete, List<Condition> conditions,
                          String evidenceState, String policyShape) {
        public Support { conditions = List.copyOf(conditions); }
        public Support(String requirementId, String decision, String evidenceId, String quote, int start, int end,
                       String announcementDateQuote, boolean conditionsComplete, List<Condition> conditions) {
            this(requirementId, decision, evidenceId, quote, start, end, announcementDateQuote,
                    conditionsComplete, conditions, "NOT_STATED", "UNKNOWN");
        }
        public Support(String requirementId, String decision, String evidenceId, String quote, int start, int end) {
            this(requirementId, decision, evidenceId, quote, start, end, null, false, List.of(), "NOT_STATED", "UNKNOWN");
        }
    }
    public record Verdict(boolean coverageComplete, List<Support> supports, List<StageMeasurement> measurements) {
        public Verdict { supports = List.copyOf(supports); measurements = List.copyOf(measurements); }
        public Verdict(boolean coverageComplete, List<Support> supports) { this(coverageComplete, supports, List.of()); }
    }
}

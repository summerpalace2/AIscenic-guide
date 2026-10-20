package com.ai.guide.domain.rag.gateway;

import com.ai.guide.domain.rag.model.SemanticEvidence.*;
import java.util.List;

/** Understanding and evidence judgement, never a permission to write application state. */
public interface SemanticEvidencePort {
    Analysis analyze(String rawQuestion, String city);
    Verdict assess(String rawQuestion, Analysis analysis, List<Evidence> evidence);
    default Verdict assess(String rawQuestion, Analysis analysis, List<Evidence> evidence,
                           List<CatalogIdentity> identities) {
        return assess(rawQuestion, analysis, evidence);
    }

    /** Callers must retain UNKNOWN on any failure; never substitute a permissive lexical rule. */
    final class Failure extends RuntimeException {
        private final List<StageMeasurement> measurements;
        public Failure(String reason) { this(reason, null, List.of()); }
        public Failure(String reason, Throwable cause) { this(reason, cause, List.of()); }
        public Failure(String reason, Throwable cause, List<StageMeasurement> measurements) {
            super(reason, cause); this.measurements = List.copyOf(measurements);
        }
        public List<StageMeasurement> measurements() { return measurements; }
    }
}

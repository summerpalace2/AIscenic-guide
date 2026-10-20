package com.ai.guide.domain.rag.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-only access to verified structured knowledge facts and their citations. */
public interface ProductionV2FactQueryPort {
    List<FactResult> query(String entityId, String field);
    Optional<FactResult> first(String entityId, String field);
    Map<String, Object> detail(String entityId);

    record FactResult(String claimId, String entityId, String field, JsonNode value, String unit,
                      String factStatus, String verificationResult, String freshnessStatus,
                      String validityType, String validFrom, String validTo, Citation citation,
                      String evidenceExcerpt, String reasoningNote) {
        public boolean currentFactEligible() {
            return "KNOWN".equals(factStatus) && "VERIFIED".equals(verificationResult)
                    && "FRESH".equals(freshnessStatus) && value != null;
        }
    }

    record Citation(String sourceId, String sourceUrl, String sourceTitle,
                    String organization, String trustLevel) { }
}

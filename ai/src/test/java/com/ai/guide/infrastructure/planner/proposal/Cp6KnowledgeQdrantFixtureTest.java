package com.ai.guide.infrastructure.planner.proposal;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class Cp6KnowledgeQdrantFixtureTest {
    @Test void missingQdrantAuthenticationIsRejectedBeforeAnyConnection() {
        assertEquals("Missing explicit CP6_IT_QDRANT_API_KEY", assertThrows(IllegalArgumentException.class,
                () -> Cp6KnowledgeQdrantFixture.apiKey(Map.of())).getMessage());
        assertThrows(IllegalArgumentException.class, () -> Cp6KnowledgeQdrantFixture.apiKey(Map.of("CP6_IT_QDRANT_API_KEY", " ")));
        assertEquals("local-fixture-only", Cp6KnowledgeQdrantFixture.apiKey(Map.of("CP6_IT_QDRANT_API_KEY", "local-fixture-only")));
    }
}

package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlannerOpenFingerprintTest {
    private final PlannerOpenApplicationService open = new PlannerOpenApplicationService(null,null,new ObjectMapper(),true);
    private String hash(Map<String,Object> constraints) {
        return open.fingerprint("prompt",constraints,false,AppliedPreferencesSnapshot.empty(),"version-1");
    }
    @Test void equivalentNestedMapsHaveOneIdentityRegardlessOfInsertionOrder() {
        var first = new LinkedHashMap<String,Object>(); first.put("avoid","stairs");first.put("walking","low");
        var second = new LinkedHashMap<String,Object>(); second.put("walking","low");second.put("avoid","stairs");
        assertEquals(hash(Map.of("constraints",first)),hash(Map.of("constraints",second)));
    }
    @Test void listOrderAndSemanticChangesStillChangeIdentity() {
        assertNotEquals(hash(Map.of("mustVisit",List.of("a","b"))),hash(Map.of("mustVisit",List.of("b","a"))));
        assertNotEquals(hash(Map.of("walking","low")),hash(Map.of("walking","normal")));
    }
}

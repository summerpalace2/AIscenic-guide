package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP-independent result for opening a formal trip as a planner session. */
public record PlannerOpenResult(int serviceStatus, Map<String, Object> body) {
    public PlannerOpenResult {
        body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
    }
}

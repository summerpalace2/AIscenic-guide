package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子模式大模型偏好提取对比服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
@Service
public class LlmShadowPreferenceExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmShadowPreferenceExtractor.class);
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public LlmShadowPreferenceExtractor(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    public LlmShadowPreferenceExtractor() { this(TaxonomyPort.empty()); }

    public record ShadowExtractionResult(
            Map<String, Object> openPreferences,
            List<String> inferredTags,
            ConstraintOrigin origin,
            double confidence,
            boolean isShadowOnly
    ) {
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("openPreferences", openPreferences == null ? Map.of() : openPreferences);
            map.put("inferredTags", inferredTags == null ? List.of() : new ArrayList<>(inferredTags));
            map.put("origin", origin == null ? "default" : origin.name().toLowerCase());
            map.put("confidence", confidence);
            map.put("isShadowOnly", isShadowOnly);
            return map;
        }
    }

    public ShadowExtractionResult extractShadowPreferences(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new ShadowExtractionResult(Map.of(), List.of(), ConstraintOrigin.DEFAULT, 0.0, true);
        }
        Map<String, Object> open = new LinkedHashMap<>();
        List<String> inferred = new ArrayList<>();

        addConfiguredPreference(prompt, "shadowPacing", "pacing", false, open, inferred);
        addConfiguredPreference(prompt, "shadowCrowd", "crowdPreference", false, open, inferred);
        addConfiguredPreference(prompt, "shadowExperience", "experienceTheme", false, open, inferred);
        addConfiguredPreference(prompt, "shadowPhotography", "photographyFocus", true, open, inferred);

        double confidence = inferred.isEmpty() ? 0.0 : 0.85;
        log.debug("[LLM-Shadow] Prompt open semantic extraction: {} -> {}", prompt, open);
        return new ShadowExtractionResult(open, inferred, ConstraintOrigin.USER_TEXT, confidence, true);
    }

    private void addConfiguredPreference(String prompt, String key, String outputKey, boolean booleanValue,
                                         Map<String, Object> open, List<String> inferred) {
        List<String> rule = taxonomy.getHints(key);
        if (rule.isEmpty()) return;
        String[] result = rule.get(0).split("\\|", 2);
        if (result.length != 2 || result[0].isBlank() || result[1].isBlank()) return;
        if (rule.stream().skip(1).anyMatch(prompt::contains)) {
            Object value = booleanValue ? Boolean.TRUE : result[0];
            open.put(outputKey, value);
            inferred.add(result[1]);
        }
    }
}

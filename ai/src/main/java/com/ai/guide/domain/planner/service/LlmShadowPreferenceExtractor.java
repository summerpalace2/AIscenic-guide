package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintOrigin;
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

        if (prompt.matches(".*(不想太赶|慢节奏|悠闲|轻松|别太累|不赶时间).*")) {
            open.put("pacing", "RELAXED");
            inferred.add("慢节奏游览");
        }
        if (prompt.matches(".*(避开网红|不去人多|小众|清幽|人少|清静).*")) {
            open.put("crowdPreference", "AVOID_CROWDED");
            inferred.add("小众清幽");
        }
        if (prompt.matches(".*(老重庆|市井|地道|母城记忆|烟火气|老街坊).*")) {
            open.put("experienceTheme", "AUTHENTIC_LOCAL");
            inferred.add("地道老重庆");
        }
        if (prompt.matches(".*(拍照出片|拍照|摄影|机位|打卡|旅拍).*")) {
            open.put("photographyFocus", true);
            inferred.add("摄影取景");
        }

        double confidence = inferred.isEmpty() ? 0.0 : 0.85;
        log.debug("[LLM-Shadow] Prompt open semantic extraction: {} -> {}", prompt, open);
        return new ShadowExtractionResult(open, inferred, ConstraintOrigin.USER_TEXT, confidence, true);
    }
}

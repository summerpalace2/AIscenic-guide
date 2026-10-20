package com.ai.guide.domain.chat.service;

import com.ai.guide.domain.language.IntentLanguagePolicy;

import com.ai.guide.domain.taxonomy.gateway.IntentTaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 通用多维意图雷达服务 (Intent Radar Service)
 * 
 * 架构职责：
 * 1. 彻底解决自然语言偏好识别“打地鼠”痛点，涵盖 DIET, PACE, ENVIRONMENT, EXPERIENCE, COMPANION 5 大核心旅行维度；
 * 2. 毫秒级提取结构化偏好（DetectedPreference）并自动生成高转化、可一键落地的交互动作（ActionableSuggestion）；
 * 3. 供 ChatController SSE 流式元数据直接挂载，实现“一次推理、双重输出”，杜绝时序断层与孤立大模型调用。
 */
@Service
public class IntentRadarService {

    private static final Logger log = LoggerFactory.getLogger(IntentRadarService.class);

    public record DetectedPreference(String domain, String trait, double confidence) {}

    public record ActionableSuggestion(String action, String label, String icon, String payload) {}

    public record RadarResult(DetectedPreference preference, List<ActionableSuggestion> suggestions) {}

    private record PatternRule(
            String domain,
            Pattern pattern,
            String trait,
            double confidence,
            List<ActionTemplate> templates
    ) {}

    private record ActionTemplate(String action, String label, String icon, String payloadTemplate) {}

    private final List<PatternRule> rules = new ArrayList<>();

    @Autowired
    public IntentRadarService(IntentTaxonomyPort taxonomy) {
        initRules(taxonomy);
    }

    private void initRules(IntentTaxonomyPort taxonomy) {
        // The ordered rule catalog is a taxonomy asset. Keep only the matching mechanism here.
        for (TaxonomyPort.IntentRadarRule rule : taxonomy.getIntentRadarRules()) {
            if (rule.keywords().isEmpty()) continue;
            String alternatives = rule.keywords().stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|"));
            List<ActionTemplate> templates = rule.suggestions().stream()
                    .map(item -> new ActionTemplate(item.action(), item.label(), item.icon(), item.payload())).toList();
            rules.add(new PatternRule(rule.domain(), Pattern.compile(".*(?:" + alternatives + ").*"),
                    rule.trait(), rule.confidence(), templates));
        }

        if (rules.isEmpty()) throw new IllegalStateException("Intent radar taxonomy has no usable rules");
    }

    /**
     * 针对用户输入分析意图雷达感知与可执行动作
     */
    public RadarResult scan(String message) {
        if (message == null || message.trim().length() < 2) return null;
        String text = com.ai.guide.domain.language.IntentLanguagePolicy.positiveText(message.trim());

        // 避免纯系统指令被误判为微调诉求
        if (text.startsWith("/") || text.matches("^(确定|取消|返回|跳过|刷新|是|否|好|对)$")) {
            return null;
        }

        for (PatternRule rule : rules) {
            if (rule.pattern.matcher(text).find()) {
                DetectedPreference pref = new DetectedPreference(rule.domain, rule.trait, rule.confidence);
                List<ActionableSuggestion> actions = new ArrayList<>();
                for (ActionTemplate tmpl : rule.templates) {
                    actions.add(new ActionableSuggestion(tmpl.action, tmpl.label, tmpl.icon, tmpl.payloadTemplate));
                }
                log.info("[IntentRadar] Match domain={}, trait={}, actionCount={}", rule.domain, rule.trait, actions.size());
                return new RadarResult(pref, actions);
            }
        }
        return null;
    }
}

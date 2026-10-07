package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划期用户偏好注入与来源解析服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：将用户登录画像中的偏好与请求 Prompt 中显式指定的约束进行优先级合并，并标记每个字段的来源 (Origin)。
 */
@Service
public class PlannerPreferencesResolver {

    private final PreferencesService preferencesService;
    private final ObjectMapper objectMapper;
    private final TravelMemoryService memoryService;

    public PlannerPreferencesResolver(PreferencesService preferencesService, ObjectMapper objectMapper) {
        this(preferencesService, objectMapper, null);
    }

    @Autowired
    public PlannerPreferencesResolver(PreferencesService preferencesService, ObjectMapper objectMapper,
                                      TravelMemoryService memoryService) {
        this.preferencesService = preferencesService;
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;
    }

    public Resolution resolve(TravelConstraints base, boolean usePreferences) {
        TravelConstraints safeBase = base == null ? new TravelConstraints() : base.copy();
        if (!usePreferences || UserContext.isAnonymous()) return new Resolution(safeBase, AppliedPreferencesSnapshot.empty());
        String userId = UserContext.getUserId();
        if (!validOwner(userId)) return new Resolution(safeBase, AppliedPreferencesSnapshot.empty());

        UserPreferences preferences;
        try {
            preferences = preferencesService.get(userId);
            if (preferences == null || !userId.equals(preferences.userId())
                    || preferences.schemaVersion() != PreferencesSchema.CURRENT_SCHEMA_VERSION
                    || preferences.revision() < 0) {
                return new Resolution(safeBase, AppliedPreferencesSnapshot.empty());
            }
        } catch (RuntimeException ignored) {
            // A preference read must never make planning unsafe or leak another source.
            return new Resolution(safeBase, AppliedPreferencesSnapshot.empty());
        }

        TravelConstraints next = safeBase.copy();
        Map<String, Object> applied = new LinkedHashMap<>();
        applyInterests(next, preferences, applied);
        applyWalking(next, preferences, applied);
        applyBudget(next, preferences, applied);
        applyCompanions(next, preferences, applied);
        applyTransport(next, preferences, applied);
        applyDiet(next, preferences, applied);
        applyStayArea(next, preferences, applied);
        if (Boolean.TRUE.equals(preferences.legacyMetadata().get("travelMemoryEnabled"))) {
            applyConfirmedMemories(next, userId);
        }

        AppliedPreferencesSnapshot snapshot;
        try {
            snapshot = AppliedPreferencesSnapshot.create(applied, preferences.revision(),
                    preferences.schemaVersion(), PreferencesSchema.NORMALIZATION_VERSION, objectMapper);
        } catch (RuntimeException invalid) {
            return new Resolution(safeBase, AppliedPreferencesSnapshot.empty());
        }
        return new Resolution(next, snapshot);
    }

    private void applyInterests(TravelConstraints next, UserPreferences preferences,
                                Map<String, Object> applied) {
        if (!next.canPreferenceOverride("interests") || preferences.interests() == null
                || preferences.interests().isEmpty()) return;
        List<String> values = new ArrayList<>(preferences.interests());
        next.setInterests(values);
        next.markOrigin("interests", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("interests", values);
    }

    private void applyWalking(TravelConstraints next, UserPreferences preferences,
                              Map<String, Object> applied) {
        UserPreferences.WalkingTolerance value = preferences.walkingTolerance();
        if (!next.canPreferenceOverride("walkingTolerance") || value == null
                || value == UserPreferences.WalkingTolerance.UNSPECIFIED) return;
        String display = switch (value) {
            case LOW -> "低";
            case NORMAL -> "正常";
            case HIGH -> "高";
            case UNSPECIFIED -> "";
        };
        if (display.isBlank()) return;
        next.setWalkingTolerance(display);
        next.markOrigin("walkingTolerance", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("walkingTolerance", value.name());
    }

    private void applyBudget(TravelConstraints next, UserPreferences preferences,
                             Map<String, Object> applied) {
        BudgetPreference value = preferences.budget();
        if (!next.canPreferenceOverride("budget") || value == null
                || value.level() == null || value.level() == BudgetPreference.Level.UNSPECIFIED) return;
        String display = budgetDisplay(value);
        if (display.isBlank()) return;
        next.setBudget(display);
        next.markOrigin("budget", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("budget", budgetSnapshot(value));
    }

    private void applyCompanions(TravelConstraints next, UserPreferences preferences,
                                 Map<String, Object> applied) {
        UserPreferences.CompanionPreference value = preferences.companions();
        if (!next.canPreferenceOverride("companions") || value == null
                || value == UserPreferences.CompanionPreference.UNSPECIFIED) return;
        String display = switch (value) {
            case SOLO -> "独自出发";
            case COUPLE -> "情侣出游";
            case FAMILY -> "家庭出游";
            case FRIENDS -> "朋友出游";
            case PARENTS -> "带父母";
            case CHILDREN -> "带孩子";
            case GROUP -> "团队出游";
            case UNSPECIFIED -> "";
        };
        if (display.isBlank()) return;
        next.setCompanions(display);
        next.markOrigin("companions", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("companions", value.name());
    }

    private void applyTransport(TravelConstraints next, UserPreferences preferences,
                                Map<String, Object> applied) {
        UserPreferences.TransportPreference value = preferences.transportPreference();
        if (!next.canPreferenceOverride("transportPreference") || value == null
                || value == UserPreferences.TransportPreference.UNSPECIFIED) return;
        String display = switch (value) {
            case PUBLIC_TRANSIT -> "公共交通优先";
            case WALKING -> "步行优先";
            case DRIVING -> "驾车优先";
            case TAXI -> "打车优先";
            case BICYCLE -> "骑行优先";
            case MIXED -> "综合交通优先";
            case UNSPECIFIED -> "";
        };
        if (display.isBlank()) return;
        next.setTransportPreference(display);
        next.markOrigin("transportPreference", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("transportPreference", value.name());
    }

    private void applyDiet(TravelConstraints next, UserPreferences preferences,
                           Map<String, Object> applied) {
        UserPreferences.DietPreference value = preferences.dietPreference();
        if (!next.canPreferenceOverride("dietPreference") || value == null
                || value == UserPreferences.DietPreference.UNSPECIFIED) return;
        String display = switch (value) {
            case NONE -> "无特殊限制";
            case VEGETARIAN -> "素食";
            case VEGAN -> "纯素";
            case HALAL -> "清真";
            case KOSHER -> "犹太洁食";
            case ALLERGY -> "过敏原需避开";
            case UNSPECIFIED -> "";
        };
        if (display.isBlank()) return;
        next.setDietPreference(display);
        next.markOrigin("dietPreference", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("dietPreference", value.name());
    }

    private void applyStayArea(TravelConstraints next, UserPreferences preferences,
                               Map<String, Object> applied) {
        if (!next.canPreferenceOverride("stayArea") || preferences.stayArea() == null
                || preferences.stayArea().isBlank()) return;
        String value = preferences.stayArea().trim();
        next.setStayArea(value);
        next.markOrigin("stayArea", com.ai.guide.domain.planner.model.ConstraintOrigin.PREFERENCE);
        applied.put("stayArea", value);
    }

    /** Free-text categories do not imply canonical diet, budget, pace or companion values. */
    private void applyConfirmedMemories(TravelConstraints next, String userId) {
        if (memoryService == null) return;
        try {
            String context = memoryService.promptContext(userId);
            if (context == null || context.isBlank()) return;
            Map<String, Object> semantic = next.getShadowSemanticPreferences() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(next.getShadowSemanticPreferences());
            semantic.put("confirmedMemoryContext", context);
            next.setShadowSemanticPreferences(semantic);
        } catch (RuntimeException ignored) {
            // Recall can fail without changing the canonical request constraints.
        }
    }

    private String budgetDisplay(BudgetPreference value) {
        return switch (value.level()) {
            case LIMITED, LOW -> "有限";
            case MEDIUM -> "中等";
            case HIGH -> "较高";
            case AMOUNT -> {
                String amount = value.maxAmount() == null ? "" : value.maxAmount().stripTrailingZeros().toPlainString();
                String currency = value.currency() == null || value.currency().isBlank() ? "" : " " + value.currency();
                yield amount.isBlank() ? "" : "约 " + amount + currency + " / 人 / 天";
            }
            case UNSPECIFIED -> "";
        };
    }

    private Map<String, Object> budgetSnapshot(BudgetPreference value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", value.level().name());
        if (value.maxAmount() != null) result.put("maxAmount", value.maxAmount().stripTrailingZeros().toPlainString());
        if (value.currency() != null && !value.currency().isBlank()) result.put("currency", value.currency());
        return result;
    }

    private boolean validOwner(String userId) {
        return userId != null && !userId.isBlank()
                && !"anonymous".equalsIgnoreCase(userId)
                && !"guest".equalsIgnoreCase(userId)
                && !"null".equalsIgnoreCase(userId);
    }

    public record Resolution(TravelConstraints constraints, AppliedPreferencesSnapshot snapshot) {
    }
}

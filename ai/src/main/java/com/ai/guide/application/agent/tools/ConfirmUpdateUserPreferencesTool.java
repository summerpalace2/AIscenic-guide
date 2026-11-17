package com.ai.guide.application.agent.tools;

import com.ai.guide.application.preferences.PreferencesApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.service.PreferenceConflictException;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 正式个人偏好增量写入工具（HIL 写操作工具：{@code requiresConfirmation = true}）。
 *
 * <p>必须经用户显式确认后才调用 {@link PreferencesApplicationService#mergePreferences} 落库，
 * 严禁在对话推理中静默篡改用户长期偏好档案。
 */
@Component
public class ConfirmUpdateUserPreferencesTool implements AgentToolPort {

    public static final String TOOL_NAME = "confirm_update_user_preferences";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "interests": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "可选：兴趣偏好标签列表（注意会覆盖旧列表，未变更时请勿传）"
                },
                "walkingTolerance": {
                  "type": "string",
                  "enum": ["LOW", "NORMAL", "HIGH"],
                  "description": "可选：步行耐受度"
                },
                "budgetLevel": {
                  "type": "string",
                  "enum": ["LIMITED", "LOW", "MEDIUM", "HIGH", "AMOUNT"],
                  "description": "可选：预算档位"
                },
                "budgetMaxAmount": {
                  "type": "number",
                  "description": "可选：当 budgetLevel 为 AMOUNT 时填写的最大预算金额"
                },
                "budgetCurrency": {
                  "type": "string",
                  "description": "可选：预算币种（如 CNY）"
                },
                "companions": {
                  "type": "string",
                  "enum": ["SOLO", "COUPLE", "FAMILY", "FRIENDS", "PARENTS", "CHILDREN", "GROUP"],
                  "description": "可选：同行人群"
                },
                "transportPreference": {
                  "type": "string",
                  "enum": ["PUBLIC_TRANSIT", "WALKING", "DRIVING", "TAXI", "BICYCLE", "MIXED"],
                  "description": "可选：交通方式偏好"
                },
                "dietPreference": {
                  "type": "string",
                  "enum": ["NONE", "VEGETARIAN", "VEGAN", "HALAL", "KOSHER", "ALLERGY"],
                  "description": "可选：饮食限制或偏好"
                },
                "stayArea": {
                  "type": "string",
                  "description": "可选：偏好的住宿片区（如“解放碑”）"
                },
                "expectedRevision": {
                  "type": "integer",
                  "description": "可选：预期偏好版本号（CAS 并发控制）"
                },
                "changeSummary": {
                  "type": "string",
                  "description": "向用户确认卡片展示的简洁中文更新说明"
                }
              },
              "required": ["changeSummary"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "请求用户确认并将个人旅行偏好（步行耐受度、同行人、交通、饮食、住宿片区等）持久化写入档案（触发 HIL 确认，不会静默写入）。",
            SCHEMA,
            true
    );

    private final PreferencesApplicationService preferencesApplicationService;
    private final ObjectMapper objectMapper;

    public ConfirmUpdateUserPreferencesTool(PreferencesApplicationService preferencesApplicationService,
                                            ObjectMapper objectMapper) {
        this.preferencesApplicationService = preferencesApplicationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        if (context == null || context.anonymous() || context.userId().isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "LOGIN_REQUIRED",
                    "游客模式无法保存正式个人偏好，请先登录后再试。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        Long expectedRevision = args.hasNonNull("expectedRevision") ? args.get("expectedRevision").asLong() : null;

        List<String> interests = null;
        if (args.hasNonNull("interests") && args.get("interests").isArray()) {
            interests = new ArrayList<>();
            for (JsonNode n : args.get("interests")) {
                String tag = n.asText("").trim();
                if (!tag.isEmpty()) interests.add(tag);
            }
        }

        UserPreferences.WalkingTolerance walking = parseEnum(args, "walkingTolerance", UserPreferences.WalkingTolerance.class);
        UserPreferences.CompanionPreference companions = parseEnum(args, "companions", UserPreferences.CompanionPreference.class);
        UserPreferences.TransportPreference transport = parseEnum(args, "transportPreference", UserPreferences.TransportPreference.class);
        UserPreferences.DietPreference diet = parseEnum(args, "dietPreference", UserPreferences.DietPreference.class);
        BudgetPreference budget = parseBudget(args);
        String stayArea = args.hasNonNull("stayArea") ? args.get("stayArea").asText("").trim() : null;

        UserPreferencesPatch patch = new UserPreferencesPatch(
                interests, walking, budget, companions, transport, diet, stayArea, null, expectedRevision);

        try {
            PreferencesService.MutationResult result =
                    preferencesApplicationService.mergePreferences(context.userId(), patch, expectedRevision);
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("ok", true);
            receipt.put("revision", result.preferences().revision());
            receipt.put("preferences", result.preferences());
            String summary = "个人偏好画像已更新（revision=" + result.preferences().revision() + "）";
            return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, receipt), summary);
        } catch (PreferenceConflictException conflict) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "REVISION_CONFLICT",
                    "个人偏好版本已变化，请重新读取后再更新。");
        } catch (IllegalArgumentException invalid) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "INVALID_PREFERENCE_PATCH",
                    invalid.getMessage());
        }
    }

    private static BudgetPreference parseBudget(JsonNode args) {
        if (!args.hasNonNull("budgetLevel") && !args.hasNonNull("budgetMaxAmount")) {
            return null;
        }
        BudgetPreference.Level level = parseEnum(args, "budgetLevel", BudgetPreference.Level.class);
        BigDecimal maxAmount = args.hasNonNull("budgetMaxAmount") ? args.get("budgetMaxAmount").decimalValue() : null;
        if (level == null && maxAmount != null) {
            level = BudgetPreference.Level.AMOUNT;
        }
        String currency = args.hasNonNull("budgetCurrency") ? args.get("budgetCurrency").asText("CNY")
                : (maxAmount != null ? "CNY" : "");
        return new BudgetPreference(level, maxAmount, currency);
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode args, String field, Class<E> enumType) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        String raw = args.get(field).asText("").trim().toUpperCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return null;
        }
        return Enum.valueOf(enumType, raw);
    }
}

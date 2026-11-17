package com.ai.guide.application.agent.tools;

import com.ai.guide.application.preferences.PreferencesApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户正式偏好画像只读查询工具。
 *
 * <p>执行层遮蔽游客态请求：工具列表对模型始终恒定，当游客调用时返回 {@code LOGIN_REQUIRED} 结构化提示。
 */
@Component
public class GetUserPreferencesTool implements AgentToolPort {

    public static final String TOOL_NAME = "get_user_preferences";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {}
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "查询当前已登录用户的正式个性化旅行偏好（兴趣、步行耐受度、预算、同行人、交通、饮食偏好、常住区域及版本号 revision）。",
            SCHEMA,
            false
    );

    private final PreferencesApplicationService preferencesApplicationService;
    private final ObjectMapper objectMapper;

    public GetUserPreferencesTool(PreferencesApplicationService preferencesApplicationService,
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
                    "当前为游客模式，请先登录后再查询或管理个人旅行偏好画像。");
        }
        UserPreferences prefs = preferencesApplicationService.getPreferences(context.userId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("revision", prefs.revision());
        payload.put("interests", prefs.interests());
        payload.put("walkingTolerance", prefs.walkingTolerance().name());
        payload.put("budgetLevel", prefs.budget().level().name());
        if (prefs.budget().maxAmount() != null) {
            payload.put("budgetMaxAmount", prefs.budget().maxAmount());
            payload.put("budgetCurrency", prefs.budget().currency());
        }
        payload.put("companions", prefs.companions().name());
        payload.put("transportPreference", prefs.transportPreference().name());
        payload.put("dietPreference", prefs.dietPreference().name());
        payload.put("stayArea", prefs.stayArea());

        String summary = "已读取个人偏好画像（revision=" + prefs.revision() + "）";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload), summary);
    }
}

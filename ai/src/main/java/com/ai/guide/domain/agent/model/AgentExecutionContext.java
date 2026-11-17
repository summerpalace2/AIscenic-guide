package com.ai.guide.domain.agent.model;

/**
 * 智能体单轮运行的不可变执行上下文。
 *
 * <p>架构定位：COLA 领域层（domain.agent.model）。由安全上下文与前端当前视图状态装配，
 * 工具执行时只能从此上下文读取身份与绑定会话，防止模型伪造越权参数。
 *
 * @param userId             当前已认证用户 ID（游客为空字符串）
 * @param anonymous          是否为未登录游客身份
 * @param sessionId          对话会话 ID
 * @param plannerSessionId   当前绑定的规划草稿会话 ID
 * @param plannerAccessToken 游客规划会话访问令牌（X-Plan-Session-Token，仅由服务端透传，模型不可见）
 * @param tripId             当前关联的正式行程 ID
 * @param baseRevision       前端当前观测到的行程版本号（用于 CAS 乐观锁预检，可空）
 * @param activeCity         当前上下文城市（默认“重庆”）
 * @param activeDay          当前聚焦的行程天数（可空）
 * @param activeStopId       当前选中的站点 ID（可空）
 * @param activeStopName     当前选中的站点名称（可空）
 * @param deepMode           是否启用深度检索模式
 */
public record AgentExecutionContext(
        String userId,
        boolean anonymous,
        String sessionId,
        String plannerSessionId,
        String plannerAccessToken,
        String tripId,
        Integer baseRevision,
        String activeCity,
        Integer activeDay,
        String activeStopId,
        String activeStopName,
        boolean deepMode
) {
    public AgentExecutionContext {
        userId = userId == null ? "" : userId;
        sessionId = sessionId == null ? "" : sessionId;
        plannerSessionId = plannerSessionId == null ? "" : plannerSessionId;
        plannerAccessToken = plannerAccessToken == null ? "" : plannerAccessToken;
        tripId = tripId == null ? "" : tripId;
        activeCity = (activeCity == null || activeCity.isBlank()) ? "重庆" : activeCity.trim();
        activeStopId = activeStopId == null ? "" : activeStopId;
        activeStopName = activeStopName == null ? "" : activeStopName;
    }

    /** 兼容 Phase 1 的 9 参数构造器（默认依据 userId 是否为空推导游客态） */
    public AgentExecutionContext(
            String userId,
            String sessionId,
            String plannerSessionId,
            String tripId,
            String activeCity,
            Integer activeDay,
            String activeStopId,
            String activeStopName,
            boolean deepMode) {
        this(
                userId,
                userId == null || userId.isBlank(),
                sessionId,
                plannerSessionId,
                "",
                tripId,
                null,
                activeCity,
                activeDay,
                activeStopId,
                activeStopName,
                deepMode
        );
    }

    /** 是否已绑定有效规划会话 */
    public boolean hasPlannerSession() {
        return !plannerSessionId.isBlank();
    }

    /** 构造归属隔离键：已登录用户按 userId 绑定，游客按对话 sessionId 绑定 */
    public String ownerKey() {
        if (!anonymous && !userId.isBlank()) {
            return "user:" + userId;
        }
        String sid = sessionId.isBlank() ? "default" : sessionId;
        return "guest:" + sid;
    }
}

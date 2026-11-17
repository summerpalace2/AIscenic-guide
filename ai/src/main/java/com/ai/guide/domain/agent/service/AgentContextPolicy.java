package com.ai.guide.domain.agent.service;

import com.ai.guide.domain.agent.model.AgentExecutionContext;

import java.util.List;

/**
 * 智能体上下文工程与系统提示词领域策略。
 *
 * <p>架构定位：COLA 领域服务（domain.agent.service）。集中落实上下文工程原则：
 * <ul>
 *   <li>静态系统提示词前缀保持字节级恒定，严禁混入动态天气、检索正文或当前时间，以保护 KV Cache；</li>
 *   <li>动态视图上下文与多步 Todo 清单仅追加在用户轮次消息末尾，对抗 Lost in the middle；</li>
 *   <li>明确约束模型严禁口算路线耗时、编造未核验状态或绕过确定性规划沙箱直接改写行程。</li>
 * </ul>
 */
public final class AgentContextPolicy {

    /** 单轮对话允许的最大工具推理步数上限（防死循环熔断） */
    public static final int DEFAULT_MAX_STEPS = 8;

    /**
     * 静态不变的系统提示词前缀（禁止拼接任何动态变量，以最大化前缀缓存命中率）。
     */
    public static final String STATIC_SYSTEM_PROMPT = """
            你是「渝游智策」全智能体原生文旅决策引擎（Full-Agent Native），专注重庆及周边城市的实时旅行规划、景点餐饮推荐与全生命周期编排。

            核心工作准则：
            1. 按需极简调用工具（严禁一次盲目调用全量工具浪费时间）：
               - 【局部修改已有行程】（替换某个景点/餐厅、删一站、加一站、改时间或跨天移动）：
                 ① 先调 1 次 get_current_trip_draft 核对站点 stopId、所在 day 与 baseRevision；
                 ② 若需寻找替代地点，只调用 1~2 次 search_poi_and_dining 查找同区备选即可（删站/移动/改时间无需检索）；
                 ③ 立刻调用 preview_trip_adjustment，优先传入 operations 局部补丁（如 REPLACE_STOP/REMOVE_STOP/ADD_STOP/MOVE_STOP），只改目标站点并保持其余天/站原封不动；
                 ④ 拿到 proposalId 后立即调用 confirm_apply_trip_adjustment 弹窗请求用户确认。
                 ⚠️ 局部修改时严禁顺手调用 query_weather_forecast、search_travel_knowledge，也严禁提前循环调用 calculate_route_matrix 逐对试算（preview_trip_adjustment 内部会自动水合高德真实路线与耗时）。仅当用户主动问及偏好、天气或预约政策时才按需补查。
               - 【从零新建多天行程（create_trip_plan）】：
                 按需调用 query_weather_forecast（至多 1 次）+ get_user_preferences（1 次）+ search_poi_and_dining（补齐景点与顺路美食 POI/坐标）后直接调用 create_trip_plan；同样无需提前循环调用 calculate_route_matrix，create_trip_plan 会自动执行高德水合；用户未问历史/门票/闭馆政策时不要调用 search_travel_knowledge。
               - 【纯问答 / 专题查询】：仅调用回答问题直接需要的那 1 个工具（问天气→query_weather_forecast；问两地怎么走/耗时→calculate_route_matrix，单轮不超过 2 次；问历史文化/门票预约/开放政策→search_travel_knowledge）。严禁口算距离耗时或编造未核验坐标。
            2. 全智能体自由编排（彻底尊重用户细粒度指定）：
               - 制定多天新行程时，直接按天编排每日 theme、景点（SCENIC）、顺路餐饮（DINING）与弹性休闲时段（FREE_TIME）传入 create_trip_plan(title, city, days...)；必须严格把用户指定的站点放到其指定的那一天（绝不擅自换天或私自替换）。
               - 同一轮内一旦 create_trip_plan 已成功返回 plannerSessionId，不得在同一轮内重复调用 create_trip_plan。
            3. 人机确认（HIL）护栏：
               - 只有获得有效 proposalId，或用户明确指示固定/取消固定站点、保存正式行程、更新个人偏好时，才调用对应 HIL 确认工具（confirm_apply_trip_adjustment / confirm_update_pinned_stops / confirm_save_formal_trip / confirm_update_user_preferences）；未确认绝不静默改写已有行程。
            4. 保留失败信息并主动自纠：若工具返回 UNVERIFIED_STOP_LOCATION、PINNED_STOP_PROTECTED、TARGET_STOP_NOT_FOUND 或 REVISION_CONFLICT，请按提示补充检索真实 POI、保留固定站点或重读最新草稿修正后再试。
            5. 上下文精简：总结行程或变更时只讲核心亮点、路线耗时与注意事项，不复述整份 JSON。
            """;

    private AgentContextPolicy() {
    }

    /**
     * 构建追加到用户消息中的动态视图上下文后缀（不修改静态 System 前缀）。
     */
    public static String buildDynamicContextSuffix(AgentExecutionContext context, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("[当前运行时上下文]\n");
        sb.append("- 用户身份: ").append(context.anonymous() ? "游客（仅支持只读问答，新建规划/保存行程与正式偏好需先登录）" : "已登录用户").append('\n');
        sb.append("- 目标城市: ").append(context.activeCity()).append('\n');
        if (context.hasPlannerSession()) {
            sb.append("- 已绑定行程草稿（plannerSessionId=").append(context.plannerSessionId());
            if (context.baseRevision() != null) {
                sb.append(", baseRevision=").append(context.baseRevision());
            }
            sb.append("）\n");
        } else {
            sb.append("- 未绑定行程草稿（可回答文旅咨询，或应已登录用户要求调用 create_trip_plan 生成新行程）\n");
        }
        if (context.activeDay() != null && context.activeDay() > 0) {
            sb.append("- 当前聚焦第 ").append(context.activeDay()).append(" 天\n");
        }
        if (!context.activeStopName().isBlank() || !context.activeStopId().isBlank()) {
            sb.append("- 当前选中站点: ");
            if (!context.activeStopName().isBlank()) {
                sb.append(context.activeStopName());
            }
            if (!context.activeStopId().isBlank()) {
                sb.append(" (stopId=").append(context.activeStopId()).append(')');
            }
            sb.append('\n');
        }
        sb.append("- 检索深度: ").append(context.deepMode() ? "deep" : "normal").append('\n');
        sb.append("\n[用户请求]\n").append(userMessage == null ? "" : userMessage.trim());
        return sb.toString();
    }

    /**
     * 在多步工具推理末尾重写当前待办进度清单，防止长链路下的目标遗忘（Lost in the middle）。
     */
    public static String buildLoopChecklistReminder(int currentStep, int maxSteps, List<String> completedActions) {
        StringBuilder sb = new StringBuilder();
        sb.append("[执行进度提醒 第 ").append(currentStep).append('/').append(maxSteps).append(" 步]\n");
        if (completedActions != null && !completedActions.isEmpty()) {
            sb.append("已完成工具调用：\n");
            for (String action : completedActions) {
                sb.append("- [x] ").append(action).append('\n');
            }
        }
        sb.append("请检查用户原始诉求是否已全部完成；若信息已充分，请直接输出准确、简洁的中文回答；若仍缺必要事实且未超步数上限，继续调用工具。");
        return sb.toString();
    }
}

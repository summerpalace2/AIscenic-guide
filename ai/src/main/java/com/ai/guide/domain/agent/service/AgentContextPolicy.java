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
    public static final int DEFAULT_MAX_STEPS = 6;

    /**
     * 静态不变的系统提示词前缀（禁止拼接任何动态变量，以最大化前缀缓存命中率）。
     */
    public static final String STATIC_SYSTEM_PROMPT = """
            你是「渝游智策」智慧文旅决策智能体，专注重庆及周边城市的真实旅行规划、景点问答与行程全生命周期管理。

            核心工作准则：
            1. 事实必须先查证后回答：涉及天气、景点开放/门票政策、实时 POI、美食推荐或两点间交通耗时，必须优先调用对应工具获取客观结果；严禁凭猜测口算距离耗时或编造未核验的营业时间、票价与公告。
            2. 行程规划与全生命周期工具协同规范：
               - 从零制定多天行程时，调用 create_trip_plan 通过确定性规划引擎生成新草案（未登录游客若调用将被提示先登录）。同一轮内一旦 create_trip_plan 已成功并返回 plannerSessionId，不得在同一轮内重复调用 create_trip_plan。
               - 调整已有行程前，须先通过 get_current_trip_draft 了解当前站点 ID（stopId）与版本号，再调用 preview_trip_adjustment 在沙箱中生成候选方案与路线差异，绝不可自行编造 proposalId、optionId 或 stopId。
               - 只有当用户明确选定调整方案、明确指示固定/取消固定站点、明确保存正式行程或要求持久化个人偏好时，才调用对应 HIL 确认工具（confirm_apply_trip_adjustment / confirm_update_pinned_stops / confirm_save_formal_trip / confirm_update_user_preferences）发起人工确认。
            3. 保留失败信息并主动调整：若工具返回未绑定行程、未登录、闭馆、跨午夜超时或版本冲突，请根据错误原因改用备选方案或如实告知用户，不得隐瞒失败。
            4. 上下文精简原则：行程或百科只引用关键结论、站点名称与核验来源，不冗长复述整份 JSON。
            5. 复合诉求按序完成：当用户提出多个并列要求时，逐项核对进度并确认全部处理完毕后再给出最终总结。
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

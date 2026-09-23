package com.ai.guide.domain.chat.service;

import org.springframework.ai.chat.messages.Message;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 重庆 Chat 产品身份的 Runtime 边界。
 *
 * 这里有意保持为小型纯策略，使提示词和检索回归检查无需连接 LLM、Redis 或 Qdrant
 * 即可运行。
 */
public /**
 * AI 导游对话领域边界与安全策略防护服务
 *
 * 所属领域：domain.chat.service（AI 导游交互服务层）
 * 架构职责：对大模型提示词注入防御、对话上下文长度截断与非文旅无关提问进行过滤与边界约束。
 */
final class ChatDomainPolicy {

    public static final String PRODUCT_IDENTITY = "渝游智策 / 重庆智慧文旅 AI 决策助手";
    public static final String LEGACY_CORPUS_MARKER =
            "[RAG_STATUS: LEGACY_CORPUS_CONTAMINATION] 检索结果未绑定重庆生产语料；禁止引用该结果，请明确告知用户当前知识库仍待激活。";
    public static final String GOLDEN_DEMO_QUERY =
            "帮我规划周末重庆两日游，带父母，少走路，喜欢人文和夜景";

    private static final Pattern LEGACY_DOMAIN_PATTERN = Pattern.compile(
            "灵山|无锡|江南导游|小导|灵仙儿|灵山智慧导游",
            Pattern.CASE_INSENSITIVE);

    public static final String SYSTEM_PROMPT = """
            # 角色：渝游智策 / 重庆智慧文旅 AI 决策助手

            你只服务于重庆智慧文旅决策场景，帮助用户理解重庆旅行约束、景点选择、美食推荐、特色体验与行程方案微调。
            不得采用其他项目的产品身份；不得把非重庆语料包装成重庆事实。

            # 核心能力与行为准则
            1. **承接上下文与偏好感知**：拥有完整对话记忆与偏好感知（如少走路、长辈同行、情侣、亲子、美食偏好等），回答时主动结合用户当前行程与诉求。
            2. **专业推荐与行程微调建议**：当用户询问景点、美食、路线或换点建议时，给出条理清晰、真实实用的推荐，并可主动引导用户微调当前行程方案。
            3. **事实可信**：优先基于背景知识中的无障碍设施、交通路线、门票规则及开放时间进行建议；对于开放性咨询，结合重庆真实风土人情与地标常识给出靠谱建议。
            4. **自然交流**：排版清晰优雅，使用 Markdown 小标题、加粗与无序列表，条理分明。
            5. **严禁输出底层技术标识符**：严禁在回答中向用户直接输出任何技术 ID（如 `session-xxx`、`tripId`、UUID、会话流水号等内部系统标识）。如果提及当前草稿或行程状态，请一律使用“当前行程草稿”、“未保存的草稿”等自然语言表达。
            6. **智能交互动作卡片（Action Chips）**：
               当你通过语义理解发现用户的发言中蕴含明确的旅行偏好倾向（如想吃甜食、偏好辣味/清淡、需要少走路、怕累、想晚起、避雨避暑、摄影打卡、长辈适老、亲子儿童等）或行程微调诉求时，请在**整篇回答的最末尾**输出 1~3 个标准交互动作卡片标签（每行一个），格式如下：
               <action_chip action="操作类型" icon="图标Emoji" label="按钮文案" payload="执行指令或沉淀内容" />

               常用操作类型与示例：
               - `replan_pace`（步调/体力动线调整）：<action_chip action="replan_pace" icon="🚶" label="优化为少台阶与低步行平缓路线" payload="优化今天行程，少走路、少台阶、优先平路" />
               - `reduce_density`（精简日程站点）：<action_chip action="reduce_density" icon="⏱" label="精简日程，减少1处步行较远的站点" payload="今天行程太累了，少推荐一个景点" />
               - `add_stop`（追加特色站点/甜点/咖啡）：<action_chip action="add_stop" icon="🍰" label="沿途加一站甜点/糖水小憩" payload="在当前行程加入一处甜点或特色糖水小憩停留" />
               - `replace_meal`（餐饮类型替换）：<action_chip action="replace_meal" icon="🍲" label="替换为地道老火锅正餐" payload="寻找并替换为附近地道老火锅餐厅" />
               - `indoor_mode`（雨天避雨/防晒全室内模式）：<action_chip action="indoor_mode" icon="☂" label="一键切换为全室内避雨场馆" payload="今天下雨，请全部替换为室内舒适场馆" />
               - `save_memory`（沉淀长期旅行偏好至档案）：<action_chip action="save_memory" icon="✦" label="将【偏好少走路与平路】沉淀至旅行档案" payload="偏好少走路，优先平路与平缓动线" />

               【输出约束】：
               - 必须将 `<action_chip>` 标签置于整篇回答的最末尾（正文之后独立成行），严禁嵌入正文段落中。
               - 每种 action 操作类型在单次回答中最多输出 1 个；总卡片数严格控制在 1~2 个（通常为 1 个微调动作 + 1 个沉淀档案动作），严禁输出同类型或语义重复的卡片。
               - 若用户只是普通问候（如“你好”）、无倾向性咨询（如“磁器口门票多少钱”），则不要输出任何 `<action_chip>` 标签。
            """;

    private ChatDomainPolicy() {
    }

    public static boolean containsLegacyDomain(String text) {
        return text != null && LEGACY_DOMAIN_PATTERN.matcher(text).find();
    }

    public static boolean isLegacyCorpusMarker(String text) {
        return Objects.equals(LEGACY_CORPUS_MARKER, text);
    }

    /** 替换受到污染的检索文本，避免将旧事实传给模型。 */
    public static String protectRetrievedContext(String context) {
        if (context == null || context.isBlank()) return "";
        return context.contains("LEGACY_CORPUS_CONTAMINATION") || containsLegacyDomain(context)
                ? LEGACY_CORPUS_MARKER : context;
    }

    /** 将并行检索器返回的列表作为一个完整检索结果统一防护。 */
    public static List<String> protectRetrievedFragments(List<String> fragments) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        if (fragments.stream().anyMatch(ChatDomainPolicy::containsLegacyDomain)) {
            return List.of(LEGACY_CORPUS_MARKER);
        }
        return fragments.stream().filter(fragment -> fragment != null && !fragment.isBlank()).toList();
    }

    /** 防止旧摘要或偏好变成隐藏在系统消息中的污染内容。 */
    public static List<Message> filterLegacyMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> !containsLegacyDomain(message.getContent()))
                .toList();
    }
}

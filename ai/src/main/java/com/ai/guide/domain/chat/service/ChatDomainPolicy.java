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

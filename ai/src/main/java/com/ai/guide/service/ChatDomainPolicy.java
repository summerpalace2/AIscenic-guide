package com.ai.guide.service;

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
public final class ChatDomainPolicy {

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

            你只服务于重庆智慧文旅决策场景，帮助用户理解重庆旅行约束、景点选择与行程取舍。
            不得采用其他项目的产品身份、景区身份或导游昵称；不得把检索到的非重庆语料包装成重庆事实。

            # 行为准则
            1. **承接上下文**：拥有对话记忆，像老朋友聊天，追问时结合之前的回答。
            2. **关联性**：只在用户问景点/餐厅/政策时从背景知识提取信息。
            3. **精准度优先**：紧扣用户需求，推荐类问题可适当展开多个维度，封闭性问题简洁精准。
            4. **按需回答**：打招呼、纯感受表达（"好玩""不好玩"）时<b>不要推荐任何项目</b>——只共情回应。除非用户明确说"推荐""介绍"。
            5. **回答自然**：承接用户问题顺势答复。结尾加猜你想问（必须空一行），格式：💡猜你想问：1. xxx 2. xxx
            6. **污染处理**：若背景知识包含 `[RAG_STATUS: LEGACY_CORPUS_CONTAMINATION]`，只能说明重庆知识库尚未激活，禁止引用、复述或推断检索内容，不得生成景区事实。

            # 槽位感知
            有【已知用户偏好】时<b>以此为第一优先级</b>。如用户偏好"美食+半天"，模糊请求也直接推美食。缺失关键信息时先追问再推荐。

            # 排版
            每个项目名: ### 数字. 名称（单个不用数字），属性用 `- **属性**：值` 无序列表，严禁同行写两个属性
            模板: ### 1. 名称 换行 - **价格**：内容 换行 - **特色**：内容

            # 禁令
            严禁"(空一行)""回车"等描述文字，严禁编造任何知识库中不存在的信息（酒店名称/价格/距离/时间/政策等），知识库未覆盖时必须明确说"暂无相关信息"，不得编造看似合理的内容，哪怕用户追问也要保持一致；严禁编造价格政策，严禁标题放列表符号后面，严禁把打招呼变成长篇介绍。
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

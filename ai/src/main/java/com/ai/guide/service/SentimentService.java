package com.ai.guide.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

/**
 * 情感分析 Service
 * 基于关键词匹配判断文本情感倾向
 * （由 javap -p -c 反编译 bytecode 恢复）
 */
@Service
public class SentimentService {

    /**
     * 情感枚举
     */
    @Getter
    public enum Sentiment {
        /** 正面 */
        POSITIVE,
        /** 中性 */
        NEUTRAL,
        /** 负面 */
        NEGATIVE
    }

    /** 正面关键词列表（18 个） */
    private static final String[] POSITIVE = {
            "很棒", "太棒", "不错", "喜欢", "推荐", "漂亮",
            "好吃", "好玩", "厉害", "牛", "感谢", "开心",
            "满意", "完美", "优秀", "👍", "很好", "真好"
    };

    /** 负面关键词列表（20 个） */
    private static final String[] NEGATIVE = {
            "太差", "很差", "不好", "糟透", "糟糕", "垃圾",
            "没用", "坑", "骗", "失望", "差评", "难吃",
            "难玩", "无聊", "太贵", "不值", "错了", "不对",
            "瞎说", "👎"
    };

    public SentimentService() {
    }

    /**
     * 分析文本情感倾向
     *
     * @param text 输入文本
     * @return 情感分类结果
     */
    public Sentiment analyze(String text) {
        if (text == null || text.isBlank()) {
            return Sentiment.NEUTRAL;
        }

        String lowerText = text.toLowerCase();

        // 先检测负面（优先级更高）
        for (String keyword : NEGATIVE) {
            if (lowerText.contains(keyword)) {
                return Sentiment.NEGATIVE;
            }
        }

        // 再检测正面
        for (String keyword : POSITIVE) {
            if (lowerText.contains(keyword)) {
                return Sentiment.POSITIVE;
            }
        }

        // 默认中性
        return Sentiment.NEUTRAL;
    }

    /**
     * 根据情感生成提示词指令
     *
     * @param sentiment 情感枚举
     * @return 提示词文本
     */
    public String toPromptHint(Sentiment sentiment) {
        switch (sentiment.ordinal()) {
            case 0: // POSITIVE
                return "\n【用户情绪：正面，回复可以轻松愉快】\n";
            case 2: // NEGATIVE
                return "\n【最高优先级：用户情绪负面】用户感到失望或不愉快。\n"
                        + "你的回复<b>开头必须先共情安抚</b>（如\"非常抱歉\"\"我完全理解您的感受\"）。\n"
                        + "<b>不要推荐任何具体项目、菜品或路线</b>——不要提价格、不要提\"背景知识里有\"。\n"
                        + "认真回应情绪，<b>不要一句话带过</b>——先道歉，再询问具体原因，\n"
                        + "等用户情绪明显缓和后再说\"要不要我帮您推荐\"。";
            default: // NEUTRAL
                return "";
        }
    }

    /**
     * 重载：直接传入文本生成情感提示
     */
    public String toPromptHint(String text) {
        return toPromptHint(analyze(text));
    }

    /**
     * 根据情感生成标签字符串
     *
     * @param sentiment 情感枚举
     * @return 标签（positive / negative / 空串）
     */
    public String toEmojiTag(Sentiment sentiment) {
        switch (sentiment.ordinal()) {
            case 0:
                return "positive";
            case 2:
                return "negative";
            default:
                return "";
        }
    }

    /**
     * 重载：直接传入文本生成情感标签
     */
    public String toEmojiTag(String text) {
        return toEmojiTag(analyze(text));
    }
}

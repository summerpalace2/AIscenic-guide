package com.ai.guide.service;

import org.springframework.stereotype.Service;

/**
 *
 * 情感分析 实现 --关键词匹配
 *
 * 输出标签：POSITIVE（正面）/ NEUTRAL（中性）/ NEGATIVE（负面）
 */
@Service
public class SentimentService {

    public enum Sentiment { POSITIVE, NEUTRAL, NEGATIVE }

    private static final String[] POSITIVE = {
        "很棒", "太棒", "不错", "喜欢", "推荐", "漂亮", "好吃", "好玩",
        "厉害", "牛", "感谢", "开心", "满意", "完美", "优秀", "👍", "很好", "真好"
    };

    private static final String[] NEGATIVE = {
        "太差", "很差", "不好", "糟透", "糟糕", "垃圾", "没用", "坑", "骗", "失望", "差评",
        "难吃", "难玩", "无聊", "太贵", "不值", "错了", "不对", "瞎说", "👎"
    };

    public Sentiment analyze(String message) {
        if (message == null || message.isBlank()) return Sentiment.NEUTRAL;
        String msg = message.toLowerCase();

        for (String w : NEGATIVE) {
            if (msg.contains(w)) return Sentiment.NEGATIVE;
        }
        for (String w : POSITIVE) {
            if (msg.contains(w)) return Sentiment.POSITIVE;
        }
        return Sentiment.NEUTRAL;
    }

    /** 根据情感枚举生成提示（避免重复分析） */
    public String toPromptHint(Sentiment s) {
        return switch (s) {
            case NEGATIVE -> """
                
                【最高优先级：用户情绪负面】用户感到失望或不愉快。
                你的回复<b>开头必须先共情安抚</b>（如"非常抱歉""我完全理解您的感受"）。
                <b>不要推荐任何具体项目、菜品或路线</b>——不要提价格、不要提"背景知识里有"。
                认真回应情绪，<b>不要一句话带过</b>——先道歉，再询问具体原因，
                等用户情绪明显缓和后再说"要不要我帮您推荐"。""";
            case POSITIVE -> "\n【用户情绪：正面，回复可以轻松愉快】\n";
            default -> "";
        };
    }

    /** 根据文本分析情感并生成提示 */
    public String toPromptHint(String message) {
        return toPromptHint(analyze(message));
    }

    /** 情感转 SS E 标签 */
    public String toEmojiTag(Sentiment s) {
        return switch (s) {
            case POSITIVE -> "positive";
            case NEGATIVE -> "negative";
            default -> "";
        };
    }

    /** 情感转前端不可见标记 */
    public String toEmojiTag(String message) {
        return toEmojiTag(analyze(message));
    }
}

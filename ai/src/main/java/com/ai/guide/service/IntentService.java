package com.ai.guide.service;

import org.springframework.stereotype.Service;

/**
 * 意图分类 —— 关键词匹配，零 API 调用
 *
 * 路由结果：
 *
 * QA         → 知识问答（走 RAG + 大模型）
 * NAVIGATION → 路线导航（走 RAG，强调时间和路线）
 * CHITCHAT   → 闲聊（跳过 RAG，直接大模型）
 * COMPLAINT  → 投诉建议（跳过 RAG，共情回复）
 * UNKNOWN    → 兜底（走默认流程）
 *
 */
@Service
public class IntentService {

    public enum Intent { QA, NAVIGATION, CHITCHAT, COMPLAINT, UNKNOWN }

    public Intent classify(String message) {
        if (message == null || message.isBlank()) return Intent.UNKNOWN;

        // 闲聊：问候、自我介绍、道谢道别
        if (message.matches(".*(你好|早上好|下午好|晚上好|嗨|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你能做什么|谢谢|再见|拜拜|晚安|好的|嗯|哦|哈哈).*")
                || message.matches("^[a-zA-Z]+$") && message.length() <= 6) {
            return Intent.CHITCHAT;
        }

        // 投诉：负面反馈（优先于问答，即使句子含疑问词也先安抚）
        if (message.matches(".*(投诉|差劲|糟糕|糟透|不好|太差|垃圾|没用|坑|骗|烂|失望|差评|不好用|错了|不对|瞎说|胡扯).*")) {
            return Intent.COMPLAINT;
        }

        // 导航：路线、交通、怎么走
        if (message.matches(".*(路线|怎么走|怎么去|导航|地图|在哪|位置|多远|多久|交通|公交|开车|步行|停车场|入口|出口|门|怎么到|走哪条).*")) {
            return Intent.NAVIGATION;
        }

        // 问答：含疑问词或是什么/为什么/哪些等
        if (message.matches(".*(什么|为什么|怎么|哪些|哪个|哪里|是谁|多少|多久|好不好|行不行|可以吗|推荐|介绍|说说|讲讲|有没有|有没有什么).*")
                || message.endsWith("?") || message.endsWith("？")) {
            return Intent.QA;
        }

        // 默认：陈述句/模糊意图 → 走问答
        return Intent.QA;
    }
}

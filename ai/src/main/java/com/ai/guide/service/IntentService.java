package com.ai.guide.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

/**
 * 意图识别 Service
 * 基于正则表达式进行用户意图分类
 * （由 javap -p -c 反编译 bytecode 恢复）
 */
@Service
public class IntentService {

    /**
     * 用户意图枚举
     */
    @Getter
    public enum Intent {
        /** 问答 */
        QA,
        /** 导航 */
        NAVIGATION,
        /** 闲聊 */
        CHITCHAT,
        /** 投诉 */
        COMPLAINT,
        /** 未知 */
        UNKNOWN
    }

    public IntentService() {
    }

    /**
     * 对用户输入进行意图分类
     *
     * @param input 用户输入文本
     * @return 匹配的意图
     */
    public Intent classify(String input) {
        // 空输入返回 UNKNOWN
        if (input == null || input.isBlank()) {
            return Intent.UNKNOWN;
        }

        // 闲聊检测：打招呼 / 简单英文单词
        if (input.matches(".*(你好|早上好|下午好|晚上好|嗨|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你能做什么|谢谢|再见|拜拜|晚安|好的|嗯|哦|哈哈).*")
                || (input.matches("^[a-zA-Z]+$") && input.length() <= 6)) {
            return Intent.CHITCHAT;
        }

        // 投诉检测
        if (input.matches(".*(投诉|差劲|糟糕|糟透|不好|太差|垃圾|没用|坑|骗|烂|失望|差评|不好用|错了|不对|瞎说|胡扯).*")) {
            return Intent.COMPLAINT;
        }

        // 导航检测
        if (input.matches(".*(路线|怎么走|怎么去|导航|地图|在哪|位置|多远|多久|交通|公交|开车|步行|停车场|入口|出口|门|怎么到|走哪条).*")) {
            return Intent.NAVIGATION;
        }

        // 问答检测：疑问词或问号结尾
        if (input.matches(".*(什么|为什么|怎么|哪些|哪个|哪里|是谁|多少|多久|好不好|行不行|可以吗|推荐|介绍|说说|讲讲|有没有|有没有什么).*")
                || input.endsWith("?")
                || input.endsWith("？")) {
            return Intent.QA;
        }

        // 兜底为 QA
        return Intent.QA;
    }
}

package com.ai.guide.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

/**
 * 意图识别 Service — 三层剥离分析法
 *
 * 第1层：剥离闲聊/应答关键词（你好、谢谢、再见...）
 * 第2层：剥离语气助词和程度副词（太、真、好、很、非常、了、呢、吧、啊...）
 * 第3层：剥离赞美形容词（漂亮、可爱、棒、厉害、美、帅、牛...）
 * 剥离后残余 < 2字 → 闲聊；有实质内容 → 投诉/导航/问答
 */
@Service
public class IntentService {

    @Getter
    public enum Intent {
        QA,
        NAVIGATION,
        CHITCHAT,
        COMPLAINT,
        UNKNOWN
    }

    public IntentService() {}

    /** 第1层：闲聊/应答关键词 */
    private static final String CHITCHAT_PATTERN =
            "你好|您好|早上好|下午好|晚上好|嗨|hi|hello|hey|在吗|在不在|你是谁|你叫什么|你能做什么|" +
            "谢谢|感谢|多谢|再见|拜拜|晚安|好的|嗯|哦|哈哈|呵呵|嗯嗯|好吧|行|可以|对|是的|没错|" +
            "收到|辛苦了|拜托|请问|请|问一下|咨询|哇塞|天哪|天啊|我的天|好家伙|哎呀|哎喂";

    /** 第2层：语气助词 + 程度副词 */
    private static final String PARTICLE_PATTERN =
            "太|真|好|很|非常|特别|最|超|极|更|比较|挺|蛮|" +
            "了|呢|吧|啊|呀|哦|哈|嘛|噢|喔|诶|喂|喽|哩|咧|呐|吗|么";

    /** 第3层：赞美形容词 */
    private static final String COMPLIMENT_PATTERN =
            "漂亮|可爱|美|帅|好看|好听|好吃|好玩|厉害|棒|强|牛|聪明|优秀|出色|完美|不错|" +
            "优美|壮观|精彩|高大|雄伟|迷人|仙境";

    /** 投诉关键词 */
    private static final String COMPLAINT_PATTERN =
            "投诉|差劲|糟糕|糟透|不好|太差|垃圾|没用|坑|骗|烂|失望|差评|不好用|错了|不对|瞎说|胡扯|退钱|退款|骗人";

    /** 导航关键词 */
    private static final String NAVIGATION_PATTERN =
            "路线|怎么走|怎么去|导航|地图|在哪|位置|多远|多久|交通|公交|开车|步行|停车场|入口|出口|门|怎么到|走哪条|方向";

    public Intent classify(String input) {
        if (input == null || input.isBlank()) {
            return Intent.UNKNOWN;
        }

        String trimmed = input.trim();

        // 第1层：剥离闲聊关键词 + 标点
        String residual = trimmed.replaceAll(CHITCHAT_PATTERN, "").trim();
        residual = residual.replaceAll("[\\pP\\pS]", "").trim();

        // 第2层：剥离语气助词 + 程度副词
        residual = residual.replaceAll(PARTICLE_PATTERN, "").trim();

        // 第3层：剥离赞美形容词
        residual = residual.replaceAll(COMPLIMENT_PATTERN, "").trim();

        // 三层剥离后 < 2字 → 闲聊
        if (residual.length() < 2) {
            return Intent.CHITCHAT;
        }

        // 有实质内容 → 按优先级判断
        if (trimmed.matches(".*(" + COMPLAINT_PATTERN + ").*")) {
            return Intent.COMPLAINT;
        }

        if (trimmed.matches(".*(" + NAVIGATION_PATTERN + ").*")) {
            return Intent.NAVIGATION;
        }

        return Intent.QA;
    }
}

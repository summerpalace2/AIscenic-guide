package com.ai.guide.domain.ai.gateway;

import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import org.reactivestreams.Publisher;

import java.util.List;

/**
 * 供应商中立的大语言模型能力端口（Domain Gateway Port）。
 *
 * <p>架构定位：COLA 领域网关接口。由基础设施层适配器实现，隔离 Spring AI / OpenAI SDK 细节。
 */
public interface LanguageModelPort {
    Publisher<String> stream(List<LanguageModelMessage> messages);
    <T> T complete(List<LanguageModelMessage> messages, Class<T> responseType);
    String completeText(List<LanguageModelMessage> messages);

    /**
     * 兼容旧实现的可度量文本补全默认方法。
     */
    default LanguageModelCompletion completeMeasured(List<LanguageModelMessage> messages, double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature 必须为 0 到 2 之间的有限数值");
        }
        long started = System.nanoTime();
        String text = completeText(messages);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new LanguageModelCompletion(text, LanguageModelUsage.unknown(), null, elapsedMs, false);
    }

    /**
     * 执行带工具声明的单轮推理（代理工具调用模式：SDK 不自动黑盒执行，直接把 toolCalls 交还给编排器）。
     *
     * @param messages    完整上下文消息列表（建议保持 System 前缀不变以命中 KV Cache）
     * @param tools       固定顺序的工具定义列表
     * @param temperature 采样温度（0.0 ~ 2.0）
     * @return 单轮工具调用或文本响应结果
     */
    default LanguageModelToolTurn completeWithTools(List<LanguageModelMessage> messages,
                                                      List<LanguageModelToolDefinition> tools,
                                                      double temperature) {
        throw new UnsupportedOperationException("当前 LanguageModelPort 实现尚未支持工具调用");
    }
}

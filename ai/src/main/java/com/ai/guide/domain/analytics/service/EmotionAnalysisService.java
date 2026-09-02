package com.ai.guide.domain.analytics.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 游客情绪与满意度异步分析服务 (Emotion Analysis Service)
 *
 * 所属领域：domain.analytics (运营监控与服务看板域)
 * 架构职责：在 AI 对话完成之后，通过异步后台线程池调用大模型进行游客满意度分类（POSITIVE / NEUTRAL / NEGATIVE），并结合规则情感分析作为熔断降级兜底。
 *
 * 核心方法与职责：
 * 1. {@link #analyzeAsync}: 异步提交大模型情绪判定任务，超时（默认 1500ms）或拒绝策略触发时自动回退至规则引擎。
 *    - 参数：text (用户输入文本)。
 *    - 返回值：CompletableFuture 封装的 Sentiment 结果。
 */
@Service
public class EmotionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(EmotionAnalysisService.class);

    private static final String CLASSIFIER_PROMPT = """
            你是景区服务满意度分类器。只判断用户这句话表达的情绪态度，不判断问题是否有答案。
            只允许输出一个英文标签：POSITIVE、NEUTRAL、NEGATIVE，不要输出解释、标点或其他文字。

            POSITIVE：满意、喜欢、认可、感谢、夸赞、觉得体验好。
            NEGATIVE：不满、抱怨、失望、质疑、批评、觉得体验差。
            NEUTRAL：提问、请求推荐、客观陈述、没有明显情绪，或无法确定。
            """;

    private final ChatClient chatClient;
    private final ExecutorService executor;
    private final SentimentService ruleBasedSentiment;
    private final boolean enabled;
    private final long timeoutMs;

    public EmotionAnalysisService(ChatClient.Builder builder,
                                  @Qualifier("emotionExecutor") ExecutorService executor,
                                  SentimentService ruleBasedSentiment,
                                  @Value("${analytics.emotion-ai-enabled:true}") boolean enabled,
                                  @Value("${analytics.emotion-ai-timeout-ms:4500}") long timeoutMs) {
        this.chatClient = builder.build();
        this.executor = executor;
        this.ruleBasedSentiment = ruleBasedSentiment;
        this.enabled = enabled;
        this.timeoutMs = Math.max(500L, timeoutMs);
    }

    /** 熔断器：连续失败次数 */
    private volatile int consecutiveFailures = 0;

    /** 熔断器：熔断恢复时间戳（毫秒），0 表示未熔断 */
    private volatile long circuitOpenUntil = 0;

    private synchronized void recordSuccess() {
        if (consecutiveFailures > 0) {
            log.info("[Emotion] AI 情感复核恢复，重置熔断计数器");
        }
        consecutiveFailures = 0;
        circuitOpenUntil = 0;
    }

    private synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= 3 && circuitOpenUntil == 0) {
            circuitOpenUntil = System.currentTimeMillis() + 60_000;
            log.warn("[Emotion] AI 情感复核连续 {} 次失败，熔断 60 秒", consecutiveFailures);
        }
    }

    private boolean isCircuitOpen() {
        if (circuitOpenUntil > 0 && System.currentTimeMillis() < circuitOpenUntil) {
            return true;
        }
        if (circuitOpenUntil > 0 && System.currentTimeMillis() >= circuitOpenUntil) {
            circuitOpenUntil = 0;
            consecutiveFailures = 0;
            log.info("[Emotion] AI 情感复核熔断期结束，恢复尝试");
        }
        return false;
    }

    /**
     * 异步判断用户情绪；调用方不会等待 AI 返回。
     *
     * @param text 用户原始输入
     * @return 最终分类结果；AI 失败时返回规则分类结果
     */
    public CompletableFuture<SentimentService.Sentiment> classifyAsync(String text) {
        SentimentService.Sentiment fallback = ruleBasedSentiment.analyze(text);
        if (!enabled || text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(fallback);
        }

        // 熔断检查：连续 3 次 AI 失败后暂停 60 秒
        if (isCircuitOpen()) {
            return CompletableFuture.completedFuture(fallback);
        }

        String normalizedText = text.trim().length() > 300
                ? text.trim().substring(0, 300)
                : text.trim();

        try {
            return CompletableFuture
                    .supplyAsync(() -> classify(normalizedText, fallback), executor)
                    .completeOnTimeout(fallback, timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        recordFailure();
                        return fallback;
                    });
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.completedFuture(fallback);
        }
    }

    /** 同步执行一次分类，仅由情绪线程池调用。 */
    private SentimentService.Sentiment classify(String text, SentimentService.Sentiment fallback) {
        try {
            String response = chatClient.prompt()
                    .messages(
                            new SystemMessage(CLASSIFIER_PROMPT),
                            new UserMessage("用户原话：" + text)
                    )
                    .call()
                    .content();
            SentimentService.Sentiment parsed = parseLabel(response);
            SentimentService.Sentiment result = parsed != null ? parsed : fallback;
            recordSuccess();
            return result;
        } catch (Exception ignored) {
            recordFailure();
            return fallback;
        }
    }

    /** 解析模型的严格标签，同时兼容模型偶尔返回中文标签的情况。 */
    private SentimentService.Sentiment parseLabel(String response) {
        if (response == null || response.isBlank()) return null;
        String normalized = response.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("POSITIVE") || normalized.contains("正面") || normalized.contains("积极")) {
            return SentimentService.Sentiment.POSITIVE;
        }
        if (normalized.contains("NEGATIVE") || normalized.contains("负面") || normalized.contains("消极")) {
            return SentimentService.Sentiment.NEGATIVE;
        }
        if (normalized.contains("NEUTRAL") || normalized.contains("中性")) {
            return SentimentService.Sentiment.NEUTRAL;
        }
        return null;
    }
}

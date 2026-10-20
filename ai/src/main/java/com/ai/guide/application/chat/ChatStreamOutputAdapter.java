package com.ai.guide.application.chat;

import com.ai.guide.application.chat.dto.ChatStreamEvent;
import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.chat.service.IntentService;
import com.ai.guide.domain.analytics.service.SentimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns provider-stream lifecycle mapping, cancellation persistence, timeout and analytics side effects. */
@Component
public class ChatStreamOutputAdapter {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamOutputAdapter.class);

    public Flux<ChatStreamEvent> adapt(org.reactivestreams.Publisher<String> providerStream, long timeoutMs,
                                       String sessionKey, String sessionId, String userMessage,
                                       SentimentService.Sentiment sentiment, IntentService.Intent intent,
                                       ChatConversationMemoryPort memory, AnalyticsService analytics) {
        return adapt(providerStream,timeoutMs,sessionKey,sessionId,userMessage,sentiment,intent,memory,analytics,
                memory.currentWriteEpoch(sessionKey));
    }

    public Flux<ChatStreamEvent> adapt(org.reactivestreams.Publisher<String> providerStream,long timeoutMs,
                                      String sessionKey,String sessionId,String userMessage,SentimentService.Sentiment sentiment,
                                      IntentService.Intent intent,ChatConversationMemoryPort memory,AnalyticsService analytics,String writeEpoch) {
        StringBuilder fullReply = new StringBuilder();
        long streamStart = System.currentTimeMillis();
        AtomicBoolean firstChunk = new AtomicBoolean(true);
        return Flux.from(providerStream)
                .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(Math.max(1000L, timeoutMs)))
                .filter(content -> content != null && !content.isEmpty())
                .map(content -> {
                    if (firstChunk.compareAndSet(true, false))
                        log.info("[CHAT_STREAM_FIRST_CHUNK] session={} latencyMs={}", sessionKey, System.currentTimeMillis() - streamStart);
                    fullReply.append(content);
                    return new ChatStreamEvent("text", content);
                })
                .switchIfEmpty(Flux.defer(() -> {
                    log.warn("[CHAT_STREAM_END] session={} kind=EMPTY_PROVIDER_STREAM latencyMs={}", sessionKey, System.currentTimeMillis() - streamStart);
                    return Flux.just(new ChatStreamEvent("error", "AI 服务未返回内容，请稍后重试。"));
                }))
                .doOnComplete(() -> {
                    String reply = fullReply.toString();
                    if (!reply.isEmpty()) {
                        memory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.ASSISTANT, reply)),writeEpoch);
                        log.info("[CHAT_STREAM_COMPLETE] session={} latencyMs={} replyLength={}", sessionKey, System.currentTimeMillis() - streamStart, reply.length());
                        if (analytics != null) analytics.logService(sessionId, userMessage, sentiment.name(), intent.name(), System.currentTimeMillis() - streamStart);
                    }
                })
                .doOnCancel(() -> {
                    String partialReply = fullReply.toString();
                    log.info("[CHAT_STREAM_CANCEL] session={} latencyMs={} partialLength={}", sessionKey, System.currentTimeMillis() - streamStart, partialReply.length());
                    reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
                        try { memory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.ASSISTANT,
                                partialReply.isEmpty() ? "(回答已取消)" : partialReply + " (已停止生成)")),writeEpoch); }
                        catch(RuntimeException unavailable) { log.warn("[CHAT_HISTORY] cancelled reply persistence unavailable; session={}",sessionKey); }
                    });
                })
                .onErrorResume(error -> {
                    boolean timedOut = error instanceof TimeoutException;
                    String fallback = timedOut ? "本次回复等待超时，你可以重新提问。" : "AI 服务暂时不可用，请稍后重试";
                    String partialReply = fullReply.toString();
                    memory.appendConfirmed(sessionKey, List.of(storedMessage(ChatConversationMemoryPort.Role.ASSISTANT,
                            partialReply.isEmpty() ? fallback : partialReply + "\n（回答未完成：" + fallback + "）")),writeEpoch);
                    log.warn("[CHAT_STREAM_END] session={} kind={} latencyMs={}", sessionKey, timedOut ? "TIMEOUT" : "PROVIDER_ERROR", System.currentTimeMillis() - streamStart);
                    return Flux.just(new ChatStreamEvent("error", fallback));
                });
    }

    private static ChatConversationMemoryPort.StoredMessage storedMessage(ChatConversationMemoryPort.Role role, String content) {
        return new ChatConversationMemoryPort.StoredMessage(role, content);
    }
}

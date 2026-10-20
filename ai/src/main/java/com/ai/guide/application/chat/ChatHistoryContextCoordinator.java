package com.ai.guide.application.chat;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.chat.service.ChatContextBudgetPolicy;
import com.ai.guide.domain.chat.service.ChatDomainPolicy;
import com.ai.guide.domain.taxonomy.gateway.LegacyHintTaxonomyPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/** Owns bounded history reads, safety-constraint retention, and asynchronous summary scheduling. */
@Component
public class ChatHistoryContextCoordinator {
    private static final String SUMMARY_PROMPT = "你是对话摘要生成器。生成150字内中文摘要。";

    private final ChatConversationMemoryPort memory;
    private final LanguageModelPort model;
    private final ExecutorService compressionExecutor;
    private final LegacyHintTaxonomyPort taxonomy;
    private final Set<String> compressionInFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong constraintSequence = new AtomicLong();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatHistoryContextCoordinator(ChatConversationMemoryPort memory,
                                         LanguageModelPort model,
                                         @Qualifier("compressionExecutor") ExecutorService compressionExecutor,
                                         LegacyHintTaxonomyPort taxonomy) {
        this.memory = memory;
        this.model = model;
        this.compressionExecutor = compressionExecutor;
        this.taxonomy = taxonomy;
    }

    public void rememberCurrentConstraints(String sessionKey, String message) {
        rememberCurrentConstraints(sessionKey,message,memory.currentWriteEpoch(sessionKey));
    }
    public void rememberCurrentConstraints(String sessionKey,String message,String writeEpoch) {
        boolean retain = ChatContextBudgetPolicy.isCritical(message);
        if (!retain && isRevision(message)) {
            var snapshot = memory.loadSummarySnapshot(sessionKey);
            if (snapshot == null) throw new ChatContextBudgetPolicy.ContextReadException();
            retain = !snapshot.criticalConstraints().isEmpty();
        }
        if (retain) {
            long sequence = constraintSequence.updateAndGet(previous -> Math.max(previous + 1, System.currentTimeMillis() * 1000L));
            memory.rememberCriticalConstraints(sessionKey, List.of(message), ChatContextBudgetPolicy.CRITICAL_UTF8_BYTES, sequence,writeEpoch);
        }
    }

    private boolean isRevision(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return taxonomy.getHints("chatSafetyRevisionSignals").stream()
                .filter(signal -> signal != null && !signal.isBlank())
                .anyMatch(signal -> normalized.contains(signal.toLowerCase(java.util.Locale.ROOT)));
    }

    public List<LanguageModelMessage> boundedContext(String sessionKey) {
        String writeEpoch=memory.currentWriteEpoch(sessionKey);
        var history = memory.loadHistory(sessionKey, 50);
        if (history == null) throw new ChatContextBudgetPolicy.ContextReadException();
        var recent = new ArrayList<>(toModelMessages(history.messages()));
        var snapshot = memory.loadSummarySnapshot(sessionKey);
        if (snapshot == null) throw new ChatContextBudgetPolicy.ContextReadException();
        long total = Math.max(history.total(), recent.size());
        long offset = total - recent.size();
        long cursor = Math.max(0, snapshot.coveredThrough());
        long backlog = Math.max(0, offset - cursor);
        List<LanguageModelMessage> recovery = List.of();
        if (backlog > 0) {
            var read = memory.readRange(sessionKey, cursor,
                    (int) Math.min(ChatContextBudgetPolicy.COMPRESSION_MESSAGES, backlog));
            recovery = read == null ? List.of() : toModelMessages(read);
        }
        var critical = new ArrayList<>(snapshot.criticalConstraints());
        rememberCriticalFrom(sessionKey, recovery, cursor, critical,writeEpoch);
        rememberCriticalFrom(sessionKey, recent, offset, critical,writeEpoch);

        var contiguous = new ArrayList<LanguageModelMessage>();
        int limit;
        if (backlog > 0) {
            contiguous.addAll(recovery);
            if (recovery.size() == backlog) contiguous.addAll(recent);
            limit = recovery.size() == backlog ? Math.max(0, contiguous.size() - 12) : recovery.size();
        } else {
            int covered = (int) Math.max(0, Math.min(recent.size(), cursor - offset));
            contiguous.addAll(recent.subList(covered, recent.size()));
            limit = Math.max(0, contiguous.size() - 12);
        }

        var batch = new ArrayList<LanguageModelMessage>();
        int chars = SUMMARY_PROMPT.length();
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            String summaryContent = "此前摘要：" + ChatContextBudgetPolicy.boundedFact(snapshot.summary(), 2000);
            batch.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, summaryContent));
            chars += summaryContent.length() + 5;
        }
        int coveredCount = 0;
        for (int i = 0; i < limit && coveredCount < ChatContextBudgetPolicy.COMPRESSION_MESSAGES; i++) {
            LanguageModelMessage item = contiguous.get(i);
            int itemChars = item.content().length() + 5;
            if (itemChars + SUMMARY_PROMPT.length() > ChatContextBudgetPolicy.COMPRESSION_CHARS) {
                if (coveredCount == 0) throw new ChatContextBudgetPolicy.ContextBudgetException(
                        "历史中一条消息超过整理预算，请清空当前会话后继续；原始历史仍保留，本次未调用模型。");
                break;
            }
            if (coveredCount == 0 && chars + itemChars > ChatContextBudgetPolicy.COMPRESSION_CHARS) {
                batch.clear(); chars = SUMMARY_PROMPT.length();
            }
            if (chars + itemChars > ChatContextBudgetPolicy.COMPRESSION_CHARS) break;
            batch.add(item); chars += itemChars; coveredCount++;
        }
        if (coveredCount > 0 && compressionInFlight.add(sessionKey)) {
            long nextCursor = cursor + coveredCount;
            try {
                compressionExecutor.submit(() -> {
                    try {
                        String summary = summarize(batch);
                        if (summary != null) memory.saveSummarySnapshot(sessionKey, summary, nextCursor,writeEpoch);
                    } finally { compressionInFlight.remove(sessionKey); }
                });
            } catch (RuntimeException rejected) { compressionInFlight.remove(sessionKey); }
        }

        var visible = new ArrayList<LanguageModelMessage>();
        if (backlog > 0 && recovery.size() == backlog) visible.addAll(recovery);
        int coveredInRecent = (int) Math.max(0, Math.min(recent.size(), cursor - offset));
        visible.addAll(recent.subList(coveredInRecent, recent.size()));
        var result = boundedHistory(visible, snapshot.summary(), total - cursor);
        if (!critical.isEmpty()) result.add(0, new LanguageModelMessage(LanguageModelMessage.Role.USER,
                "[本会话安全与行动限制原文，按时间顺序；最新明确更正优先，冲突不明先确认] " + json(Map.of("statements", critical))));
        return result;
    }

    private void rememberCriticalFrom(String sessionKey, List<LanguageModelMessage> messages, long offset,
                                      List<String> accumulated,String writeEpoch) {
        for (int i = 0; i < messages.size(); i++) {
            var item = messages.get(i);
            if (item.role() == LanguageModelMessage.Role.USER && (ChatContextBudgetPolicy.isCritical(item.content())
                    || !accumulated.isEmpty() && isRevision(item.content()))) {
                var saved = memory.rememberCriticalConstraints(sessionKey, List.of(item.content()),
                        ChatContextBudgetPolicy.CRITICAL_UTF8_BYTES, offset + i,writeEpoch);
                if (saved != null) { accumulated.clear(); accumulated.addAll(saved); }
            }
        }
    }

    private List<LanguageModelMessage> boundedHistory(List<LanguageModelMessage> history, String summary, long uncovered) {
        var selected = new ArrayList<LanguageModelMessage>();
        int remaining = ChatContextBudgetPolicy.HISTORY_CHARS;
        String shortSummary = ChatContextBudgetPolicy.boundedFact(summary, 2000);
        if (!shortSummary.isBlank()) remaining -= shortSummary.length();
        for (int i = history.size() - 1; i >= 0 && selected.size() < ChatContextBudgetPolicy.MODEL_HISTORY_MESSAGES && remaining > 0; i--) {
            LanguageModelMessage item = history.get(i);
            if (item.content().length() > remaining) break;
            selected.add(0, item); remaining -= item.content().length();
        }
        var result = new ArrayList<LanguageModelMessage>();
        if (!shortSummary.isBlank()) result.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM,
                "[历史对话摘要，仅供参考，安全限制见独立原文] " + shortSummary));
        if (uncovered > selected.size() || selected.size() < history.size()) result.add(new LanguageModelMessage(
                LanguageModelMessage.Role.SYSTEM,
                "[历史窗口已限额] 部分原文仍保存在历史中、等待整理，不能假定遗漏内容已被摘要覆盖；当前回答以最新消息与独立安全限制为准。"));
        result.addAll(selected);
        return result;
    }

    private String summarize(List<LanguageModelMessage> messages) {
        StringBuilder input = new StringBuilder();
        for (var message : messages) {
            if (ChatDomainPolicy.containsLegacyDomain(message.content())) continue;
            String role = message.role() == LanguageModelMessage.Role.USER ? "用户" : "助手";
            input.append(role).append(": ").append(message.content()).append('\n');
        }
        try {
            String result = model.completeText(List.of(
                    new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, SUMMARY_PROMPT),
                    new LanguageModelMessage(LanguageModelMessage.Role.USER, input.toString())));
            return result == null || result.isBlank() || ChatDomainPolicy.containsLegacyDomain(result) ? null : result;
        } catch (Exception ignored) { return null; }
    }

    private static List<LanguageModelMessage> toModelMessages(List<ChatConversationMemoryPort.StoredMessage> messages) {
        if (messages == null || messages.isEmpty()) return List.of();
        return messages.stream().map(message -> switch (message.role()) {
            case SYSTEM -> new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, message.content());
            case USER -> new LanguageModelMessage(LanguageModelMessage.Role.USER, message.content());
            case ASSISTANT -> new LanguageModelMessage(LanguageModelMessage.Role.ASSISTANT, message.content());
        }).toList();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("对话安全限制无法结构化", invalid); }
    }
}

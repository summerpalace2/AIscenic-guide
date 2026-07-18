package com.ai.guide.service;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ContextCompressionService {

    private final int keepRecent;
    private final int lazyCompressMin;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int COMPRESS_RESET_THRESHOLD = 5;
    private static final String SUMMARY_KEY_SUFFIX = ":summary";
    private static final String SUMMARY_PREFIX = "[历史对话摘要] ";
    private static final String COMPRESS_COUNT_SUFFIX = ":compress_count";

    private final RedisTemplate<String, String> redisTemplate;
    private final ChatClient chatClient;
    private final SlotTrackingService slotTrackingService;

    public ContextCompressionService(RedisTemplate<String, String> redisTemplate,
                                    ChatClient.Builder builder,
                                    SlotTrackingService slotTrackingService,
                                    @Value("${context.keep-recent:12}") int keepRecent) {
        this.redisTemplate = redisTemplate;
        this.chatClient = builder.build();
        this.slotTrackingService = slotTrackingService;
        this.keepRecent = keepRecent;
        this.lazyCompressMin = keepRecent;
    }

    /**
     * 获取上下文（多账户：基于 userId 读取偏好）
     * @param sessionId 会话 ID
     * @param allHistory 历史消息列表
     * @param userId 用户 ID（用于读取槽位偏好）
     */
    public List<Message> getContext(String sessionId, List<Message> allHistory, String userId) {
        String summary = getSummary(sessionId);
        int compressedCount = getCompressedCount(sessionId);
        return getContextWithData(sessionId, allHistory, summary, compressedCount, userId);
    }

    public List<Message> getContextWithData(String sessionId, List<Message> allHistory,
                                             String summary, int compressedCount,
                                             String userId) {
        List<Message> result = new ArrayList<>();
        if (allHistory.size() <= keepRecent) {
            result.addAll(allHistory);
            return result;
        }
        List<Message> recent;
        List<Message> toCompress;
        if (summary != null) {
            int newMsgCount = allHistory.size() - compressedCount;
            int compressCount = getCompressCount(sessionId);
            boolean needReset = summary.length() > MAX_SUMMARY_LENGTH || compressCount >= COMPRESS_RESET_THRESHOLD;
            if (!needReset && newMsgCount <= lazyCompressMin) {
                summaryMsg(result, summary);
                result.addAll(allHistory.subList(Math.max(0, allHistory.size() - keepRecent), allHistory.size()));
                return result;
            }
            if (needReset) {
                List<Message> allToCompress = new ArrayList<>(allHistory.size() + 1);
                allToCompress.add(new SystemMessage("旧摘要: " + summary));
                allToCompress.addAll(allHistory.subList(compressedCount, allHistory.size()));
                String resetSummary = compress(allToCompress, null, userId);
                if (resetSummary == null) {
                    toCompress = allHistory.subList(compressedCount, allHistory.size() - keepRecent);
                    recent = allHistory.subList(allHistory.size() - keepRecent, allHistory.size());
                } else {
                    saveSummary(sessionId, resetSummary, allHistory.size() - keepRecent);
                    resetCompressCount(sessionId);
                    summaryMsg(result, resetSummary);
                    String ss = slotTrackingService.toPromptContext(userId);
                    if (!ss.isEmpty()) result.add(new SystemMessage(ss));
                    result.addAll(allHistory.subList(Math.max(0, allHistory.size() - keepRecent), allHistory.size()));
                    return result;
                }
            } else {
                toCompress = allHistory.subList(compressedCount, allHistory.size() - keepRecent);
                recent = allHistory.subList(allHistory.size() - keepRecent, allHistory.size());
            }
        } else {
            toCompress = allHistory.subList(0, allHistory.size() - keepRecent);
            recent = allHistory.subList(allHistory.size() - keepRecent, allHistory.size());
        }
        if (toCompress.isEmpty()) {
            summaryMsg(result, summary != null ? summary : "对话已发生");
            result.addAll(allHistory.subList(Math.max(0, allHistory.size() - keepRecent), allHistory.size()));
            return result;
        }
        String newSummary = compress(toCompress, summary, userId);
        if (newSummary == null) {
            summaryMsg(result, summary != null ? summary : "对话已发生");
            result.addAll(allHistory.subList(Math.max(0, allHistory.size() - keepRecent), allHistory.size()));
            return result;
        }
        saveSummary(sessionId, newSummary, allHistory.size() - keepRecent);
        incrementCompressCount(sessionId, false);
        summaryMsg(result, newSummary);
        String ss = slotTrackingService.toPromptContext(userId);
        if (ss != null && !ss.isEmpty()) result.add(new SystemMessage(ss));
        result.addAll(recent);
        return result;
    }

    private String compress(List<Message> messages, String existingSummary, String userId) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isEmpty()) {
            sb.append("已有的对话摘要：\n").append(existingSummary).append("\n\n");
        }
        sb.append("新增对话内容：\n");
        String slots = slotTrackingService.toPromptContext(userId);
        if (slots != null && !slots.isEmpty()) {
            sb.append("\n用户偏好快照：\n").append(slots);
        }
        for (Message msg : messages) {
            String role = msg.getMessageType() == MessageType.USER ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.getContent()).append("\n");
        }
        try {
            String result = chatClient.prompt()
                .messages(new SystemMessage("你是对话摘要生成器。生成150字内中文摘要。"), new UserMessage(sb.toString()))
                .call().content();
            return (result != null && !result.isBlank()) ? result : "对话已发生";
        } catch (Exception e) {
            return null;
        }
    }

    private void summaryMsg(List<Message> result, String summary) {
        if (summary != null && !summary.isEmpty()) result.add(new SystemMessage(SUMMARY_PREFIX + summary));
    }

    private String getSummary(String sessionId) {
        try { return redisTemplate.opsForValue().get(sessionId + SUMMARY_KEY_SUFFIX); }
        catch (Exception e) { return null; }
    }

    private void saveSummary(String sessionId, String summary, int compressedCount) {
        try {
            redisTemplate.opsForValue().set(sessionId + SUMMARY_KEY_SUFFIX, summary);
            redisTemplate.opsForValue().set(sessionId + ":compressed_count", String.valueOf(compressedCount));
        } catch (Exception e) {}
    }

    private int getCompressedCount(String sessionId) {
        try {
            String v = redisTemplate.opsForValue().get(sessionId + ":compressed_count");
            return v != null ? Integer.parseInt(v) : 0;
        } catch (Exception e) { return 0; }
    }

    private int getCompressCount(String sessionId) {
        try {
            String v = redisTemplate.opsForValue().get(sessionId + COMPRESS_COUNT_SUFFIX);
            return v != null ? Integer.parseInt(v) : 0;
        } catch (Exception e) { return 0; }
    }

    private void incrementCompressCount(String sessionId, boolean reset) {
        try {
            if (reset) {
                redisTemplate.opsForValue().set(sessionId + COMPRESS_COUNT_SUFFIX, "0");
            } else {
                redisTemplate.opsForValue().increment(sessionId + COMPRESS_COUNT_SUFFIX, 1);
            }
        } catch (Exception ignored) {}
    }

    private void resetCompressCount(String sessionId) { incrementCompressCount(sessionId, true); }

    public void clearSummary(String sessionId) {
        try {
            redisTemplate.delete(sessionId + SUMMARY_KEY_SUFFIX);
            redisTemplate.delete(sessionId + ":compressed_count");
            redisTemplate.delete(sessionId + COMPRESS_COUNT_SUFFIX);
        } catch (Exception ignored) {}
    }
}

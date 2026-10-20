package com.ai.guide.domain.chat.service;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.chat.gateway.ChatSummaryStorePort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 多轮对话历史上下文智能压缩与滑动窗口管理服务
 *
 * 所属领域：domain.chat.service（AI 导游交互服务层）
 * 架构职责：在用户多轮交互历史超出大模型上下文预算时，自动提取关键实体与意图进行上下文动态压缩。
 */
@Service
public class ContextCompressionService {

    private final int keepRecent;
    private final int lazyCompressMin;
    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int COMPRESS_RESET_THRESHOLD = 5;
    private static final String SUMMARY_PREFIX = "[历史对话摘要] ";

    private final ChatSummaryStorePort summaryStore;
    private final LanguageModelPort modelGateway;
    private final SlotTrackingService slotTrackingService;

    public ContextCompressionService(ChatSummaryStorePort summaryStore,
                                    LanguageModelPort modelGateway,
                                    SlotTrackingService slotTrackingService,
                                    @Value("${context.keep-recent:12}") int keepRecent) {
        this.summaryStore = summaryStore;
        this.modelGateway = modelGateway;
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
    public List<LanguageModelMessage> getContext(String sessionId, List<LanguageModelMessage> allHistory, String userId) {
        String summary = getSummary(sessionId);
        int compressedCount = summaryStore.getCompressedCount(sessionId);
        return getContextWithData(sessionId, allHistory, summary, compressedCount, userId);
    }

    public List<LanguageModelMessage> getContextWithData(String sessionId, List<LanguageModelMessage> allHistory,
                                             String summary, int compressedCount,
                                             String userId) {
        List<LanguageModelMessage> result = new ArrayList<>();
        if (allHistory.size() <= keepRecent) {
            result.addAll(allHistory);
            return result;
        }
        List<LanguageModelMessage> recent;
        List<LanguageModelMessage> toCompress;
        if (summary != null) {
            int newMsgCount = allHistory.size() - compressedCount;
            int compressCount = summaryStore.getCompressionCount(sessionId);
            boolean needReset = summary.length() > MAX_SUMMARY_LENGTH || compressCount >= COMPRESS_RESET_THRESHOLD;
            if (!needReset && newMsgCount <= lazyCompressMin) {
                summaryMsg(result, summary);
                result.addAll(allHistory.subList(Math.max(0, allHistory.size() - keepRecent), allHistory.size()));
                return result;
            }
            if (needReset) {
                List<LanguageModelMessage> allToCompress = new ArrayList<>(allHistory.size() + 1);
                allToCompress.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, "旧摘要: " + summary));
                allToCompress.addAll(allHistory.subList(compressedCount, allHistory.size()));
                String resetSummary = compress(allToCompress, null, userId);
                if (resetSummary == null) {
                    toCompress = allHistory.subList(compressedCount, allHistory.size() - keepRecent);
                    recent = allHistory.subList(allHistory.size() - keepRecent, allHistory.size());
                } else {
                    summaryStore.saveSummary(sessionId, resetSummary, allHistory.size() - keepRecent);
                    summaryStore.resetCompressionCount(sessionId);
                    summaryMsg(result, resetSummary);
                    String ss = slotTrackingService.toPromptContext(userId);
                    if (!ss.isEmpty()) result.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, ss));
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
        summaryStore.saveSummary(sessionId, newSummary, allHistory.size() - keepRecent);
        summaryStore.incrementCompressionCount(sessionId);
        summaryMsg(result, newSummary);
        String ss = slotTrackingService.toPromptContext(userId);
        if (ss != null && !ss.isEmpty()) result.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, ss));
        result.addAll(recent);
        return result;
    }

    private String compress(List<LanguageModelMessage> messages, String existingSummary, String userId) {
        StringBuilder sb = new StringBuilder();
        if (existingSummary != null && !existingSummary.isEmpty()) {
            sb.append("已有的对话摘要：\n").append(existingSummary).append("\n\n");
        }
        sb.append("新增对话内容：\n");
        String slots = slotTrackingService.toPromptContext(userId);
        if (slots != null && !slots.isEmpty()) {
            sb.append("\n用户偏好快照：\n").append(slots);
        }
        for (LanguageModelMessage msg : messages) {
            String role = msg.role() == LanguageModelMessage.Role.USER ? "用户" : "助手";
            sb.append(role).append(": ").append(msg.content()).append("\n");
        }
        try {
            String result = modelGateway.completeText(List.of(
                    new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, "你是对话摘要生成器。生成150字内中文摘要。"),
                    new LanguageModelMessage(LanguageModelMessage.Role.USER, sb.toString())));
            return (result != null && !result.isBlank()) ? result : "对话已发生";
        } catch (Exception e) {
            return null;
        }
    }

    private void summaryMsg(List<LanguageModelMessage> result, String summary) {
        if (summary != null && !summary.isEmpty()) result.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, SUMMARY_PREFIX + summary));
    }

    private String getSummary(String sessionId) {
        return summaryStore.getSummary(sessionId);
    }

    public void clearSummary(String sessionId) {
        summaryStore.clear(sessionId);
    }
}

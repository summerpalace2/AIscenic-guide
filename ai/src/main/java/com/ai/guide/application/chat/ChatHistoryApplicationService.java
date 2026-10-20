package com.ai.guide.application.chat;

import com.ai.guide.domain.chat.model.ChatHistoryVO;
import com.ai.guide.domain.chat.model.ConversationVO;
import com.ai.guide.domain.chat.service.ChatHistoryService;
import org.springframework.stereotype.Service;

import java.util.List;

/** Application use cases for listing, reading, and deleting a user's chat history. */
@Service
public class ChatHistoryApplicationService {
    private final ChatHistoryService historyService;

    public ChatHistoryApplicationService(ChatHistoryService historyService) {
        this.historyService = historyService;
    }

    public List<ConversationVO> listSessions() {
        return historyService.getAllSessions();
    }

    public List<ChatHistoryVO> getMessages(String sessionId) {
        return historyService.getMessages(sessionId);
    }

    public void deleteSession(String sessionId) {
        historyService.deleteSession(sessionId);
    }
}

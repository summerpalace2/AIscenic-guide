package com.ai.guide.adapter.chat.web;

import com.ai.guide.common.model.Result;
import com.ai.guide.application.chat.ChatHistoryApplicationService;
import com.ai.guide.domain.chat.model.ChatHistoryNotFoundException;
import com.ai.guide.domain.chat.model.ChatHistoryVO;
import com.ai.guide.domain.chat.model.ConversationVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 会话历史适配器控制器 - 管理聊天会话和消息记录
 *
 * 架构定位：COLA 适配层 (Adapter Layer) - Web 协议入口。
 */
@RestController
@RequestMapping("/ai")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final ChatHistoryApplicationService chatHistoryService;

    public HistoryController(ChatHistoryApplicationService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping({"/history", "/history/sessions"})
    public Result<List<ConversationVO>> listSessions() {
        log.info("[历史API] 收到请求：查询所有会话");
        List<ConversationVO> sessions = chatHistoryService.listSessions();
        log.info("会话数: " + sessions.size());
        return Result.success("查询成功", sessions);
    }

    @GetMapping(path = {"/history/{sessionId}", "/history/messages"})
    public Result<List<ChatHistoryVO>> getMessages(
            @PathVariable(required = false) String sessionId,
            @RequestParam(value = "sessionId", required = false) String querySessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = querySessionId;
        }
        log.info("获取会话消息: " + sessionId);
        List<ChatHistoryVO> messages = getMessagesOrMapNotFound(sessionId);
        log.info("消息数: " + messages.size());
        return Result.success("查询成功", messages);
    }

    @DeleteMapping("/history/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        log.info("删除会话: " + sessionId);
        try {
            chatHistoryService.deleteSession(sessionId);
        } catch (ChatHistoryNotFoundException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage());
        }
        return Result.success("删除成功", null);
    }

    private List<ChatHistoryVO> getMessagesOrMapNotFound(String sessionId) {
        try {
            return chatHistoryService.getMessages(sessionId);
        } catch (ChatHistoryNotFoundException missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missing.getMessage());
        }
    }
}

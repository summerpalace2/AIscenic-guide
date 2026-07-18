package com.ai.guide.controller;

import com.ai.guide.model.ChatHistoryVO;
import com.ai.guide.model.ConversationVO;
import com.ai.guide.model.Result;
import com.ai.guide.service.ChatHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话历史控制器 - 管理聊天会话和消息记录
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);

    private final ChatHistoryService chatHistoryService;

    public HistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping({"/history", "/history/sessions"})
    public Result<List<ConversationVO>> listSessions() {
        log.info("[历史API] 收到请求：查询所有会话");
        List<ConversationVO> sessions = chatHistoryService.getAllSessions();
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
        List<ChatHistoryVO> messages = chatHistoryService.getMessages(sessionId);
        log.info("消息数: " + messages.size());
        return Result.success("查询成功", messages);
    }

    @DeleteMapping("/history/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        log.info("删除会话: " + sessionId);
        chatHistoryService.deleteSession(sessionId);
        return Result.success("删除成功", null);
    }
}
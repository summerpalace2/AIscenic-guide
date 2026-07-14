package com.ai.guide.controller;

import com.ai.guide.model.ChatHistoryVO;
import com.ai.guide.model.ConversationVO;
import com.ai.guide.model.Result;
import com.ai.guide.service.ChatHistoryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话历史控制器 - 管理聊天会话和消息记录
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class HistoryController {

    private final ChatHistoryService chatHistoryService;

    public HistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping({"/history", "/history/sessions"})
    public Result<List<ConversationVO>> listSessions() {
        System.out.println("[历史API] 收到请求：查询所有会话");
        List<ConversationVO> sessions = chatHistoryService.getAllSessions();
        System.out.println("会话数: " + sessions.size());
        return Result.success("查询成功", sessions);
    }

    @GetMapping(path = {"/history/{sessionId}", "/history/messages"})
    public Result<List<ChatHistoryVO>> getMessages(
            @PathVariable(required = false) String sessionId,
            @RequestParam(value = "sessionId", required = false) String querySessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = querySessionId;
        }
        System.out.println("获取会话消息: " + sessionId);
        List<ChatHistoryVO> messages = chatHistoryService.getMessages(sessionId);
        System.out.println("消息数: " + messages.size());
        return Result.success("查询成功", messages);
    }

    @DeleteMapping("/history/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        System.out.println("删除会话: " + sessionId);
        chatHistoryService.deleteSession(sessionId);
        return Result.success("删除成功", null);
    }
}
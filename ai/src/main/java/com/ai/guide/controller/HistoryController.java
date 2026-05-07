package com.ai.guide.controller;

import com.ai.guide.model.ChatHistoryVO;
import com.ai.guide.model.ConversationVO;
import com.ai.guide.model.Result;
import com.ai.guide.service.ChatHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 历史记录 API 控制器
 *
 * 提供前端侧边栏所需的历史数据 API：
 *
 *   GET  /ai/history/sessions         - 会话列表（按活跃时间倒序）
 *   GET  /ai/history/messages          - 指定会话的完整消息历史
 *   DELETE /ai/history/sessions/{id}   - 删除会话及所有消息
 *
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/history")
public class HistoryController {

    private final ChatHistoryService chatHistoryService;

    public HistoryController(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * GET /ai/history/sessions
     * 返回所有会话列表，按最近活跃时间倒序
     * 前端用于渲染侧边栏
     */
    @GetMapping("/sessions")
    public Result<List<ConversationVO>> listSessions() {
        System.out.println("[历史API] 收到请求：查询所有会话");
        List<ConversationVO> sessions = chatHistoryService.getAllSessions();
        System.out.println("[历史API] 返回 " + sessions.size() + " 个会话");
        return Result.success("查询成功", sessions);
    }

    /**
     * GET /ai/history/messages?sessionId=xxx
     * 返回指定会话的完整消息历史（user + assistant 成对）
     * 前端用于切换会话时加载对话记录
     */
    @GetMapping("/messages")
    public Result<List<ChatHistoryVO>> getMessages(@RequestParam("sessionId") String sessionId) {
        System.out.println("[历史API] 收到请求：查询会话 " + sessionId + " 的消息");
        List<ChatHistoryVO> messages = chatHistoryService.getMessages(sessionId);
        System.out.println("[历史API] 返回 " + messages.size() + " 条消息");
        return Result.success("查询成功", messages);
    }

    /**
     * DELETE /ai/history/sessions/{sessionId}
     * 删除指定会话及所有关联消息
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        System.out.println("[历史API] 收到请求：删除会话 " + sessionId);
        chatHistoryService.deleteSession(sessionId);
        return Result.success("删除成功", null);
    }
}

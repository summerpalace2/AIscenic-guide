package com.ai.guide.domain.analytics.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.analytics.service.OnlinePresenceService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线用户与服务人数看板控制器 (Online Presence Controller)
 *
 * 所属领域：domain.analytics (运营监控与服务看板域)
 * 架构职责：负责前端游客设备/登录用户的心跳续约接收、在线活跃会话维护，以及为管理端大屏提供实时在线人数与活跃会话列表。
 *
 * 核心接口与关键方法：
 * 1. {@link #heartbeat}: 接收客户端周期性发送的心跳请求，基于当前用户身份刷新 Redis/内存中的在线存活 TTL。
 *    - 鉴权约束：必须携带有效 Token (非匿名)。
 * 2. {@link #onlineUsers}: 查询系统当前处于活跃在线状态的用户数与会话列表。
 *    - 鉴权约束：需要 ADMIN 权限。
 *    - 返回结果：包含当前在线总人数 (total)、活跃用户列表 (items) 以及有效窗口秒数 (ttlSeconds)。
 */
@RestController
@RequestMapping("/ai")
public class OnlinePresenceController {
    private final OnlinePresenceService presenceService;

    public OnlinePresenceController(OnlinePresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @PostMapping("/online/heartbeat")
    public Result<Void> heartbeat() {
        if (UserContext.isAnonymous()) return Result.error(401, "请先登录");
        presenceService.heartbeat();
        return Result.success("心跳已更新", null);
    }

    @GetMapping("/admin/online-users")
    public Result<Map<String, Object>> onlineUsers() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        List<Map<String, Object>> items = presenceService.listOnlineUsers();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        data.put("ttlSeconds", 90);
        return Result.success("查询成功", data);
    }
}

package com.ai.guide.adapter.analytics.web;

import com.ai.guide.application.analytics.AnalyticsApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
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
 * <p>所属架构层级：COLA 4.0 Adapter Layer (适配层 Web 控制器)</p>
 * <p>架构职责：负责前端游客设备/登录用户的心跳续约接收、在线活跃会话维护，以及为管理端大屏提供实时在线人数与活跃会话列表。</p>
 * <p>切斯特顿围栏保护：根路径严格保持 {@code @RequestMapping("/ai")}，端点为 {@code /ai/online/heartbeat} 与 {@code /ai/admin/online-users}。</p>
 */
@RestController
@RequestMapping("/ai")
public class OnlinePresenceController {

    private final AnalyticsApplicationService analyticsApplicationService;

    public OnlinePresenceController(AnalyticsApplicationService analyticsApplicationService) {
        this.analyticsApplicationService = analyticsApplicationService;
    }

    /**
     * 接收客户端心跳上报请求，刷新用户活跃状态 TTL
     *
     * @return 成功返回 200 "心跳已更新"；匿名请求返回 401 "请先登录"
     */
    @PostMapping("/online/heartbeat")
    public Result<Void> heartbeat() {
        if (UserContext.isAnonymous()) return Result.error(401, "请先登录");
        analyticsApplicationService.heartbeat();
        return Result.success("心跳已更新", null);
    }

    /**
     * 管理端大屏查询当前在线用户与会话明细列表
     *
     * @return 成功返回 200 "查询成功"（包含 items 列表、total 总数、ttlSeconds 有效窗口秒数）；非管理员返回 403 "无管理员权限"
     */
    @GetMapping("/admin/online-users")
    public Result<Map<String, Object>> onlineUsers() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        List<Map<String, Object>> items = analyticsApplicationService.listOnlineUsers();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", items.size());
        data.put("ttlSeconds", 90);
        return Result.success("查询成功", data);
    }
}

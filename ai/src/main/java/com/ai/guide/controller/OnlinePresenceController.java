package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.OnlinePresenceService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
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

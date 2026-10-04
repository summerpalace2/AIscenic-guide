package com.ai.guide.adapter.user.web;

import com.ai.guide.application.user.UserApplicationService;
import com.ai.guide.common.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 用户认证与令牌管理控制器 (COLA 适配层)
 */
@RestController
@RequestMapping("/ai/auth")
public class AuthController {

    private final UserApplicationService userApplicationService;

    @Autowired
    public AuthController(UserApplicationService userApplicationService) {
        this.userApplicationService = userApplicationService;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        return userApplicationService.register(body);
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        return userApplicationService.login(body);
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return userApplicationService.logout(authorization);
    }
}

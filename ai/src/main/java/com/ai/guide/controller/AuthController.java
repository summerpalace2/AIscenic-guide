package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证控制器
 * 对应 Python: backend/app/api/v1/auth.py
 * 原始 Python API 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        try {
            String username = body.get("username");
            String password = body.get("password");
            if (username == null || password == null || username.isBlank() || password.isBlank()) {
                return Result.error(400, "用户名和密码不能为空");
            }
            Map<String, Object> data = userService.register(username, password);
            return Result.success("注册成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[Auth] 注册失败: {}", e.getMessage());
            return Result.error(500, "注册失败: " + e.getMessage());
        }
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        try {
            String username = body.get("username");
            String password = body.get("password");
            if (username == null || password == null) {
                return Result.error(400, "用户名和密码不能为空");
            }
            Map<String, Object> data = userService.login(username, password);
            return Result.success("登录成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(401, e.getMessage());
        } catch (Exception e) {
            log.error("[Auth] 登录失败: {}", e.getMessage());
            return Result.error(500, "登录失败: " + e.getMessage());
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        // JWT 无状态，客户端直接丢弃 token，服务端无需处理（黑名单可选）
        return Result.success("退出成功", null);
    }
}
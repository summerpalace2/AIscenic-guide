package com.ai.guide.domain.user.api;

import com.ai.guide.common.security.JwtRevocationService;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 用户认证与令牌管理控制器 (Authentication Controller)
 *
 * 所属领域：domain.user (用户与认证域)
 * 架构职责：作为系统唯一合法的身份认证权威入口，负责用户注册、账密鉴权、JWT 令牌签发以及登出令牌作废注销。
 *
 * 核心对外接口与关键方法：
 * 1. {@link #register}: 新用户注册并自动发放登录凭证。
 *    - 关键参数：username (邮箱/手机号/账号), password (明文密码)。
 *    - 返回结果：包含脱敏用户信息与有效 JWT Token 的数据 Map。
 * 2. {@link #login}: 用户身份验证登录（支持 BCrypt 与自动平滑升级旧 Node Scrypt 密码）。
 *    - 关键参数：username, password。
 *    - 返回结果：验证成功后的 user 信息与签发的 JWT Token。
 * 3. {@link #logout}: 用户退出登录。
 *    - 关键参数：authHeader (HTTP Authorization Bearer Token)。
 *    - 返回结果：将当前 Token 写入黑名单完成注销。
 */
@RestController
@RequestMapping("/ai/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final JwtRevocationService revocationService;

    public AuthController(UserService userService,
                          JwtUtil jwtUtil,
                          JwtRevocationService revocationService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.revocationService = revocationService;
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
            log.error("[Auth] 注册失败: {}", e.getClass().getSimpleName());
            return Result.error(500, "注册失败，请稍后重试");
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
            log.error("[Auth] 登录失败: {}", e.getClass().getSimpleName());
            return Result.error(500, "登录失败，请稍后重试");
        }
    }

    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Result.error(401, "请提供有效的登录凭据");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        var claims = jwtUtil.parse(token);
        if (claims == null) return Result.error(401, "登录凭据无效或已过期");
        if (!revocationService.revoke(token, claims)) {
            return Result.error(503, "退出登录暂不可用，请稍后重试");
        }
        return Result.success("退出成功", null);
    }
}

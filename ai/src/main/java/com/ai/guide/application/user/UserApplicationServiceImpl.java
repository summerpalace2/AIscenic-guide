package com.ai.guide.application.user;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.common.security.JwtRevocationService;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * 用户身份认证与账户应用服务实现
 */
@Service
public class UserApplicationServiceImpl implements UserApplicationService {

    private static final Logger log = LoggerFactory.getLogger(UserApplicationServiceImpl.class);

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final JwtRevocationService revocationService;

    @Autowired
    public UserApplicationServiceImpl(UserService userService,
                                      JwtUtil jwtUtil,
                                      JwtRevocationService revocationService) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.revocationService = revocationService;
    }

    @Override
    public Result<Map<String, Object>> register(Map<String, String> body) {
        try {
            if (body == null) {
                return Result.error(400, "用户名和密码不能为空");
            }
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

    @Override
    public Result<Map<String, Object>> login(Map<String, String> body) {
        try {
            if (body == null) {
                return Result.error(400, "用户名和密码不能为空");
            }
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

    @Override
    public Result<Void> logout(String authorization) {
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

    @Override
    public Result<?> getCurrentUser() {
        if (UserContext.isAnonymous()) {
            return Result.error(401, "未登录");
        }
        try {
            String userId = UserContext.getUserId();
            return Result.success("查询成功", userService.getUserById(userId));
        } catch (Exception e) {
            return Result.error(500, "查询失败，请稍后重试");
        }
    }
}

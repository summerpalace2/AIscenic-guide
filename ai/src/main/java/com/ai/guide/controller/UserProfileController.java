package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户信息控制器 — /ai/auth/me
 * 对应 Python: GET /auth/me
 * 原始 Python 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 *
 * 联动偏好设置：返回当前 userId 的偏好信息
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/auth")
public class UserProfileController {

    private static final Logger log = LoggerFactory.getLogger(UserProfileController.class);

    private final UserService userService;

    public UserProfileController(UserService userService) {
        this.userService = userService;
    }

    /**
     * GET /ai/auth/me — 获取当前用户信息
     * 需带 Authorization: Bearer {token}
     */
    @GetMapping("/me")
    public Result<?> getCurrentUser() {
        if (UserContext.isAnonymous()) {
            return Result.error(401, "未登录");
        }
        try {
            String userId = UserContext.getUserId();
            return Result.success("查询成功", userService.getUserById(userId));
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }
}
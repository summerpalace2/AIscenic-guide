package com.ai.guide.adapter.user.web;

import com.ai.guide.application.user.UserApplicationService;
import com.ai.guide.common.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户信息控制器 (COLA 适配层) — /ai/auth/me
 */
@RestController
@RequestMapping("/ai/auth")
public class UserProfileController {

    private final UserApplicationService userApplicationService;

    @Autowired
    public UserProfileController(UserApplicationService userApplicationService) {
        this.userApplicationService = userApplicationService;
    }

    /**
     * GET /ai/auth/me — 获取当前用户信息
     */
    @GetMapping("/me")
    public Result<?> getCurrentUser() {
        return userApplicationService.getCurrentUser();
    }
}

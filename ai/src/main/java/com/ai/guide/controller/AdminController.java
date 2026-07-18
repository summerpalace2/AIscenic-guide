package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理员控制器
 * 对应 Python: backend/app/api/v1/admin.py
 * 原始 Python API 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 * 权限：需 Admin/SuperAdmin Token
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserService userService;

    public AdminController(UserService userService) {
        this.userService = userService;
    }

    private String checkAdmin() {
        if (!UserContext.isAdmin()) {
            return "需要管理员权限";
        }
        return null;
    }

    @GetMapping("/users")
    public Result<?> listUsers(@RequestParam(value = "page", defaultValue = "1") int page,
                                @RequestParam(value = "size", defaultValue = "20") int size) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            return Result.success("查询成功", userService.listUsers(page, size));
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    @PutMapping("/users/{userId}/role")
    public Result<Void> updateUserRole(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            userService.updateUser(userId, body.get("role"), body.get("status"));
            return Result.success("更新成功", null);
        } catch (Exception e) {
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/users/{userId}")
    public Result<Void> deleteUser(@PathVariable String userId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            userService.updateUser(userId, null, "disabled");
            return Result.success("账号已禁用", null);
        } catch (Exception e) {
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    @GetMapping("/users/{userId}")
    public Result<Map<String, Object>> getUserDetail(@PathVariable String userId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            Map<String, Object> user = userService.getUserById(userId);
            if (user == null) return Result.error(404, "用户不存在");
            return Result.success("查询成功", user);
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }
}
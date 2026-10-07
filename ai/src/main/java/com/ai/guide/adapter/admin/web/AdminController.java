package com.ai.guide.adapter.admin.web;

import com.ai.guide.application.admin.AdminApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统后台全局治理与用户管理控制器 (Admin Controller)
 *
 * <p>所属架构层级：COLA 4.0 Adapter Layer (适配层 Web 控制器)</p>
 * <p>架构职责：为管理后台提供系统级用户权限管控、状态冻结、数字人形象与音色配置、全站全局设置等管控端点。</p>
 * <p>切斯特顿围栏保护：
 * 1. 统一进行管理员校验，非管理员严格返回 HTTP 403，错误文案分毫不差为 {@code "需要管理员权限"}。
 * 2. 数字人与系统设置接口保持与历史 app_config 持久化数据兼容，自动剔除历史废弃字段（如 asrEnabled）。
 * 3. 参数校验异常返回 400，未命中资源返回 404，系统内部故障返回 500。</p>
 */
@RestController
@RequestMapping("/ai/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final AdminApplicationService adminApplicationService;

    public AdminController(AdminApplicationService adminApplicationService) {
        this.adminApplicationService = adminApplicationService;
    }

    /**
     * 内部鉴权拦截辅助方法：校验当前请求用户是否具备管理员角色
     *
     * @return 若无权限返回 "需要管理员权限"；若具备管理员权限返回 null
     */
    private String checkAdmin() {
        if (!UserContext.isAdmin()) {
            return "需要管理员权限";
        }
        return null;
    }

    /**
     * 分页查询系统用户列表
     *
     * @param page 分页页码（默认 1）
     * @param size 每页记录条数（默认 20）
     * @return 200 包含用户列表；未授权返回 403；异常返回 400 或 500
     */
    @GetMapping("/users")
    public Result<?> listUsers(@RequestParam(value = "page", defaultValue = "1") int page,
                               @RequestParam(value = "size", defaultValue = "20") int size) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            return Result.success("查询成功", adminApplicationService.listUsers(page, size));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 调整指定用户的角色权限或账号状态
     *
     * @param userId 目标用户唯一标识
     * @param body   请求体 JSON，包含 "role" 与 "status"
     * @return 200 "更新成功"；未授权返回 403；异常返回 400 或 500
     */
    @PutMapping("/users/{userId}/role")
    public Result<Void> updateUserRole(@PathVariable String userId, @RequestBody Map<String, String> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            adminApplicationService.updateUserRole(userId, body.get("role"), body.get("status"));
            return Result.success("更新成功", null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新失败，请稍后重试");
        }
    }

    /**
     * 禁用指定用户账户（软冻结）
     *
     * @param userId 目标用户唯一标识
     * @return 200 "账号已禁用"；未授权返回 403；异常返回 400 或 500
     */
    @DeleteMapping("/users/{userId}")
    public Result<Void> deleteUser(@PathVariable String userId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            adminApplicationService.disableUser(userId);
            return Result.success("账号已禁用", null);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "删除失败，请稍后重试");
        }
    }

    /**
     * 获取指定用户的完整资料详情
     *
     * @param userId 目标用户唯一标识
     * @return 200 "查询成功"；用户不存在返回 404 "用户不存在"；未授权返回 403；异常返回 400 或 500
     */
    @GetMapping("/users/{userId}")
    public Result<Map<String, Object>> getUserDetail(@PathVariable String userId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            Map<String, Object> user = adminApplicationService.getUserDetail(userId);
            if (user == null) return Result.error(404, "用户不存在");
            return Result.success("查询成功", user);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    // ==================== 数字人配置 ====================

    /**
     * 查询数字人导游配置详情
     *
     * @return 200 "查询成功"，包含数字人名称、发音人、语言、问候语及启用状态
     */
    @GetMapping("/digital-human")
    public Result<Map<String, Object>> getDigitalHuman() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        return Result.success("查询成功", adminApplicationService.getDigitalHumanConfig());
    }

    /**
     * 更新数字人导游配置
     *
     * @param body 请求体 JSON，包含待更新的配置项
     * @return 200 "更新成功"；未授权返回 403；异常返回 500 "更新失败，请稍后重试"
     */
    @PutMapping("/digital-human")
    public Result<Void> updateDigitalHuman(@RequestBody Map<String, Object> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            adminApplicationService.updateDigitalHumanConfig(body);
            return Result.success("更新成功", null);
        } catch (Exception e) {
            return Result.error(500, "更新失败，请稍后重试");
        }
    }

    /**
     * 查询数字人预设发音人音色列表
     *
     * @return 200 "查询成功"，包含支持的发音人声音模型元数据
     */
    @GetMapping("/digital-human/voices")
    public Result<List<Map<String, Object>>> listVoices() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        return Result.success("查询成功", adminApplicationService.listDigitalHumanVoices());
    }

    // ==================== 系统设置 ====================

    /**
     * 查询全站系统全局设置项
     *
     * @return 200 "查询成功"，包含站点名称、默认语言、TTS/知识同步开关、上传大小限制等
     */
    @GetMapping("/settings")
    public Result<Map<String, Object>> getSettings() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        return Result.success("查询成功", adminApplicationService.getSettings());
    }

    /**
     * 更新全站系统全局设置项
     *
     * @param body 请求体 JSON，包含设置键值对
     * @return 200 "更新成功"；未授权返回 403；异常返回 500 "更新失败，请稍后重试"
     */
    @PutMapping("/settings")
    public Result<Void> updateSettings(@RequestBody Map<String, Object> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            adminApplicationService.updateSettings(body);
            return Result.success("更新成功", null);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新失败，请稍后重试");
        }
    }
}

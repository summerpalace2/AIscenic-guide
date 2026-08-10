package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.SlotTrackingService;
import org.springframework.data.redis.core.RedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户偏好控制器 - 保存/读取用户偏好设置（基于 Redis Hash）
 * 多账户改造：使用 UserContext.getUserId() 实现用户隔离
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class PreferencesController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final SlotTrackingService slotTrackingService;

    public PreferencesController(RedisTemplate<String, String> redisTemplate,
                                  SlotTrackingService slotTrackingService) {
        this.redisTemplate = redisTemplate;
        this.slotTrackingService = slotTrackingService;
    }

    /**
     * 保存用户偏好
     * 多账户：基于 userId 存储
     */
    @PostMapping("/preferences")
    public Result<Void> savePreferences(@RequestBody Map<String, String> preferences) {
        try {
            String userId = UserContext.getUserId();
            slotTrackingService.saveManualPreferences(userId, preferences);
            return Result.success("偏好已保存", null);
        } catch (Exception e) {
            return Result.error(500, "保存失败: " + e.getMessage());
        }
    }

    /**
     * 读取用户偏好
     * 多账户：基于 userId 读取
     */
    @GetMapping("/preferences")
    public Result<Map<String, String>> loadPreferences() {
        try {
            String userId = UserContext.getUserId();
            Map<String, String> result = slotTrackingService.getManualPreferences(userId);
            result.remove("prefs_initialized");
            log.info("[PREF] loadPreferences userId=" + userId + ", data=" + result);
            return Result.success("查询成功", result);
        } catch (Exception e) {
            return Result.success("查询成功", Map.of());
        }
    }

    /**
     * 清空用户偏好
     * 多账户：清空当前用户的偏好 + 自动槽位
     */
    @DeleteMapping("/preferences")
    public Result<Void> clearPreferences() {
        try {
            String userId = UserContext.getUserId();
            slotTrackingService.clearManualPreferences(userId);
            slotTrackingService.clearSlots(userId);
            log.info("[PREF] clearPreferences userId=" + userId + " (manual+auto cleared)");
            return Result.success("偏好已清空", null);
        } catch (Exception e) {
            return Result.error(500, "清空失败: " + e.getMessage());
        }
    }
}

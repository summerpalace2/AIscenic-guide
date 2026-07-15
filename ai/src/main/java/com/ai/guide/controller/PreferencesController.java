package com.ai.guide.controller;

import com.ai.guide.model.Result;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好控制器 - 保存/读取用户偏好设置（基于 Redis Hash）
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class PreferencesController {

    private static final String PREFS_KEY = "user:preferences";

    private final RedisTemplate<String, String> redisTemplate;

    public PreferencesController(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostMapping("/preferences")
    public Result<Void> savePreferences(@RequestBody Map<String, String> preferences) {
        try {
            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
            hashOps.putAll(PREFS_KEY, preferences);
            System.out.println("保存偏好: " + preferences.size() + " 条");
            return Result.success("偏好已保存", null);
        } catch (Exception e) {
            return Result.error(500, "保存失败: " + e.getMessage());
        }
    }

    @GetMapping("/preferences")
    public Result<Map<String, String>> loadPreferences() {
        try {
            HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
            Map<String, String> entries = hashOps.entries(PREFS_KEY);
            Map<String, String> result = new LinkedHashMap<>();
            entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return Result.success("查询成功", result);
        } catch (Exception e) {
            return Result.success("查询成功", Map.of());
        }
    }
}
package com.ai.guide.controller;

import com.ai.guide.model.Result;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好接口 —— 个性化推荐基座
 * <p>
 * Redis Key: user:preferences (Hash)
 * <p>
 * 当前用固定键（单用户场景），未来接入登录后改为 user:{userId}:prefs
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

    /** 保存偏好 */
    @PostMapping("/preferences")
    public Result<Void> savePreferences(@RequestBody Map<String, String> prefs) {
        try {
            redisTemplate.opsForHash().putAll(PREFS_KEY, prefs);
            System.out.println("[偏好] 已保存 " + prefs.size() + " 项");
            return Result.success("偏好已保存", null);
        } catch (Exception e) {
            return Result.error(500, "保存失败：" + e.getMessage());
        }
    }

    /** 读取偏好 */
    @GetMapping("/preferences")
    public Result<Map<String, String>> loadPreferences() {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(PREFS_KEY);
            Map<String, String> prefs = new LinkedHashMap<>();
            entries.forEach((k, v) -> prefs.put(k.toString(), v.toString()));
            return Result.success("查询成功", prefs);
        } catch (Exception e) {
            return Result.success("查询成功", Map.of());
        }
    }
}

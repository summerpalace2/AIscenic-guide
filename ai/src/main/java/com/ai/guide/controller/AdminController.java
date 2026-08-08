package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
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
    private final JdbcTemplate jdbcTemplate;

    public AdminController(UserService userService,
                           @Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.userService = userService;
        this.jdbcTemplate = jdbcTemplate;
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
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
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
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
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
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
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
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }


    // ==================== 数字人配置 ====================

    /** 可用音色（与 TtsController 白名单保持一致，支持中英双语） */
    private static final List<Map<String, Object>> DIGITAL_HUMAN_VOICES = List.of(
            voice("106", "度博文", "男", "专业讲解风，最适合景区导游", true),
            voice("0", "度小美", "女", "自然流畅，通用", false),
            voice("1", "度小宇", "男", "沉稳大气，适合讲解", false),
            voice("3", "度逍遥", "男", "适合故事、文化类", false),
            voice("4", "度丫丫", "女", "可爱活泼", false),
            voice("5", "度小云", "女", "柔和知性，通用", false),
            voice("103", "度米朵", "女", "甜美活泼", false),
            voice("110", "度小童", "男", "童声，适合亲子", false),
            voice("111", "度小萌", "女", "软萌童声", false)
    );

    private static Map<String, Object> voice(String id, String name, String gender, String style, boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("gender", gender);
        m.put("style", style);
        m.put("isDefault", isDefault);
        m.put("languages", List.of("zh", "en"));
        return m;
    }

    @GetMapping("/digital-human")
    public Result<Map<String, Object>> getDigitalHuman() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "灵仙儿");
        data.put("voiceId", "106");
        data.put("language", "zh");
        data.put("greeting", "你好，欢迎来到灵山胜境，我是您的AI导览员灵仙儿");
        data.put("enabled", true);
        data.putAll(readConfig("digital_human_"));
        if (data.get("enabled") instanceof String s) {
            data.put("enabled", Boolean.parseBoolean(s));
        }
        return Result.success("查询成功", data);
    }

    @PutMapping("/digital-human")
    public Result<Void> updateDigitalHuman(@RequestBody Map<String, Object> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            writeConfig("digital_human_", body);
            return Result.success("更新成功", null);
        } catch (Exception e) {
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    @GetMapping("/digital-human/voices")
    public Result<List<Map<String, Object>>> listVoices() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        return Result.success("查询成功", DIGITAL_HUMAN_VOICES);
    }

    // ==================== 系统设置 ====================

    @GetMapping("/settings")
    public Result<Map<String, Object>> getSettings() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("siteName", "灵山胜境 · AI 智慧导览");
        data.put("defaultLanguage", "zh");
        data.put("ttsEnabled", true);
        data.put("asrEnabled", true);
        data.put("knowledgeSyncEnabled", true);
        data.put("maxUploadSizeMb", 50);
        data.putAll(readConfig("settings_"));
        for (String boolKey : List.of("ttsEnabled", "asrEnabled", "knowledgeSyncEnabled")) {
            if (data.get(boolKey) instanceof String s) {
                data.put(boolKey, Boolean.parseBoolean(s));
            }
        }
        if (data.get("maxUploadSizeMb") instanceof String s) {
            try {
                data.put("maxUploadSizeMb", Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return Result.success("查询成功", data);
    }

    @PutMapping("/settings")
    public Result<Void> updateSettings(@RequestBody Map<String, Object> body) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            writeConfig("settings_", body);
            return Result.success("更新成功", null);
        } catch (Exception e) {
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    // ==================== 配置读写（app_config 键值表） ====================

    /** 读取指定前缀配置，返回去掉前缀后的键值 */
    private Map<String, String> readConfig(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("SELECT key, value FROM app_config WHERE key LIKE ?",
                    rs -> {
                        String key = rs.getString("key");
                        result.put(key.substring(prefix.length()), rs.getString("value"));
                    }, prefix + "%");
        } catch (Exception e) {
            log.error("[Admin] 读取配置失败: {}", e.getMessage());
        }
        return result;
    }

    /** 写入配置：键自动加上前缀，存在则覆盖 */
    private void writeConfig(String prefix, Map<String, Object> values) {
        values.forEach((k, v) -> {
            String key = k.startsWith(prefix) ? k : prefix + k;
            jdbcTemplate.update(
                    "INSERT OR REPLACE INTO app_config (key, value, updated_at) VALUES (?, ?, datetime('now','localtime'))",
                    key, v == null ? "" : String.valueOf(v));
        });
    }
}
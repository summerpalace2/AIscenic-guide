package com.ai.guide.domain.preferences.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.preferences.service.PreferenceConflictException;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户偏好画像与个性化档案控制器 (Preferences Controller)
 *
 * 所属领域：domain.preferences (用户偏好画像域)
 * 架构职责：作为系统偏好数据的唯一合法所有者 (Source of Truth)，提供强类型的偏好读写、版本并发控制 (Revision-based OCC)、字段增量合并与审计追踪。
 *
 * 核心对外接口与关键方法：
 * 1. {@link #loadPreferences}: 查询当前登录用户的完整偏好配置（兴趣标签、步行耐受度、预算偏好、同行人类型、交通与饮食偏好）。
 * 2. {@link #mergePreferences}: 部分字段增量合并更新 (POST/PATCH)。
 *    - 关键参数：patch (待更新的字段子集), expectedRevision (乐观锁期望版本号，防止并发写覆盖)。
 * 3. {@link #replacePreferences}: 全量替换更新用户偏好 (PUT)。
 * 4. {@link #clearPreferences}: 重置/清空偏好为初始默认状态 (DELETE)。
 */
@RestController
@RequestMapping("/ai")
public class PreferencesController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/preferences")
    public ResponseEntity<?> loadPreferences() {
        String userId = authenticatedUser();
        if (userId == null) return error(401, "请先登录后查询正式偏好", Map.of());
        try {
            return ResponseEntity.ok(Result.success("查询成功", preferencesService.get(userId)));
        } catch (IllegalArgumentException error) {
            return error(401, error.getMessage(), Map.of());
        } catch (Exception error) {
            log.warn("[PREF] load failed userId={} type={}", userId, error.getClass().getSimpleName());
            return error(500, "查询偏好失败", Map.of());
        }
    }

    /** Backward-compatible verb whose body is now the typed merge DTO. */
    @PostMapping("/preferences")
    public ResponseEntity<?> mergePreferences(
            @RequestBody(required = false) UserPreferencesPatch patch,
            @RequestParam(required = false) Long expectedRevision) {
        return mutate(patch, expectedRevision, Mutation.MERGE);
    }

    @PatchMapping("/preferences")
    public ResponseEntity<?> patchPreferences(
            @RequestBody(required = false) UserPreferencesPatch patch,
            @RequestParam(required = false) Long expectedRevision) {
        return mutate(patch, expectedRevision, Mutation.MERGE);
    }

    @PutMapping("/preferences")
    public ResponseEntity<?> replacePreferences(
            @RequestBody(required = false) UserPreferencesPatch patch,
            @RequestParam(required = false) Long expectedRevision) {
        return mutate(patch, expectedRevision, Mutation.REPLACE);
    }

    /** Clears formal preferences only; automatic Redis slots are independent. */
    @DeleteMapping("/preferences")
    public ResponseEntity<?> clearPreferences(
            @RequestParam(required = false) Long expectedRevision) {
        String userId = authenticatedUser();
        if (userId == null) return error(401, "请先登录后清空正式偏好", Map.of());
        try {
            PreferencesService.MutationResult result = preferencesService.clear(userId, expectedRevision);
            return ResponseEntity.ok(Result.success("偏好已清空", result.preferences()));
        } catch (PreferenceConflictException conflict) {
            return conflict(conflict);
        } catch (IllegalArgumentException error) {
            return error(400, error.getMessage(), Map.of());
        } catch (Exception error) {
            log.warn("[PREF] clear failed userId={} type={}", userId, error.getClass().getSimpleName());
            return error(500, "清空偏好失败", Map.of());
        }
    }

    private ResponseEntity<?> mutate(UserPreferencesPatch patch, Long expectedRevision, Mutation mutation) {
        String userId = authenticatedUser();
        if (userId == null) return error(401, "请先登录后修改正式偏好", Map.of());
        try {
            PreferencesService.MutationResult result = mutation == Mutation.REPLACE
                    ? preferencesService.replace(userId, patch, expectedRevision)
                    : preferencesService.merge(userId, patch, expectedRevision);
            int status = result.created() ? 201 : 200;
            String message = mutation == Mutation.REPLACE ? "偏好已替换" : "偏好已更新";
            return ResponseEntity.status(status).body(Result.success(message, result.preferences()));
        } catch (PreferenceConflictException conflict) {
            return conflict(conflict);
        } catch (IllegalArgumentException error) {
            return error(400, error.getMessage(), Map.of());
        } catch (Exception error) {
            log.warn("[PREF] mutation failed userId={} operation={} type={}",
                    userId, mutation, error.getClass().getSimpleName());
            return error(500, "保存偏好失败", Map.of());
        }
    }

    private String authenticatedUser() {
        if (UserContext.isAnonymous()) return null;
        String userId = UserContext.getUserId();
        return userId == null || userId.isBlank() ? null : userId;
    }

    private ResponseEntity<Result<Map<String, Object>>> conflict(PreferenceConflictException conflict) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("expectedRevision", conflict.getExpectedRevision());
        data.put("currentRevision", conflict.getCurrentRevision());
        return error(409, conflict.getMessage(), data);
    }

    private ResponseEntity<Result<Map<String, Object>>> error(int status, String message,
                                                               Map<String, Object> data) {
        return ResponseEntity.status(status).body(new Result<>(status, message, data, false));
    }

    private enum Mutation {
        MERGE,
        REPLACE
    }
}

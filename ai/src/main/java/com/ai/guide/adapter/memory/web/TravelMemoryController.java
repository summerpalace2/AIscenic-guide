package com.ai.guide.adapter.memory.web;

import com.ai.guide.application.memory.TravelMemoryApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 游客旅行记忆与长期偏好画像控制器 (Travel Memory Controller)
 *
 * <p>所属架构层级：COLA 4.0 Adapter Layer (适配层 Web 控制器)</p>
 * <p>架构职责：对外提供游客旅行偏好沉淀、大模型/规则双轨候选建议、后台线索收集、个性化前置提示词维护等 11 大 HTTP REST 接口。</p>
 * <p>切斯特顿围栏保护：
 * 1. {@code /ai/memories/candidate} 保持免登开放，支持匿名即时偏好萃取。
 * 2. {@code /ai/memories/observations} 返回 HTTP 202 异步受理状态码。
 * 3. {@code /ai/memories} 显式确认创建返回 HTTP 201 状态码。
 * 4. 严格保持所有 401 拦截文案与历史基准分毫不差。</p>
 */
@RestController
@RequestMapping("/ai/memories")
public class TravelMemoryController {

    private final TravelMemoryApplicationService memoryApplicationService;

    public TravelMemoryController(TravelMemoryApplicationService memoryApplicationService) {
        this.memoryApplicationService = memoryApplicationService;
    }

    /**
     * 查询当前登录用户的生效旅行记忆列表（7 天滑动窗口）
     *
     * @return 200 包含记忆列表；未登录返回 401 "请先登录后查看旅行记忆"
     */
    @GetMapping
    public ResponseEntity<?> list() {
        String user = user();
        return user == null ? error(401, "请先登录后查看旅行记忆") : ResponseEntity.ok(Result.success(memoryApplicationService.list(user)));
    }

    /**
     * 查询为当前用户推荐的待确认记忆候选列表
     *
     * @return 200 包含候选建议列表；未登录返回 401 "请先登录后查看记忆建议"
     */
    @GetMapping("/candidates")
    public ResponseEntity<?> candidates() {
        String user = user();
        return user == null ? error(401, "请先登录后查看记忆建议") : ResponseEntity.ok(Result.success(memoryApplicationService.candidates(user)));
    }

    /**
     * 对话单句即时记忆偏好提取推荐（免登录开放）
     *
     * @param body 请求体 JSON，包含 "message" 句子文本
     * @return 200 包含提取出的 Candidate 结构（若未识别出偏好则返回 null）
     */
    @PostMapping("/candidate")
    public ResponseEntity<?> candidate(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(Result.success(memoryApplicationService.suggest(String.valueOf(body == null ? "" : body.getOrDefault("message", "")))));
    }

    /**
     * 接收对话中捕获的记忆线索，异步入队后台整理队列
     *
     * @param body 请求体 JSON，包含 "message"（对话文本）与 "sessionId"（会话 ID）
     * @return HTTP 202 ACCEPTED "记忆线索已进入后台整理队列"；参数异常返回 400；未登录返回 401 "请先登录后使用旅行记忆"
     */
    @PostMapping("/observations")
    public ResponseEntity<?> observe(@RequestBody(required = false) Map<String, Object> body) {
        String user = user();
        if (user == null) return error(401, "请先登录后使用旅行记忆");
        try {
            memoryApplicationService.captureObservation(user,
                    String.valueOf(body == null ? "" : body.getOrDefault("message", "")),
                    String.valueOf(body == null ? "" : body.getOrDefault("sessionId", "")));
            return ResponseEntity.accepted().body(Result.success("记忆线索已进入后台整理队列", Map.of()));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /**
     * 显式保存一条新的旅行记忆
     *
     * @param body 请求体 JSON，包含 "content"、"category"（默认 CUSTOM）、"sourceType"（默认 CHAT_CONFIRMED）、"sourceRef"
     * @return HTTP 201 CREATED "旅行记忆已保存"；参数异常返回 400；未登录返回 401 "请先登录后保存旅行记忆"
     */
    @PostMapping
    public ResponseEntity<?> confirm(@RequestBody(required = false) Map<String, Object> body) {
        String user = user();
        if (user == null) return error(401, "请先登录后保存旅行记忆");
        try {
            return ResponseEntity.status(201).body(Result.success("旅行记忆已保存",
                    memoryApplicationService.confirm(user,
                            String.valueOf(body == null ? "" : body.getOrDefault("content", "")),
                            String.valueOf(body == null ? "CUSTOM" : body.getOrDefault("category", "CUSTOM")),
                            String.valueOf(body == null ? "CHAT_CONFIRMED" : body.getOrDefault("sourceType", "CHAT_CONFIRMED")),
                            String.valueOf(body == null ? "" : body.getOrDefault("sourceRef", "")))));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /**
     * 编辑更新已存在的旅行记忆条目
     *
     * @param id   待更新的旅行记忆 ID
     * @param body 请求体 JSON，包含 "content" 与 "category"
     * @return 200 "旅行记忆已更新"；参数异常返回 400；未登录返回 401 "请先登录后编辑旅行记忆"
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        String user = user();
        if (user == null) return error(401, "请先登录后编辑旅行记忆");
        try {
            return ResponseEntity.ok(Result.success("旅行记忆已更新",
                    memoryApplicationService.update(user, id,
                            String.valueOf(body == null ? "" : body.getOrDefault("content", "")),
                            String.valueOf(body == null ? "CUSTOM" : body.getOrDefault("category", "CUSTOM")))));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /**
     * 删除指定的旅行记忆
     *
     * @param id 旅行记忆 ID
     * @return 200 "旅行记忆已删除"；未登录返回 401 "请先登录后删除旅行记忆"
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        String user = user();
        if (user == null) return error(401, "请先登录后删除旅行记忆");
        memoryApplicationService.delete(user, id);
        return ResponseEntity.ok(Result.success("旅行记忆已删除", Map.of()));
    }

    /**
     * 确认并采纳候选记忆建议
     *
     * @param id 候选记忆建议 ID
     * @return 200 "旅行记忆已确认"；未登录返回 401 "请先登录后确认记忆建议"
     */
    @PostMapping("/candidates/{id}/confirm")
    public ResponseEntity<?> confirmCandidate(@PathVariable String id) {
        String user = user();
        if (user == null) return error(401, "请先登录后确认记忆建议");
        try {
            return ResponseEntity.ok(Result.success("旅行记忆已确认", memoryApplicationService.confirmCandidate(user, id)));
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
    }

    /**
     * 忽略并关闭候选记忆建议
     *
     * @param id 候选记忆建议 ID
     * @return 200 "记忆建议已忽略"；未登录返回 401 "请先登录后忽略记忆建议"
     */
    @PostMapping("/candidates/{id}/dismiss")
    public ResponseEntity<?> dismissCandidate(@PathVariable String id) {
        String user = user();
        if (user == null) return error(401, "请先登录后忽略记忆建议");
        memoryApplicationService.dismissCandidate(user, id);
        return ResponseEntity.ok(Result.success("记忆建议已忽略", Map.of()));
    }

    /**
     * 获取用户当前生效的偏好前置提示词 (Prepend Prompt)
     *
     * @return 200 包含 "prependPrompt" 键值对；未登录返回 401 "请先登录后查看旅行偏好提示词"
     */
    @GetMapping("/prepend-prompt")
    public ResponseEntity<?> getPrependPrompt() {
        String user = user();
        if (user == null) return error(401, "请先登录后查看旅行偏好提示词");
        return ResponseEntity.ok(Result.success(Map.of("prependPrompt", memoryApplicationService.getPromptContext(user))));
    }

    /**
     * 保存或重新生成用户的偏好前置提示词
     *
     * @param body 请求体 JSON，包含 "action"（为 "resynthesize" 时触发重新生成，默认 save）与 "prependPrompt"
     * @return 200 包含 "prependPrompt" 键值对；未登录返回 401 "请先登录后保存旅行偏好提示词"
     */
    @PostMapping("/prepend-prompt")
    public ResponseEntity<?> updatePrependPrompt(@RequestBody(required = false) Map<String, Object> body) {
        String user = user();
        if (user == null) return error(401, "请先登录后保存旅行偏好提示词");
        String action = String.valueOf(body == null ? "save" : body.getOrDefault("action", "save"));
        if ("resynthesize".equalsIgnoreCase(action)) {
            String prompt = memoryApplicationService.resynthesizePrependPrompt(user);
            return ResponseEntity.ok(Result.success("偏好提示词已重新生成", Map.of("prependPrompt", prompt)));
        }
        String customPrompt = String.valueOf(body == null ? "" : body.getOrDefault("prependPrompt", ""));
        String saved = memoryApplicationService.saveUserCustomizedPrompt(user, customPrompt);
        return ResponseEntity.ok(Result.success("旅行偏好提示词已保存", Map.of("prependPrompt", saved)));
    }

    private String user() {
        return UserContext.isAnonymous() ? null : UserContext.getUserId();
    }

    private ResponseEntity<?> error(int status, String message) {
        return ResponseEntity.status(status).body(Result.error(status, message));
    }
}

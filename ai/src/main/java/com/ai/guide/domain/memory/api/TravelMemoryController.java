package com.ai.guide.domain.memory.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/ai/memories")
public class TravelMemoryController {
    private final TravelMemoryService memories;
    public TravelMemoryController(TravelMemoryService memories) { this.memories = memories; }

    @GetMapping public ResponseEntity<?> list() { String user = user(); return user == null ? error(401, "请先登录后查看旅行记忆") : ResponseEntity.ok(Result.success(memories.list(user))); }
    @GetMapping("/candidates") public ResponseEntity<?> candidates() { String user = user(); return user == null ? error(401, "请先登录后查看记忆建议") : ResponseEntity.ok(Result.success(memories.candidates(user))); }
    @PostMapping("/candidate") public ResponseEntity<?> candidate(@RequestBody(required = false) Map<String, Object> body) { String user = user(); if (user == null) return error(401, "请先登录后使用旅行记忆"); return ResponseEntity.ok(Result.success(memories.suggest(String.valueOf(body == null ? "" : body.getOrDefault("message", ""))))); }
    @PostMapping("/observations") public ResponseEntity<?> observe(@RequestBody(required = false) Map<String, Object> body) { String user = user(); if (user == null) return error(401, "请先登录后使用旅行记忆"); try { memories.captureObservation(user, String.valueOf(body == null ? "" : body.getOrDefault("message", "")), String.valueOf(body == null ? "" : body.getOrDefault("sessionId", ""))); return ResponseEntity.accepted().body(Result.success("记忆线索已进入后台整理队列", Map.of())); } catch (IllegalArgumentException e) { return error(400, e.getMessage()); } }
    @PostMapping public ResponseEntity<?> confirm(@RequestBody(required = false) Map<String, Object> body) { String user = user(); if (user == null) return error(401, "请先登录后保存旅行记忆"); try { return ResponseEntity.status(201).body(Result.success("旅行记忆已保存", memories.confirm(user, String.valueOf(body == null ? "" : body.getOrDefault("content", "")), String.valueOf(body == null ? "CUSTOM" : body.getOrDefault("category", "CUSTOM")), String.valueOf(body == null ? "CHAT_CONFIRMED" : body.getOrDefault("sourceType", "CHAT_CONFIRMED"))))); } catch (IllegalArgumentException e) { return error(400, e.getMessage()); } }
    @PatchMapping("/{id}") public ResponseEntity<?> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) { String user = user(); if (user == null) return error(401, "请先登录后编辑旅行记忆"); try { return ResponseEntity.ok(Result.success("旅行记忆已更新", memories.update(user, id, String.valueOf(body == null ? "" : body.getOrDefault("content", "")), String.valueOf(body == null ? "CUSTOM" : body.getOrDefault("category", "CUSTOM"))))); } catch (IllegalArgumentException e) { return error(400, e.getMessage()); } }
    @DeleteMapping("/{id}") public ResponseEntity<?> delete(@PathVariable String id) { String user = user(); if (user == null) return error(401, "请先登录后删除旅行记忆"); memories.delete(user, id); return ResponseEntity.ok(Result.success("旅行记忆已删除", Map.of())); }
    @PostMapping("/candidates/{id}/confirm") public ResponseEntity<?> confirmCandidate(@PathVariable String id) { String user = user(); if (user == null) return error(401, "请先登录后确认记忆建议"); try { return ResponseEntity.ok(Result.success("旅行记忆已确认", memories.confirmCandidate(user, id))); } catch (IllegalArgumentException e) { return error(400, e.getMessage()); } }
    @PostMapping("/candidates/{id}/dismiss") public ResponseEntity<?> dismissCandidate(@PathVariable String id) { String user = user(); if (user == null) return error(401, "请先登录后忽略记忆建议"); memories.dismissCandidate(user, id); return ResponseEntity.ok(Result.success("记忆建议已忽略", Map.of())); }
    private String user() { return UserContext.isAnonymous() ? null : UserContext.getUserId(); }
    private ResponseEntity<?> error(int status, String message) { return ResponseEntity.status(status).body(Result.error(status, message)); }
}

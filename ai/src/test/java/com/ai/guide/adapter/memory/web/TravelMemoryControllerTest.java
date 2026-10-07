package com.ai.guide.adapter.memory.web;

import com.ai.guide.application.memory.TravelMemoryApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TravelMemoryControllerTest {

    private TravelMemoryApplicationService memoryApplicationService;
    private TravelMemoryController controller;

    @BeforeEach
    void setUp() {
        memoryApplicationService = Mockito.mock(TravelMemoryApplicationService.class);
        controller = new TravelMemoryController(memoryApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testList_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.list();
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertNotNull(body);
        assertEquals(401, body.getCode());
        assertEquals("请先登录后查看旅行记忆", body.getMessage());
    }

    @Test
    void testList_Authenticated() {
        UserContext.set("user-123", "USER");
        TravelMemory mem = new TravelMemory("m1", "PACE", "少走路", "CUSTOM", "", "CONFIRMED", 1.0, 1000L, 1000L);
        when(memoryApplicationService.list("user-123")).thenReturn(List.of(mem));

        ResponseEntity<?> resp = controller.list();
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertNotNull(body);
        assertEquals(200, body.getCode());
        List<?> list = (List<?>) body.getData();
        assertEquals(1, list.size());
    }

    @Test
    void testCandidates_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.candidates();
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("请先登录后查看记忆建议", body.getMessage());
    }

    @Test
    void testCandidates_Authenticated() {
        UserContext.set("user-123", "USER");
        TravelMemoryService.MemoryCandidate candidate = new TravelMemoryService.MemoryCandidate("c1", "PACE", "少走路", "ref", "PENDING", 0.9, 1000L, 1000L);
        when(memoryApplicationService.candidates("user-123")).thenReturn(List.of(candidate));

        ResponseEntity<?> resp = controller.candidates();
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals(200, body.getCode());
    }

    @Test
    void testCandidateSuggest_NoLoginRequired() {
        TravelMemoryService.Candidate candidate = new TravelMemoryService.Candidate("DIET", "不吃辣", 0.95);
        when(memoryApplicationService.suggest("我不吃辣")).thenReturn(candidate);

        ResponseEntity<?> resp = controller.candidate(Map.of("message", "我不吃辣"));
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals(200, body.getCode());
        assertEquals(candidate, body.getData());
    }

    @Test
    void testObserve_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.observe(Map.of("message", "hi"));
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("请先登录后使用旅行记忆", body.getMessage());
    }

    @Test
    void testObserve_Authenticated_Success() {
        UserContext.set("user-123", "USER");
        ResponseEntity<?> resp = controller.observe(Map.of("message", "以后少走路", "sessionId", "sess-1"));
        assertEquals(202, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("记忆线索已进入后台整理队列", body.getMessage());
        verify(memoryApplicationService).captureObservation("user-123", "以后少走路", "sess-1");
    }

    @Test
    void testObserve_IllegalArgumentException() {
        UserContext.set("user-123", "USER");
        doThrow(new IllegalArgumentException("消息不能为空")).when(memoryApplicationService)
                .captureObservation(eq("user-123"), anyString(), anyString());

        ResponseEntity<?> resp = controller.observe(Map.of());
        assertEquals(400, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("消息不能为空", body.getMessage());
    }

    @Test
    void testConfirm_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.confirm(Map.of("content", "少走路"));
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("请先登录后保存旅行记忆", body.getMessage());
    }

    @Test
    void testConfirm_Authenticated_Success() {
        UserContext.set("user-123", "USER");
        TravelMemory mem = new TravelMemory("m1", "PACE", "少走路", "CHAT_CONFIRMED", "ref1", "CONFIRMED", 1.0, 1000L, 1000L);
        when(memoryApplicationService.confirm("user-123", "少走路", "PACE", "CHAT_CONFIRMED", "ref1")).thenReturn(mem);

        ResponseEntity<?> resp = controller.confirm(Map.of(
                "content", "少走路",
                "category", "PACE",
                "sourceType", "CHAT_CONFIRMED",
                "sourceRef", "ref1"
        ));
        assertEquals(201, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("旅行记忆已保存", body.getMessage());
        assertEquals(mem, body.getData());
    }

    @Test
    void testUpdate_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.update("m1", Map.of());
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("请先登录后编辑旅行记忆", body.getMessage());
    }

    @Test
    void testUpdate_Success() {
        UserContext.set("user-123", "USER");
        TravelMemory mem = new TravelMemory("m1", "PACE", "多走走", "CUSTOM", "", "CONFIRMED", 1.0, 1000L, 2000L);
        when(memoryApplicationService.update("user-123", "m1", "多走走", "PACE")).thenReturn(mem);

        ResponseEntity<?> resp = controller.update("m1", Map.of("content", "多走走", "category", "PACE"));
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("旅行记忆已更新", body.getMessage());
    }

    @Test
    void testDelete_Anonymous() {
        UserContext.clear();
        ResponseEntity<?> resp = controller.delete("m1");
        assertEquals(401, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("请先登录后删除旅行记忆", body.getMessage());
    }

    @Test
    void testDelete_Success() {
        UserContext.set("user-123", "USER");
        ResponseEntity<?> resp = controller.delete("m1");
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("旅行记忆已删除", body.getMessage());
        verify(memoryApplicationService).delete("user-123", "m1");
    }

    @Test
    void testConfirmCandidate_Success() {
        UserContext.set("user-123", "USER");
        TravelMemory mem = new TravelMemory("m2", "PACE", "少走路", "OBSERVATION", "", "CONFIRMED", 0.9, 1000L, 1000L);
        when(memoryApplicationService.confirmCandidate("user-123", "c1")).thenReturn(mem);

        ResponseEntity<?> resp = controller.confirmCandidate("c1");
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("旅行记忆已确认", body.getMessage());
    }

    @Test
    void testDismissCandidate_Success() {
        UserContext.set("user-123", "USER");
        ResponseEntity<?> resp = controller.dismissCandidate("c1");
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("记忆建议已忽略", body.getMessage());
        verify(memoryApplicationService).dismissCandidate("user-123", "c1");
    }

    @Test
    void testGetPrependPrompt_Success() {
        UserContext.set("user-123", "USER");
        when(memoryApplicationService.promptDetails("user-123")).thenReturn(Map.of("prependPrompt", "用户偏好少走路", "userCustomizedPrompt", ""));

        ResponseEntity<?> resp = controller.getPrependPrompt();
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        Map<?, ?> data = (Map<?, ?>) body.getData();
        assertEquals("用户偏好少走路", data.get("prependPrompt"));
    }

    @Test
    void testUpdatePrependPrompt_Resynthesize() {
        UserContext.set("user-123", "USER");
        when(memoryApplicationService.resynthesizePrependPrompt("user-123")).thenReturn("新合成偏好");
        when(memoryApplicationService.promptDetails("user-123")).thenReturn(Map.of("prependPrompt", "新合成偏好", "userCustomizedPrompt", ""));

        ResponseEntity<?> resp = controller.updatePrependPrompt(Map.of("action", "resynthesize"));
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("偏好提示词已重新生成", body.getMessage());
        Map<?, ?> data = (Map<?, ?>) body.getData();
        assertEquals("新合成偏好", data.get("prependPrompt"));
    }

    @Test
    void testUpdatePrependPrompt_Save() {
        UserContext.set("user-123", "USER");
        when(memoryApplicationService.saveUserCustomizedPrompt("user-123", "自定义偏好")).thenReturn("自定义偏好");
        when(memoryApplicationService.promptDetails("user-123")).thenReturn(Map.of("prependPrompt", "自定义偏好", "userCustomizedPrompt", "自定义偏好"));

        ResponseEntity<?> resp = controller.updatePrependPrompt(Map.of("prependPrompt", "自定义偏好"));
        assertEquals(200, resp.getStatusCode().value());
        Result<?> body = (Result<?>) resp.getBody();
        assertEquals("旅行偏好提示词已保存", body.getMessage());
        Map<?, ?> data = (Map<?, ?>) body.getData();
        assertEquals("自定义偏好", data.get("prependPrompt"));
    }
}

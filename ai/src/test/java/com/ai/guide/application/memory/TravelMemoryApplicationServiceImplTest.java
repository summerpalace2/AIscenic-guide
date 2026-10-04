package com.ai.guide.application.memory;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelMemoryApplicationServiceImplTest {

    private TravelMemoryService memoryService;
    private TravelMemoryApplicationServiceImpl applicationService;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(TravelMemoryService.class);
        applicationService = new TravelMemoryApplicationServiceImpl(memoryService);
    }

    @Test
    void testList() {
        TravelMemory mem = new TravelMemory("m1", "PACE", "少走路", "CUSTOM", "", "CONFIRMED", 1.0, 1000L, 1000L);
        when(memoryService.list("user1")).thenReturn(List.of(mem));

        List<TravelMemory> result = applicationService.list("user1");
        assertEquals(1, result.size());
        assertEquals("m1", result.get(0).id());
        verify(memoryService).list("user1");
    }

    @Test
    void testCandidates() {
        TravelMemoryService.MemoryCandidate candidate = new TravelMemoryService.MemoryCandidate("c1", "PACE", "少走路", "ref", "PENDING", 0.9, 1000L, 1000L);
        when(memoryService.candidates("user1")).thenReturn(List.of(candidate));

        List<TravelMemoryService.MemoryCandidate> result = applicationService.candidates("user1");
        assertEquals(1, result.size());
        assertEquals("PACE", result.get(0).category());
        verify(memoryService).candidates("user1");
    }

    @Test
    void testSuggest() {
        TravelMemoryService.Candidate candidate = new TravelMemoryService.Candidate("DIET", "不吃辣", 0.95);
        when(memoryService.suggest("我不吃辣")).thenReturn(candidate);

        TravelMemoryService.Candidate result = applicationService.suggest("我不吃辣");
        assertNotNull(result);
        assertEquals("DIET", result.category());
        verify(memoryService).suggest("我不吃辣");
    }

    @Test
    void testCaptureObservation() {
        applicationService.captureObservation("user1", "以后少走路", "sess-1");
        verify(memoryService).captureObservation("user1", "以后少走路", "sess-1");
    }

    @Test
    void testConfirm() {
        TravelMemory mem = new TravelMemory("m1", "PACE", "少走路", "CHAT_CONFIRMED", "ref1", "CONFIRMED", 1.0, 1000L, 1000L);
        when(memoryService.confirm("user1", "少走路", "PACE", "CHAT_CONFIRMED", "ref1")).thenReturn(mem);

        TravelMemory result = applicationService.confirm("user1", "少走路", "PACE", "CHAT_CONFIRMED", "ref1");
        assertNotNull(result);
        assertEquals("m1", result.id());
        verify(memoryService).confirm("user1", "少走路", "PACE", "CHAT_CONFIRMED", "ref1");
    }

    @Test
    void testUpdate() {
        TravelMemory mem = new TravelMemory("m1", "PACE", "多走走", "CUSTOM", "", "CONFIRMED", 1.0, 1000L, 2000L);
        when(memoryService.update("user1", "m1", "多走走", "PACE")).thenReturn(mem);

        TravelMemory result = applicationService.update("user1", "m1", "多走走", "PACE");
        assertNotNull(result);
        assertEquals("多走走", result.content());
        verify(memoryService).update("user1", "m1", "多走走", "PACE");
    }

    @Test
    void testDelete() {
        applicationService.delete("user1", "m1");
        verify(memoryService).delete("user1", "m1");
    }

    @Test
    void testConfirmCandidate() {
        TravelMemory mem = new TravelMemory("m2", "PACE", "少走路", "OBSERVATION", "", "CONFIRMED", 0.9, 1000L, 1000L);
        when(memoryService.confirmCandidate("user1", "c1")).thenReturn(mem);

        TravelMemory result = applicationService.confirmCandidate("user1", "c1");
        assertNotNull(result);
        assertEquals("m2", result.id());
        verify(memoryService).confirmCandidate("user1", "c1");
    }

    @Test
    void testDismissCandidate() {
        applicationService.dismissCandidate("user1", "c1");
        verify(memoryService).dismissCandidate("user1", "c1");
    }

    @Test
    void testPromptMethods() {
        when(memoryService.promptContext("user1")).thenReturn("偏好提示词");
        when(memoryService.synthesizePrependPrompt("user1", true)).thenReturn("新合成提示词");
        when(memoryService.saveUserCustomizedPrompt("user1", "自定义")).thenReturn("自定义已保存");

        assertEquals("偏好提示词", applicationService.getPromptContext("user1"));
        assertEquals("新合成提示词", applicationService.resynthesizePrependPrompt("user1"));
        assertEquals("自定义已保存", applicationService.saveUserCustomizedPrompt("user1", "自定义"));
    }
}

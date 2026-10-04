package com.ai.guide.application.memory;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;

import java.util.List;

public interface TravelMemoryApplicationService {
    List<TravelMemory> list(String userId);
    List<TravelMemoryService.MemoryCandidate> candidates(String userId);
    TravelMemoryService.Candidate suggest(String message);
    void captureObservation(String userId, String message, String sessionId);
    TravelMemory confirm(String userId, String content, String category, String sourceType, String sourceRef);
    TravelMemory update(String userId, String id, String content, String category);
    void delete(String userId, String id);
    TravelMemory confirmCandidate(String userId, String id);
    void dismissCandidate(String userId, String id);
    String getPromptContext(String userId);
    String resynthesizePrependPrompt(String userId);
    String saveUserCustomizedPrompt(String userId, String customPrompt);
}

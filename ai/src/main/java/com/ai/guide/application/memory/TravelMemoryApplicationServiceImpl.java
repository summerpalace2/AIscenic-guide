package com.ai.guide.application.memory;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.service.TravelMemoryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TravelMemoryApplicationServiceImpl implements TravelMemoryApplicationService {

    private final TravelMemoryService travelMemoryService;

    public TravelMemoryApplicationServiceImpl(TravelMemoryService travelMemoryService) {
        this.travelMemoryService = travelMemoryService;
    }

    @Override
    public List<TravelMemory> list(String userId) {
        return travelMemoryService.list(userId);
    }

    @Override
    public List<TravelMemory> listPage(String userId, int limit, int offset) {
        return travelMemoryService.listPage(userId, limit, offset);
    }

    @Override
    public List<TravelMemoryService.MemoryCandidate> candidates(String userId) {
        return travelMemoryService.candidates(userId);
    }

    @Override
    public TravelMemoryService.Candidate suggest(String message) {
        return travelMemoryService.suggest(message);
    }

    @Override
    public void captureObservation(String userId, String message, String sessionId) {
        travelMemoryService.captureObservation(userId, message, sessionId);
    }

    @Override
    public TravelMemory confirm(String userId, String content, String category, String sourceType, String sourceRef) {
        return travelMemoryService.confirm(userId, content, category, sourceType, sourceRef);
    }

    @Override
    public TravelMemory update(String userId, String id, String content, String category) {
        return travelMemoryService.update(userId, id, content, category);
    }

    @Override
    public void delete(String userId, String id) {
        travelMemoryService.delete(userId, id);
    }

    @Override
    public TravelMemory confirmCandidate(String userId, String id) {
        return travelMemoryService.confirmCandidate(userId, id);
    }

    @Override
    public void dismissCandidate(String userId, String id) {
        travelMemoryService.dismissCandidate(userId, id);
    }

    @Override
    public String getPromptContext(String userId) {
        return travelMemoryService.promptContext(userId);
    }

    @Override
    public String resynthesizePrependPrompt(String userId) {
        return travelMemoryService.synthesizePrependPrompt(userId, true);
    }

    @Override
    public String saveUserCustomizedPrompt(String userId, String customPrompt) {
        return travelMemoryService.saveUserCustomizedPrompt(userId, customPrompt);
    }

    @Override
    public java.util.Map<String, Object> promptDetails(String userId) {
        return travelMemoryService.promptDetails(userId);
    }
}

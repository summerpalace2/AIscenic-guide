package com.ai.guide.domain.memory.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TravelMemoryServiceTest {

    private final TravelMemoryService service = new TravelMemoryService(null);

    @Test
    void explicitLongTermStatementProducesAConfirmableCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("以后出去玩尽量少走路，也少爬坡");
        assertEquals("PACE", candidate.category());
        assertEquals("偏好少走路，优先平路、电梯与短步行", candidate.content());
    }

    @Test
    void OneOffStatementDoesNotSilentlyBecomeMemory() {
        assertNull(service.suggest("今天想少走一点路"));
    }

    @Test
    void NaturalPersonalStatementCanBecomeAConfirmableCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我平时很喜欢逛博物馆和人文历史展览");
        assertEquals("INTEREST", candidate.category());
        assertEquals("偏爱人文历史与室内文化场馆", candidate.content());
    }

    @Test
    void CurrentTripInterestDoesNotBecomeLongTermMemory() {
        assertNull(service.suggest("这次想多逛博物馆"));
    }
}

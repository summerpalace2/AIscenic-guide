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

    @Test
    void spicyAndHotpotStatementProducesDietCandidate() {
        TravelMemoryService.Candidate candidate = service.suggest("我想吃火锅 后面帮我推荐辣的食物");
        org.junit.jupiter.api.Assertions.assertNotNull(candidate);
        assertEquals("DIET", candidate.category());
        assertEquals("饮食偏好麻辣、地道重庆火锅与江湖菜风味", candidate.content());
    }

    @Test
    void accessibilityAndPhotographyStatementsProduceCandidates() {
        TravelMemoryService.Candidate access = service.suggest("家里老人腿脚不便，以后出行需要无障碍安排");
        org.junit.jupiter.api.Assertions.assertNotNull(access);
        assertEquals("ACCESSIBILITY", access.category());

        TravelMemoryService.Candidate photo = service.suggest("我出门旅游很重视拍照出片和摄影机位");
        org.junit.jupiter.api.Assertions.assertNotNull(photo);
        assertEquals("INTEREST", photo.category());
    }

    @Test
    void teaAndCoffeeStatementsProduceAccurateCandidates() {
        TravelMemoryService.Candidate tea = service.suggest("路上有没有什么茶馆 我想喝茶");
        org.junit.jupiter.api.Assertions.assertNotNull(tea);
        assertEquals("DIET", tea.category());
        assertEquals("偏好特色老茶馆、盖碗茶与慢生活品茗体验", tea.content());

        TravelMemoryService.Candidate coffee = service.suggest("我平时喜欢在江边找景观咖啡馆喝下午茶");
        org.junit.jupiter.api.Assertions.assertNotNull(coffee);
        assertEquals("DIET", coffee.category());
        assertEquals("偏好景观咖啡馆、独立特调与下午茶小憩", coffee.content());
    }
}

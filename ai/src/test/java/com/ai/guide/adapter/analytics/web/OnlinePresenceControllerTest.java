package com.ai.guide.adapter.analytics.web;

import com.ai.guide.application.analytics.AnalyticsApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlinePresenceControllerTest {

    private AnalyticsApplicationService applicationService;
    private OnlinePresenceController controller;

    @BeforeEach
    void setUp() {
        applicationService = Mockito.mock(AnalyticsApplicationService.class);
        controller = new OnlinePresenceController(applicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testHeartbeat_Anonymous() {
        UserContext.clear();
        Result<Void> result = controller.heartbeat();
        assertEquals(401, result.getCode());
        assertEquals("请先登录", result.getMessage());
        verify(applicationService, never()).heartbeat();
    }

    @Test
    void testHeartbeat_Authenticated() {
        UserContext.set("user-1", "USER");
        Result<Void> result = controller.heartbeat();
        assertEquals(200, result.getCode());
        assertEquals("心跳已更新", result.getMessage());
        verify(applicationService).heartbeat();
    }

    @Test
    void testOnlineUsers_NonAdmin() {
        UserContext.set("user-1", "USER");
        Result<Map<String, Object>> result = controller.onlineUsers();
        assertEquals(403, result.getCode());
        assertEquals("无管理员权限", result.getMessage());
        verify(applicationService, never()).listOnlineUsers();
    }

    @Test
    void testOnlineUsers_Admin() {
        UserContext.set("admin-1", "ADMIN");
        when(applicationService.listOnlineUsers()).thenReturn(List.of(
                Map.of("userId", "u1", "device", "mobile"),
                Map.of("userId", "u2", "device", "pc")
        ));

        Result<Map<String, Object>> result = controller.onlineUsers();
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());

        Map<String, Object> data = result.getData();
        assertNotNull(data);
        assertEquals(2, data.get("total"));
        assertEquals(90, data.get("ttlSeconds"));
        List<?> items = (List<?>) data.get("items");
        assertEquals(2, items.size());
    }
}

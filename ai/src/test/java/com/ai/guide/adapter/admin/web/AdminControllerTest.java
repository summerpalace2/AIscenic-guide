package com.ai.guide.adapter.admin.web;

import com.ai.guide.application.admin.AdminApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 管理后台适配层控制器单元测试套件
 */
class AdminControllerTest {

    private AdminApplicationService adminApplicationService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        adminApplicationService = Mockito.mock(AdminApplicationService.class);
        controller = new AdminController(adminApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testListUsers_NonAdmin() {
        UserContext.set("u1", "USER");
        Result<?> result = controller.listUsers(1, 20);
        assertEquals(403, result.getCode());
        assertEquals("需要管理员权限", result.getMessage());
        verify(adminApplicationService, never()).listUsers(anyInt(), anyInt());
    }

    @Test
    void testListUsers_Admin_Success() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.listUsers(1, 20)).thenReturn(Map.of("items", List.of()));

        Result<?> result = controller.listUsers(1, 20);
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
    }

    @Test
    void testListUsers_IllegalArgumentException() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.listUsers(anyInt(), anyInt())).thenThrow(new IllegalArgumentException("页码错误"));

        Result<?> result = controller.listUsers(0, 20);
        assertEquals(400, result.getCode());
        assertEquals("页码错误", result.getMessage());
    }

    @Test
    void testListUsers_Exception() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.listUsers(anyInt(), anyInt())).thenThrow(new RuntimeException("DB fail"));

        Result<?> result = controller.listUsers(1, 20);
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testUpdateUserRole_NonAdmin() {
        UserContext.clear();
        Result<Void> result = controller.updateUserRole("u1", Map.of("role", "ADMIN"));
        assertEquals(403, result.getCode());
        assertEquals("需要管理员权限", result.getMessage());
    }

    @Test
    void testUpdateUserRole_Admin_Success() {
        UserContext.set("admin1", "ADMIN");
        Result<Void> result = controller.updateUserRole("u1", Map.of("role", "ADMIN", "status", "active"));
        assertEquals(200, result.getCode());
        assertEquals("更新成功", result.getMessage());
        verify(adminApplicationService).updateUserRole("u1", "ADMIN", "active");
    }

    @Test
    void testUpdateUserRole_Exception() {
        UserContext.set("admin1", "ADMIN");
        doThrow(new RuntimeException("error")).when(adminApplicationService).updateUserRole(anyString(), any(), any());

        Result<Void> result = controller.updateUserRole("u1", Map.of());
        assertEquals(500, result.getCode());
        assertEquals("更新失败，请稍后重试", result.getMessage());
    }

    @Test
    void testDeleteUser_Admin_Success() {
        UserContext.set("admin1", "ADMIN");
        Result<Void> result = controller.deleteUser("u1");
        assertEquals(200, result.getCode());
        assertEquals("账号已禁用", result.getMessage());
        verify(adminApplicationService).disableUser("u1");
    }

    @Test
    void testGetUserDetail_NotFound() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.getUserDetail("u-none")).thenReturn(null);

        Result<Map<String, Object>> result = controller.getUserDetail("u-none");
        assertEquals(404, result.getCode());
        assertEquals("用户不存在", result.getMessage());
    }

    @Test
    void testGetUserDetail_Success() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.getUserDetail("u1")).thenReturn(Map.of("id", "u1", "username", "admin"));

        Result<Map<String, Object>> result = controller.getUserDetail("u1");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals("admin", result.getData().get("username"));
    }

    @Test
    void testDigitalHumanEndpoints() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.getDigitalHumanConfig()).thenReturn(Map.of("name", "助手"));
        Result<Map<String, Object>> getRes = controller.getDigitalHuman();
        assertEquals(200, getRes.getCode());
        assertEquals("助手", getRes.getData().get("name"));

        Result<Void> putRes = controller.updateDigitalHuman(Map.of("name", "新助手"));
        assertEquals(200, putRes.getCode());
        verify(adminApplicationService).updateDigitalHumanConfig(Map.of("name", "新助手"));

        when(adminApplicationService.listDigitalHumanVoices()).thenReturn(List.of(Map.of("id", "106")));
        Result<List<Map<String, Object>>> voiceRes = controller.listVoices();
        assertEquals(200, voiceRes.getCode());
        assertEquals(1, voiceRes.getData().size());
    }

    @Test
    void testSettingsEndpoints() {
        UserContext.set("admin1", "ADMIN");
        when(adminApplicationService.getSettings()).thenReturn(Map.of("siteName", "智策"));
        Result<Map<String, Object>> getRes = controller.getSettings();
        assertEquals(200, getRes.getCode());
        assertEquals("智策", getRes.getData().get("siteName"));

        Result<Void> putRes = controller.updateSettings(Map.of("siteName", "新智策"));
        assertEquals(200, putRes.getCode());
        verify(adminApplicationService).updateSettings(Map.of("siteName", "新智策"));
    }
}

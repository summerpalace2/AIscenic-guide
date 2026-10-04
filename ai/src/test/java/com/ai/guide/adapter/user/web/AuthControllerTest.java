package com.ai.guide.adapter.user.web;

import com.ai.guide.application.user.UserApplicationService;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private UserApplicationService userApplicationService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        userApplicationService = Mockito.mock(UserApplicationService.class);
        controller = new AuthController(userApplicationService);
    }

    @Test
    void testRegisterDelegatesToApplicationService() {
        Map<String, String> body = Map.of("username", "u1", "password", "p1");
        when(userApplicationService.register(body))
                .thenReturn(Result.success("注册成功", Map.of("userId", "id-1")));

        Result<Map<String, Object>> result = controller.register(body);
        assertEquals(200, result.getCode());
        assertEquals("注册成功", result.getMessage());
        verify(userApplicationService).register(body);
    }

    @Test
    void testLoginDelegatesToApplicationService() {
        Map<String, String> body = Map.of("username", "u1", "password", "p1");
        when(userApplicationService.login(body))
                .thenReturn(Result.success("登录成功", Map.of("userId", "id-1", "token", "t1")));

        Result<Map<String, Object>> result = controller.login(body);
        assertEquals(200, result.getCode());
        assertEquals("登录成功", result.getMessage());
        verify(userApplicationService).login(body);
    }

    @Test
    void testLogoutDelegatesToApplicationService() {
        when(userApplicationService.logout("Bearer token123"))
                .thenReturn(Result.success("退出成功", null));

        Result<Void> result = controller.logout("Bearer token123");
        assertEquals(200, result.getCode());
        assertEquals("退出成功", result.getMessage());
        verify(userApplicationService).logout("Bearer token123");
    }
}

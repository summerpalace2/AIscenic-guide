package com.ai.guide.application.user;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.gateway.JwtSessionTokenPort;
import com.ai.guide.domain.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserApplicationServiceImplTest {

    private static final String SECRET = "phase5a-jwt-test-secret-0123456789";

    private UserService userService;
    private JwtUtil jwtUtil;
    private JwtSessionTokenPort sessionTokens;
    private UserApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        jwtUtil = new JwtUtil(SECRET, 7);
        sessionTokens = Mockito.mock(JwtSessionTokenPort.class);
        service = new UserApplicationServiceImpl(userService, sessionTokens);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testRegisterValidationAndSuccess() {
        // null body
        Result<Map<String, Object>> r1 = service.register(null);
        assertEquals(400, r1.getCode());

        // empty username
        Result<Map<String, Object>> r2 = service.register(Map.of("username", "", "password", "123"));
        assertEquals(400, r2.getCode());

        // username exists
        when(userService.register("exists", "123")).thenThrow(new IllegalArgumentException("用户名已存在"));
        Result<Map<String, Object>> r3 = service.register(Map.of("username", "exists", "password", "123"));
        assertEquals(400, r3.getCode());
        assertEquals("用户名已存在", r3.getMessage());

        // success
        when(userService.register("newuser", "123")).thenReturn(Map.of("userId", "uid-1"));
        Result<Map<String, Object>> r4 = service.register(Map.of("username", "newuser", "password", "123"));
        assertEquals(200, r4.getCode());
        assertEquals("注册成功", r4.getMessage());
    }

    @Test
    void testLoginValidationAndErrors() {
        // null body
        Result<Map<String, Object>> r1 = service.login(null);
        assertEquals(400, r1.getCode());

        // user not found -> 401
        when(userService.login("nouser", "123")).thenThrow(new IllegalArgumentException("用户不存在"));
        Result<Map<String, Object>> r2 = service.login(Map.of("username", "nouser", "password", "123"));
        assertEquals(401, r2.getCode());
        assertEquals("用户不存在", r2.getMessage());

        // wrong password -> 401
        when(userService.login("user1", "wrong")).thenThrow(new IllegalArgumentException("密码错误"));
        Result<Map<String, Object>> r3 = service.login(Map.of("username", "user1", "password", "wrong"));
        assertEquals(401, r3.getCode());
        assertEquals("密码错误", r3.getMessage());

        // success -> 200
        when(userService.login("user1", "correct")).thenReturn(Map.of("userId", "uid-1", "token", "jwt-token"));
        Result<Map<String, Object>> r4 = service.login(Map.of("username", "user1", "password", "correct"));
        assertEquals(200, r4.getCode());
        assertEquals("登录成功", r4.getMessage());
    }

    @Test
    void testLogoutRevocation() {
        // missing header
        Result<Void> r1 = service.logout(null);
        assertEquals(401, r1.getCode());

        // invalid header prefix
        Result<Void> r2 = service.logout("Basic 123");
        assertEquals(401, r2.getCode());

        // invalid token
        Result<Void> r3 = service.logout("Bearer invalid-token");
        assertEquals(401, r3.getCode());

        // revocation failure -> 503
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        when(sessionTokens.isValid(token)).thenReturn(true);
        when(sessionTokens.revoke(token)).thenReturn(false);
        Result<Void> r4 = service.logout("Bearer " + token);
        assertEquals(503, r4.getCode());

        // success -> 200
        when(sessionTokens.revoke(token)).thenReturn(true);
        Result<Void> r5 = service.logout("Bearer " + token);
        assertEquals(200, r5.getCode());
        assertEquals("退出成功", r5.getMessage());
    }

    @Test
    void testGetCurrentUser() {
        // anonymous -> 401
        Result<?> r1 = service.getCurrentUser();
        assertEquals(401, r1.getCode());
        assertEquals("未登录", r1.getMessage());

        // authenticated -> 200
        UserContext.set("uid-100", "testuser", "USER");
        when(userService.getUserById("uid-100")).thenReturn(Map.of("userId", "uid-100", "username", "testuser"));
        Result<?> r2 = service.getCurrentUser();
        assertEquals(200, r2.getCode());
        assertEquals("查询成功", r2.getMessage());
    }
}

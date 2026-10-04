package com.ai.guide.adapter.user.web;

import com.ai.guide.application.user.UserApplicationService;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProfileControllerTest {

    private UserApplicationService userApplicationService;
    private UserProfileController controller;

    @BeforeEach
    void setUp() {
        userApplicationService = Mockito.mock(UserApplicationService.class);
        controller = new UserProfileController(userApplicationService);
    }

    @Test
    void testGetCurrentUserDelegatesToApplicationService() {
        Result<Map<String, Object>> mockResult = Result.success("查询成功", Map.of("userId", "u1", "username", "admin"));
        Mockito.doReturn(mockResult).when(userApplicationService).getCurrentUser();

        Result<?> result = controller.getCurrentUser();
        assertEquals(200, result.getCode());
        verify(userApplicationService).getCurrentUser();
    }
}

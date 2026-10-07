package com.ai.guide.application.admin;

import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import com.ai.guide.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 管理后台应用服务单元测试套件
 */
class AdminApplicationServiceImplTest {

    private UserService userService;
    private AppConfigRepositoryPort appConfigRepository;
    private AdminApplicationServiceImpl applicationService;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        appConfigRepository = Mockito.mock(AppConfigRepositoryPort.class);
        applicationService = new AdminApplicationServiceImpl(userService, appConfigRepository);
    }

    @Test
    void testUserManagementDelegation() {
        when(userService.listUsers(1, 10)).thenReturn(Map.of("total", 1));
        Object listRes = applicationService.listUsers(1, 10);
        assertNotNull(listRes);
        verify(userService).listUsers(1, 10);

        applicationService.updateUserRole("u1", "ADMIN", "active");
        verify(userService).updateUser("u1", "ADMIN", "active");

        applicationService.disableUser("u1");
        verify(userService).updateUser("u1", null, "disabled");

        when(userService.getUserById("u1")).thenReturn(Map.of("id", "u1"));
        Map<String, Object> detail = applicationService.getUserDetail("u1");
        assertEquals("u1", detail.get("id"));
    }

    @Test
    void testDigitalHumanConfig() {
        when(appConfigRepository.readConfig("digital_human_")).thenReturn(Map.of(
                "name", "定制助手",
                "enabled", "false"
        ));

        Map<String, Object> config = applicationService.getDigitalHumanConfig();
        assertEquals("定制助手", config.get("name"));
        assertEquals("106", config.get("voiceId"));
        assertEquals(false, config.get("enabled"));

        applicationService.updateDigitalHumanConfig(Map.of("name", "新助手"));
        verify(appConfigRepository).writeConfig("digital_human_", Map.of("name", "新助手"));

        List<Map<String, Object>> voices = applicationService.listDigitalHumanVoices();
        assertEquals(9, voices.size());
        assertEquals("106", voices.get(0).get("id"));
        assertEquals(true, voices.get(0).get("isDefault"));
    }

    @Test
    void testSettings() {
        when(appConfigRepository.readConfig("settings_")).thenReturn(Map.of(
                "siteName", "定制智策",
                "asrEnabled", "true",
                "ttsEnabled", "false",
                "maxUploadSizeMb", "100"
        ));

        Map<String, Object> settings = applicationService.getSettings();
        assertEquals("定制智策", settings.get("siteName"));
        assertNull(settings.get("asrEnabled"));
        assertEquals(false, settings.get("ttsEnabled"));
        assertEquals(true, settings.get("knowledgeSyncEnabled"));
        assertEquals(100, settings.get("maxUploadSizeMb"));

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("siteName", "更新站点");
        updatePayload.put("asrEnabled", true);

        assertThrows(IllegalArgumentException.class, () -> applicationService.updateSettings(updatePayload));
        assertTrue(updatePayload.containsKey("asrEnabled"));
        verify(appConfigRepository, never()).writeConfig(anyString(), anyMap());
        applicationService.updateSettings(Map.of("siteName", "更新站点"));
        verify(appConfigRepository).writeConfig("settings_", Map.of("siteName", "更新站点"));
    }
}

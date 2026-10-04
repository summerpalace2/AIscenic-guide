package com.ai.guide.application.admin;

import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import com.ai.guide.domain.user.service.UserService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台与系统全局治理应用服务实现类 (Admin Application Service Implementation)
 *
 * <p>所属架构层级：COLA 4.0 Application Layer (应用层)</p>
 * <p>核心职责：统筹调用用户服务 {@link UserService} 和配置仓储网关 {@link AppConfigRepositoryPort}，
 * 实现用户权限变更、账号冻结、数字人发音人与问候语配置合成、系统级全局设置读写等业务用例。</p>
 */
@Service
public class AdminApplicationServiceImpl implements AdminApplicationService {

    /** 可用音色列表（与 TtsController 白名单保持一致，支持中英双语） */
    private static final List<Map<String, Object>> DIGITAL_HUMAN_VOICES = List.of(
            voice("106", "度博文", "男", "专业讲解风，最适合景区导游", true),
            voice("0", "度小美", "女", "自然流畅，通用", false),
            voice("1", "度小宇", "男", "沉稳大气，适合讲解", false),
            voice("3", "度逍遥", "男", "适合故事、文化类", false),
            voice("4", "度丫丫", "女", "可爱活泼", false),
            voice("5", "度小云", "女", "柔和知性，通用", false),
            voice("103", "度米朵", "女", "甜美活泼", false),
            voice("110", "度小童", "男", "童声，适合亲子", false),
            voice("111", "度小萌", "女", "软萌童声", false)
    );

    private static Map<String, Object> voice(String id, String name, String gender, String style, boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("gender", gender);
        m.put("style", style);
        m.put("isDefault", isDefault);
        m.put("languages", List.of("zh", "en"));
        return m;
    }

    private final UserService userService;
    private final AppConfigRepositoryPort appConfigRepository;

    public AdminApplicationServiceImpl(UserService userService, AppConfigRepositoryPort appConfigRepository) {
        this.userService = userService;
        this.appConfigRepository = appConfigRepository;
    }

    @Override
    public Object listUsers(int page, int size) {
        return userService.listUsers(page, size);
    }

    @Override
    public void updateUserRole(String userId, String role, String status) {
        userService.updateUser(userId, role, status);
    }

    @Override
    public void disableUser(String userId) {
        userService.updateUser(userId, null, "disabled");
    }

    @Override
    public Map<String, Object> getUserDetail(String userId) {
        return userService.getUserById(userId);
    }

    @Override
    public Map<String, Object> getDigitalHumanConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "渝游智策助手");
        data.put("voiceId", "106");
        data.put("language", "zh");
        data.put("greeting", "你好，欢迎来到重庆，我是渝游智策的重庆智慧文旅 AI 决策助手");
        data.put("enabled", true);
        data.putAll(appConfigRepository.readConfig("digital_human_"));
        if (data.get("enabled") instanceof String s) {
            data.put("enabled", Boolean.parseBoolean(s));
        }
        return data;
    }

    @Override
    public void updateDigitalHumanConfig(Map<String, Object> config) {
        if (config != null) {
            appConfigRepository.writeConfig("digital_human_", config);
        }
    }

    @Override
    public List<Map<String, Object>> listDigitalHumanVoices() {
        return DIGITAL_HUMAN_VOICES;
    }

    @Override
    public Map<String, Object> getSettings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("siteName", "渝游智策 · 重庆智慧文旅");
        data.put("defaultLanguage", "zh");
        data.put("ttsEnabled", true);
        data.put("knowledgeSyncEnabled", true);
        data.put("maxUploadSizeMb", 50);
        data.putAll(appConfigRepository.readConfig("settings_"));
        // ASR is no longer part of the product surface; hide any legacy row
        // that may have been persisted by an earlier admin-console version.
        data.remove("asrEnabled");
        for (String boolKey : List.of("ttsEnabled", "knowledgeSyncEnabled")) {
            if (data.get(boolKey) instanceof String s) {
                data.put(boolKey, Boolean.parseBoolean(s));
            }
        }
        if (data.get("maxUploadSizeMb") instanceof String s) {
            try {
                data.put("maxUploadSizeMb", Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return data;
    }

    @Override
    public void updateSettings(Map<String, Object> settings) {
        if (settings != null) {
            settings.remove("asrEnabled");
            appConfigRepository.writeConfig("settings_", settings);
        }
    }
}

package com.ai.guide.domain.admin.service;
import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
/** One runtime interpretation of operator settings. Read per operation so saved changes take effect immediately. */
@Service
public class RuntimeSettingsPolicy {
    private final AppConfigRepositoryPort repository;
    private final String defaultSiteName;
    private final String defaultLanguage;
    private final boolean defaultTtsEnabled;
    private final boolean defaultKnowledgeSyncEnabled;
    private final int defaultMaxUploadSizeMb;
    @Autowired
    public RuntimeSettingsPolicy(AppConfigRepositoryPort repository,
                                 @Value("${app.settings.default-site-name}") String defaultSiteName,
                                 @Value("${app.settings.default-language}") String defaultLanguage,
                                 @Value("${app.settings.tts-enabled}") boolean defaultTtsEnabled,
                                 @Value("${app.settings.knowledge-sync-enabled}") boolean defaultKnowledgeSyncEnabled,
                                 @Value("${app.settings.default-max-upload-size-mb}") int defaultMaxUploadSizeMb) {
        this.repository=repository;
        this.defaultSiteName=Objects.requireNonNull(defaultSiteName);
        this.defaultLanguage=Objects.requireNonNull(defaultLanguage);
        this.defaultTtsEnabled=defaultTtsEnabled;
        this.defaultKnowledgeSyncEnabled=defaultKnowledgeSyncEnabled;
        if (defaultMaxUploadSizeMb < 1 || defaultMaxUploadSizeMb > 500) throw new IllegalArgumentException("invalid default upload limit");
        this.defaultMaxUploadSizeMb=defaultMaxUploadSizeMb;
    }
    public boolean isTtsEnabled() { return bool(repository.readConfig("settings_").get("ttsEnabled"),defaultTtsEnabled,"ttsEnabled"); }
    public boolean isKnowledgeSyncEnabled() { return bool(repository.readConfig("settings_").get("knowledgeSyncEnabled"),defaultKnowledgeSyncEnabled,"knowledgeSyncEnabled"); }
    public void requireKnowledgeSyncEnabled() { if(!isKnowledgeSyncEnabled())throw new IllegalStateException("知识同步已由管理员暂停"); }
    public void checkUploadSize(long bytes) {
        int mb=uploadLimit(repository.readConfig("settings_").get("maxUploadSizeMb"));
        if(bytes<0 || bytes>mb*1024L*1024L)throw new IllegalArgumentException("上传文件超过配置的"+mb+"MB上限");
    }
    public Map<String,Object> settingsSnapshot(Map<String,String> stored) {
        var result=new LinkedHashMap<String,Object>();
        result.put("siteName",stored.getOrDefault("siteName",defaultSiteName));
        result.put("defaultLanguage",stored.getOrDefault("defaultLanguage",defaultLanguage));
        result.put("ttsEnabled",bool(stored.get("ttsEnabled"),defaultTtsEnabled,"ttsEnabled"));
        result.put("knowledgeSyncEnabled",bool(stored.get("knowledgeSyncEnabled"),defaultKnowledgeSyncEnabled,"knowledgeSyncEnabled"));
        result.put("maxUploadSizeMb",uploadLimit(stored.get("maxUploadSizeMb")));
        return validatePatch(result);
    }
    public static Map<String,Object> validatePatch(Map<String,Object> patch) {
        if(patch==null)throw new IllegalArgumentException("设置不能为空");var result=new LinkedHashMap<String,Object>();
        for(var e:patch.entrySet()) {
            String key=e.getKey();Object value=e.getValue();if(value==null)throw new IllegalArgumentException(key+"不能为空");
            switch(key) {
                case "ttsEnabled","knowledgeSyncEnabled" -> { if(!(value instanceof Boolean))throw new IllegalArgumentException(key+"必须为布尔值");result.put(key,value); }
                case "maxUploadSizeMb" -> {
                    if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 500)
                        throw new IllegalArgumentException("上传上限须为1至500MB的整数");
                    result.put(key, n.intValue());
                }
                case "siteName" -> { if(!(value instanceof String text)||text.isBlank()||text.length()>120)throw new IllegalArgumentException("站点名称必须为1至120字");result.put(key,text.trim()); }
                case "defaultLanguage" -> { if(!Set.of("zh","en").contains(value))throw new IllegalArgumentException("语言仅支持zh/en");result.put(key,value); }
                case "asrEnabled" -> throw new IllegalArgumentException("语音识别开关不在当前运营设置中支持");
                default -> throw new IllegalArgumentException("未知设置项："+key);
            }
        }return result;
    }
    private static boolean bool(String raw,boolean fallback,String key) {
        if(raw==null)return fallback;if("true".equalsIgnoreCase(raw))return true;if("false".equalsIgnoreCase(raw))return false;throw new IllegalStateException("无效运行配置："+key);
    }
    private int uploadLimit(String raw) {
        if(raw==null)return defaultMaxUploadSizeMb;try { int n=Integer.parseInt(raw);if(n>=1&&n<=500)return n; }catch(NumberFormatException ignored){}throw new IllegalArgumentException("上传上限须为1至500MB");
    }
}

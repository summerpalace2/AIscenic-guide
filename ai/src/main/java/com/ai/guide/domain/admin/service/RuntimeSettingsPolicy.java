package com.ai.guide.domain.admin.service;
import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import org.springframework.stereotype.Service;
import java.util.*;
/** One runtime interpretation of operator settings. Read per operation so saved changes take effect immediately. */
@Service
public class RuntimeSettingsPolicy {
    private final AppConfigRepositoryPort repository;
    public RuntimeSettingsPolicy(AppConfigRepositoryPort repository) { this.repository=repository; }
    public boolean isTtsEnabled() { return bool(repository.readConfig("settings_").get("ttsEnabled"),true,"ttsEnabled"); }
    public boolean isKnowledgeSyncEnabled() { return bool(repository.readConfig("settings_").get("knowledgeSyncEnabled"),true,"knowledgeSyncEnabled"); }
    public void requireKnowledgeSyncEnabled() { if(!isKnowledgeSyncEnabled())throw new IllegalStateException("知识同步已由管理员暂停"); }
    public void checkUploadSize(long bytes) {
        int mb=uploadLimit(repository.readConfig("settings_").get("maxUploadSizeMb"));
        if(bytes<0 || bytes>mb*1024L*1024L)throw new IllegalArgumentException("上传文件超过配置的"+mb+"MB上限");
    }
    public static Map<String,Object> settingsSnapshot(Map<String,String> stored) {
        var result=new LinkedHashMap<String,Object>();
        result.put("siteName",stored.getOrDefault("siteName","渝游智策 · 重庆智慧文旅"));
        result.put("defaultLanguage",stored.getOrDefault("defaultLanguage","zh"));
        result.put("ttsEnabled",bool(stored.get("ttsEnabled"),true,"ttsEnabled"));
        result.put("knowledgeSyncEnabled",bool(stored.get("knowledgeSyncEnabled"),true,"knowledgeSyncEnabled"));
        result.put("maxUploadSizeMb",uploadLimit(stored.get("maxUploadSizeMb")));
        return validatePatch(result);
    }
    public static Map<String,Object> validatePatch(Map<String,Object> patch) {
        if(patch==null)throw new IllegalArgumentException("设置不能为空");var result=new LinkedHashMap<String,Object>();
        for(var e:patch.entrySet()) {
            String key=e.getKey();Object value=e.getValue();if(value==null)throw new IllegalArgumentException(key+"不能为空");
            switch(key) {
                case "ttsEnabled","knowledgeSyncEnabled" -> { if(!(value instanceof Boolean))throw new IllegalArgumentException(key+"必须为布尔值");result.put(key,value); }
                case "maxUploadSizeMb" -> { if(!(value instanceof Number n) || n.doubleValue()!=n.intValue())throw new IllegalArgumentException("上传大小必须为整数");result.put(key,uploadLimit(String.valueOf(n.intValue()))); }
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
    private static int uploadLimit(String raw) {
        if(raw==null)return 50;try { int n=Integer.parseInt(raw);if(n>=1&&n<=500)return n; }catch(NumberFormatException ignored){}throw new IllegalArgumentException("上传上限须为1至500MB");
    }
}

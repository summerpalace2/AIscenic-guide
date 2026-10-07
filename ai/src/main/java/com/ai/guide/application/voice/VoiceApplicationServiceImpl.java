package com.ai.guide.application.voice;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 语音交互应用服务实现
 *
 * 所属应用层：application.voice
 * 职责：处理语音请求参数校验、音频格式清洗、音色与语言白名单过滤，协调底层第三方语音网关。
 */
@Service
public class VoiceApplicationServiceImpl implements VoiceApplicationService {

    /** 默认语种 1537 = 中文普通话；前端可通过 dev_pid 指定：1737=英语 / 1637=粤语 / 1837=四川话 */
    private static final int DEV_PID = 1537;

    /** 默认音色：度博文（专业讲解风） */
    private static final String DEFAULT_VOICE_PER = "106";

    /** 可用的音色编号白名单 */
    private static final Set<String> VALID_PER = new HashSet<>(
            Arrays.asList("0", "1", "3", "4", "5", "103", "106", "110", "111"));

    private final VoiceGatewayPort voiceGatewayPort;
    private com.ai.guide.domain.admin.service.RuntimeSettingsPolicy runtimePolicy;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRuntimePolicy(com.ai.guide.domain.admin.service.RuntimeSettingsPolicy policy) { this.runtimePolicy=policy; }

    public VoiceApplicationServiceImpl(VoiceGatewayPort voiceGatewayPort) {
        this.voiceGatewayPort = voiceGatewayPort;
    }

    @Override
    public Result<String> transcribe(Map<String, String> body) {
        if (body == null) {
            return Result.error(400, "音频数据为空");
        }
        String audioDataUrl = body.get("audio");
        if (audioDataUrl == null || audioDataUrl.isBlank()) {
            return Result.error(400, "音频数据为空");
        }

        if(audioDataUrl.length()>14*1024*1024)return Result.error(413,"音频超过10MB上限");
        if(audioDataUrl.startsWith("data:")&&!audioDataUrl.matches("(?s)data:audio/(?:wav|x-wav|wave);base64,.*"))return Result.error(400,"仅支持WAV音频");
        // 提取纯 base64（去掉 "data:audio/wav;base64," 前缀）
        String base64Audio = audioDataUrl;
        int commaIdx = audioDataUrl.indexOf(",");
        if (commaIdx != -1) {
            base64Audio = audioDataUrl.substring(commaIdx + 1);
        }

        byte[] audioBytes;
        try {
            audioBytes = Base64.getDecoder().decode(base64Audio);
        } catch (IllegalArgumentException e) {
            return Result.error(400, "音频格式参数异常，请重新录制");
        }

        if(audioBytes.length==0 || audioBytes.length>10*1024*1024)return Result.error(400,"音频数据为空或超过大小上限");
        String pid=body.containsKey("dev_pid")?body.get("dev_pid"):"1537";
        if(pid==null || !Set.of("1537","1737","1637","1837").contains(pid.trim()))return Result.error(400,"dev_pid参数不支持");
        int devPid=Integer.parseInt(pid.trim());
        return voiceGatewayPort.transcribe(audioBytes, base64Audio, devPid);
    }

    @Override
    public ResponseEntity<byte[]> synthesize(Map<String, String> body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        if(text.length()>2000)return ResponseEntity.status(413).build();
        if(runtimePolicy!=null&&!runtimePolicy.isTtsEnabled())return ResponseEntity.status(503).build();
        // 解析音色参数：前端传入百度 per 编号，白名单校验，显式非法值拒绝
        String per = body.getOrDefault("voice", DEFAULT_VOICE_PER);
        if (!VALID_PER.contains(per)) {
            return ResponseEntity.badRequest().build();
        }

        // 语言：zh=中文 / en=英文（百度 TTS lan 参数），显式非法值拒绝
        String lang = body.getOrDefault("lang", "zh");
        if (!"en".equals(lang) && !"zh".equals(lang)) {
            return ResponseEntity.badRequest().build();
        }

        return voiceGatewayPort.synthesize(text, per, lang);
    }

}

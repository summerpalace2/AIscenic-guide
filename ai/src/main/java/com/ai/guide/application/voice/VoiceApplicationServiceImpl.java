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

    public VoiceApplicationServiceImpl(VoiceGatewayPort voiceGatewayPort) {
        this.voiceGatewayPort = voiceGatewayPort;
    }

    @Override
    public Result<String> transcribe(Map<String, String> body) {
        if (body == null) {
            return Result.error(400, "音频数据为空");
        }
        String audioDataUrl = body.getOrDefault("audio", "");
        if (audioDataUrl.isEmpty()) {
            return Result.error(400, "音频数据为空");
        }

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

        int devPid = parseDevPid(body.getOrDefault("dev_pid", "1537"));
        return voiceGatewayPort.transcribe(audioBytes, base64Audio, devPid);
    }

    @Override
    public ResponseEntity<byte[]> synthesize(Map<String, String> body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        String text = body.getOrDefault("text", "");
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        // 解析音色参数：前端传入百度 per 编号，白名单校验，非法值用默认
        String per = body.getOrDefault("voice", DEFAULT_VOICE_PER);
        if (!VALID_PER.contains(per)) {
            per = DEFAULT_VOICE_PER;
        }

        // 语言：zh=中文 / en=英文（百度 TTS lan 参数），非法值回落中文
        String lang = body.getOrDefault("lang", "zh");
        if (!"en".equals(lang) && !"zh".equals(lang)) {
            lang = "zh";
        }

        return voiceGatewayPort.synthesize(text, per, lang);
    }

    /**
     * 解析并校验 dev_pid（白名单：1537 中文普通话 / 1737 英语 / 1637 粤语 / 1837 四川话）
     */
    private int parseDevPid(String raw) {
        int devPid = DEV_PID;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed == 1537 || parsed == 1737 || parsed == 1637 || parsed == 1837) {
                devPid = parsed;
            }
        } catch (NumberFormatException ignored) {
        }
        return devPid;
    }
}

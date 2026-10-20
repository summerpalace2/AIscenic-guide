package com.ai.guide.application.voice;

import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import com.ai.guide.domain.voice.model.VoiceSynthesisResult;
import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
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
    public VoiceTranscriptionResult transcribe(Map<String, String> body) {
        if (body == null) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频数据为空");
        }
        String audioDataUrl = body.get("audio");
        if (audioDataUrl == null || audioDataUrl.isBlank()) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频数据为空");
        }

        if (audioDataUrl.length() > 14 * 1024 * 1024) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PAYLOAD_TOO_LARGE, "音频超过10MB上限");
        }
        if (audioDataUrl.startsWith("data:") && !audioDataUrl.matches("(?s)data:audio/(?:wav|x-wav|wave);base64,.*")) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "仅支持WAV音频");
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
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频格式参数异常，请重新录制");
        }

        if (audioBytes.length == 0) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频数据为空或超过大小上限");
        }
        if (audioBytes.length > 10 * 1024 * 1024) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PAYLOAD_TOO_LARGE, "音频数据为空或超过大小上限");
        }
        if (!validAsrWav(audioBytes)) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST,
                    "仅支持16kHz、单声道、16位PCM的WAV录音，时长不超过60秒");
        }
        String pid=body.containsKey("dev_pid")?body.get("dev_pid"):"1537";
        if (pid == null || !Set.of("1537", "1737", "1637", "1837").contains(pid.trim())) {
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "dev_pid参数不支持");
        }
        int devPid=Integer.parseInt(pid.trim());
        return voiceGatewayPort.transcribe(audioBytes, base64Audio, devPid);
    }

    /** Inspect RIFF chunks instead of assuming a fixed 44-byte WAV header. */
    private static boolean validAsrWav(byte[] wav) {
        if (wav.length < 44 || !tag(wav, 0, "RIFF") || !tag(wav, 8, "WAVE")
                || unsigned32(wav, 4) != wav.length - 8L) return false;
        boolean foundFormat = false;
        boolean foundData = false;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            long size = unsigned32(wav, offset + 4);
            long end = (long) offset + 8 + size;
            if (end > wav.length || end + (size & 1L) > wav.length) return false;
            if (tag(wav, offset, "fmt ")) {
                if (foundFormat || size < 16) return false;
                int start = offset + 8;
                if (unsigned16(wav, start) != 1 || unsigned16(wav, start + 2) != 1
                        || unsigned32(wav, start + 4) != 16_000
                        || unsigned32(wav, start + 8) != 32_000
                        || unsigned16(wav, start + 12) != 2
                        || unsigned16(wav, start + 14) != 16) return false;
                foundFormat = true;
            } else if (tag(wav, offset, "data")) {
                if (foundData || size == 0 || (size & 1L) != 0 || size > 60L * 16_000 * 2) return false;
                foundData = true;
            }
            offset = (int) (end + (size & 1L));
        }
        return offset == wav.length && foundFormat && foundData;
    }

    private static boolean tag(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + 4 > bytes.length) return false;
        for (int i = 0; i < 4; i++) if (bytes[offset + i] != (byte) value.charAt(i)) return false;
        return true;
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }

    private static long unsigned32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8)
                | ((bytes[offset + 2] & 255) << 16) | ((bytes[offset + 3] & 255) << 24));
    }

    @Override
    public VoiceSynthesisResult synthesize(Map<String, String> body) {
        if (body == null) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.BAD_REQUEST, "请求内容不能为空");
        }
        String text = body.get("text");
        if (text == null || text.isBlank()) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.BAD_REQUEST, "待合成文本不能为空");
        }

        if (text.length() > 2000) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE, "合成文本超过长度限制");
        }
        if (runtimePolicy != null && !runtimePolicy.isTtsEnabled()) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.UNAVAILABLE, "语音合成功能暂不可用");
        }
        // 解析音色参数：前端传入百度 per 编号，白名单校验，显式非法值拒绝
        String per = body.getOrDefault("voice", DEFAULT_VOICE_PER);
        if (!VALID_PER.contains(per)) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.BAD_REQUEST, "音色参数不支持");
        }

        // 语言：zh=中文 / en=英文（百度 TTS lan 参数），显式非法值拒绝
        String lang = body.getOrDefault("lang", "zh");
        if (!"en".equals(lang) && !"zh".equals(lang)) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.BAD_REQUEST, "语言参数不支持");
        }

        return voiceGatewayPort.synthesize(text, per, lang);
    }

}

package com.ai.guide.application.voice;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("VoiceApplicationServiceImpl 应用服务测试")
class VoiceApplicationServiceImplTest {

    private final VoiceGatewayPort voiceGatewayPort = mock(VoiceGatewayPort.class);
    private final VoiceApplicationService service = new VoiceApplicationServiceImpl(voiceGatewayPort);

    @Test
    @DisplayName("transcribe - 音频为空时返回 400")
    void testTranscribeEmptyAudio() {
        Result<String> r1 = service.transcribe(null);
        assertEquals(400, r1.getCode());
        assertEquals("音频数据为空", r1.getMessage());

        Result<String> r2 = service.transcribe(Map.of("audio", ""));
        assertEquals(400, r2.getCode());
        assertEquals("音频数据为空", r2.getMessage());
    }

    @Test
    @DisplayName("transcribe - Base64 格式异常时返回 400")
    void testTranscribeInvalidBase64() {
        Result<String> r = service.transcribe(Map.of("audio", "invalid-base-64!!!"));
        assertEquals(400, r.getCode());
        assertEquals("音频格式参数异常，请重新录制", r.getMessage());
    }

    @Test
    @DisplayName("transcribe - 正常数据剥离 DataUrl 前缀并校验语种 dev_pid 成功委派网关")
    void testTranscribeSuccess() {
        // "UklGRg==" 是合法的 base64 "RIFF"
        String dataUrl = "data:audio/wav;base64,UklGRg==";
        when(voiceGatewayPort.transcribe(any(byte[].class), eq("UklGRg=="), eq(1837)))
                .thenReturn(Result.success("识别成功", "要得"));

        Result<String> result = service.transcribe(Map.of("audio", dataUrl, "dev_pid", "1837"));
        assertEquals(200, result.getCode());
        assertEquals("要得", result.getData());
        verify(voiceGatewayPort).transcribe(any(byte[].class), eq("UklGRg=="), eq(1837));
    }

    @Test
    @DisplayName("synthesize - 文本为空时返回 400 Bad Request")
    void testSynthesizeEmptyText() {
        ResponseEntity<byte[]> r1 = service.synthesize(null);
        assertEquals(HttpStatus.BAD_REQUEST, r1.getStatusCode());

        ResponseEntity<byte[]> r2 = service.synthesize(Map.of("text", ""));
        assertEquals(HttpStatus.BAD_REQUEST, r2.getStatusCode());
    }

    @Test
    void explicitInvalidVoiceAndLanguageRejectWithoutCallingSupplier() {
        assertEquals(400, service.synthesize(Map.of("text","重庆欢迎你","voice","999","lang","fr")).getStatusCode().value());
        var nullable=new java.util.HashMap<String,String>(); nullable.put("audio",null);
        assertEquals(400, service.transcribe(nullable).getCode());
        nullable.clear();nullable.put("text",null);
        assertEquals(400, service.synthesize(nullable).getStatusCode().value());
        assertEquals(400,service.transcribe(Map.of("audio","AQI=","dev_pid","invalid")).getCode());
        assertEquals(400,service.transcribe(Map.of("audio","data:audio/mp3;base64,AQI=")).getCode());
        assertEquals(413,service.synthesize(Map.of("text","a".repeat(2001))).getStatusCode().value());
        verifyNoInteractions(voiceGatewayPort);
    }
    @Test
    void disabledTtsReadsLiveRuntimeSetting() {
        var repository=mock(com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort.class);
        when(repository.readConfig("settings_")).thenReturn(Map.of("ttsEnabled","false"));
        ((VoiceApplicationServiceImpl)service).setRuntimePolicy(new com.ai.guide.domain.admin.service.RuntimeSettingsPolicy(repository));
        assertEquals(503,service.synthesize(Map.of("text","hello")).getStatusCode().value());
        verifyNoInteractions(voiceGatewayPort);
        when(repository.readConfig("settings_")).thenReturn(Map.of("ttsEnabled","true"));
        when(voiceGatewayPort.synthesize("hello","106","zh")).thenReturn(ResponseEntity.ok(new byte[]{1}));
        assertEquals(200,service.synthesize(Map.of("text","hello")).getStatusCode().value());
    }
}

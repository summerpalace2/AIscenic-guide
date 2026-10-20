package com.ai.guide.application.voice;

import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import com.ai.guide.domain.voice.model.VoiceSynthesisResult;
import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

@DisplayName("VoiceApplicationServiceImpl 应用服务测试")
class VoiceApplicationServiceImplTest {

    private final VoiceGatewayPort voiceGatewayPort = mock(VoiceGatewayPort.class);
    private final VoiceApplicationService service = new VoiceApplicationServiceImpl(voiceGatewayPort);

    @Test
    @DisplayName("transcribe - 音频为空时返回 400")
    void testTranscribeEmptyAudio() {
        VoiceTranscriptionResult r1 = service.transcribe(null);
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, r1.status());
        assertEquals("音频数据为空", r1.message());

        VoiceTranscriptionResult r2 = service.transcribe(Map.of("audio", ""));
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, r2.status());
        assertEquals("音频数据为空", r2.message());
    }

    @Test
    @DisplayName("transcribe - Base64 格式异常时返回 400")
    void testTranscribeInvalidBase64() {
        VoiceTranscriptionResult r = service.transcribe(Map.of("audio", "invalid-base-64!!!"));
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, r.status());
        assertEquals("音频格式参数异常，请重新录制", r.message());
    }

    @Test
    @DisplayName("transcribe - 正常数据剥离 DataUrl 前缀并校验语种 dev_pid 成功委派网关")
    void testTranscribeSuccess() {
        byte[] wav = wav(32_000);
        String base64 = Base64.getEncoder().encodeToString(wav);
        String dataUrl = "data:audio/wav;base64," + base64;
        when(voiceGatewayPort.transcribe(aryEq(wav), eq(base64), eq(1837)))
                .thenReturn(VoiceTranscriptionResult.success("识别成功", "要得"));

        VoiceTranscriptionResult result = service.transcribe(Map.of("audio", dataUrl, "dev_pid", "1837"));
        assertTrue(result.successful());
        assertEquals("要得", result.transcript());
        verify(voiceGatewayPort).transcribe(aryEq(wav), eq(base64), eq(1837));
    }

    @Test
    void wavRejectsWrongRateChannelsCompressionTruncationAndExcessDuration() {
        byte[] wrongRate = wav(32_000);
        ByteBuffer.wrap(wrongRate).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48_000);
        byte[] stereo = wav(32_000);
        stereo[22] = 2;
        byte[] compressed = wav(32_000);
        compressed[20] = 3;
        byte[] truncated = java.util.Arrays.copyOf(wav(32_000), 100);
        byte[] oddData = wav(32_000);
        ByteBuffer.wrap(oddData).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 31_999);
        for (byte[] bad : new byte[][]{new byte[]{'R','I','F','F'}, wrongRate, stereo, compressed,
                truncated, oddData, wav(0), wav(60 * 32_000 + 2)}) {
            assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST,
                    service.transcribe(Map.of("audio", Base64.getEncoder().encodeToString(bad))).status());
        }
        verifyNoInteractions(voiceGatewayPort);
    }

    @Test
    void wavAllowsAncillaryChunksAndSixtySecondBoundary() {
        byte[] standard = wav(60 * 32_000);
        byte[] extended = new byte[standard.length + 12];
        System.arraycopy(standard, 0, extended, 0, 36);
        var buffer = ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4, extended.length - 8);
        buffer.position(36);
        buffer.put(new byte[]{'J','U','N','K'}).putInt(3).put(new byte[]{1,2,3,0});
        System.arraycopy(standard, 36, extended, 48, standard.length - 36);
        when(voiceGatewayPort.transcribe(any(byte[].class), anyString(), eq(1537)))
                .thenReturn(VoiceTranscriptionResult.success("识别成功", "边界"));
        assertTrue(service.transcribe(Map.of("audio", Base64.getEncoder().encodeToString(extended))).successful());
        verify(voiceGatewayPort).transcribe(aryEq(extended), anyString(), eq(1537));
    }

    private static byte[] wav(int dataBytes) {
        var buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R','I','F','F'}).putInt(36 + dataBytes).put(new byte[]{'W','A','V','E'});
        buffer.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[]{'d','a','t','a'}).putInt(dataBytes);
        return buffer.array();
    }

    @Test
    @DisplayName("synthesize - 文本为空时返回 400 Bad Request")
    void testSynthesizeEmptyText() {
        VoiceSynthesisResult r1 = service.synthesize(null);
        assertEquals(VoiceSynthesisResult.Status.BAD_REQUEST, r1.status());

        VoiceSynthesisResult r2 = service.synthesize(Map.of("text", ""));
        assertEquals(VoiceSynthesisResult.Status.BAD_REQUEST, r2.status());
    }

    @Test
    void explicitInvalidVoiceAndLanguageRejectWithoutCallingSupplier() {
        assertEquals(VoiceSynthesisResult.Status.BAD_REQUEST, service.synthesize(Map.of("text","重庆欢迎你","voice","999","lang","fr")).status());
        var nullable=new java.util.HashMap<String,String>(); nullable.put("audio",null);
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, service.transcribe(nullable).status());
        nullable.clear();nullable.put("text",null);
        assertEquals(VoiceSynthesisResult.Status.BAD_REQUEST, service.synthesize(nullable).status());
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST,service.transcribe(Map.of("audio",Base64.getEncoder().encodeToString(wav(32)),"dev_pid","invalid")).status());
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST,service.transcribe(Map.of("audio","data:audio/mp3;base64,AQI=")).status());
        assertEquals(VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE,service.synthesize(Map.of("text","a".repeat(2001))).status());
        verifyNoInteractions(voiceGatewayPort);
    }
    @Test
    void disabledTtsReadsLiveRuntimeSetting() {
        var repository=mock(com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort.class);
        when(repository.readConfig("settings_")).thenReturn(Map.of("ttsEnabled","false"));
        ((VoiceApplicationServiceImpl)service).setRuntimePolicy(new com.ai.guide.domain.admin.service.RuntimeSettingsPolicy(repository,"test","zh",true,true,50));
        assertEquals(VoiceSynthesisResult.Status.UNAVAILABLE,service.synthesize(Map.of("text","hello")).status());
        verifyNoInteractions(voiceGatewayPort);
        when(repository.readConfig("settings_")).thenReturn(Map.of("ttsEnabled","true"));
        when(voiceGatewayPort.synthesize("hello","106","zh")).thenReturn(VoiceSynthesisResult.success(new byte[]{1}));
        assertTrue(service.synthesize(Map.of("text","hello")).successful());
    }
}

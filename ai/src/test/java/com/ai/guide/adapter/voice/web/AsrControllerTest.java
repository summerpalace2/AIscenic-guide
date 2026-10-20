package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AsrController 适配层测试")
class AsrControllerTest {

    private final VoiceApplicationService applicationService = mock(VoiceApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AsrController(applicationService)).build();

    @Test
    @DisplayName("POST /ai/asr - 成功识别语音")
    void testTranscribeSuccess() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(VoiceTranscriptionResult.success("识别成功", "我想去洪崖洞"));

        mockMvc.perform(post("/ai/asr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"data:audio/wav;base64,UklGRg==\",\"dev_pid\":\"1537\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("识别成功"))
                .andExpect(jsonPath("$.data").value("我想去洪崖洞"));
    }

    @Test
    @DisplayName("POST /ai/asr - 音频为空时返回 400 校验错误")
    void testTranscribeEmptyAudio() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频数据为空"));

        mockMvc.perform(post("/ai/asr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("音频数据为空"));
    }

    @Test
    @DisplayName("POST /ai/asr - 将领域错误分类映射为 HTTP 状态且保留 REST 响应信封")
    void testTranscribeFailureStatusMapping() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.RATE_LIMITED, "请求频率过快"));

        mockMvc.perform(post("/ai/asr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"AQI=\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("POST /ai/asr - 映射供应商鉴权和容量错误")
    void testTranscribeForbiddenAndPayloadTooLargeMappings() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.FORBIDDEN, "供应商鉴权失败"))
                .thenReturn(VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PAYLOAD_TOO_LARGE, "音频过大"));

        mockMvc.perform(post("/ai/asr").contentType(MediaType.APPLICATION_JSON).content("{\"audio\":\"AQI=\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(post("/ai/asr").contentType(MediaType.APPLICATION_JSON).content("{\"audio\":\"AQI=\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413));
    }
}

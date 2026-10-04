package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TtsController 适配层测试")
class TtsControllerTest {

    private final VoiceApplicationService applicationService = mock(VoiceApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TtsController(applicationService)).build();

    @Test
    @DisplayName("POST /ai/tts - 成功合成语音二进制流")
    void testSynthesizeSuccess() throws Exception {
        byte[] mp3Bytes = new byte[]{1, 2, 3, 4};
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("Content-Disposition", "attachment; filename=tts.mp3");

        when(applicationService.synthesize(anyMap()))
                .thenReturn(new ResponseEntity<>(mp3Bytes, headers, org.springframework.http.HttpStatus.OK));

        mockMvc.perform(post("/ai/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"欢迎来到重庆\",\"voice\":\"106\",\"lang\":\"zh\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=tts.mp3"))
                .andExpect(content().bytes(mp3Bytes));
    }

    @Test
    @DisplayName("POST /ai/tts - 文本为空时返回 400 Bad Request")
    void testSynthesizeEmptyText() throws Exception {
        when(applicationService.synthesize(anyMap()))
                .thenReturn(ResponseEntity.badRequest().build());

        mockMvc.perform(post("/ai/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}

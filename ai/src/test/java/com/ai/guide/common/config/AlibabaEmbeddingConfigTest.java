package com.ai.guide.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class AlibabaEmbeddingConfigTest {
    private static final String EMBEDDING_URL = "https://embedding.test/v1";

    @Test
    void missingApiKeyFailsBeforeAnyHttpRequest() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AlibabaEmbeddingConfig config = new AlibabaEmbeddingConfig("  ", EMBEDDING_URL, restTemplate, new ObjectMapper());

        assertThrows(IllegalStateException.class, () -> config.embeddingModel().embed("query"));

        server.verify();
    }

    @Test
    void configuredApiKeyMakesAuthenticatedEmbeddingRequest() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        float[] expected = new float[1536];
        expected[0] = 1f;
        String response = new ObjectMapper().writeValueAsString(Map.of(
                "output", Map.of("embeddings", List.of(Map.of("embedding", expected)))));
        server.expect(requestTo(EMBEDDING_URL))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        AlibabaEmbeddingConfig config = new AlibabaEmbeddingConfig("test-key", EMBEDDING_URL, restTemplate, new ObjectMapper());

        assertArrayEquals(expected, config.embeddingModel().embed("query"));

        server.verify();
    }
}

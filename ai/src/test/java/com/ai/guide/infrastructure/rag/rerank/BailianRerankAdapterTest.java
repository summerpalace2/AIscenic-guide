package com.ai.guide.infrastructure.rag.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BailianRerankAdapterTest {
    @Test
    void translatesProviderResponseIntoInputPositions() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.postForObject(eq("https://provider.invalid/rerank"), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"output\":{\"results\":[{\"index\":1},{\"index\":0}]}}");
        var adapter = new BailianRerankAdapter(http, new ObjectMapper(), "secret", "https://provider.invalid/rerank");

        assertEquals(List.of(1), adapter.rerank("query", List.of("first", "second"), 1));
        verify(http).postForObject(eq("https://provider.invalid/rerank"), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void rejectsMissingCredentialsAndInvalidIndexes() {
        RestTemplate http = mock(RestTemplate.class);
        var unconfigured = new BailianRerankAdapter(http, new ObjectMapper(), " ", "https://provider.invalid/rerank");
        assertThrows(IllegalStateException.class, () -> unconfigured.rerank("q", List.of("doc"), 1));

        when(http.postForObject(eq("https://provider.invalid/rerank"), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"output\":{\"results\":[{\"index\":3}]}}");
        var configured = new BailianRerankAdapter(http, new ObjectMapper(), "secret", "https://provider.invalid/rerank");
        assertThrows(IllegalStateException.class, () -> configured.rerank("q", List.of("doc"), 1));
    }
}

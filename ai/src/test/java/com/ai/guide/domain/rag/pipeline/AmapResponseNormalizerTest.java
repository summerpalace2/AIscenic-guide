package com.ai.guide.domain.rag.pipeline;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmapResponseNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesFirstUsablePoiPhotoAndTitle() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[{"id":"poi-1","name":"洪崖洞","location":"106.58,29.56","photos":[{"url":"","title":"空图"},{"src":"https://example.invalid/hongya.jpg","title":"夜景"}]}]}
                """);

        List<AmapResponseNormalizer.PoiCandidate> candidates = AmapResponseNormalizer.normalizePois(response);

        assertEquals(1, candidates.size());
        assertEquals("https://example.invalid/hongya.jpg", candidates.get(0).photoUrl());
        assertEquals("夜景", candidates.get(0).photoTitle());
    }

    @Test
    void preservesProviderInfoAndInfocodeOnSemanticFailure() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"0","info":"CUQPS_HAS_EXCEEDED_THE_LIMIT","infocode":"10021"}
                """);

        var failure = assertThrows(AmapResponseNormalizer.AmapResponseException.class,
                () -> AmapResponseNormalizer.normalizePois(response));

        assertEquals("CUQPS_HAS_EXCEEDED_THE_LIMIT", failure.info());
        assertEquals("10021", failure.infocode());
    }

    @Test
    void leavesPhotoUnavailableWhenProviderReturnsNoUsablePhoto() throws Exception {
        var response = objectMapper.readTree("""
                {"status":"1","pois":[{"id":"poi-1","name":"洪崖洞","location":"106.58,29.56","photos":[{"url":""}]}]}
                """);

        var candidate = AmapResponseNormalizer.normalizePois(response).get(0);

        assertNull(candidate.photoUrl());
        assertNull(candidate.photoTitle());
    }
}

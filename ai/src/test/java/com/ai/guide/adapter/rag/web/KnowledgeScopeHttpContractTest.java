package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.*;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.model.KnowledgeDocumentUpload;
import com.ai.guide.domain.rag.service.*;
import com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP binding tests complement the BFF's actual outbound multipart/JSON tests. */
class KnowledgeScopeHttpContractTest {
    @BeforeEach void admin() { UserContext.set("contract-admin", "contract", "ADMIN"); }
    @AfterEach void clearContext() { UserContext.clear(); }

    @Test void multipartAndFormBindExplicitScopeAndPreserveOmittedUpdateFields() throws Exception {
        var application = mock(KnowledgeApplicationService.class);
        when(application.createDocument(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Result.success("created", Map.of("id", "doc")));
        when(application.updateDocument(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Result.success("updated", Map.of("id", "doc")));
        var http = MockMvcBuilders.standaloneSetup(new KnowledgeController(application)).build();
        byte[] original = "全文\nEnglish words".getBytes(StandardCharsets.UTF_8);
        http.perform(multipart("/ai/knowledge")
                .file(new MockMultipartFile("file", "document.txt", "text/plain", original))
                .param("title", "标题").param("category", "history")
                .param("scope", "CITY").param("city", "新增目的地"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        var upload = ArgumentCaptor.forClass(KnowledgeDocumentUpload.class);
        verify(application).createDocument(eq("标题"), eq("history"), isNull(), isNull(), upload.capture(), eq("CITY"), eq("新增目的地"));
        assertArrayEquals(original, upload.getValue().readBytes());
        http.perform(put("/ai/knowledge/doc").param("scope", "GLOBAL").param("city", ""))
                .andExpect(status().isOk());
        verify(application).updateDocument(eq("doc"), isNull(), isNull(), isNull(), isNull(), eq("GLOBAL"), eq(""));
        http.perform(put("/ai/knowledge/doc").param("title", "只改标题"))
                .andExpect(status().isOk());
        verify(application).updateDocument(eq("doc"), eq("只改标题"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test void jsonUpdateBindsScopeThroughApplicationToDomainIncludingExplicitEmptyContent() throws Exception {
        var documents = mock(KnowledgeDocumentService.class);
        when(documents.updateDocument(any(), any(), any(), any(), any(), any(), any())).thenReturn(new KnowledgeDocument());
        when(documents.toResponse(any(), eq(true))).thenReturn(Map.of("id", "doc"));
        var application = new RagApplicationServiceImpl(mock(ScenicKnowledgePort.class), mock(RerankService.class),
                mock(RagRetrievalService.class), documents, RagTaxonomyPort.empty());
        var http = MockMvcBuilders.standaloneSetup(new RagController(application)).build();
        http.perform(put("/ai/rag/knowledge/doc").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"CITY\",\"city\":\"杭州\",\"content\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(documents).updateDocument(eq("doc"), isNull(), isNull(), eq(""), isNull(), eq("CITY"), eq("杭州"));
        http.perform(put("/ai/rag/knowledge/doc").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"只改标题\"}"))
                .andExpect(status().isOk());
        verify(documents).updateDocument(eq("doc"), eq("只改标题"), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test void retrievalWithoutCityDoesNotInjectConfiguredDefaultCity() throws Exception {
        var taxonomy = mock(RagTaxonomyPort.class);
        when(taxonomy.getDefaultCity()).thenReturn("旧默认目的地");
        var retrieval = mock(RagRetrievalService.class);
        when(retrieval.retrieve(any(), any(), anyMap(), anyBoolean())).thenReturn(Map.of("ok", true));
        var application = new RagApplicationServiceImpl(mock(ScenicKnowledgePort.class), mock(RerankService.class),
                retrieval, mock(KnowledgeDocumentService.class), taxonomy);
        var http = MockMvcBuilders.standaloneSetup(new RagController(application)).build();
        http.perform(post("/ai/rag/retrieve").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"通用出游建议\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(retrieval).retrieve(eq("通用出游建议"), eq(""), anyMap(), eq(false));
        verify(taxonomy, never()).getDefaultCity();
    }
}

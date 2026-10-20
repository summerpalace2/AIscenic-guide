package com.ai.guide.infrastructure.rag.qdrant;

import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.infrastructure.rag.index.QdrantScenicKnowledgeAdapter;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QdrantPublicationFailureTest {
    @Test void embeddingFailureIsNotSuccessfulEmptySyncAndDoesNotEraseExistingIndex() {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("fixture embedding unavailable"));
        when(client.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, mock(RerankService.class));

        assertThrows(RuntimeException.class, () -> adapter.reindexText("owned-source", "更新后的有效知识正文", KnowledgeScope.global()));
        verify(client, never()).deleteAsync(anyString(), any(Points.Filter.class));
        verify(client, never()).upsertAsync(anyString(), anyList());
    }

    @Test void failureInAnyChunkRejectsWholePreparedReplacement() {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector).thenThrow(new IllegalStateException("fixture second chunk failed"));
        when(client.deleteAsync(anyString(), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, mock(RerankService.class));

        assertThrows(RuntimeException.class, () -> adapter.reindexText("owned-source",
                "This is a valid paragraph about reliable knowledge publication. ".repeat(1500), KnowledgeScope.global()));
        verify(embedding, atLeast(2)).embed(anyString());
        verify(client, never()).deleteAsync(anyString(), any(Points.Filter.class));
        verify(client, never()).upsertAsync(anyString(), anyList());
    }

    @Test void fileReplacementCannotIgnoreFailedOldPointCleanupAndReportSuccess() {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        when(client.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                Points.ScrollResponse.newBuilder().addResult(Points.RetrievedPoint.newBuilder().setId(PointIdFactory.id(7L))).build()));
        when(client.deleteAsync(anyString(), anyList(), any(java.time.Duration.class)))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("fixture delete refused")));
        when(client.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, mock(RerankService.class));
        var file = new MockMultipartFile("file", "owned.txt", "text/plain", "这是需要完整替换的知识资料正文。".getBytes(StandardCharsets.UTF_8));

        assertThrows(Exception.class, () -> adapter.importUniversalDocument(file, KnowledgeScope.global()));
        verify(client).upsertAsync(anyString(), anyList());
        verify(client).deleteAsync(anyString(), eq(java.util.List.of(PointIdFactory.id(7L))), any(java.time.Duration.class));
    }

    @Test void upsertFailureDoesNotDeleteExistingPoints() {
        var client = mock(QdrantClient.class);
        var embedding = mock(EmbeddingModel.class);
        float[] vector = new float[1536]; vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        when(client.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                Points.ScrollResponse.newBuilder().addResult(Points.RetrievedPoint.newBuilder().setId(PointIdFactory.id(7L))).build()));
        when(client.upsertAsync(anyString(), anyList()))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("fixture upsert refused")));
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, mock(RerankService.class));
        assertThrows(RuntimeException.class, () -> adapter.reindexText("owned", "更新后的知识资料正文", KnowledgeScope.global()));
        verify(client, never()).deleteAsync(anyString(), anyList(), any(java.time.Duration.class));
        verify(client, never()).deleteAsync(anyString(), any(Points.Filter.class));
    }
}

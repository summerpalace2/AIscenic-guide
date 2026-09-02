package com.ai.guide.common.config;


import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantConfigTest {

    @Test
    void missingRagEligibleIndexCreatesConfiguredBoolIndex() throws Exception {
        QdrantConfig config = configFor("configured-production-v2");
        QdrantClient client = mock(QdrantClient.class);
        Collections.CollectionInfo info = Collections.CollectionInfo.newBuilder().build();
        when(client.getCollectionInfoAsync("configured-production-v2"))
                .thenReturn(Futures.immediateFuture(info));
        when(client.createPayloadIndexAsync(
                eq("configured-production-v2"), eq("rag_eligible"),
                eq(Collections.PayloadSchemaType.Bool), isNull(), eq(true), isNull(), isNull()))
                .thenReturn(Futures.immediateFuture(Points.UpdateResult.getDefaultInstance()));
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.getDefaultInstance()));

        assertEquals(QdrantConfig.PayloadIndexAction.CREATED, config.ensureRagEligibleIndex(client));
        verify(client).createPayloadIndexAsync(
                eq("configured-production-v2"), eq("rag_eligible"),
                eq(Collections.PayloadSchemaType.Bool), isNull(), eq(true), isNull(), isNull());
    }

    @Test
    void existingBoolIndexIsInspectedAndSkipped() throws Exception {
        QdrantConfig config = configFor("configured-production-v2");
        QdrantClient client = mock(QdrantClient.class);
        Collections.PayloadSchemaInfo schema = Collections.PayloadSchemaInfo.newBuilder()
                .setDataType(Collections.PayloadSchemaType.Bool)
                .build();
        Collections.CollectionInfo info = Collections.CollectionInfo.newBuilder()
                .putPayloadSchema("rag_eligible", schema)
                .build();
        when(client.getCollectionInfoAsync("configured-production-v2"))
                .thenReturn(Futures.immediateFuture(info));
        when(client.scrollAsync(any(Points.ScrollPoints.class)))
                .thenReturn(Futures.immediateFuture(Points.ScrollResponse.getDefaultInstance()));

        assertEquals(QdrantConfig.PayloadIndexAction.ALREADY_PRESENT, config.ensureRagEligibleIndex(client));
        verify(client, never()).createPayloadIndexAsync(
                any(String.class), any(String.class), any(Collections.PayloadSchemaType.class),
                any(), any(), any(), any());
    }

    @Test
    void unavailableQdrantDoesNotEscapeAsUnhandledException() throws Exception {
        QdrantConfig config = configFor("configured-production-v2");
        QdrantClient client = mock(QdrantClient.class);
        when(client.getCollectionInfoAsync("configured-production-v2"))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("qdrant unavailable")));

        assertEquals(QdrantConfig.PayloadIndexAction.UNAVAILABLE, config.ensureRagEligibleIndex(client));
    }

    private QdrantConfig configFor(String collectionName) throws Exception {
        QdrantConfig config = new QdrantConfig();
        Field field = QdrantConfig.class.getDeclaredField("collectionName");
        field.setAccessible(true);
        field.set(config, collectionName);
        return config;
    }
}

package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.infrastructure.rag.index.QdrantScenicKnowledgeAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Points;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Disposable local Qdrant only; no application .env, cloud embedding or default collection. */
final class Cp6KnowledgeQdrantFixture implements AutoCloseable {
    final QdrantClient client;
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    final EmbeddingModel embedding;
    final String collection;

    Cp6KnowledgeQdrantFixture(String collection, Runnable embeddingGate) {
        var env = System.getenv();
        if (!"true".equals(env.get("CP6_IT_QDRANT_ENABLE"))
                || !Cp6InfrastructureTarget.PERMISSION.equals(env.get("CP6_IT_QDRANT_PERMISSION")))
            throw new IllegalArgumentException("Disposable Qdrant requires explicit permission and opt-in");
        String host = env.getOrDefault("CP6_IT_QDRANT_HOST", "");
        int port = Integer.parseInt(env.getOrDefault("CP6_IT_QDRANT_GRPC_PORT", "0"));
        if (!Set.of("127.0.0.1", "localhost", "::1").contains(host) || port < 1 || port > 65535
                || !collection.matches("cp6_[a-f0-9]{32}"))
            throw new IllegalArgumentException("Qdrant probe requires loopback port and random disposable collection");
        this.collection = collection;
        client = new QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).withApiKey(apiKey(env)).build());
        embedding = mock(EmbeddingModel.class);
        when(embedding.embed(anyString())).thenAnswer(call -> {
            if (embeddingGate != null) embeddingGate.run();
            float[] vector = new float[1536]; vector[0] = 1; return vector;
        });
    }

    static String apiKey(Map<String,String> environment) {
        String value = environment.get("CP6_IT_QDRANT_API_KEY");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing explicit CP6_IT_QDRANT_API_KEY");
        return value;
    }

    RerankService cache() {
        return new RerankService(executor, (query, documents, topN) ->
                java.util.stream.IntStream.range(0, Math.min(topN, documents.size())).boxed().toList(), new ObjectMapper());
    }
    QdrantScenicKnowledgeAdapter adapter(RerankService cache,
            com.ai.guide.domain.rag.gateway.KnowledgeIndexVisibilityPort visibility) {
        var adapter = new QdrantScenicKnowledgeAdapter(client, embedding, cache, visibility);
        ReflectionTestUtils.setField(adapter, "configuredCollectionName", collection);
        return adapter;
    }
    List<Points.RetrievedPoint> points() throws Exception {
        var result = new ArrayList<Points.RetrievedPoint>();
        var request = Points.ScrollPoints.newBuilder().setCollectionName(collection).setLimit(1000)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true));
        while (true) {
            var response = client.scrollAsync(request.build()).get(10, TimeUnit.SECONDS);
            result.addAll(response.getResultList());
            var next = response.getNextPageOffset();
            if (!next.hasNum() && !next.hasUuid()) return result;
            request.setOffset(next);
        }
    }
    @Override public void close() throws Exception { try { client.close(); } finally { executor.shutdownNow(); } }
}

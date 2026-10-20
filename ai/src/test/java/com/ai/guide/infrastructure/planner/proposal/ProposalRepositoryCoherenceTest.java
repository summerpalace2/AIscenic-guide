package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalRepositoryCoherenceTest {
    @Test void remoteRemovalRejectsAnAlreadyQueuedPublication() {
        var fixture = new SharedRedis();
        var queued = new ArrayDeque<Runnable>();
        var publisher = new CaffeineRedisProposalRepository(fixture.redis, new ObjectMapper(), queued::addLast);
        var remover = fixture.repository();
        publisher.put(proposal("queued-cancel", "epoch-original"));

        remover.remove("queued-cancel");
        var beforePublication = publisher.get("queued-cancel");
        queued.removeFirst().run();

        assertAll(
                () -> assertNull(beforePublication, "a remote cancellation must hide even an unpublished local copy"),
                () -> assertNull(publisher.get("queued-cancel")),
                () -> assertNull(fixture.repository().get("queued-cancel"), "queued publication must not resurrect a remotely cancelled proposal"));
        assertEquals(Duration.ofMillis(PlanAdjustmentProposal.DEFAULT_TTL_MS), fixture.durations.get("planner:proposal:queued-cancel"));
    }

    @Test void delayedPublicationRecalculatesTtlAndDoesNotPublishExpiredWork() {
        var fixture = new SharedRedis();
        var queued = new ArrayDeque<Runnable>();
        var publisher = new CaffeineRedisProposalRepository(fixture.redis, new ObjectMapper(), queued::addLast);
        var delayed = proposal("queued-shortened", "epoch-original");
        publisher.put(delayed);
        delayed.setExpiresAt(System.currentTimeMillis() + 5_000);
        queued.removeFirst().run();
        long actualTtl = fixture.durations.get("planner:proposal:queued-shortened").toMillis();
        assertTrue(actualTtl > 0 && actualTtl <= 5_000, "queued time must not extend the absolute expiry");

        var expired = proposal("queued-expired", "epoch-original");
        publisher.put(expired);
        expired.setExpiresAt(System.currentTimeMillis() - 1);
        queued.removeFirst().run();
        assertFalse(fixture.snapshots.containsKey("planner:proposal:queued-expired"));
        assertNull(publisher.get("queued-expired"));
    }

    @Test void invalidAndExpiredOldPutsCannotRemoveANewerRemoteSnapshot() {
        var fixture = new SharedRedis();
        var first = fixture.repository();
        var second = fixture.repository();
        for (String kind : new String[]{"expired", "future", "too-long"}) {
            String id = "invalid-" + kind;
            first.put(proposal(id, "epoch-original"));
            second.put(proposal(id, "epoch-newer"));
            assertEquals("epoch-newer", first.get(id).getConversationEpoch());
            var invalid = proposal(id, "epoch-invalid");
            if (kind.equals("expired")) invalid.setExpiresAt(System.currentTimeMillis() - 1);
            if (kind.equals("future")) { invalid.setCreatedAt(System.currentTimeMillis() + 60_000); invalid.setExpiresAt(invalid.getCreatedAt() + 120_000); }
            if (kind.equals("too-long")) invalid.setExpiresAt(invalid.getCreatedAt() + PlanAdjustmentProposal.DEFAULT_TTL_MS + 1);
            first.put(invalid);
            assertEquals("epoch-newer", second.get(id).getConversationEpoch(), kind);
            assertEquals("epoch-newer", first.get(id).getConversationEpoch(), kind);
        }
    }

    @Test void expiredReadCleanupCannotDeleteAConcurrentReplacement() throws Exception {
        var fixture = new SharedRedis();
        String id = "expired-read-replaced";
        var expired = proposal(id, "epoch-expired");
        expired.setExpiresAt(System.currentTimeMillis() - 1);
        String stale = new ObjectMapper().writeValueAsString(expired);
        String fresh = new ObjectMapper().writeValueAsString(proposal(id, "epoch-newer"));
        fixture.snapshots.put("planner:proposal:" + id, stale);
        doAnswer(call -> {
            String key = call.getArgument(0);
            String previous = fixture.snapshots.get(key);
            if (stale.equals(previous)) fixture.snapshots.put(key, fresh);
            return previous;
        }).when(fixture.values).get("planner:proposal:" + id);
        var reader = fixture.repository();
        assertNull(reader.get(id));
        assertEquals("epoch-newer", reader.get(id).getConversationEpoch());
    }

    @Test void remoteDeletionInvalidatesBothPublisherAndHydratedReader() {
        var fixture = new SharedRedis();
        var publisher = fixture.repository();
        var reader = fixture.repository();
        var remover = fixture.repository();
        publisher.put(proposal("shared-delete", "epoch-original"));
        assertNotNull(reader.get("shared-delete"));

        remover.remove("shared-delete");

        assertNull(reader.get("shared-delete"), "hydrated L1 must observe a remote deletion");
        assertNull(publisher.get("shared-delete"), "publisher L1 must also observe a remote deletion");
    }

    @Test void remoteReplacementRefreshesWarmL1() {
        var fixture = new SharedRedis();
        var first = fixture.repository();
        var second = fixture.repository();
        first.put(proposal("shared-update", "epoch-original"));
        assertEquals("epoch-original", second.get("shared-update").getConversationEpoch());

        first.put(proposal("shared-update", "epoch-updated"));

        assertEquals("epoch-updated", second.get("shared-update").getConversationEpoch());
    }

    @Test void sharedSnapshotCannotBeServedWhenRedisCannotVerifyIt() {
        var fixture = new SharedRedis();
        var publisher = fixture.repository();
        var reader = fixture.repository();
        publisher.put(proposal("shared-disconnect", "epoch-original"));
        assertNotNull(reader.get("shared-disconnect"));
        when(fixture.values.get(anyString())).thenThrow(new IllegalStateException("disconnected"));

        assertNull(reader.get("shared-disconnect"), "remote cancellation cannot be excluded during an outage");
        assertNull(publisher.get("shared-disconnect"));

        doAnswer(call -> fixture.snapshots.get(call.getArgument(0, String.class))).when(fixture.values).get(anyString());
        assertNotNull(reader.get("shared-disconnect"), "a transient verification outage must allow recovery");
        publisher.remove("shared-disconnect");
        assertNull(reader.get("shared-disconnect"));
    }

    @Test void expiredSharedLocalCopyDoesNotDeleteANewerRemoteReplacement() {
        var fixture = new SharedRedis();
        var first = fixture.repository();
        var second = fixture.repository();
        var older = proposal("shared-expired-copy", "epoch-original");
        first.put(older);
        second.put(proposal("shared-expired-copy", "epoch-newer"));
        older.setExpiresAt(System.currentTimeMillis() - 1);

        assertEquals("epoch-newer", first.get("shared-expired-copy").getConversationEpoch());
        assertNotNull(second.get("shared-expired-copy"), "stale local expiry must not delete the authoritative replacement");
    }

    private static PlanAdjustmentProposal proposal(String id, String epoch) {
        long now = System.currentTimeMillis();
        return PlanAdjustmentProposal.builder().proposalId(id).sessionId("shared-session")
                .conversationEpoch(epoch).baseRevision(1).createdAt(now).expiresAt(now + 120_000).build();
    }

    private static final class SharedRedis {
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        final ValueOperations<String, String> values = mock(ValueOperations.class);
        final ConcurrentHashMap<String, String> snapshots = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Duration> durations = new ConcurrentHashMap<>();

        SharedRedis() {
            ProposalRedisScriptStub.install(redis, values);
            when(values.get(anyString())).thenAnswer(call -> snapshots.get(call.getArgument(0, String.class)));
            doAnswer(call -> {
                snapshots.put(call.getArgument(0), call.getArgument(1));
                durations.put(call.getArgument(0), call.getArgument(2));
                return null;
            }).when(values).set(anyString(), anyString(), any(Duration.class));
            when(redis.delete(anyString())).thenAnswer(call -> snapshots.remove(call.getArgument(0, String.class)) != null);
        }

        CaffeineRedisProposalRepository repository() {
            return new CaffeineRedisProposalRepository(redis, new ObjectMapper(), Runnable::run);
        }
    }
}

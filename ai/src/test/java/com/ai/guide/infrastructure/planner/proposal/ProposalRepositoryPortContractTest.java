package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.gateway.ProposalRepositoryPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.service.ConversationIntentClassifier;
import com.ai.guide.domain.planner.service.PendingIntentContinuations;
import com.ai.guide.domain.planner.service.PlanAdjustmentService;
import com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProposalRepositoryPortContractTest {
    @TempDir Path directory;

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void putGetRemoveAndAbsoluteExpiryFollowPortContract() {
        ProposalRepositoryPort repository = new CaffeineRedisProposalRepository();
        long now = System.currentTimeMillis();
        assertEquals(900_000L, PlanAdjustmentProposal.DEFAULT_TTL_MS);
        PlanAdjustmentProposal proposal = proposal("contract-basic", "session-basic", now + 60_000L);

        repository.put(proposal);
        assertSame(proposal, repository.get(proposal.getProposalId()));
        assertSame(proposal, repository.remove(proposal.getProposalId()));
        assertNull(repository.get(proposal.getProposalId()));

        PlanAdjustmentProposal expired = proposal("contract-expired", "session-basic", now - 1L);
        repository.put(expired);
        assertNull(repository.get(expired.getProposalId()));

        long boundary = 1_800_000_000_000L;
        PlanAdjustmentProposal exactBoundary = proposal("contract-expiry-boundary", "session-basic", boundary);
        assertFalse(exactBoundary.isExpired(boundary - 1));
        assertTrue(exactBoundary.isExpired(boundary), "expiry is exclusive at the exact deadline");
        assertTrue(exactBoundary.isExpired(boundary + 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisUnavailableKeepsLocalL1BehaviorAndDoesNotThrow() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("Redis unavailable"));
        ProposalRepositoryPort repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper());
        PlanAdjustmentProposal proposal = proposal("contract-degraded", "session-degraded", System.currentTimeMillis() + 60_000L);

        assertDoesNotThrow(() -> repository.put(proposal));
        assertSame(proposal, repository.get(proposal.getProposalId()));
        assertDoesNotThrow(() -> repository.remove(proposal.getProposalId()));
        assertNull(repository.get(proposal.getProposalId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeInvalidatesQueuedRedisPutSoItCannotResurrectTheKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> redisValue = new AtomicReference<>();
        doAnswer(invocation -> {
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get("planner:proposal:contract-remove-race")).thenAnswer(invocation -> redisValue.get());
        when(redis.delete("planner:proposal:contract-remove-race")).thenAnswer(invocation -> redisValue.getAndSet(null) != null);
        ManualExecutor executor = new ManualExecutor();
        ProposalRepositoryPort repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), executor);
        PlanAdjustmentProposal proposal = proposal("contract-remove-race", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);

        repository.put(proposal);
        assertEquals(1, executor.queuedTasks());
        repository.remove(proposal.getProposalId());
        executor.runNext();

        assertNull(repository.get(proposal.getProposalId()), "a pending L2 write must not undo a completed remove");
        assertEquals(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT, redisValue.get());
        verify(values, times(1)).set(anyString(), eq(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT), any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void olderQueuedSameIdPutCannotOverwriteNewerProposalSnapshot() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> redisValue = new AtomicReference<>();
        doAnswer(invocation -> {
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get("planner:proposal:contract-put-race")).thenAnswer(invocation -> redisValue.get());
        ManualExecutor executor = new ManualExecutor();
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), executor);
        PlanAdjustmentProposal older = proposal("contract-put-race", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        older.setConversationEpoch("older");
        PlanAdjustmentProposal newer = proposal("contract-put-race", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        newer.setConversationEpoch("newer");

        repository.put(older);
        repository.put(newer);
        executor.runAt(1);
        executor.runAt(0);
        repository.clearLocalCache();

        PlanAdjustmentProposal restored = repository.get("contract-put-race");
        assertNotNull(restored);
        assertEquals("newer", restored.getConversationEpoch(), "an older asynchronous snapshot must not overwrite a later put");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overlappingSameIdPutsKeepL1AndL2AtTheSameLinearizationOrder() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> redisValue = new AtomicReference<>();
        doAnswer(invocation -> {
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get("planner:proposal:contract-overlap-put")).thenAnswer(invocation -> redisValue.get());

        ManualExecutor executor = new ManualExecutor();
        CountDownLatch firstL1Write = new CountDownLatch(1);
        CountDownLatch releaseFirstPut = new CountDownLatch(1);
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        AtomicReference<Integer> hookCalls = new AtomicReference<>(0);
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(
                redis, new ObjectMapper(), executor, () -> {
                    if (hookCalls.getAndSet(hookCalls.get() + 1) == 0) {
                        firstL1Write.countDown();
                        awaitLatch(releaseFirstPut);
                    }
                });
        PlanAdjustmentProposal older = proposal("contract-overlap-put", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        older.setConversationEpoch("older");
        PlanAdjustmentProposal newer = proposal("contract-overlap-put", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        newer.setConversationEpoch("newer");

        Thread first = new Thread(() -> runPut(repository, older, threadFailure), "older-proposal-put");
        first.start();
        assertTrue(firstL1Write.await(5, TimeUnit.SECONDS));

        CountDownLatch secondStarted = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            secondStarted.countDown();
            runPut(repository, newer, threadFailure);
        }, "newer-proposal-put");
        second.start();
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        awaitThreadState(second, Thread.State.BLOCKED);
        releaseFirstPut.countDown();
        first.join(5_000);
        second.join(5_000);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(threadFailure.get());
        assertEquals(2, executor.queuedTasks());
        executor.runAt(1);
        executor.runAt(0);
        assertEquals("newer", repository.get("contract-overlap-put").getConversationEpoch());
        repository.clearLocalCache();
        assertEquals("newer", repository.get("contract-overlap-put").getConversationEpoch(),
                "L2 must reflect the same ordered put as L1 even when task execution is reversed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void delayedRedisGetCannotRepopulateL1AfterRemoveCompletes() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        PlanAdjustmentProposal cached = proposal("contract-read-remove", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        cached.setConversationEpoch("epoch-read-remove");
        String json = new ObjectMapper().writeValueAsString(cached);
        AtomicReference<String> redisValue = new AtomicReference<>(json);
        AtomicReference<Integer> reads = new AtomicReference<>(0);
        CountDownLatch redisReadEntered = new CountDownLatch(1);
        CountDownLatch releaseRedisRead = new CountDownLatch(1);
        when(values.get("planner:proposal:contract-read-remove")).thenAnswer(invocation -> {
            if (reads.getAndSet(reads.get() + 1) == 0) {
                redisReadEntered.countDown();
                awaitLatch(releaseRedisRead);
                return json;
            }
            return redisValue.get();
        });
        when(redis.delete("planner:proposal:contract-read-remove")).thenAnswer(invocation -> redisValue.getAndSet(null) != null);
        doAnswer(invocation -> { redisValue.set(invocation.getArgument(1)); return null; })
                .when(values).set(eq("planner:proposal:contract-read-remove"), anyString(), any(Duration.class));
        CountDownLatch removeStarted = new CountDownLatch(1);
        CountDownLatch removeCompleted = new CountDownLatch(1);
        AtomicReference<PlanAdjustmentProposal> readResult = new AtomicReference<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), new ManualExecutor());

        Thread reader = new Thread(() -> {
            try {
                readResult.set(repository.get(cached.getProposalId()));
            } catch (Throwable error) {
                threadFailure.set(error);
            }
        }, "delayed-proposal-read");
        reader.start();
        assertTrue(redisReadEntered.await(5, TimeUnit.SECONDS));
        Thread remover = new Thread(() -> {
            removeStarted.countDown();
            try {
                repository.remove(cached.getProposalId());
            } catch (Throwable error) {
                threadFailure.set(error);
            } finally {
                removeCompleted.countDown();
            }
        }, "proposal-remove-during-read");
        remover.start();
        assertTrue(removeStarted.await(5, TimeUnit.SECONDS));
        awaitThreadState(remover, Thread.State.BLOCKED);
        assertEquals(1L, removeCompleted.getCount(), "remove must wait until the in-flight L2 read is resolved");
        releaseRedisRead.countDown();
        reader.join(5_000);
        remover.join(5_000);

        assertFalse(reader.isAlive());
        assertFalse(remover.isAlive());
        assertNull(threadFailure.get());
        assertNotNull(readResult.get());
        assertNull(repository.get(cached.getProposalId()), "remove must leave neither L1 nor L2 readable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeWaitsForRedisSetAlreadyInsideCriticalSectionThenDeletesIt() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> redisValue = new AtomicReference<>();
        CountDownLatch setEntered = new CountDownLatch(1);
        CountDownLatch releaseSet = new CountDownLatch(1);
        CountDownLatch deleteEntered = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (CaffeineRedisProposalRepository.REMOVED_SNAPSHOT.equals(invocation.getArgument(1))) {
                redisValue.set(invocation.getArgument(1));
                deleteEntered.countDown();
                return null;
            }
            setEntered.countDown();
            awaitLatch(releaseSet);
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.get("planner:proposal:contract-set-remove")).thenAnswer(invocation -> redisValue.get());
        when(redis.delete("planner:proposal:contract-set-remove")).thenAnswer(invocation -> {
            deleteEntered.countDown();
            return redisValue.getAndSet(null) != null;
        });
        ManualExecutor executor = new ManualExecutor();
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), executor);
        PlanAdjustmentProposal proposal = proposal("contract-set-remove", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        repository.put(proposal);

        Thread writer = new Thread(executor::runNext, "proposal-redis-set");
        writer.start();
        assertTrue(setEntered.await(5, TimeUnit.SECONDS));
        CountDownLatch removeStarted = new CountDownLatch(1);
        CountDownLatch removeCompleted = new CountDownLatch(1);
        Thread remover = new Thread(() -> {
            removeStarted.countDown();
            repository.remove(proposal.getProposalId());
            removeCompleted.countDown();
        }, "proposal-remove-during-set");
        remover.start();
        assertTrue(removeStarted.await(5, TimeUnit.SECONDS));
        awaitThreadState(remover, Thread.State.BLOCKED);
        assertEquals(1L, deleteEntered.getCount(), "Redis delete must wait until the in-flight set leaves the critical section");
        releaseSet.countDown();
        writer.join(5_000);
        remover.join(5_000);

        assertFalse(writer.isAlive());
        assertFalse(remover.isAlive());
        assertEquals(0L, deleteEntered.getCount());
        assertEquals(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT, redisValue.get());
        assertNull(repository.get(proposal.getProposalId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectedRedisExecutorKeepsL1AndReleasesPerKeyState() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("test rejection");
        };
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis,
                new ObjectMapper(), rejectingExecutor);
        PlanAdjustmentProposal proposal = proposal("contract-rejected-executor", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);

        assertDoesNotThrow(() -> repository.put(proposal));
        assertSame(proposal, repository.get(proposal.getProposalId()), "L1 is the documented fallback");
        repository.remove(proposal.getProposalId());
        assertEquals(0, redisKeyStateCount(repository), "rejected task accounting must not leak per-key state");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteFailureTombstoneBlocksRehydrationAndReconstructedApplyWithoutSqlWrite() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JdbcTemplate jdbc = temporaryPlannerDatabase();
        PlannerSessionRepository sessions = spy(new PlannerSessionRepository(jdbc, mapper));
        String guestId = "guest-tombstone-" + UUID.randomUUID();
        var created = sessions.create("ANONYMOUS", guestId, "proposal tombstone fixture", Map.of(),
                AppliedPreferencesSnapshot.empty(), Map.of("days", java.util.List.of()), "create-" + guestId, "fingerprint");
        String sessionId = created.session().sessionId();
        String accessToken = created.accessToken();

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        PlanAdjustmentService originalService = new PlanAdjustmentService(
                sessions, null, null, new RouteCostProvider(null), new PlanVerifier(), null,
                new CaffeineRedisProposalRepository(), new ConversationIntentClassifier(), mapper);
        var continuationsField = PlanAdjustmentService.class.getDeclaredField("continuations");
        continuationsField.setAccessible(true);
        PendingIntentContinuations originalContinuations = (PendingIntentContinuations) continuationsField.get(originalService);
        originalContinuations.begin(sessionId);

        PlanAdjustmentProposal oldProposal = proposal("proposal-delete-failure", sessionId,
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        oldProposal.setConversationEpoch(originalContinuations.epoch(sessionId));
        assertTrue(originalContinuations.validProposal(sessionId, oldProposal.getConversationEpoch(), oldProposal.getCreatedAt()),
                "the proposal epoch must be demonstrably current before cancellation");
        String staleSnapshot = mapper.writeValueAsString(oldProposal);
        when(values.get("planner:proposal:proposal-delete-failure")).thenReturn(staleSnapshot);
        when(redis.delete("planner:proposal:proposal-delete-failure"))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(values).set(eq("planner:proposal:proposal-delete-failure"),
                        eq(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT), any(Duration.class));
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis, mapper,
                new ManualExecutor());

        assertNotNull(repository.get(oldProposal.getProposalId()), "fixture should first restore the old snapshot");
        assertNotNull(repository.remove(oldProposal.getProposalId()));
        repository.clearLocalCache();
        assertNull(repository.get(oldProposal.getProposalId()), "local tombstone must block stale L2 rehydration");
        verify(values, times(1)).get("planner:proposal:proposal-delete-failure");

        CaffeineRedisProposalRepository reconstructedRepository = new CaffeineRedisProposalRepository(redis, mapper,
                new ManualExecutor());
        PlanAdjustmentProposal restoredAfterRepositoryReconstruction = reconstructedRepository.get(oldProposal.getProposalId());
        assertNotNull(restoredAfterRepositoryReconstruction,
                "a new repository has no local tombstone and can read the stale mocked L2 snapshot");
        assertEquals(oldProposal.getConversationEpoch(), restoredAfterRepositoryReconstruction.getConversationEpoch());

        PlanAdjustmentService reconstructedService = new PlanAdjustmentService(
                sessions, null, null, new RouteCostProvider(null), new PlanVerifier(), null,
                reconstructedRepository, new ConversationIntentClassifier(), mapper);
        ApplyAdjustmentRequestDto request = new ApplyAdjustmentRequestDto();
        request.setProposalId(oldProposal.getProposalId());
        request.setBaseRevision(1);
        request.setSessionAccessToken(accessToken);
        var result = reconstructedService.apply(sessionId, request);

        assertEquals(409, result.status(), "a fresh service epoch must reject the previously current proposal");
        assertEquals(1, sessions.revisionCount(sessionId));
        verify(sessions, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiredPutCannotCancelAnExistingLiveSnapshot() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> redisValue = new AtomicReference<>();
        doAnswer(invocation -> {
            redisValue.set(invocation.getArgument(1));
            return null;
        }).when(values).set(eq("planner:proposal:contract-expired-overwrite"), anyString(), any(Duration.class));
        when(values.get("planner:proposal:contract-expired-overwrite")).thenAnswer(invocation -> redisValue.get());
        when(redis.delete("planner:proposal:contract-expired-overwrite"))
                .thenAnswer(invocation -> redisValue.getAndSet(null) != null);
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis,
                new ObjectMapper(), Runnable::run);
        PlanAdjustmentProposal previous = proposal("contract-expired-overwrite", "session-race",
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        previous.setConversationEpoch("epoch-live");
        repository.put(previous);
        assertNotNull(redisValue.get(), "the initial valid proposal should be present in L2");

        PlanAdjustmentProposal expired = proposal("contract-expired-overwrite", "session-race",
                System.currentTimeMillis() - 1L);
        repository.put(expired);
        repository.clearLocalCache();

        assertNotNull(redisValue.get(), "invalid input must not act as cancellation of an existing live proposal");
        assertEquals("epoch-live", repository.get(expired.getProposalId()).getConversationEpoch());
        verify(redis, never()).delete("planner:proposal:contract-expired-overwrite");
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoredRedisProposalMustMatchKeyAndRequiredShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        Map<String, String> snapshots = new LinkedHashMap<>();
        long createdAt = System.currentTimeMillis();
        PlanAdjustmentProposal valid = proposal("valid-source-id", "session-valid",
                createdAt + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        valid.setCreatedAt(createdAt);
        valid.setConversationEpoch("epoch-valid");
        ObjectNode validJson = (ObjectNode) mapper.valueToTree(valid);

        snapshots.put("mismatched", mutate(validJson, n -> n.put("proposalId", "different-id"), mapper));
        snapshots.put("missing-session", mutate(validJson, n -> n.remove("sessionId"), mapper));
        snapshots.put("missing-epoch", mutate(validJson, n -> n.remove("conversationEpoch"), mapper));
        snapshots.put("missing-revision", mutate(validJson, n -> n.remove("baseRevision"), mapper));
        snapshots.put("missing-expiry", mutate(validJson, n -> n.remove("expiresAt"), mapper));
        snapshots.put("implausible-expiry", mutate(validJson,
                n -> n.put("expiresAt", createdAt + 2 * PlanAdjustmentProposal.DEFAULT_TTL_MS), mapper));
        snapshots.put("future-created", mutate(validJson,
                n -> n.put("createdAt", System.currentTimeMillis() + 60_000L), mapper));
        when(values.get(startsWith("planner:proposal:"))).thenAnswer(invocation ->
                snapshots.get(invocation.getArgument(0, String.class).substring("planner:proposal:".length())));
        doAnswer(invocation -> {
            snapshots.remove(invocation.getArgument(0, String.class).substring("planner:proposal:".length()));
            return true;
        }).when(redis).delete(startsWith("planner:proposal:"));
        CaffeineRedisProposalRepository repository = new CaffeineRedisProposalRepository(redis, mapper,
                new ManualExecutor());

        for (String key : snapshots.keySet().toArray(String[]::new)) {
            assertNull(repository.get(key), "invalid snapshot should not be restored for key " + key);
            assertNull(repository.get(key), "invalid snapshot must not have been cached in L1 for key " + key);
        }
        assertTrue(snapshots.isEmpty(), "invalid snapshots should be best-effort deleted from L2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoredOldProposalFailsClosedAfterServiceReconstructionWithoutSqlWrite() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JdbcTemplate jdbc = temporaryPlannerDatabase();
        PlannerSessionRepository sessions = spy(new PlannerSessionRepository(jdbc, mapper));
        String guestId = "guest-" + UUID.randomUUID();
        var created = sessions.create("ANONYMOUS", guestId, "proposal recovery fixture", Map.of(),
                AppliedPreferencesSnapshot.empty(), Map.of("days", java.util.List.of()), "create-" + guestId, "fingerprint");
        String sessionId = created.session().sessionId();
        String accessToken = created.accessToken();
        assertNotNull(accessToken);
        assertEquals(1, sessions.revisionCount(sessionId));

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ProposalRedisScriptStub.install(redis, values);
        AtomicReference<String> serialized = new AtomicReference<>();
        CountDownLatch writeCompleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            serialized.set(invocation.getArgument(1));
            writeCompleted.countDown();
            return null;
        }).when(values).set(eq("planner:proposal:proposal-restart"), anyString(), any(Duration.class));

        PlanAdjustmentProposal oldProposal = proposal("proposal-restart", sessionId,
                System.currentTimeMillis() + PlanAdjustmentProposal.DEFAULT_TTL_MS);
        oldProposal.setConversationEpoch("epoch-from-old-service");
        ProposalRepositoryPort oldRepository = new CaffeineRedisProposalRepository(redis, mapper);
        oldRepository.put(oldProposal);
        assertTrue(writeCompleted.await(15, TimeUnit.SECONDS), "L2 snapshot write should finish in the test fixture");
        assertNotNull(serialized.get());

        when(values.get("planner:proposal:proposal-restart")).thenReturn(serialized.get());
        ProposalRepositoryPort reconstructedRepository = new CaffeineRedisProposalRepository(redis, mapper);
        PlanAdjustmentProposal restored = reconstructedRepository.get("proposal-restart");
        assertNotNull(restored, "proposal can be restored from the L2 snapshot");
        assertEquals(oldProposal.getExpiresAt(), restored.getExpiresAt(), "serialization must preserve the absolute expiry timestamp");

        PlanAdjustmentService reconstructedService = new PlanAdjustmentService(
                sessions, null, null, new RouteCostProvider(null), new PlanVerifier(), null,
                reconstructedRepository, new ConversationIntentClassifier(), mapper);
        ApplyAdjustmentRequestDto request = new ApplyAdjustmentRequestDto();
        request.setProposalId("proposal-restart");
        request.setBaseRevision(1);
        request.setSessionAccessToken(accessToken);

        var result = reconstructedService.apply(sessionId, request);

        assertEquals(409, result.status(), "a new service instance has no matching current epoch and must reject the old proposal");
        assertEquals(1, sessions.findAnonymousByAccessToken(accessToken).currentVersion());
        assertEquals(1, sessions.revisionCount(sessionId), "fail-closed apply must not append a plan revision");
        verify(sessions, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }

    private JdbcTemplate temporaryPlannerDatabase() throws Exception {
        Path databaseFile = directory.resolve("proposal-contract-" + UUID.randomUUID() + ".sqlite");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + databaseFile);
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("busy_timeout", "5000");
        dataSource.setConnectionProperties(connectionProperties);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        new KnowledgeSchemaInitializer().initialize(jdbc, true);
        return jdbc;
    }

    private static PlanAdjustmentProposal proposal(String id, String sessionId, long expiresAt) {
        long createdAt = System.currentTimeMillis();
        return PlanAdjustmentProposal.builder()
                .proposalId(id)
                .sessionId(sessionId)
                .baseRevision(1)
                .createdAt(createdAt)
                .expiresAt(expiresAt)
                .proposedTrip(Map.of("days", java.util.List.of()))
                .build();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", e);
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && thread.getState() != expected) Thread.yield();
        assertEquals(expected, thread.getState(), "thread should be blocked on the per-key lock");
    }

    private static void runPut(CaffeineRedisProposalRepository repository, PlanAdjustmentProposal proposal,
                               AtomicReference<Throwable> failure) {
        try {
            repository.put(proposal);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    private static int redisKeyStateCount(CaffeineRedisProposalRepository repository) throws Exception {
        var field = CaffeineRedisProposalRepository.class.getDeclaredField("redisKeyStates");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(repository)).size();
    }

    private static String mutate(ObjectNode source, java.util.function.Consumer<ObjectNode> mutation,
                                 ObjectMapper mapper) throws Exception {
        ObjectNode copy = source.deepCopy();
        mutation.accept(copy);
        return mapper.writeValueAsString(copy);
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int queuedTasks() {
            return tasks.size();
        }

        void runNext() {
            runAt(0);
        }

        void runAt(int index) {
            tasks.remove(index).run();
        }
    }
}

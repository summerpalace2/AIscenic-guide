package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real services only: no Spring context, SQLite migration, mocks or connection fallback. */
@EnabledIfEnvironmentVariable(named = "CP6_IT_ENABLE", matches = "true",
        disabledReason = "Real PostgreSQL/Redis unavailable or not explicitly enabled; NOT infrastructure acceptance")
class Cp6RealInfrastructureIntegrationTest {
    private Cp6InfrastructureTarget target;
    private String schema;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private String proposalId;

    @BeforeEach void setup() {
        target = new Cp6InfrastructureTarget(System.getenv());
        schema = "cp6_" + UUID.randomUUID().toString().replace("-", "");
        admin = new JdbcTemplate(target.adminDataSource());
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(target.dataSource(schema));
        factory = target.redisFactory();
        redis = Cp6InfrastructureTarget.redis(factory);
        assertEquals("PONG", redis.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping()));
        proposalId = "cp6-it-" + UUID.randomUUID();
    }

    @AfterEach void cleanup() {
        try {
            if (redis != null && proposalId != null) redis.delete("planner:proposal:" + proposalId);
        } finally {
            try { if (factory != null) factory.destroy(); }
            finally { if (admin != null && schema != null) admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    @Test void realPostgresMigrationIsRepeatable() {
        var initializer = new KnowledgeSchemaInitializer();
        initializer.initialize(jdbc, false);
        var before = jdbc.queryForList("SELECT version, description FROM schema_migration ORDER BY version");
        assertFalse(before.isEmpty());
        initializer.initialize(jdbc, false);
        assertEquals(before, jdbc.queryForList("SELECT version, description FROM schema_migration ORDER BY version"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));
    }

    @Test void twoIndependentJvmInitializersCanStartOnEmptySchema() throws Exception {
        Path run = Files.createTempDirectory("cp6-migration-");
        Process first = worker("migrate", run.resolve("first.log"));
        Process second = null;
        try {
            second = worker("migrate", run.resolve("second.log"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && !(ready(run.resolve("first.log")) && ready(run.resolve("second.log")))) Thread.sleep(25);
            assertTrue(ready(run.resolve("first.log")) && ready(run.resolve("second.log")), "initializer readiness timed out; logs: " + run);
            Files.createFile(run.resolve("start"));
            assertTrue(first.waitFor(60, TimeUnit.SECONDS), "first initializer timed out; logs: " + run);
            assertTrue(second.waitFor(60, TimeUnit.SECONDS), "second initializer timed out; logs: " + run);
            assertEquals(0, first.exitValue(), "first migration failed; logs: " + run);
            assertEquals(0, second.exitValue(), "second migration failed; logs: " + run);
            assertFalse(jdbc.queryForList("SELECT version FROM schema_migration").isEmpty());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT version FROM schema_migration GROUP BY version HAVING COUNT(*) > 1) duplicates", Integer.class));
        } finally {
            stop(first); stop(second);
        }
    }

    @Test void realRedisDeletionInvalidatesASecondRepositoryL1() throws Exception {
        var first = repository();
        var second = repository();
        first.put(proposal(proposalId));
        awaitRedisSnapshot();
        assertNotNull(second.get(proposalId), "second instance must hydrate from Redis");
        var queuedTasks = new java.util.ArrayDeque<Runnable>();
        var delayedPublisher = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), queuedTasks::addLast);
        delayedPublisher.put(proposal(proposalId));
        first.remove(proposalId);
        assertEquals(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT, redis.opsForValue().get("planner:proposal:" + proposalId));
        assertNull(second.get(proposalId), "a remote deletion must not leave an actionable stale L1 proposal");
        assertNull(delayedPublisher.get(proposalId));
        queuedTasks.removeFirst().run();
        assertNull(repository().get(proposalId), "a queued publication must not resurrect the cancelled ID");
        assertEquals(CaffeineRedisProposalRepository.REMOVED_SNAPSHOT, redis.opsForValue().get("planner:proposal:" + proposalId));
    }

    @Test void committedPostgresAndRedisSnapshotsSurviveJavaWorkerKill() throws Exception {
        jdbc.execute("CREATE TABLE crash_probe(id VARCHAR(128) PRIMARY KEY, value VARCHAR(128), snapshot TEXT NOT NULL)");
        Path run = Files.createTempDirectory("cp6-crash-");
        Path log = run.resolve("worker.log");
        Process child = worker("write-and-wait", log);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && child.isAlive()
                    && !(Files.exists(log) && Files.readString(log).contains("CP6_COMMITTED"))) Thread.sleep(50);
            assertTrue(child.isAlive() && Files.readString(log).contains("CP6_COMMITTED"), "worker did not commit before kill; log: " + log);
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            Process restarted = worker("verify-recovered", run.resolve("restarted.log"));
            try {
                assertTrue(restarted.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, restarted.exitValue(), "new JVM must see committed snapshots; logs: " + run);
            } finally { stop(restarted); }
        } finally { stop(child); }
    }

    @Test void twoJvmFormalUpdatesKeepOneWinningVersionAndOneConflict() throws Exception {
        new KnowledgeSchemaInitializer().initialize(jdbc,false);
        var mapper = new ObjectMapper();
        var repository = new com.ai.guide.infrastructure.trip.repository.TripRepository(jdbc,mapper);
        var service = new com.ai.guide.domain.trip.service.TripService(repository,null,mapper);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var initial = tx.execute(status -> service.create("cp6-owner",java.util.Map.of("plan",Cp6InfrastructureWorker.formalPlan("base"))));
        assertEquals(201,initial.status());
        var results = formalResults("formal-update",initial.tripId());
        assertEquals(java.util.List.of(200,409),results.stream().map(node -> node.get("status").asInt()).sorted().toList());
        var stored = repository.find("cp6-owner",initial.tripId());
        assertEquals(2,stored.currentVersion());
        assertEquals(java.util.List.of(1,2),repository.listVersions("cp6-owner",initial.tripId()).stream()
                .map(com.ai.guide.domain.trip.model.TripVersion::versionNumber).toList());
        var winner = results.stream().filter(node -> node.get("status").asInt()==200).findFirst().orElseThrow();
        assertEquals(winner.get("trip").get("plan"),mapper.valueToTree(stored.plan()),"aggregate must equal the winner, not the rejected snapshot");
        assertNull(repository.find("another-owner",initial.tripId()));
    }

    @Test void twoJvmFirstSavesWithOneIdempotencyKeyReturnOneCompletedTrip() throws Exception {
        new KnowledgeSchemaInitializer().initialize(jdbc,false);
        String key = "formal-" + UUID.randomUUID();
        var results = formalResults("formal-create",key);
        assertTrue(results.stream().allMatch(node -> node.get("status").asInt()<300));
        assertEquals(results.get(0).get("tripId"),results.get(1).get("tripId"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip_version",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip_idempotency",Integer.class));
        var repository = new com.ai.guide.infrastructure.trip.repository.TripRepository(jdbc,new ObjectMapper());
        var replay = repository.deserializeIdempotentTrip(repository.findIdempotency("cp6-owner","CREATE",key));
        assertNotNull(replay,"the reservation must be complete, not an orphan version=0 row");
        assertEquals(results.get(0).get("tripId").asText(),replay.id());
        assertEquals(1,replay.currentVersion());
        assertEquals(repository.find("cp6-owner",replay.id()),replay);
    }

    @Test void twoJvmSameFormalOpenOperationReturnsOnePendingSession() throws Exception {
        new KnowledgeSchemaInitializer().initialize(jdbc,false);
        var mapper = new ObjectMapper();
        var service = new com.ai.guide.domain.trip.service.TripService(
                new com.ai.guide.infrastructure.trip.repository.TripRepository(jdbc,mapper),null,mapper);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var formal = tx.execute(status -> service.create("cp6-owner",java.util.Map.of("plan",Cp6InfrastructureWorker.formalPlan("base"))));
        assertEquals(201,formal.status());
        var results = formalResults("formal-open",formal.tripId());
        assertTrue(results.stream().allMatch(node -> node.get("serviceStatus").asInt()==200));
        assertEquals(results.get(0).get("body").get("sessionId"),results.get(1).get("body").get("sessionId"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM planner_session",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM planner_plan_revision",Integer.class));
        assertEquals("OPEN_PENDING",jdbc.queryForObject("SELECT status FROM planner_session",String.class));
    }

    private java.util.List<com.fasterxml.jackson.databind.JsonNode> formalResults(String mode,String entityId) throws Exception {
        Path run = Files.createTempDirectory("cp6-formal-");
        Path firstLog = run.resolve("first.log"), secondLog = run.resolve("second.log");
        Process first = worker(mode,firstLog,entityId), second = null;
        try {
            second = worker(mode,secondLog,entityId);
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime()<deadline && first.isAlive() && second.isAlive() && !(ready(firstLog)&&ready(secondLog))) Thread.sleep(25);
            assertTrue(ready(firstLog)&&ready(secondLog),"both JVMs must have read the same pre-mutation state; logs: "+run);
            Files.createFile(run.resolve("start"));
            assertTrue(first.waitFor(60,TimeUnit.SECONDS),"first formal operation timeout; logs: "+run);
            assertTrue(second.waitFor(60,TimeUnit.SECONDS),"second formal operation timeout; logs: "+run);
            assertEquals(0,first.exitValue(),"first formal operation failed; logs: "+run);
            assertEquals(0,second.exitValue(),"second formal operation failed; logs: "+run);
            var mapper = new ObjectMapper();
            var output = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (Path log : java.util.List.of(firstLog,secondLog)) {
                String result = Files.readAllLines(log).stream().filter(line -> line.startsWith("CP6_RESULT "))
                        .findFirst().orElseThrow(() -> new AssertionError("No formal receipt; log: "+log));
                output.add(mapper.readTree(result.substring("CP6_RESULT ".length())));
            }
            return output;
        } finally { stop(first); stop(second); }
    }

    private CaffeineRedisProposalRepository repository() {
        return new CaffeineRedisProposalRepository(redis, new ObjectMapper(), Runnable::run);
    }

    static PlanAdjustmentProposal proposal(String id) {
        long now = System.currentTimeMillis();
        return PlanAdjustmentProposal.builder().proposalId(id).sessionId("cp6-session")
                .conversationEpoch("cp6-epoch").baseRevision(1).createdAt(now).expiresAt(now + 120_000).build();
    }

    private void awaitRedisSnapshot() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !Boolean.TRUE.equals(redis.hasKey("planner:proposal:" + proposalId))) Thread.sleep(20);
        assertTrue(Boolean.TRUE.equals(redis.hasKey("planner:proposal:" + proposalId)));
    }

    private Process worker(String mode, Path log) throws Exception {
        return worker(mode, log, proposalId);
    }

    private Process worker(String mode, Path log, String entityId) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(javaExecutable))) javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(javaExecutable, "-cp", classpath, Cp6InfrastructureWorker.class.getName(), mode, schema, entityId,
                log.getParent().resolve("start").toString(), log.getFileName().toString());
        var inherited = new java.util.HashMap<>(builder.environment());
        builder.environment().clear();
        inherited.forEach((key, value) -> {
            if (key.startsWith("CP6_IT_") || java.util.Set.of("SystemRoot", "WINDIR", "TEMP", "TMP").contains(key))
                builder.environment().put(key, value);
        });
        builder.directory(log.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        return builder.start();
    }

    private static void stop(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); }
    }

    private static boolean ready(Path log) throws Exception {
        return Files.exists(log) && Files.readString(log).contains("CP6_READY");
    }
}

package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

/** Child JVM used only by opt-in integration probes; it never starts the application or imports legacy SQLite. */
public final class Cp6InfrastructureWorker {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("CP6_IT_ENABLE"))) throw new IllegalStateException("Probe not enabled");
        var target = new Cp6InfrastructureTarget(System.getenv());
        var jdbc = new JdbcTemplate(target.dataSource(args[1]));
        if (args[0].startsWith("formal-")) {
            formalProbe(args, jdbc);
            return;
        }
        if ("migrate".equals(args[0])) {
            System.out.println("CP6_READY"); System.out.flush();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3]))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Migration start barrier timed out");
                Thread.sleep(25);
            }
            new KnowledgeSchemaInitializer().initialize(jdbc, false);
            return;
        }
        var factory = target.redisFactory();
        try {
            var redis = Cp6InfrastructureTarget.redis(factory);
            var repository = new CaffeineRedisProposalRepository(redis, new ObjectMapper(), Runnable::run);
            if ("write-and-wait".equals(args[0])) {
                var proposal = Cp6RealInfrastructureIntegrationTest.proposal(args[2]);
                jdbc.update("INSERT INTO crash_probe(id,value,snapshot) VALUES (?,?,?)", args[2], "committed", new ObjectMapper().writeValueAsString(proposal));
                repository.put(proposal);
                if (!Boolean.TRUE.equals(redis.hasKey("planner:proposal:" + args[2]))) throw new IllegalStateException("Redis write did not persist");
                System.out.println("CP6_COMMITTED"); System.out.flush();
                Thread.sleep(120_000);
            } else if ("verify-recovered".equals(args[0])) {
                var recovered = repository.get(args[2]);
                var mapper = new ObjectMapper();
                if (!"committed".equals(jdbc.queryForObject("SELECT value FROM crash_probe WHERE id=?", String.class, args[2]))
                        || recovered == null || !mapper.readTree(jdbc.queryForObject("SELECT snapshot FROM crash_probe WHERE id=?", String.class,args[2]))
                        .equals(mapper.valueToTree(recovered))) throw new IllegalStateException("Committed snapshots unavailable or changed after worker restart");
            } else throw new IllegalArgumentException("Unknown worker mode");
        } finally { factory.destroy(); }
    }

    /** Real repository SQL and service operations, with a barrier after the initial authoritative read. */
    private static void formalProbe(String[] args, JdbcTemplate jdbc) throws Exception {
        var mapper = new ObjectMapper();
        var firstRead = new java.util.concurrent.atomic.AtomicBoolean(true);
        if ("formal-open".equals(args[0])) {
            var sessions = new com.ai.guide.infrastructure.planner.repository.PlannerSessionRepository(jdbc,mapper) {
                @Override public StoredSession findByIdempotency(String ownerType,String ownerId,String key) {
                    var row = super.findByIdempotency(ownerType,ownerId,key);
                    if (row==null && firstRead.compareAndSet(true,false)) barrier(args[3]);
                    return row;
                }
            };
            var trips = new com.ai.guide.domain.trip.service.TripService(
                    new com.ai.guide.infrastructure.trip.repository.TripRepository(jdbc,mapper),null,mapper);
            var open = new com.ai.guide.application.planner.PlannerOpenApplicationService(sessions,trips,mapper,true);
            var tx = new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
            var result = tx.execute(status -> open.openFormalTrip(new com.ai.guide.domain.planner.model.PlannerOpenActor("cp6-owner",false),args[2],"cp6-open-operation"));
            System.out.println("CP6_RESULT " + mapper.writeValueAsString(result)); System.out.flush();
            return;
        }
        var repository = new com.ai.guide.infrastructure.trip.repository.TripRepository(jdbc,mapper) {
            @Override public com.ai.guide.domain.trip.gateway.TripRepositoryPort.IdempotencyRecord findIdempotency(String owner,String operation,String key) {
                var row = super.findIdempotency(owner,operation,key);
                if ("formal-create".equals(args[0]) && row == null && firstRead.compareAndSet(true,false)) barrier(args[3]);
                return row;
            }
            @Override public com.ai.guide.domain.trip.model.Trip find(String owner,String id) {
                var row = super.find(owner,id);
                if ("formal-update".equals(args[0]) && row != null && row.currentVersion()==1 && firstRead.compareAndSet(true,false)) barrier(args[3]);
                return row;
            }
        };
        var service = new com.ai.guide.domain.trip.service.TripService(repository,null,mapper);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var result = tx.execute(status -> "formal-create".equals(args[0])
                ? service.create("cp6-owner",java.util.Map.of("plan",formalPlan("base"),"idempotencyKey",args[2]))
                : service.update("cp6-owner",args[2],java.util.Map.of("plan",formalPlan(args[4]),"expectedVersion",1)));
        System.out.println("CP6_RESULT " + mapper.writeValueAsString(result));
        System.out.flush();
    }

    static java.util.Map<String,Object> formalPlan(String name) {
        return java.util.Map.of("title","probe","days",java.util.List.of(java.util.Map.of("dayNumber",1,
                "stops",java.util.List.of(java.util.Map.of("id","stop-a","name",name)))));
    }

    private static void barrier(String path) {
        System.out.println("CP6_READY"); System.out.flush();
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try {
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
                if (System.nanoTime()>deadline) throw new IllegalStateException("Formal operation barrier timed out");
                Thread.sleep(25);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}

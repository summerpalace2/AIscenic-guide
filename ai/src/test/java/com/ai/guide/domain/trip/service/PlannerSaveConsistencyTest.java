package com.ai.guide.domain.trip.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.infrastructure.trip.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlannerSaveConsistencyTest {
    @TempDir Path directory;
    private DriverManagerDataSource source() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("save.db"));
        var properties = new Properties();
        properties.setProperty("busy_timeout", "5000");
        properties.setProperty("journal_mode", "WAL");
        ds.setConnectionProperties(properties);
        return ds;
    }
    private void schema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE trip(id TEXT PRIMARY KEY,owner_id TEXT,title TEXT,status TEXT,current_version INTEGER,created_at INTEGER,updated_at INTEGER)");
        jdbc.execute("CREATE TABLE trip_version(id TEXT PRIMARY KEY,trip_id TEXT,version_number INTEGER,snapshot TEXT,change_reason TEXT,created_at INTEGER,created_by TEXT,UNIQUE(trip_id,version_number))");
        jdbc.execute("CREATE TABLE trip_idempotency(owner_id TEXT,operation TEXT,idempotency_key TEXT,request_fingerprint TEXT,trip_id TEXT,version_number INTEGER,response_json TEXT,created_at INTEGER,PRIMARY KEY(owner_id,operation,idempotency_key))");
    }
    private Map<String,Object> plan(String title) {
        return new LinkedHashMap<>(Map.of("title",title,"days",List.of(Map.of("day",1,"stops",List.of()))));
    }
    private PlannerSessionRepositoryPort.StoredSession session(int version, Map<String,Object> plan) {
        return new PlannerSessionRepositoryPort.StoredSession("save-session","USER","owner","","fp","prompt",Map.of(),AppliedPreferencesSnapshot.empty(),plan,version,version,"ACTIVE",1,1);
    }
    @Test void concurrentDraftEditLinksSavedIdentityWithoutReplacingNewerContent() {
        var ds = source(); var jdbc = new JdbcTemplate(ds); schema(jdbc);
        var real = new TripService(new TripRepository(jdbc,new ObjectMapper()),null,new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var trips = mock(TripService.class);
        var current = new AtomicReference<>(session(1,plan("first")));
        when(trips.savePlannerSnapshot(anyString(),anyMap(),anyString())).thenAnswer(call -> {
            var result = tx.execute(status -> real.savePlannerSnapshot(call.getArgument(0),call.getArgument(1),call.getArgument(2)));
            if (current.get().currentVersion() == 1) current.set(session(2,plan("edited during save")));
            return result;
        });
        var repo = mock(PlannerSessionRepositoryPort.class);
        when(repo.find(anyString(),anyString(),anyString(),anyString())).thenAnswer(call -> current.get());
        when(repo.linkFormalTrip(any(),anyMap())).thenAnswer(call -> {
            PlannerSessionRepositoryPort.StoredSession old = call.getArgument(0);
            if (old.currentVersion()!=current.get().currentVersion()) return false;
            current.set(session(old.currentVersion(),new LinkedHashMap<>(call.getArgument(1)))); return true;
        });
        var planner = new PlannerService(null,null,repo,trips,new ObjectMapper(),null,null,null);
        UserContext.set("owner","owner","USER");
        try {
            var first = planner.save("save-session","");
            assertEquals(201,first.status()); assertEquals(true,first.body().get("draftLinked"));
            assertEquals("edited during save",current.get().trip().get("title"));
            assertEquals(first.body().get("tripId"),current.get().trip().get("formalTripId"));
            var second = planner.save("save-session","");
            assertEquals(200,second.status()); assertEquals(first.body().get("tripId"),second.body().get("tripId"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip",Integer.class));
            assertEquals("edited during save",real.get("owner",String.valueOf(first.body().get("tripId"))).title());
        } finally { UserContext.clear(); }
    }
    @Test void committedIdentityRecoversAfterServiceReconstructionWithoutDraftLink() {
        var ds = source(); var jdbc = new JdbcTemplate(ds); schema(jdbc);
        var firstService = new TripService(new TripRepository(jdbc,new ObjectMapper()),null,new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var initial = plan("first"); initial.put("plannerSessionId","save-session");
        var first = tx.execute(status -> firstService.savePlannerSnapshot("owner",initial,"revision-1"));
        // New connection and all new service instances simulate losing process memory.
        var restartedSource = source(); var restartedJdbc = new JdbcTemplate(restartedSource);
        var restarted = new TripService(new TripRepository(restartedJdbc,new ObjectMapper()),null,new ObjectMapper());
        var restartedTx = new TransactionTemplate(new DataSourceTransactionManager(restartedSource));
        var changed = plan("next edit"); changed.put("plannerSessionId","save-session");
        var second = restartedTx.execute(status -> restarted.savePlannerSnapshot("owner",changed,"revision-2"));
        assertEquals(200,second.status()); assertEquals(first.tripId(),second.tripId());
        assertEquals(2,second.currentVersion());
        assertEquals(1,restartedJdbc.queryForObject("SELECT COUNT(*) FROM trip",Integer.class));
        assertEquals(first.tripId(),restartedJdbc.queryForObject("SELECT trip_id FROM trip_idempotency WHERE operation='PLANNER_LINK'",String.class));
    }
    @Test void failedSaveRollsBackIdentityTogetherWithFormalTrip() {
        var ds = source(); var jdbc = new JdbcTemplate(ds); schema(jdbc);
        var repo = spy(new TripRepository(jdbc,new ObjectMapper()));
        var service = new TripService(repo,null,new ObjectMapper());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var snapshot = plan("first"); snapshot.put("plannerSessionId","save-session");
        doThrow(new IllegalStateException("simulated commit gap")).when(repo).completeIdempotency(eq("owner"),eq("PLANNER_LINK"),eq("save-session"),any());
        assertThrows(IllegalStateException.class,()->tx.execute(status -> service.savePlannerSnapshot("owner",snapshot,"revision-1")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trip",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM trip_idempotency",Integer.class));
    }
    @Test void concurrentReservationsCreateOneFormalTripAndRetryRecoversSqliteContention() throws Exception {
        var ds=source();var jdbc=new JdbcTemplate(ds);schema(jdbc);
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        Callable<TripService.OperationResult> save=()->{
            var separate=source();var service=new TripService(new TripRepository(new JdbcTemplate(separate),new ObjectMapper()),null,new ObjectMapper());
            var tx=new TransactionTemplate(new DataSourceTransactionManager(separate));
            var snapshot=plan("same");snapshot.put("plannerSessionId","save-session");
            gate.await();
            for(int attempt=0;attempt<10;attempt++) {
                try{return tx.execute(status->service.savePlannerSnapshot("owner",snapshot,"revision-1"));}
                catch(org.springframework.dao.DataAccessException busy){if(attempt==9)throw busy;Thread.sleep(20);}
            }
            throw new AssertionError();
        };
        try {var a=pool.submit(save);var b=pool.submit(save);gate.countDown();var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertEquals(first.tripId(),second.tripId());assertTrue(first.status()<400);assertTrue(second.status()<400);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM trip",Integer.class));
        }finally{pool.shutdownNow();}
    }
}

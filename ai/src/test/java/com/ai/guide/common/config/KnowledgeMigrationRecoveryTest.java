package com.ai.guide.common.config;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeMigrationRecoveryTest {
 SingleConnectionDataSource source;JdbcTemplate jdbc;KnowledgeDbConfig config;
 @BeforeEach void setup(){source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);config=new KnowledgeDbConfig();jdbc.execute("CREATE TABLE schema_migration(version INTEGER PRIMARY KEY,description TEXT,applied_at BIGINT)");}
 @AfterEach void close(){source.destroy();}
 void migration(int version,Runnable body){try{var method=KnowledgeDbConfig.class.getDeclaredMethod("applyMigration",JdbcTemplate.class,int.class,String.class,Runnable.class);method.setAccessible(true);method.invoke(config,jdbc,version,"fixture",body);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new RuntimeException(e);}}
 void all(){try{var method=KnowledgeDbConfig.class.getDeclaredMethod("applyVersionedMigrations",JdbcTemplate.class);method.setAccessible(true);method.invoke(config,jdbc);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new RuntimeException(e);}}
 @Test void failedDdlRollsBackAndRetryAppliesOnce(){
  assertThrows(IllegalStateException.class,()->migration(90,()->{jdbc.execute("CREATE TABLE recovery(value TEXT)");throw new IllegalStateException("simulated crash");}));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='recovery'",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
  migration(90,()->jdbc.execute("CREATE TABLE recovery(value TEXT)"));migration(90,()->fail("must not rerun"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
 }
 @Test void partialLegacyIndexesRecoverButWrongStructureFails(){
  jdbc.execute("CREATE TABLE planner_session(session_id TEXT PRIMARY KEY,owner_type TEXT,owner_id TEXT,idempotency_key TEXT)");
  all();jdbc.update("DELETE FROM schema_migration WHERE version IN(1,2)");all();assertEquals(8,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
  jdbc.update("DELETE FROM schema_migration WHERE version=1");jdbc.execute("DROP INDEX idx_trip_owner_updated");jdbc.execute("CREATE INDEX idx_trip_owner_updated ON trip(updated_at)");
  assertThrows(IllegalStateException.class,this::all);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM schema_migration WHERE version=1",Integer.class));
 }
 @Test void parallelAttemptsDoNotRunSameMigrationTwice() throws Exception{
  var pool=Executors.newFixedThreadPool(2);try{var gate=new CountDownLatch(1);Callable<Void> work=()->{gate.await();migration(91,()->jdbc.execute("CREATE TABLE parallel_fixture(value TEXT)"));return null;};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));}finally{pool.shutdownNow();}
 }
}

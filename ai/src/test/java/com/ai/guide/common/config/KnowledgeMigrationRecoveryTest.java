package com.ai.guide.common.config;

import com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.lang.reflect.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeMigrationRecoveryTest {
 SingleConnectionDataSource source;JdbcTemplate jdbc;KnowledgeSchemaInitializer config;
 @BeforeEach void setup(){source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);config=new KnowledgeSchemaInitializer();jdbc.execute("CREATE TABLE schema_migration(version INTEGER PRIMARY KEY,description TEXT,applied_at BIGINT)");}
 @AfterEach void close(){source.destroy();}
 void migration(int version,Runnable body){try{var method=KnowledgeSchemaInitializer.class.getDeclaredMethod("applyMigration",JdbcTemplate.class,int.class,String.class,Runnable.class);method.setAccessible(true);method.invoke(config,jdbc,version,"fixture",body);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new RuntimeException(e);}}
 void ensureColumn(String table,String definition){try{var method=KnowledgeSchemaInitializer.class.getDeclaredMethod("ensureColumn",JdbcTemplate.class,String.class,String.class);method.setAccessible(true);method.invoke(config,jdbc,table,definition);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException runtime)throw runtime;throw new RuntimeException(e.getCause());}catch(Exception e){throw new RuntimeException(e);}}
 void all(){try{var method=KnowledgeSchemaInitializer.class.getDeclaredMethod("applyVersionedMigrations",JdbcTemplate.class);method.setAccessible(true);method.invoke(config,jdbc);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}catch(Exception e){throw new RuntimeException(e);}}
 @Test void failedDdlRollsBackAndRetryAppliesOnce(){
  assertThrows(IllegalStateException.class,()->migration(90,()->{jdbc.execute("CREATE TABLE recovery(value TEXT)");throw new IllegalStateException("simulated crash");}));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='recovery'",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
  migration(90,()->jdbc.execute("CREATE TABLE recovery(value TEXT)"));migration(90,()->fail("must not rerun"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
 }
 @Test void failedColumnMigrationIsNotMarkedAppliedAndCanRetry(){
  jdbc.execute("CREATE TABLE migration_fixture(id INTEGER PRIMARY KEY)");
  assertThrows(IllegalStateException.class,()->migration(92,()->ensureColumn("migration_fixture","required_value INTEGER DEFAULT (")));
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM schema_migration WHERE version=92",Integer.class));
  migration(92,()->ensureColumn("migration_fixture","required_value INTEGER DEFAULT 0"));
  assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migration WHERE version=92",Integer.class));
  assertDoesNotThrow(()->ensureColumn("migration_fixture","required_value INTEGER DEFAULT 0"));
 }
 @Test void partialLegacyIndexesRecoverButWrongStructureFails(){
  jdbc.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,status TEXT)");
  jdbc.execute("CREATE TABLE planner_session(session_id TEXT PRIMARY KEY,owner_type TEXT,owner_id TEXT,idempotency_key TEXT)");
  all();jdbc.update("DELETE FROM schema_migration WHERE version IN(1,2)");all();assertEquals(11,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));
  jdbc.update("DELETE FROM schema_migration WHERE version=1");jdbc.execute("DROP INDEX idx_trip_owner_updated");jdbc.execute("CREATE INDEX idx_trip_owner_updated ON trip(updated_at)");
  assertThrows(IllegalStateException.class,this::all);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM schema_migration WHERE version=1",Integer.class));
 }
 @Test void parallelAttemptsDoNotRunSameMigrationTwice() throws Exception{
  var pool=Executors.newFixedThreadPool(2);try{var gate=new CountDownLatch(1);Callable<Void> work=()->{gate.await();migration(91,()->jdbc.execute("CREATE TABLE parallel_fixture(value TEXT)"));return null;};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM schema_migration",Integer.class));}finally{pool.shutdownNow();}
 }
}

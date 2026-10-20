package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;

import com.ai.guide.domain.admin.service.RuntimeSettingsPolicy;
import com.ai.guide.infrastructure.admin.repository.AppConfigRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeBackfillDeletionRegressionTest {
    @TempDir Path folder;
    JdbcTemplate jdbc;
    ScenicKnowledgePort provider;
    KnowledgeDocumentService service;
    @BeforeEach void setup() {
        var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+folder.resolve("fixture.sqlite"));
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,file_url TEXT,file_md5 TEXT,source_name TEXT,source_url TEXT NOT NULL DEFAULT '',index_request_mode VARCHAR(16) NOT NULL DEFAULT '',index_attempts INTEGER NOT NULL DEFAULT 0,index_next_attempt_at BIGINT NOT NULL DEFAULT 0,index_lease_token VARCHAR(64) NOT NULL DEFAULT '',index_lease_until BIGINT NOT NULL DEFAULT 0,status TEXT,vector_status TEXT,chunk_count INTEGER,created_by TEXT,created_at TEXT,updated_at TEXT,index_revision INTEGER NOT NULL DEFAULT 0,scope TEXT NOT NULL DEFAULT 'UNSPECIFIED',city TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE TABLE kb_backfill_source_lock(source_name TEXT PRIMARY KEY)");
        var config=new AppConfigRepository(jdbc);config.writeConfig("settings_",Map.of("knowledgeSyncEnabled",false));
        provider=mock(ScenicKnowledgePort.class);
        service=new KnowledgeDocumentService(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc),provider, noFileStorage(), Runnable::run, () -> {}, new com.ai.guide.infrastructure.rag.config.KnowledgeRuntimePolicyAdapter(new RuntimeSettingsPolicy(config,"test","zh",true,true,50), 200, 4096));
    }
    private Map<String,Object> summary(String source) {return Map.of("sourceName",source,"chunkCount",3,"content","旧正文","indexRevision",0L);}
    private com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort noFileStorage() {
        return new com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort() {
            public String save(String filename, byte[] bytes) { throw new UnsupportedOperationException(); }
            public String basename(String path) { return Path.of(path).getFileName().toString(); }
            public void deleteOwned(String path) {}
        };
    }
    @Test void documentContentAndDeletionInvalidateDerivedRerankResults() throws Exception {
        var cache = mock(com.ai.guide.domain.rag.gateway.RerankCacheInvalidationPort.class);
        var cachedService = new KnowledgeDocumentService(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), provider, noFileStorage(), Runnable::run, cache, new com.ai.guide.infrastructure.rag.config.KnowledgeRuntimePolicyAdapter(new RuntimeSettingsPolicy(new AppConfigRepository(jdbc),"test","zh",true,true,50), 200, 4096));
        var doc = cachedService.createDocument("资料", "other", "旧正文", "[]", null, "fixture");
        cachedService.updateDocument(doc.getId(), "新标题", null, null, null);
        verifyNoInteractions(cache);
        cachedService.updateDocument(doc.getId(), null, null, "新正文", null);
        verify(cache, times(1)).invalidateAll();
        cachedService.deleteDocument(doc.getId());
        verify(cache, times(2)).invalidateAll();
    }
    @Test void staleSnapshotCannotResurrectSourceArchivedByAnotherService() throws Exception {
        var doc=service.createDocument("资料","other","旧正文","[]",null,"fixture");
        var captured=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(provider.listSourceSummaries()).thenAnswer(call->{
            var snapshot=List.of(summary(doc.getSourceName()));captured.countDown();
            if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("timeout");return snapshot;
        });
        var pool=Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(service::backfillDocumentsFromVectors);
            assertTrue(captured.await(5,TimeUnit.SECONDS));
            com.ai.guide.testing.KnowledgeTestPorts.service(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), provider).deleteDocument(doc.getId());
            release.countDown();
            var result=pending.get(5,TimeUnit.SECONDS);
            assertEquals(0,result.get("created"));assertEquals(1,result.get("skipped"));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE status='active'",Integer.class));
            assertEquals("archived",service.getDocument(doc.getId()).getStatus());
            verify(provider).deleteSource(doc.getSourceName());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void archivedLegacyFileSourceIsRespectedAndExplicitReimportRemainsAllowed() throws Exception {
        var old=service.createDocument("资料","other","旧正文","[]",null,"fixture");
        jdbc.update("UPDATE kb_document SET status='archived',source_name='',file_url=? WHERE id=?",folder.resolve("legacy.pdf").toString(),old.getId());
        when(provider.listSourceSummaries()).thenReturn(List.of(summary("legacy.pdf")));
        assertEquals(1,service.backfillDocumentsFromVectors().get("skipped"));
        var imported=service.createDocument("资料","other","管理员明确重新导入的正文","[]",null,"fixture");
        assertNotEquals(old.getSourceName(),imported.getSourceName());
        when(provider.listSourceSummaries()).thenReturn(List.of(summary("legacy.pdf"),summary(imported.getSourceName())));
        var result=service.backfillDocumentsFromVectors();
        assertEquals(0,result.get("created"));assertEquals(1,result.get("updated"));assertEquals(1,result.get("skipped"));
        assertEquals("管理员明确重新导入的正文",service.getDocument(imported.getId()).getContent());
    }
    @Test void archivedTombstoneCannotPassConditionalInsert() throws Exception {
        var doc=service.createDocument("资料","other","旧正文","[]",null,"fixture");
        when(provider.listSourceSummaries()).thenReturn(List.of(summary(doc.getSourceName())));
        service.deleteDocument(doc.getId());
        var result=service.backfillDocumentsFromVectors();
        assertEquals(0,result.get("created"));assertEquals(1,result.get("skipped"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE status='active'",Integer.class));
    }
    @Test void liveAndUntrackedSourcesBackfillNormallyAndEmptySnapshotDoesNothing() throws Exception {
        var live=service.createDocument("资料","other","正文","[]",null,"fixture");
        when(provider.listSourceSummaries()).thenReturn(List.of(summary(live.getSourceName()),summary("legacy-live")));
        var result=service.backfillDocumentsFromVectors();
        assertEquals(1,result.get("created"));assertEquals(1,result.get("updated"));assertEquals(0,result.get("skipped"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE status='active'",Integer.class));
        when(provider.listSourceSummaries()).thenReturn(List.of());
        assertEquals(0,service.backfillDocumentsFromVectors().get("created"));
    }
}

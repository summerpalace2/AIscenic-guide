package com.ai.guide.domain.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalRankingBoundaryTest {
    private JdbcTemplate database(java.sql.Connection connection) {
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TABLE kb_document(id TEXT,title TEXT,content TEXT,tags TEXT,source_name TEXT,source_url TEXT NOT NULL DEFAULT '',status TEXT,updated_at INTEGER,scope TEXT NOT NULL DEFAULT 'GLOBAL',city TEXT NOT NULL DEFAULT '')");
        return jdbc;
    }
    private void document(JdbcTemplate jdbc, String id, String title, String text, int date) {
        jdbc.update("INSERT INTO kb_document(id,title,content,tags,source_name,status,updated_at) VALUES(?,?,?,?,?,'active',?)", id,title,text,"校园","isolated",date);
    }
    @Test void exactEntitySurvivesNewerGenericDocuments() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var jdbc=database(connection);
            document(jdbc,"target","蒙民伟理科馆开放时间","蒙民伟理科馆开放时间09:00-17:00",1);
            for(int i=0;i<210;i++) document(jdbc,"generic"+i,"其他场馆开放时间","开放时间见现场说明",100+i);
            var result=com.ai.guide.testing.KnowledgeTestPorts.service(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), com.ai.guide.testing.KnowledgeTestPorts.noIndex()).searchLocalKnowledge("蒙民伟理科馆 开放时间",5);
            assertEquals("target",result.getFirst().get("id"));
        }
    }
    @Test void tailEvidenceIsScoredBeforeTopFiveSelection() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var jdbc=database(connection);
            document(jdbc,"target","校园参观指南","开放时间是常见问题。"+"背景介绍".repeat(1600)+"蒙民伟理科馆：开放时间09:00-17:00，参观需要预约",1);
            for(int i=0;i<6;i++) document(jdbc,"generic"+i,"其他场馆开放时间","开放时间见现场说明",100+i);
            var result=com.ai.guide.testing.KnowledgeTestPorts.service(new com.ai.guide.infrastructure.rag.repository.JdbcKnowledgeDocumentStoreAdapter(jdbc), com.ai.guide.testing.KnowledgeTestPorts.noIndex()).searchLocalKnowledge("开放时间 蒙民伟理科馆",5);
            var target=result.stream().filter(r->"target".equals(r.get("id"))).findFirst().orElseThrow();
            assertTrue(target.get("content").toString().contains("09:00-17:00"));
            assertTrue(target.get("content").toString().length()<=4096);
        }
    }
}

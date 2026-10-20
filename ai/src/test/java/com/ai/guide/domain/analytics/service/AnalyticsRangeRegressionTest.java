package com.ai.guide.domain.analytics.service;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import com.ai.guide.infrastructure.analytics.repository.JdbcAnalyticsRepositoryAdapter;
import com.ai.guide.domain.analytics.gateway.AnalyticsRepositoryPort;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AnalyticsRangeRegressionTest {
 SingleConnectionDataSource source;JdbcTemplate jdbc;AnalyticsService service;
 @BeforeEach void setup(){source=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);jdbc=new JdbcTemplate(source);
  jdbc.execute("CREATE TABLE service_log(id TEXT,session_id TEXT,question TEXT,emotion TEXT,intent TEXT,response_time_ms INTEGER,created_at TEXT)");
  service=new AnalyticsService(new JdbcAnalyticsRepositoryAdapter(jdbc),mock(SentimentService.class),mock(EmotionAnalysisService.class));
 }
 @AfterEach void close(){source.destroy();}
 void log(String date,String emotion){jdbc.update("INSERT INTO service_log VALUES(?, 's', 'q', ?, '', 1, ?)",UUID.randomUUID().toString(),emotion,date);}
 @Test void endDateIncludesEveningButExcludesNextMidnight(){
  log("2026-10-06 00:00:00","POSITIVE");log("2026-10-06 23:59:59","NEGATIVE");log("2026-10-07 00:00:00","NEUTRAL");
  assertEquals(2,service.serviceCount("2026-10-06","2026-10-06","day").get(0).get("count"));
  var row=service.sentimentTrend("2026-10-06","2026-10-06","day").get(0);assertEquals(.5,row.get("positive"));assertEquals(.5,row.get("negative"));
  assertEquals(2,service.serviceCount("2026-10-06","2026-10-06","hour").size());
 }
 @Test void weekAndMonthAreDistinctCalendarBuckets(){
  log("2026-10-05 10:00:00","POSITIVE");log("2026-10-11 20:00:00","NEGATIVE");log("2026-10-12 10:00:00","NEUTRAL");
  var weeks=service.serviceCount(null,null,"week");assertEquals(2,weeks.size());assertEquals("2026-10-05",weeks.get(0).get("period"));assertEquals(2,weeks.get(0).get("count"));
  assertEquals(1,service.serviceCount(null,null,"month").size());
  assertEquals(.5,service.sentimentTrend(null,null,"week").get(0).get("positive"));
 }
 @Test void malformedAndReversedRangesReject(){
  assertThrows(IllegalArgumentException.class,()->service.serviceCount("2026-10-07","2026-10-06","day"));
  assertThrows(IllegalArgumentException.class,()->service.sentimentTrend("invalid",null,"day"));
  assertThrows(IllegalArgumentException.class,()->service.serviceCount(null,null,"year"));
  assertThrows(IllegalArgumentException.class,()->service.hotQuestions("invalid",10));
  assertThrows(IllegalArgumentException.class,()->service.hotQuestions("today",0));
 }
 @Test void savedPdfHasNoDependencyOnCurrentServiceLogs(){
  var dependency=mock(AnalyticsRepositoryPort.class);var exporter=new PdfExportService(new com.ai.guide.infrastructure.analytics.pdf.OpenPdfAnalyticsReportRenderer(dependency));
  byte[] pdf=exporter.exportStoredReport(Map.of("title","snapshot","type","week","periodStart","2026-10-01","periodEnd","2026-10-08","data","{\"totalServices\":73}"));
  assertTrue(pdf.length>100);assertEquals("%PDF",new String(pdf,0,4,java.nio.charset.StandardCharsets.US_ASCII));verifyNoInteractions(dependency);
 }
}

package com.ai.guide.domain.memory.service;

import com.ai.guide.infrastructure.memory.repository.JdbcMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;
class MemoryPaginationRegressionTest {
 @Test void allConfirmedSourcesAreReachableAndEqualTimestampsHaveStableOrder(){
  var ds=new SingleConnectionDataSource("jdbc:sqlite::memory:",true);
  try{
   var jdbc=new JdbcTemplate(ds);
   jdbc.execute("CREATE TABLE travel_memory(id TEXT,user_id TEXT,category TEXT,content TEXT,source_type TEXT,source_ref TEXT,status TEXT,confidence REAL,created_at INTEGER,updated_at INTEGER)");
   for(int i=0;i<151;i++)jdbc.update("INSERT INTO travel_memory VALUES(?, 'owner', 'CUSTOM', ?, 'USER_EDIT', '', 'CONFIRMED', 1, 1, 1)",String.format("mem-%03d",i),"偏好条目"+i);
   var service=com.ai.guide.testing.MemoryTestFixtures.service(new JdbcMemoryRepository(jdbc));var first=service.listPage("owner",100,0);var second=service.listPage("owner",100,100);
   assertEquals(100,first.size());assertEquals(51,second.size());assertEquals("mem-150",first.get(0).id());assertEquals("mem-000",second.get(50).id());
   assertTrue(service.listPage("owner",100,200).isEmpty());assertTrue(service.listPage("other-owner",100,0).isEmpty());
   assertEquals(50,service.list("owner").size());
   assertThrows(IllegalArgumentException.class,()->service.listPage("owner",101,0));
   assertThrows(IllegalArgumentException.class,()->service.listPage("owner",0,0));
   assertThrows(IllegalArgumentException.class,()->service.listPage("owner",100,-1));
  }finally{ds.destroy();}
 }
}

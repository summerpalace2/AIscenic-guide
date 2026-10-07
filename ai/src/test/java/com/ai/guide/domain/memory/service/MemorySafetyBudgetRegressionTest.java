package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.planner.service.taxonomy.TaxonomyProvider;
import com.ai.guide.infrastructure.preferences.repository.PreferencesRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class MemorySafetyBudgetRegressionTest {
    Connection connection;
    JdbcTemplate jdbc;
    TravelMemoryService memory;
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("CREATE TABLE travel_memory(id TEXT PRIMARY KEY,user_id TEXT,category TEXT,content TEXT,source_type TEXT,source_ref TEXT,status TEXT,confidence REAL,created_at INTEGER,updated_at INTEGER)");
        jdbc.execute("CREATE TABLE user_preferences(user_id TEXT PRIMARY KEY,schema_version INTEGER,revision INTEGER,interests_json TEXT,walking_tolerance TEXT,budget_json TEXT,companions TEXT,transport_preference TEXT,diet_preference TEXT,stay_area TEXT,legacy_metadata_json TEXT,created_at INTEGER,updated_at INTEGER)");
        var preferences=new PreferencesService(new PreferencesRepository(jdbc,new ObjectMapper()),(u,b,a,o)->{});
        memory=new TravelMemoryService(jdbc,preferences,null,new ObjectMapper(),new MemoryPreferenceProjector(new TaxonomyProvider()));
    }
    @AfterEach void close() throws Exception {connection.close();}
    private void manyOptionalPreferences() {
        for(int i=0;i<14;i++) memory.confirm("owner","摄影主题"+i+"："+"城市风貌摄影兴趣描述".repeat(55),"INTEREST","USER_EDIT");
        memory.confirm("owner","其他摄影偏好："+"城市日落摄影兴趣".repeat(12),"INTEREST","USER_EDIT");
    }
    @Test void oldConfirmedEnglishAllergySurvivesDefaultBudgetPressure() {
        String constraint="Allergic to peanuts. Never eat peanuts. Severe reaction to peanuts; avoid peanut oil and all dishes containing peanuts. Always tell the restaurant about my allergy and verify ingredients before recommending meals.";
        var old=memory.confirm("owner",constraint,"DIET","USER_EDIT");
        jdbc.update("UPDATE travel_memory SET updated_at=1 WHERE id=?",old.id());
        manyOptionalPreferences();
        assertTrue(memory.synthesizePrependPrompt("owner",false).length()>6000);
        String context=memory.promptContext("owner");
        assertTrue(context.contains(constraint));
        assertTrue(context.contains("未注入"));
        assertTrue(context.length()<=6000);
    }
    @Test void unknownLanguageAndQualifiersArePreservedRatherThanCollapsedToSweetFacet() {
        String constraint="喜欢甜食; Alergia grave al maní; nunca consumir maní ni aceite de maní.";
        memory.confirm("owner",constraint,"DIET","USER_EDIT");
        manyOptionalPreferences();
        assertTrue(memory.promptContext("owner").contains(constraint));
    }
    @Test void unknownDietOverflowIsVisibleWithoutTruncatingPersistedMemory() {
        String constraint=("Dietary restriction: "+"ingredient restrictions ".repeat(240)).trim();
        var first=memory.confirm("owner",constraint,"DIET","USER_EDIT");
        memory.confirm("owner","Additional restriction: "+"other ingredients ".repeat(100),"DIET","USER_EDIT");
        assertThrows(TravelMemoryService.MemoryContextOverflowException.class,()->memory.promptContext("owner"));
        assertEquals(constraint,jdbc.queryForObject("SELECT content FROM travel_memory WHERE id=?",String.class,first.id()));
    }
    @Test void recognisedPureTastePreferencesRemainOptionalAndCanonical() {
        for(String word:new String[]{"甜食","甜点","糖水"})memory.confirm("owner",word.repeat(1000),"DIET","USER_EDIT");
        manyOptionalPreferences();
        String context=assertDoesNotThrow(()->memory.promptContext("owner"));
        assertTrue(context.contains("喜欢甜口、甜品与糖水"));
        assertTrue(context.length()<=6000);
    }
}

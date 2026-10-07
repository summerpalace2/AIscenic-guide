package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.infrastructure.preferences.repository.PreferencesRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPromptLifecycleRegressionTest {
    Connection connection;
    PreferencesService preferences;
    TravelMemoryService memory;
    @BeforeEach void setup() throws Exception {
        connection=DriverManager.getConnection("jdbc:sqlite::memory:");
        var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("CREATE TABLE travel_memory(id TEXT PRIMARY KEY,user_id TEXT,category TEXT,content TEXT,source_type TEXT,source_ref TEXT,status TEXT,confidence REAL,created_at INTEGER,updated_at INTEGER)");
        jdbc.execute("CREATE TABLE user_preferences(user_id TEXT PRIMARY KEY,schema_version INTEGER,revision INTEGER,interests_json TEXT,walking_tolerance TEXT,budget_json TEXT,companions TEXT,transport_preference TEXT,diet_preference TEXT,stay_area TEXT,legacy_metadata_json TEXT,created_at INTEGER,updated_at INTEGER)");
        preferences=new PreferencesService(new PreferencesRepository(jdbc,new ObjectMapper()),(u,b,a,o)->{});
        memory=new TravelMemoryService(jdbc,preferences);
    }
    @AfterEach void close() throws Exception { connection.close(); }
    @Test void generatedCopyDoesNotReviveDeletedMemoryAndManualNoteSurvivesResynthesis() {
        var sweet=memory.confirm("owner","偏好甜品糖水","DIET","USER_EDIT");
        String generated=memory.synthesizePrependPrompt("owner",false);
        memory.saveUserCustomizedPrompt("owner",generated+"\n酒店希望安静。");
        assertEquals("酒店希望安静。",preferences.get("owner").legacyMetadata().get("userCustomizedPrompt"));
        memory.delete("owner",sweet.id());
        assertFalse(memory.promptContext("owner").contains("甜品糖水"));
        assertTrue(memory.synthesizePrependPrompt("owner",true).contains("酒店希望安静"));
        memory.confirm("owner","偏好人文历史","INTEREST","USER_EDIT");
        assertFalse(memory.promptContext("owner").contains("甜品糖水"));
        assertTrue(memory.promptContext("owner").contains("酒店希望安静"));
    }
    @Test void manualNotesCanBeClearedWithoutDeletingGeneratedPreferences() {
        memory.confirm("owner","偏好人文历史","INTEREST","USER_EDIT");
        memory.saveUserCustomizedPrompt("owner","酒店希望安静。");
        memory.saveUserCustomizedPrompt("owner","");
        assertEquals("",preferences.get("owner").legacyMetadata().get("userCustomizedPrompt"));
        assertFalse(memory.promptContext("owner").contains("酒店希望安静"));
        assertTrue(memory.promptContext("owner").contains("人文历史"));
    }
    private void seedLegacy(String value) {
        preferences.merge("owner",new UserPreferencesPatch(null,null,null,null,null,null,null,
                Map.of("userCustomizedPrompt",value,"profilePrependPrompt",value),null),null);
    }
    @Test void ambiguousLegacyFullProfileIsArchivedAndVisibleForConfirmation() {
        String old="【用户专属旅行偏好画像】 • 饮食喜好:偏好甜品糖水; 酒店希望安静。";
        seedLegacy(old);
        var details=memory.promptDetails("owner");
        assertEquals(old,details.get("legacyUserCustomizedPrompt"));
        assertEquals(true,details.get("manualPromptNeedsReview"));
        assertEquals("",details.get("userCustomizedPrompt"));
        assertFalse(memory.promptContext("owner").contains("甜品糖水"));
        memory.saveUserCustomizedPrompt("owner","酒店希望安静。");
        assertEquals(false,memory.promptDetails("owner").get("manualPromptNeedsReview"));
        assertEquals(old,preferences.get("owner").legacyMetadata().get("legacyUserCustomizedPrompt"));
    }
    @Test void legacyPlainManualNotesRemainActiveAndReadsDoNotBumpRevision() {
        seedLegacy("不能吃花生，酒店希望安静。");
        assertTrue(memory.promptContext("owner").contains("不能吃花生"));
        long revision=preferences.get("owner").revision();
        memory.promptDetails("owner");memory.promptDetails("owner");
        assertEquals(revision,preferences.get("owner").revision());
        assertTrue(memory.synthesizePrependPrompt("owner",true).contains("不能吃花生"));
    }
}

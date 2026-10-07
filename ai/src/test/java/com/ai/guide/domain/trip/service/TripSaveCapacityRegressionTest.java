package com.ai.guide.domain.trip.service;

import com.ai.guide.adapter.trip.web.TripController;
import com.ai.guide.application.trip.TripApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerSession;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.infrastructure.trip.pdf.TripPlanPdfService;
import com.ai.guide.infrastructure.trip.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Current production services with isolated SQLite, no application/real provider startup. */
class TripSaveCapacityRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private Connection connection;
    private JdbcTemplate jdbc;
    private TripService trips;

    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TABLE trip(id TEXT PRIMARY KEY,owner_id TEXT,title TEXT,status TEXT,current_version INTEGER,created_at INTEGER,updated_at INTEGER)");
        jdbc.execute("CREATE TABLE trip_version(id TEXT PRIMARY KEY,trip_id TEXT,version_number INTEGER,snapshot TEXT,change_reason TEXT,created_at INTEGER,created_by TEXT,UNIQUE(trip_id,version_number))");
        jdbc.execute("CREATE TABLE trip_idempotency(owner_id TEXT,operation TEXT,idempotency_key TEXT,request_fingerprint TEXT,trip_id TEXT,version_number INTEGER,response_json TEXT,created_at INTEGER,PRIMARY KEY(owner_id,operation,idempotency_key))");
        trips = new TripService(new TripRepository(jdbc, mapper), null, mapper);
        UserContext.set("fixture-owner", "fixture", "USER");
    }

    @AfterEach void cleanup() throws Exception { UserContext.clear(); connection.close(); }

    private Map<String, Object> plan() {
        return new LinkedHashMap<>(Map.of("title", "旧标题", "days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "one", "name", "fixture"))))));
    }

    private StoredSession session(Map<String, Object> plan, int version) {
        return new StoredSession("fixture-session", "USER", "fixture-owner", "", "", "", Map.of(),
                AppliedPreferencesSnapshot.empty(), plan, version, 1, "DRAFT", 1, 1);
    }

    private PlannerService planner(PlannerSessionRepositoryPort repository) {
        return new PlannerService(null, null, repository, trips, mapper, null, null, null);
    }

    @Test void repeatedPlannerSaveBeforeAndAfterIdLinkingStaysAtVersionOne() {
        var repository = mock(PlannerSessionRepositoryPort.class);
        var active = new AtomicReference<>(session(plan(), 1));
        when(repository.find(anyString(), eq("USER"), eq("fixture-owner"), anyString())).thenAnswer(call -> active.get());
        when(repository.linkFormalTrip(any(), anyMap())).thenAnswer(call -> {
            active.set(session(new LinkedHashMap<>(call.getArgument(1)), active.get().currentVersion()));
            return true;
        });
        var planner = planner(repository);
        var first = planner.save("fixture-session", "");
        var second = planner.save("fixture-session", "");
        var third = planner.save("fixture-session", "");
        assertEquals(201, first.status());
        assertEquals(200, second.status());
        assertEquals(200, third.status());
        assertEquals(first.body().get("tripId"), third.body().get("tripId"));
        assertEquals(1, third.body().get("currentVersion"));
        assertEquals(1, trips.versions("fixture-owner", String.valueOf(first.body().get("tripId"))).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));

        Map<String, Object> changed = new LinkedHashMap<>(active.get().trip());
        changed.put("days", List.of(Map.of("day", 1, "stops", List.of(Map.of("id", "two", "name", "changed")))));
        active.set(session(changed, 2));
        assertEquals(2, planner.save("fixture-session", "").body().get("currentVersion"));
        assertEquals(2, planner.save("fixture-session", "").body().get("currentVersion"));
        assertEquals(2, trips.versions("fixture-owner", String.valueOf(first.body().get("tripId"))).size());
    }

    @Test void sameSaveKeyWithDifferentBusinessContentConflictsWithoutChangingTrip() {
        var snapshot = plan();
        var first = trips.savePlannerSnapshot("fixture-owner", snapshot, "save-key");
        snapshot.put("formalTripId", first.trip().id());
        snapshot.put("sourceTripVersion", first.trip().currentVersion());
        snapshot.put("title", "不同业务标题");
        assertEquals(409, trips.savePlannerSnapshot("fixture-owner", snapshot, "save-key").status());
        assertEquals(1, trips.get("fixture-owner", first.trip().id()).currentVersion());
    }

    @Test void restoredMetadataDoesNotPretendAStaleDraftHasBusinessChangesToRebase() {
        var initial = plan();
        initial.put("appliedPreferences", AppliedPreferencesSnapshot.empty().asMap());
        var first = trips.savePlannerSnapshot("fixture-owner", initial, "initial");
        var restored = new LinkedHashMap<>(first.trip().plan());
        restored.put("sourceTripVersion", 1);
        restored.put("plannerSessionRestoreSource", "RESTORED_FROM_TRIP");
        restored.put("plannerSessionRestoreDegradation", "APPLIED_PREFERENCES_UNAVAILABLE");
        // The repository decodes revision=0 as Integer; PlannerService writes its Long(0)
        // snapshot on save. Equal JSON values must not count as an independent edit.
        var restoredPreferences = new LinkedHashMap<>(AppliedPreferencesSnapshot.empty().asMap());
        restoredPreferences.put("preferenceRevision", 0L);
        restored.put("appliedPreferences", restoredPreferences);
        var external = new LinkedHashMap<>(first.trip().plan());
        external.put("title", "外部新标题");
        assertEquals(2, trips.update("fixture-owner", first.trip().id(), Map.of("plan", external, "expectedVersion", 1)).trip().currentVersion());
        var stale = trips.savePlannerSnapshot("fixture-owner", restored, "restore-save");
        assertEquals(409, stale.status());
        assertEquals("外部新标题", trips.get("fixture-owner", first.trip().id()).title());
        assertEquals(2, trips.get("fixture-owner", first.trip().id()).currentVersion());
        restored.put("title", "外部新标题");
        assertEquals("NOOP", trips.savePlannerSnapshot("fixture-owner", restored, "same-business").operationStatus());
    }

    @Test void metadataOnlySaveWithNewKeyIsNoopButCustomFieldsAndPreferencesStillCount() {
        var first = trips.savePlannerSnapshot("fixture-owner", plan(), "save-one");
        var linked = new LinkedHashMap<>(first.trip().plan());
        linked.put("sourceTripId", first.trip().id());
        linked.put("sourceTripVersion", 1);
        linked.put("plannerSessionId", "fixture-session");
        linked.put("plannerVersion", 7);
        linked.put("version", 7);
        linked.put("status", "SAVED");
        assertEquals("NOOP", trips.savePlannerSnapshot("fixture-owner", linked, "save-two").operationStatus());
        linked.put("sourceNotes", "用户自定义字段必须保留");
        linked.put("appliedPreferences", Map.of("diet", "不能吃花生"));
        var changed = trips.savePlannerSnapshot("fixture-owner", linked, "save-three");
        assertEquals("UPDATED", changed.operationStatus());
        assertEquals(2, changed.trip().currentVersion());
        assertEquals("用户自定义字段必须保留", changed.trip().plan().get("sourceNotes"));
        assertEquals(Map.of("diet", "不能吃花生"), changed.trip().plan().get("appliedPreferences"));
    }

    @Test void titleSynchronizesStoredSnapshotsAndLegacyExportRestoreUsesAggregateTitle() throws Exception {
        Trip created = trips.create("fixture-owner", Map.of("plan", plan(), "title", "创建标题")).trip();
        assertEquals("创建标题", created.plan().get("title"));
        var updated = trips.update("fixture-owner", created.id(), Map.of("plan", created.plan(), "title", "更新标题", "expectedVersion", 1)).trip();
        assertEquals("更新标题", updated.plan().get("title"));
        var historical = new LinkedHashMap<>(updated.plan());
        historical.put("title", "历史旧快照标题");
        jdbc.update("UPDATE trip_version SET snapshot=? WHERE trip_id=? AND version_number=2", mapper.writeValueAsString(historical), updated.id());
        var pdfData = new AtomicReference<Map<String, Object>>();
        var app = new TripApplicationService(trips, value -> { pdfData.set(value); return new byte[0]; });
        assertEquals("更新标题", app.exportTripPdf("fixture-owner", updated.id()).title());
        assertEquals("更新标题", pdfData.get().get("title"));

        var repository = mock(PlannerSessionRepositoryPort.class);
        var restored = new AtomicReference<Map<String, Object>>();
        when(repository.create(anyString(), anyString(), anyString(), anyMap(), any(), anyMap(), anyString(), anyString())).thenAnswer(call -> {
            Map<String, Object> incoming = call.getArgument(5); restored.set(incoming);
            return CreateResult.created(session(incoming, 1), "");
        });
        when(repository.toProjection(any(), anyString())).thenAnswer(call -> {
            StoredSession stored = call.getArgument(0);
            return new PlannerSession(stored.sessionId(), stored.ownerType(), stored.ownerId(), stored.currentVersion(), stored.syncRevision(), stored.trip(), stored.constraints(), stored.appliedPreferences(), "");
        });
        assertEquals(200, planner(repository).openFormalTrip(updated.id()).status());
        assertEquals("更新标题", restored.get().get("title"));
    }

    @Test void stalePlannerSaveRebasesIndependentCustomFieldsAndAppliedPreferences() {
        Trip base = trips.create("fixture-owner", Map.of("plan", plan())).trip();
        var otherEdit = new LinkedHashMap<>(base.plan());
        otherEdit.put("subtitle", "另一个页面的独立修改");
        assertEquals(2, trips.update("fixture-owner", base.id(), Map.of("plan", otherEdit, "expectedVersion", 1)).trip().currentVersion());
        var incoming = new LinkedHashMap<>(base.plan());
        incoming.put("sourceTripVersion", 1);
        incoming.put("sourceNotes", "用户新增的独立说明");
        incoming.put("appliedPreferences", Map.of("diet", "海鲜过敏"));
        var saved = trips.savePlannerSnapshot("fixture-owner", incoming, "stale-independent");
        assertEquals(200, saved.status());
        assertEquals(3, saved.trip().currentVersion());
        assertEquals("另一个页面的独立修改", saved.trip().plan().get("subtitle"));
        assertEquals("用户新增的独立说明", saved.trip().plan().get("sourceNotes"));
        assertEquals(Map.of("diet", "海鲜过敏"), saved.trip().plan().get("appliedPreferences"));
    }

    @Test void oversizeWritesAreRejectedWithoutPersistingAndLimitsAreConfigurable() {
        var many = plan();
        many.put("days", java.util.Collections.nCopies(31, Map.of("stops", List.of())));
        assertEquals(400, trips.create("fixture-owner", Map.of("plan", many)).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));
        var limits = new TripPlanLimits(mapper);
        ReflectionTestUtils.setField(limits, "maxDays", 35);
        trips.setPlanLimits(limits);
        assertEquals(201, trips.create("fixture-owner", Map.of("plan", many)).status());
    }

    @Test void dailyAndTotalStopLimitsBothApply() {
        var daily = plan();
        daily.put("days", List.of(Map.of("stops", java.util.Collections.nCopies(41, Map.of("name", "one")))));
        assertEquals(400, trips.create("fixture-owner", Map.of("plan", daily)).status());
        var days = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < 13; index++) days.add(Map.of("stops", java.util.Collections.nCopies(40, Map.of("name", "one"))));
        var total = plan(); total.put("days", days);
        assertEquals(400, trips.create("fixture-owner", Map.of("plan", total)).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));
    }

    @Test void textAndEncodedJsonBudgetsAreIndependentAndApplyToUpdateAndBridge() {
        Trip first = trips.create("fixture-owner", Map.of("plan", plan())).trip();
        var longText = new LinkedHashMap<>(first.plan()); longText.put("detail", "字".repeat(20001));
        assertEquals(400, trips.update("fixture-owner", first.id(), Map.of("plan", longText, "expectedVersion", 1)).status());
        assertEquals(400, trips.savePlannerSnapshot("fixture-owner", longText, "too-large").status());
        assertEquals(1, trips.get("fixture-owner", first.id()).currentVersion());
        var limits = new TripPlanLimits(mapper);
        ReflectionTestUtils.setField(limits, "maxJsonBytes", 200L);
        trips.setPlanLimits(limits);
        var utf8 = plan(); utf8.put("notes", List.of("字".repeat(40), "字".repeat(40)));
        assertEquals(400, trips.create("fixture-owner", Map.of("plan", utf8)).status());
    }

    @Test void historicalOversizePdfIsRejectedBeforeRenderingAndHttpReturns400() throws Exception {
        var oversized = plan(); oversized.put("days", java.util.Collections.nCopies(31, Map.of("stops", List.of())));
        var pdf = new TripPlanPdfService();
        assertThrows(IllegalArgumentException.class, () -> pdf.export(oversized));
        var service = mock(TripService.class);
        when(service.get("fixture-owner", "large")).thenReturn(new Trip("large", "fixture-owner", "large", "ACTIVE", 1, 1, 1, oversized));
        var controller = new TripController(new TripApplicationService(service, pdf));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/ai/trips/large/export")).andExpect(status().isBadRequest());
    }
}

package com.ai.guide.domain.planner.model;


import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppliedPreferencesSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void canonicalSnapshotRoundTripsWithStableFingerprint() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("interests", List.of("夜景", "人文"));
        fields.put("walkingTolerance", "LOW");
        fields.put("budget", Map.of("level", "LIMITED"));
        fields.put("transportPreference", "TAXI");

        AppliedPreferencesSnapshot snapshot = AppliedPreferencesSnapshot.create(
                fields, 7, PreferencesSchema.CURRENT_SCHEMA_VERSION,
                PreferencesSchema.NORMALIZATION_VERSION, objectMapper);
        AppliedPreferencesSnapshot restored = AppliedPreferencesSnapshot.fromJson(
                objectMapper.writeValueAsString(snapshot.asMap()), objectMapper);

        assertEquals(snapshot.appliedFields(), restored.appliedFields());
        assertEquals(snapshot.preferenceRevision(), restored.preferenceRevision());
        assertEquals(snapshot.preferenceSchemaVersion(), restored.preferenceSchemaVersion());
        assertEquals(snapshot.normalizationVersion(), restored.normalizationVersion());
        assertEquals(snapshot.fingerprint(), restored.fingerprint());
        assertTrue(snapshot.fingerprint().matches("[0-9a-f]{64}"));
    }

    @Test
    void legacyOrTamperedValuesBecomeSafeEmptySnapshots() throws Exception {
        AppliedPreferencesSnapshot empty = AppliedPreferencesSnapshot.empty();
        AppliedPreferencesSnapshot legacy = AppliedPreferencesSnapshot.fromJson("[]", objectMapper);
        AppliedPreferencesSnapshot malformed = AppliedPreferencesSnapshot.fromJson("{not-json", objectMapper);
        String tampered = objectMapper.writeValueAsString(Map.of(
                "appliedFields", Map.of("walkingTolerance", "LOW"),
                "preferenceRevision", 1,
                "preferenceSchemaVersion", PreferencesSchema.CURRENT_SCHEMA_VERSION,
                "normalizationVersion", PreferencesSchema.NORMALIZATION_VERSION,
                "fingerprint", "0".repeat(64)));
        AppliedPreferencesSnapshot invalid = AppliedPreferencesSnapshot.fromJson(tampered, objectMapper);

        assertTrue(legacy.isEmpty());
        assertTrue(malformed.isEmpty());
        assertTrue(invalid.isEmpty());
        assertEquals(empty.fingerprint(), legacy.fingerprint());
        assertEquals(empty.fingerprint(), malformed.fingerprint());
        assertEquals(empty.fingerprint(), invalid.fingerprint());
        assertNotEquals("0".repeat(64), empty.fingerprint());
    }

    @Test
    void unknownCanonicalFieldsNeverEnterSnapshot() {
        AppliedPreferencesSnapshot snapshot = AppliedPreferencesSnapshot.create(
                Map.of("legacyMetadata", Map.of("walkingTolerance", "LOW"),
                        "walkingTolerance", "LOW"),
                1, PreferencesSchema.CURRENT_SCHEMA_VERSION,
                PreferencesSchema.NORMALIZATION_VERSION, objectMapper);

        assertEquals(Map.of("walkingTolerance", "LOW"), snapshot.appliedFields());
    }
}

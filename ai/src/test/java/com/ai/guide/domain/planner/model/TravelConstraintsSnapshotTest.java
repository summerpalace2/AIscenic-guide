package com.ai.guide.domain.planner.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TravelConstraintsSnapshotTest {
    @Test void strictMapperReadsItsOwnSnapshotWithoutLosingHardConstraintsOrProvenance() {
        var source = TravelConstraints.builder().destination("测试城").mustVisit(List.of("必去馆"))
                .avoid(List.of("台阶")) .arrivalAt("11:00").departureAt("18:00")
                .shadowSemanticPreferences(java.util.Map.of("criticalConstraints", List.of("坚果过敏")))
                .conflicts(List.of(new ConstraintConflict("walkingTolerance", "TEST", "保留来源", "低", ConstraintOrigin.USER_TEXT,
                        "高", ConstraintOrigin.PREFERENCE))).build();
        source.markOrigin("avoid", ConstraintOrigin.USER_CONFIRMED);
        var restored = TravelConstraints.fromSnapshot(new ObjectMapper(), source.asMap());
        assertEquals(source.getMustVisit(), restored.getMustVisit());
        assertEquals(source.getAvoid(), restored.getAvoid());
        assertEquals("18:00", restored.getDepartureAt());
        assertEquals(source.getShadowSemanticPreferences(), restored.getShadowSemanticPreferences());
        assertEquals(ConstraintOrigin.USER_CONFIRMED, restored.originOf("avoid"));
        assertEquals(ConstraintOrigin.USER_TEXT, restored.getConflicts().get(0).primaryOrigin());
        assertFalse(restored.canPreferenceOverride("avoid"));
    }
}

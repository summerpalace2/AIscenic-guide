package com.ai.guide.domain.preferences.service;

import com.ai.guide.domain.preferences.api.UserPreferencesPatch;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.repository.PreferenceAuditRepository;
import com.ai.guide.domain.preferences.repository.PreferencesRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferencesServiceTest {

    @Test
    void mergeCreatesTypedOwnerBoundPreferenceAndAudit() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        when(repository.findByUserId("java-user-1")).thenReturn(Optional.empty());
        PreferencesService service = new PreferencesService(repository, audit);

        UserPreferencesPatch patch = new UserPreferencesPatch(
                List.of("城市", "城市", "夜景"),
                UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                UserPreferences.DietPreference.NONE,
                "解放碑",
                Map.of(),
                0L);

        PreferencesService.MutationResult result = service.merge("java-user-1", patch, null);

        assertTrue(result.created());
        assertEquals(1, result.preferences().revision());
        assertEquals(List.of("城市", "夜景"), result.preferences().interests());
        assertEquals(UserPreferences.WalkingTolerance.LOW, result.preferences().walkingTolerance());
        assertEquals(UserPreferences.CompanionPreference.PARENTS, result.preferences().companions());
        verify(repository).insert(any(UserPreferences.class));
        verify(audit).record(eq("java-user-1"), eq(null), any(UserPreferences.class), eq("MERGE"));
    }

    @Test
    void staleExpectedRevisionDoesNotOverwriteOrAudit() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        UserPreferences current = new UserPreferences(
                "java-user-1", PreferencesSchema.CURRENT_SCHEMA_VERSION, 4,
                List.of("城市"), UserPreferences.WalkingTolerance.NORMAL,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.UNSPECIFIED, UserPreferences.DietPreference.UNSPECIFIED,
                "", Map.of(), 10L, 20L);
        when(repository.findByUserId("java-user-1")).thenReturn(Optional.of(current));
        PreferencesService service = new PreferencesService(repository, audit);

        PreferenceConflictException error = assertThrows(PreferenceConflictException.class,
                () -> service.merge("java-user-1", UserPreferencesPatch.empty(), 3L));

        assertEquals(3, error.getExpectedRevision());
        assertEquals(4, error.getCurrentRevision());
        verify(repository, never()).updateCas(any(UserPreferences.class), any(Long.class));
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void anonymousOwnerIsRejectedBeforePersistence() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        PreferencesService service = new PreferencesService(repository, audit);

        assertThrows(IllegalArgumentException.class,
                () -> service.replace("anonymous", UserPreferencesPatch.empty(), null));
        verify(repository, never()).findByUserId(any());
    }

    @Test
    void replaceUsesOnlySuppliedTypedFieldsAndClearsOmittedValues() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        UserPreferences current = new UserPreferences(
                "java-user-1", PreferencesSchema.CURRENT_SCHEMA_VERSION, 4,
                List.of("城市"), UserPreferences.WalkingTolerance.LOW,
                new BudgetPreference(BudgetPreference.Level.LIMITED, null, "CNY"),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.TAXI, UserPreferences.DietPreference.VEGETARIAN,
                "解放碑", Map.of("legacy", "ignored-by-planner"), 10L, 20L);
        when(repository.findByUserId("java-user-1")).thenReturn(Optional.of(current));
        when(repository.updateCas(any(UserPreferences.class), eq(4L))).thenReturn(true);
        PreferencesService service = new PreferencesService(repository, audit);

        PreferencesService.MutationResult result = service.replace("java-user-1",
                new UserPreferencesPatch(List.of("夜景"), UserPreferences.WalkingTolerance.NORMAL,
                        BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                        UserPreferences.TransportPreference.UNSPECIFIED,
                        UserPreferences.DietPreference.UNSPECIFIED, "", Map.of(), null), 4L);

        assertEquals(List.of("夜景"), result.preferences().interests());
        assertEquals(UserPreferences.WalkingTolerance.NORMAL, result.preferences().walkingTolerance());
        assertEquals(UserPreferences.TransportPreference.UNSPECIFIED, result.preferences().transportPreference());
        assertEquals("", result.preferences().stayArea());
        assertEquals(Map.of(), result.preferences().legacyMetadata());
        verify(repository).updateCas(any(UserPreferences.class), eq(4L));
        verify(audit).record(eq("java-user-1"), eq(current), any(UserPreferences.class), eq("REPLACE"));
    }

    @Test
    void clearResetsFormalPreferencesWithoutTouchingAutomaticSlots() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        UserPreferences current = new UserPreferences(
                "java-user-1", PreferencesSchema.CURRENT_SCHEMA_VERSION, 4,
                List.of("城市"), UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.TAXI, UserPreferences.DietPreference.VEGETARIAN,
                "解放碑", Map.of(), 10L, 20L);
        when(repository.findByUserId("java-user-1")).thenReturn(Optional.of(current));
        when(repository.updateCas(any(UserPreferences.class), eq(4L))).thenReturn(true);
        PreferencesService service = new PreferencesService(repository, audit);

        PreferencesService.MutationResult result = service.clear("java-user-1", 4L);

        assertEquals(5L, result.preferences().revision());
        assertTrue(result.preferences().interests().isEmpty());
        assertEquals(UserPreferences.WalkingTolerance.UNSPECIFIED, result.preferences().walkingTolerance());
        assertEquals(UserPreferences.TransportPreference.UNSPECIFIED, result.preferences().transportPreference());
        assertEquals("", result.preferences().stayArea());
        verify(repository).updateCas(any(UserPreferences.class), eq(4L));
        verify(audit).record(eq("java-user-1"), eq(current), any(UserPreferences.class), eq("CLEAR"));
    }

    @Test
    void compareAndSwapFailureReturnsCurrentRevision() {
        PreferencesRepository repository = mock(PreferencesRepository.class);
        PreferenceAuditRepository audit = mock(PreferenceAuditRepository.class);
        UserPreferences current = UserPreferences.empty("java-user-1", 2, 10L);
        UserPreferences latest = UserPreferences.empty("java-user-1", 3, 10L);
        when(repository.findByUserId("java-user-1")).thenReturn(Optional.of(current), Optional.of(latest));
        when(repository.updateCas(any(UserPreferences.class), eq(2L))).thenReturn(false);
        PreferencesService service = new PreferencesService(repository, audit);

        PreferenceConflictException error = assertThrows(PreferenceConflictException.class,
                () -> service.merge("java-user-1", UserPreferencesPatch.empty(), null));

        assertEquals(2, error.getExpectedRevision());
        assertEquals(3, error.getCurrentRevision());
        verify(audit, never()).record(any(), any(), any(), any());
    }
}

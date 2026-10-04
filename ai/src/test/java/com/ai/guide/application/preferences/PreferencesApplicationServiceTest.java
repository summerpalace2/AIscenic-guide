package com.ai.guide.application.preferences;

import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferencesApplicationServiceTest {

    private final PreferencesService preferencesService = mock(PreferencesService.class);
    private final PreferencesApplicationService applicationService = new PreferencesApplicationService(preferencesService);

    @Test
    void getPreferencesDelegatesToDomainService() {
        UserPreferences preferences = samplePreferences("user-1", 1);
        when(preferencesService.get("user-1")).thenReturn(preferences);

        UserPreferences result = applicationService.getPreferences("user-1");

        assertEquals(preferences, result);
        verify(preferencesService).get("user-1");
    }

    @Test
    void mergePreferencesDelegatesToDomainService() {
        UserPreferences preferences = samplePreferences("user-1", 2);
        UserPreferencesPatch patch = UserPreferencesPatch.empty();
        PreferencesService.MutationResult expectedResult = new PreferencesService.MutationResult(preferences, false);
        when(preferencesService.merge("user-1", patch, 1L)).thenReturn(expectedResult);

        PreferencesService.MutationResult result = applicationService.mergePreferences("user-1", patch, 1L);

        assertEquals(expectedResult, result);
        verify(preferencesService).merge("user-1", patch, 1L);
    }

    @Test
    void replacePreferencesDelegatesToDomainService() {
        UserPreferences preferences = samplePreferences("user-1", 2);
        UserPreferencesPatch patch = UserPreferencesPatch.empty();
        PreferencesService.MutationResult expectedResult = new PreferencesService.MutationResult(preferences, false);
        when(preferencesService.replace("user-1", patch, 1L)).thenReturn(expectedResult);

        PreferencesService.MutationResult result = applicationService.replacePreferences("user-1", patch, 1L);

        assertEquals(expectedResult, result);
        verify(preferencesService).replace("user-1", patch, 1L);
    }

    @Test
    void clearPreferencesDelegatesToDomainService() {
        UserPreferences preferences = samplePreferences("user-1", 2);
        PreferencesService.MutationResult expectedResult = new PreferencesService.MutationResult(preferences, false);
        when(preferencesService.clear("user-1", 1L)).thenReturn(expectedResult);

        PreferencesService.MutationResult result = applicationService.clearPreferences("user-1", 1L);

        assertEquals(expectedResult, result);
        verify(preferencesService).clear("user-1", 1L);
    }

    private UserPreferences samplePreferences(String userId, long revision) {
        return new UserPreferences(
                userId, 1, revision, List.of("夜景"), UserPreferences.WalkingTolerance.NORMAL,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.UNSPECIFIED, UserPreferences.DietPreference.UNSPECIFIED,
                "", Map.of(), 10L, 20L);
    }
}

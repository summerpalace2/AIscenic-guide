package com.ai.guide.domain.preferences.api;


import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.service.PreferenceConflictException;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferencesControllerTest {

    private final PreferencesService preferencesService = mock(PreferencesService.class);
    private final PreferencesController controller = new PreferencesController(preferencesService);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void everyPreferencesVerbRequiresAnAuthenticatedJavaOwner() {
        UserContext.clear();

        assertEquals(401, controller.loadPreferences().getStatusCode().value());
        assertEquals(401, controller.mergePreferences(UserPreferencesPatch.empty(), null).getStatusCode().value());
        assertEquals(401, controller.patchPreferences(UserPreferencesPatch.empty(), null).getStatusCode().value());
        assertEquals(401, controller.replacePreferences(UserPreferencesPatch.empty(), null).getStatusCode().value());
        assertEquals(401, controller.clearPreferences(null).getStatusCode().value());
        verify(preferencesService, never()).get(any());
        verify(preferencesService, never()).merge(any(), any(), any());
        verify(preferencesService, never()).replace(any(), any(), any());
        verify(preferencesService, never()).clear(any(), any());
    }

    @Test
    void getIsOwnerBoundToCurrentJavaUser() {
        UserContext.set("java-user-a", "USER");
        UserPreferences preferences = samplePreferences("java-user-a", 3);
        when(preferencesService.get("java-user-a")).thenReturn(preferences);

        ResponseEntity<?> response = controller.loadPreferences();

        assertEquals(200, response.getStatusCode().value());
        verify(preferencesService).get("java-user-a");
        verify(preferencesService, never()).get("java-user-b");
    }

    @Test
    void postAndPatchUseMergeWhilePutUsesReplace() {
        UserContext.set("java-user", "USER");
        UserPreferencesPatch patch = new UserPreferencesPatch(
                List.of("夜景"), UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                UserPreferences.DietPreference.UNSPECIFIED, "解放碑", Map.of(), null);
        UserPreferences preferences = samplePreferences("java-user", 1);
        when(preferencesService.merge("java-user", patch, 2L))
                .thenReturn(new PreferencesService.MutationResult(preferences, false));
        when(preferencesService.replace("java-user", patch, 2L))
                .thenReturn(new PreferencesService.MutationResult(preferences, false));

        assertEquals(200, controller.mergePreferences(patch, 2L).getStatusCode().value());
        assertEquals(200, controller.patchPreferences(patch, 2L).getStatusCode().value());
        assertEquals(200, controller.replacePreferences(patch, 2L).getStatusCode().value());
        verify(preferencesService, org.mockito.Mockito.times(2)).merge("java-user", patch, 2L);
        verify(preferencesService).replace("java-user", patch, 2L);
    }

    @Test
    void clearUsesTheCurrentOwnerAndPreservesConflictDetails() {
        UserContext.set("java-user", "USER");
        when(preferencesService.clear("java-user", 4L))
                .thenThrow(new PreferenceConflictException(4L, 5L));

        ResponseEntity<?> response = controller.clearPreferences(4L);

        assertEquals(409, response.getStatusCode().value());
        assertFalse(response.getBody() == null);
        verify(preferencesService).clear("java-user", 4L);
    }

    @Test
    void invalidTypedInputReturnsBadRequest() {
        UserContext.set("java-user", "USER");
        UserPreferencesPatch invalid = UserPreferencesPatch.empty();
        when(preferencesService.merge("java-user", invalid, null))
                .thenThrow(new IllegalArgumentException("invalid preference"));

        ResponseEntity<?> response = controller.mergePreferences(invalid, null);

        assertEquals(400, response.getStatusCode().value());
        verify(preferencesService).merge("java-user", invalid, null);
    }

    @Test
    void unknownEnumTextIsRejectedBeforeControllerMutation() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(JsonProcessingException.class, () -> objectMapper.readValue(
                "{\"transportPreference\":\"NOT_A_CANONICAL_ENUM\"}",
                UserPreferencesPatch.class));
        verify(preferencesService, never()).merge(any(), any(), any());
    }

    @Test
    void createdMutationUsesCreatedStatus() {
        UserContext.set("java-user", "USER");
        UserPreferencesPatch patch = UserPreferencesPatch.empty();
        when(preferencesService.merge("java-user", patch, null))
                .thenReturn(new PreferencesService.MutationResult(samplePreferences("java-user", 1), true));

        ResponseEntity<?> response = controller.mergePreferences(patch, null);

        assertEquals(201, response.getStatusCode().value());
        assertTrue(response.getBody() != null);
    }

    private UserPreferences samplePreferences(String userId, long revision) {
        return new UserPreferences(
                userId, 1, revision, List.of("夜景"), UserPreferences.WalkingTolerance.NORMAL,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.UNSPECIFIED, UserPreferences.DietPreference.UNSPECIFIED,
                "", Map.of(), 10L, 20L);
    }
}

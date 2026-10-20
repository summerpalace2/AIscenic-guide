package com.ai.guide.adapter.preferences.web;

import com.ai.guide.application.preferences.PreferencesApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.service.PreferenceConflictException;
import com.ai.guide.domain.preferences.service.PreferencesService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferencesControllerTest {

    private final PreferencesApplicationService preferencesApplicationService = mock(PreferencesApplicationService.class);
    private final PreferencesController controller = new PreferencesController(preferencesApplicationService);

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
        verify(preferencesApplicationService, never()).getPreferences(any());
        verify(preferencesApplicationService, never()).mergePreferences(any(), any(), any());
        verify(preferencesApplicationService, never()).replacePreferences(any(), any(), any());
        verify(preferencesApplicationService, never()).clearPreferences(any(), any());
    }

    @Test
    void getIsOwnerBoundToCurrentJavaUser() {
        UserContext.set("java-user-a", "USER");
        UserPreferences preferences = samplePreferences("java-user-a", 3);
        when(preferencesApplicationService.getPreferences("java-user-a")).thenReturn(preferences);

        ResponseEntity<?> response = controller.loadPreferences();

        assertEquals(200, response.getStatusCode().value());
        verify(preferencesApplicationService).getPreferences("java-user-a");
        verify(preferencesApplicationService, never()).getPreferences("java-user-b");
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
        when(preferencesApplicationService.mergePreferences("java-user", patch, 2L))
                .thenReturn(new PreferencesService.MutationResult(preferences, false));
        when(preferencesApplicationService.replacePreferences("java-user", patch, 2L))
                .thenReturn(new PreferencesService.MutationResult(preferences, false));

        assertEquals(200, controller.mergePreferences(patch, 2L).getStatusCode().value());
        assertEquals(200, controller.patchPreferences(patch, 2L).getStatusCode().value());
        assertEquals(200, controller.replacePreferences(patch, 2L).getStatusCode().value());
        verify(preferencesApplicationService, org.mockito.Mockito.times(2)).mergePreferences("java-user", patch, 2L);
        verify(preferencesApplicationService).replacePreferences("java-user", patch, 2L);
    }

    @Test
    void clearUsesTheCurrentOwnerAndPreservesConflictDetails() {
        UserContext.set("java-user", "USER");
        when(preferencesApplicationService.clearPreferences("java-user", 4L))
                .thenThrow(new PreferenceConflictException(4L, 5L));

        ResponseEntity<?> response = controller.clearPreferences(4L);

        assertEquals(409, response.getStatusCode().value());
        assertFalse(response.getBody() == null);
        verify(preferencesApplicationService).clearPreferences("java-user", 4L);
    }

    @Test
    void invalidTypedInputReturnsBadRequest() {
        UserContext.set("java-user", "USER");
        UserPreferencesPatch invalid = UserPreferencesPatch.empty();
        when(preferencesApplicationService.mergePreferences("java-user", invalid, null))
                .thenThrow(new IllegalArgumentException("invalid preference"));

        ResponseEntity<?> response = controller.mergePreferences(invalid, null);

        assertEquals(400, response.getStatusCode().value());
        verify(preferencesApplicationService).mergePreferences("java-user", invalid, null);
    }

    @Test
    void unknownEnumTextIsRejectedBeforeControllerMutation() {
        ObjectMapper objectMapper = new ObjectMapper();

        assertThrows(JsonProcessingException.class, () -> objectMapper.readValue(
                "{\"transportPreference\":\"NOT_A_CANONICAL_ENUM\"}",
                UserPreferencesPatch.class));
        verify(preferencesApplicationService, never()).mergePreferences(any(), any(), any());
    }

    @Test
    void unknownFieldsAreRejectedWithTheSameLenientMapperUsedBySpring() throws Exception {
        UserContext.set("java-user", "USER");
        var mapper = new ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
        for (String json : List.of("{\"unexpected\":true}", "{\"unexpected\":true,\"interests\":[\"夜景\"]}")) {
            assertThrows(JsonProcessingException.class, () -> mapper.readValue(json, UserPreferencesPatch.class));
            for (var method : List.of(org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.PUT))
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, "/ai/preferences")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(preferencesApplicationService);
    }

    @Test
    void createdMutationUsesCreatedStatus() {
        UserContext.set("java-user", "USER");
        UserPreferencesPatch patch = UserPreferencesPatch.empty();
        when(preferencesApplicationService.mergePreferences("java-user", patch, null))
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

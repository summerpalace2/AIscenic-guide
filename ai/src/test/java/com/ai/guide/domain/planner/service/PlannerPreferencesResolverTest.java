package com.ai.guide.domain.planner.service;



import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerPreferencesResolverTest {

    private final TravelConstraintParser parser = new TravelConstraintParser();

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void disabledAndAnonymousPlanningNeverReadsFormalPreferences() {
        PreferencesService service = mock(PreferencesService.class);
        PlannerPreferencesResolver resolver = new PlannerPreferencesResolver(service, new ObjectMapper());

        PlannerPreferencesResolver.Resolution disabled = resolver.resolve(parser.parse("喜欢夜景"), false);
        UserContext.clear();
        PlannerPreferencesResolver.Resolution anonymous = resolver.resolve(parser.parse("喜欢夜景"), true);

        assertTrue(disabled.snapshot().isEmpty());
        assertTrue(anonymous.snapshot().isEmpty());
        verify(service, never()).get(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void authenticatedResolutionUsesOnlyTypedCanonicalFields() {
        PreferencesService service = mock(PreferencesService.class);
        PlannerPreferencesResolver resolver = new PlannerPreferencesResolver(service, new ObjectMapper());
        UserContext.set("java-user", "USER");
        UserPreferences preferences = new UserPreferences(
                "java-user", PreferencesSchema.CURRENT_SCHEMA_VERSION, 7,
                List.of("夜景", "人文"), UserPreferences.WalkingTolerance.LOW,
                new BudgetPreference(BudgetPreference.Level.LIMITED, null, ""),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.TAXI,
                UserPreferences.DietPreference.VEGETARIAN,
                "解放碑", Map.of("rawLegacy", "少走路", "history", List.of("confirmed")),
                10L, 20L);
        when(service.get(eq("java-user"))).thenReturn(preferences);

        PlannerPreferencesResolver.Resolution result = resolver.resolve(parser.parse(""), true);

        assertEquals(List.of("夜景", "人文"), result.constraints().getInterests());
        assertEquals("低", result.constraints().getWalkingTolerance());
        assertEquals("有限", result.constraints().getBudget());
        assertEquals("带父母", result.constraints().getCompanions());
        assertEquals("打车优先", result.constraints().getTransportPreference());
        assertEquals("素食", result.constraints().getDietPreference());
        assertEquals("解放碑", result.constraints().getStayArea());
        assertEquals(ConstraintOrigin.PREFERENCE, result.constraints().originOf("transportPreference"));
        assertEquals(7, result.snapshot().preferenceRevision());
        assertEquals("LOW", result.snapshot().appliedFields().get("walkingTolerance"));
        assertFalse(result.snapshot().appliedFields().containsKey("legacyMetadata"));
        verify(service).get("java-user");
    }

    @Test
    void explicitRequestAndPromptValuesRemainAboveFormalPreferences() {
        PreferencesService service = mock(PreferencesService.class);
        PlannerPreferencesResolver resolver = new PlannerPreferencesResolver(service, new ObjectMapper());
        UserContext.set("java-user", "USER");
        when(service.get("java-user")).thenReturn(new UserPreferences(
                "java-user", 1, 3, List.of("夜景"), UserPreferences.WalkingTolerance.NORMAL,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                UserPreferences.DietPreference.UNSPECIFIED, "", Map.of(), 1L, 2L));

        var prompted = parser.parse("少走路");
        var requested = parser.applyOverrides(prompted,
                Map.of("interests", List.of("美食"), "transportPreference", "打车优先"));
        var resolved = resolver.resolve(requested, true);

        assertEquals(List.of("美食"), resolved.constraints().getInterests());
        assertEquals("低", resolved.constraints().getWalkingTolerance());
        assertEquals("打车优先", resolved.constraints().getTransportPreference());
        assertEquals(ConstraintOrigin.REQUEST, resolved.constraints().originOf("transportPreference"));
        assertFalse(resolved.snapshot().appliedFields().containsKey("interests"));
        assertFalse(resolved.snapshot().appliedFields().containsKey("transportPreference"));
    }

    @Test
    void lowWalkingDerivedTransitCannotOverrideFormalTaxi() {
        PreferencesService service = mock(PreferencesService.class);
        PlannerPreferencesResolver resolver = new PlannerPreferencesResolver(service, new ObjectMapper());
        UserContext.set("java-user", "USER");
        when(service.get("java-user")).thenReturn(new UserPreferences(
                "java-user", 1, 4, List.of(), UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.TAXI,
                UserPreferences.DietPreference.UNSPECIFIED, "", Map.of(), 1L, 2L));

        var resolved = resolver.resolve(parser.parse("少走路"), true);
        var finalConstraints = parser.applyDerivedTransportFallback(resolved.constraints());

        assertEquals("打车优先", finalConstraints.getTransportPreference());
        assertEquals(ConstraintOrigin.PREFERENCE, finalConstraints.originOf("transportPreference"));
    }

    @Test
    void unsupportedSchemaFallsBackWithoutUsingMetadata() {
        PreferencesService service = mock(PreferencesService.class);
        PlannerPreferencesResolver resolver = new PlannerPreferencesResolver(service, new ObjectMapper());
        UserContext.set("java-user", "USER");
        when(service.get("java-user")).thenReturn(new UserPreferences(
                "java-user", 2, 9, List.of("不应进入规划"), UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(), UserPreferences.CompanionPreference.UNSPECIFIED,
                UserPreferences.TransportPreference.UNSPECIFIED,
                UserPreferences.DietPreference.UNSPECIFIED, "", Map.of("interests", List.of("不应进入规划")), 1L, 2L));

        var result = resolver.resolve(parser.parse(""), true);

        assertEquals(List.of("城市", "人文", "夜景"), result.constraints().getInterests());
        assertTrue(result.snapshot().isEmpty());
        assertEquals(0, result.snapshot().preferenceRevision());
    }
}

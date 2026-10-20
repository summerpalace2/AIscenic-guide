package com.ai.guide.testing;

import com.ai.guide.domain.user.gateway.UserPreferenceStorePort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Minimal isolated preference store fixture for domain tests. */
public final class InMemoryUserPreferenceStore implements UserPreferenceStorePort {
    private final Map<String, Map<String, String>> slots = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> preferences = new ConcurrentHashMap<>();

    @Override public Map<String, String> readSlots(String userId) { return copy(slots.get(userId)); }
    @Override public String readSlot(String userId, String slotName) { return slots.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).get(slotName); }
    @Override public void writeSlot(String userId, String slotName, String value) { slots.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(slotName, value); }
    @Override public void clearSlots(String userId) { slots.remove(userId); }
    @Override public Map<String, String> readManualPreferences(String userId) { return copy(preferences.get(userId)); }
    @Override public void writeManualPreferences(String userId, Map<String, String> values) {
        var copy = new ConcurrentHashMap<>(values);
        copy.put("prefs_initialized", "1");
        preferences.put(userId, copy);
    }
    @Override public void clearManualPreferences(String userId) { preferences.put(userId, new ConcurrentHashMap<>(Map.of("prefs_initialized", "1"))); }

    private static Map<String, String> copy(Map<String, String> values) {
        return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }
}

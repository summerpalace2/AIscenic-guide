package com.ai.guide.domain.user.gateway;

import java.util.Map;

/** Persistence boundary for extracted travel slots and manually configured user preferences. */
public interface UserPreferenceStorePort {
    Map<String, String> readSlots(String userId);
    String readSlot(String userId, String slotName);
    void writeSlot(String userId, String slotName, String value);
    void clearSlots(String userId);
    Map<String, String> readManualPreferences(String userId);
    void writeManualPreferences(String userId, Map<String, String> preferences);
    void clearManualPreferences(String userId);
}

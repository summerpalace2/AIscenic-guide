package com.ai.guide.domain.analytics.gateway;

import java.util.List;
import java.util.Map;

/** Storage capability for expiring online-presence records. */
public interface OnlinePresenceStorePort {
    void heartbeat(String userId, String username, String role, long lastSeenEpochMillis, long expiresAtEpochMillis);
    List<Map<String, Object>> findActive(long nowEpochMillis);
}

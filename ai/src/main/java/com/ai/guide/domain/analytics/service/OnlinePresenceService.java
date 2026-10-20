package com.ai.guide.domain.analytics.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.analytics.gateway.OnlinePresenceStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Business facade for online-presence tracking. */
@Service
public class OnlinePresenceService {
    private final OnlinePresenceStorePort store;
    private final Duration ttl;

    public OnlinePresenceService(OnlinePresenceStorePort store,
                                 @Value("${analytics.online-presence.ttl:90s}") Duration ttl) {
        this.store = store;
        this.ttl = ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(90) : ttl;
    }

    public void heartbeat() {
        heartbeat(UserContext.getUserId(), UserContext.getUsername(), UserContext.getRole());
    }

    public void heartbeat(String userId, String username, String role) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return;
        long now = System.currentTimeMillis();
        store.heartbeat(userId, username, role, now, now + ttl.toMillis());
    }

    public List<Map<String, Object>> listOnlineUsers() {
        return store.findActive(System.currentTimeMillis());
    }
}

package com.ai.guide.infrastructure.planner.proposal;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Cp6InfrastructureTargetTest {
    private Map<String, String> valid() {
        return new HashMap<>(Map.of("CP6_IT_PERMISSION", Cp6InfrastructureTarget.PERMISSION,
                "CP6_IT_PG_URL", "jdbc:postgresql://127.0.0.1:55432/cp6_test",
                "CP6_IT_PG_DATABASE", "cp6_test",
                "CP6_IT_PG_USER", "cp6_test", "CP6_IT_PG_PASSWORD", "test-only",
                "CP6_IT_REDIS_HOST", "127.0.0.1", "CP6_IT_REDIS_PORT", "56379"));
    }

    @Test void acceptsOnlyExplicitDisposableTarget() {
        assertDoesNotThrow(() -> new Cp6InfrastructureTarget(valid()));
        assertThrows(IllegalArgumentException.class, () -> new Cp6InfrastructureTarget(Map.of()));
        var env = valid(); env.remove("CP6_IT_PERMISSION");
        assertThrows(IllegalArgumentException.class, () -> new Cp6InfrastructureTarget(env));
    }

    @Test void rejectsDatabaseMismatchAndConnectionOptionBypasses() {
        for (String url : new String[]{"jdbc:postgresql://127.0.0.1:55432/production",
                "jdbc:postgresql://127.0.0.1:55432/cp6_test?currentSchema=public",
                "jdbc:sqlite::memory:"}) {
            var env = valid(); env.put("CP6_IT_PG_URL", url);
            assertThrows(IllegalArgumentException.class, () -> new Cp6InfrastructureTarget(env), url);
        }
        var env = valid(); env.put("CP6_IT_REDIS_HOST", "redis://redis.example.com");
        assertThrows(IllegalArgumentException.class, () -> new Cp6InfrastructureTarget(env));
    }

    @Test void acceptsExplicitlyAuthorizedRemoteDedicatedServices() {
        var env = valid();
        env.put("CP6_IT_PG_URL", "jdbc:postgresql://isolated-db.example.com:5432/team_test");
        env.put("CP6_IT_PG_DATABASE", "team_test");
        env.put("CP6_IT_REDIS_HOST", "isolated-redis.example.com");
        env.put("CP6_IT_REDIS_PORT", "6379");
        assertDoesNotThrow(() -> new Cp6InfrastructureTarget(env));
    }

    @Test void bothAdministrativeAndScopedConnectionsAreBounded() {
        var target = new Cp6InfrastructureTarget(valid());
        assertEquals(target.jdbcUrl + "?connectTimeout=5&socketTimeout=15", target.adminDataSource().getUrl());
        assertTrue(target.dataSource("cp6_" + "a".repeat(32)).getUrl().endsWith("&connectTimeout=5&socketTimeout=15"));
        assertThrows(IllegalArgumentException.class, () -> target.dataSource("public"));
    }
}

package com.ai.guide.common.context;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class OperationDeadlineTest {
    @Test void nestedScopesAndReusedWorkersRestoreTheirPreviousBudget() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos());
            try (var outer = OperationDeadline.open(1000)) {
                long before = OperationDeadline.remainingNanos();
                try (var inner = OperationDeadline.open(10)) {
                    assertTrue(OperationDeadline.remainingNanos() < before);
                }
                assertTrue(OperationDeadline.remainingNanos() > 100_000_000L);
                var work = OperationDeadline.carry(OperationDeadline::remainingNanos);
                assertTrue(executor.submit(work::get).get() < Long.MAX_VALUE);
                assertEquals(Long.MAX_VALUE, executor.submit(OperationDeadline::remainingNanos).get());
            }
            assertEquals(Long.MAX_VALUE, OperationDeadline.remainingNanos());
        } finally { executor.shutdownNow(); }
    }
}

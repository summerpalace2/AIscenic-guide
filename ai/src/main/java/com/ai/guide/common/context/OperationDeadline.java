package com.ai.guide.common.context;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One monotonic request budget, explicitly carried across provider executor boundaries. */
public final class OperationDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private OperationDeadline(long deadline) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? deadline : Math.min(previous, deadline));
    }

    public static OperationDeadline open(long timeoutMs) {
        return new OperationDeadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs)));
    }

    public static long remainingNanos() {
        Long deadline = CURRENT.get();
        return deadline == null ? Long.MAX_VALUE : Math.max(0L, deadline - System.nanoTime());
    }

    public static boolean expired() { return remainingNanos() == 0L; }

    public static <T> Supplier<T> carry(Supplier<T> work) {
        Long deadline = CURRENT.get();
        return () -> {
            if (deadline == null) return work.get();
            try (OperationDeadline ignored = new OperationDeadline(deadline)) { return work.get(); }
        };
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}

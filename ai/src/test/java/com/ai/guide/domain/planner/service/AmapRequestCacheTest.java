package com.ai.guide.domain.planner.service;


import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapRequestCacheTest {

    @Test
    void successfulValueIsReusedUntilTtlExpires() {
        AtomicLong now = new AtomicLong(100);
        AtomicInteger calls = new AtomicInteger();
        AmapRequestCache cache = new AmapRequestCache(8, now::get);
        AmapRequestCache.RequestKey key = new AmapRequestCache.RequestKey("poi", List.of("one"));

        assertEquals("first-1", cache.getOrLoad(key, 10, true, true,
                () -> "first-" + calls.incrementAndGet(), value -> true));
        assertEquals("first-1", cache.getOrLoad(key, 10, true, true,
                () -> "second-" + calls.incrementAndGet(), value -> true));
        assertEquals(1, calls.get());

        now.set(110);
        assertEquals("second-2", cache.getOrLoad(key, 10, true, true,
                () -> "second-" + calls.incrementAndGet(), value -> true));
        assertEquals(2, calls.get());
    }

    @Test
    void identicalConcurrentLoadsShareOneProviderCall() throws Exception {
        AmapRequestCache cache = new AmapRequestCache(8);
        AmapRequestCache.RequestKey key = new AmapRequestCache.RequestKey("poi", List.of("same"));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch followerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> owner = pool.submit(() -> cache.getOrLoad(key, 0, false, true, () -> {
                calls.incrementAndGet();
                entered.countDown();
                await(release);
                return "shared";
            }, value -> true));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> follower = pool.submit(() -> {
                followerStarted.countDown();
                return cache.getOrLoad(key, 0, false, true,
                        () -> "unexpected", value -> true);
            });
            assertTrue(followerStarted.await(2, TimeUnit.SECONDS));
            sleepBriefly();
            release.countDown();
            assertEquals("shared", owner.get(2, TimeUnit.SECONDS));
            assertEquals("shared", follower.get(2, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysDoNotShareCompletedValue() {
        AmapRequestCache cache = new AmapRequestCache(8);
        AmapRequestCache.RequestKey first = new AmapRequestCache.RequestKey("poi", List.of("a"));
        AmapRequestCache.RequestKey second = new AmapRequestCache.RequestKey("poi", List.of("b"));
        AtomicInteger calls = new AtomicInteger();

        assertEquals("a", cache.getOrLoad(first, 100, true, true,
                () -> {
                    calls.incrementAndGet();
                    return "a";
                }, value -> true));
        assertEquals("b", cache.getOrLoad(second, 100, true, true,
                () -> {
                    calls.incrementAndGet();
                    return "b";
                }, value -> true));
        assertEquals(2, calls.get());
    }

    @Test
    void failedLoadIsNotCachedAndInFlightEntryIsCleanedUp() {
        AmapRequestCache cache = new AmapRequestCache(8);
        AmapRequestCache.RequestKey key = new AmapRequestCache.RequestKey("poi", List.of("retry"));
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> cache.getOrLoad(key, 10, true, true,
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("temporary");
                }, value -> true));
        assertEquals("recovered", cache.getOrLoad(key, 10, true, true,
                () -> {
                    calls.incrementAndGet();
                    return "recovered";
                }, value -> true));
        assertEquals(2, calls.get());
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

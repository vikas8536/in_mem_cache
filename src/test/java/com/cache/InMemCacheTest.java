package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class InMemCacheTest {
    @Test
    void putAndGet() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder()
            .maxEntries(100)
            .segments(4)
            .build(k -> null);
        cache.put("a", "apple", 60_000);
        assertEquals("apple", cache.get("a"));
        cache.close();
    }

    @Test
    void deleteReturnsValueAndRemoves() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "apple", 60_000);
        assertEquals("apple", cache.delete("a"));
        assertNull(cache.get("a"));
        cache.close();
    }

    @Test
    void getReturnsNullForMissingKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertNull(cache.get("missing"));
        cache.close();
    }

    @Test
    void refreshFunctionCalledOnMiss() {
        AtomicInteger calls = new AtomicInteger(0);
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4)
            .build(k -> { calls.incrementAndGet(); return "loaded:" + k; });
        assertEquals("loaded:x", cache.get("x"));
        assertEquals(1, calls.get());
        cache.close();
    }

    @Test
    void sizeReflectsEntryCount() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertEquals(0, cache.size());
        cache.put(1, "a", 60_000);
        cache.put(2, "b", 60_000);
        assertEquals(2, cache.size());
        cache.delete(1);
        assertEquals(1, cache.size());
        cache.close();
    }

    @Test
    void clearRemovesAll() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put(1, "a", 60_000);
        cache.put(2, "b", 60_000);
        cache.clear();
        assertEquals(0, cache.size());
        cache.close();
    }

    @Test
    void concurrentReadsDoNotBlock() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);
        for (int i = 0; i < 10_000; i++) {
            cache.put(i, i, 60_000);
        }
        AtomicInteger errors = new AtomicInteger(0);
        Thread[] readers = new Thread[8];
        for (int t = 0; t < 8; t++) {
            int threadId = t;
            readers[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    Integer v = cache.get(i);
                    if (v != null && v != i) {
                        errors.incrementAndGet();
                    }
                }
            });
            readers[t].start();
        }
        for (Thread r : readers) r.join();
        assertEquals(0, errors.get());
        cache.close();
    }

    @Test
    void readAfterWriteConsistency() {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(1000).segments(4).build(k -> null);
        cache.put(42, 100, 60_000);
        assertEquals(100, (int) cache.get(42));
        cache.put(42, 200, 60_000);
        assertEquals(200, (int) cache.get(42));
        cache.close();
    }
}

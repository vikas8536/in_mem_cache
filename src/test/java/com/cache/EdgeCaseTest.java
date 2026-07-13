package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EdgeCaseTest {
    @Test
    void nullKeyThrows() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertThrows(NullPointerException.class, () -> cache.put(null, "x", 1000));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.delete(null));
        cache.close();
    }

    @Test
    void ttlOfZeroExpiresImmediately() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "apple", 0);
        assertNull(cache.get("a"));
        cache.close();
    }

    @Test
    void putOverwritesExistingKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "first", 60_000);
        cache.put("a", "second", 60_000);
        assertEquals("second", cache.get("a"));
        cache.close();
    }

    @Test
    void maxTtlDoesNotOverflow() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "value", Long.MAX_VALUE);
        assertEquals("value", cache.get("a"));
        cache.close();
    }

    @Test
    void refreshFunctionCalledWithCorrectKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> {
                assertEquals("expected-key", k);
                return "loaded";
            });
        assertEquals("loaded", cache.get("expected-key"));
        cache.close();
    }

    @Test
    void deleteOnEvictedEntryReturnsNull() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(10).segments(1).build(k -> null);
        for (int i = 0; i < 100; i++) {
            cache.put(i, "v" + i, 60_000);
        }
        boolean foundEvicted = false;
        for (int i = 0; i < 50; i++) {
            if (cache.delete(i) == null) {
                foundEvicted = true;
                break;
            }
        }
        assertTrue(foundEvicted, "Expected some entries to have been evicted");
        cache.close();
    }

    @Test
    void repeatedPutCyclesDoNotLeak() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(1000).segments(4).build(k -> null);
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 2000; i++) {
                cache.put(i, "round" + round, 60_000);
            }
        }
        assertTrue(cache.size() <= 1000, "Cache exceeded max entries: " + cache.size());
        cache.close();
    }
}

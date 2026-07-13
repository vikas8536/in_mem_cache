package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class SegmentTest {
    private final CacheConfig cfg = new CacheConfig(100, 4, 10, 10);

    @Test
    void putAndGet() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        assertEquals("apple", seg.get("a"));
    }

    @Test
    void getReturnsNullForMissingKey() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        assertNull(seg.get("missing"));
    }

    @Test
    void deleteRemovesEntry() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        assertEquals("apple", seg.delete("a"));
        assertNull(seg.get("a"));
    }

    @Test
    void deleteReturnsNullForMissing() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        assertNull(seg.delete("missing"));
    }

    @Test
    void refreshFunctionCalledOnMiss() {
        AtomicInteger calls = new AtomicInteger(0);
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> {
            calls.incrementAndGet();
            return "loaded:" + k;
        });
        assertEquals("loaded:x", seg.get("x"));
        assertEquals(1, calls.get());
    }

    @Test
    void refreshFunctionNotCalledOnHit() {
        AtomicInteger calls = new AtomicInteger(0);
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> {
            calls.incrementAndGet();
            return "loaded:" + k;
        });
        seg.put("x", "cached", 60_000);
        assertEquals("cached", seg.get("x"));
        assertEquals(0, calls.get());
    }

    @Test
    void expiredEntryReturnsNullAndTriggersMiss() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("x", "stale", -1); // already expired
        assertNull(seg.get("x"));
    }

    @Test
    void evictionFiresWhenFull() {
        Segment<Integer, String> seg = new Segment<>(
            new CacheConfig(10, 4, 10, 10), 0, k -> null);
        for (int i = 0; i < 20; i++) {
            seg.put(i, "v" + i, 60_000);
        }
        assertTrue(seg.size() <= 12); // some evicted (10 max + evict loop buffer)
    }

    @Test
    void expiredEntryNotReturnedByGet() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        seg.put("c", "cherry", -1); // already expired
        assertNull(seg.get("c"));
        assertEquals("apple", seg.get("a"));
    }
}

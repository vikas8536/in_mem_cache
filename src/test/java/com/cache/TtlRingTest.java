package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Queue;

class TtlRingTest {
    @Test
    void addAndDrainBucket() {
        TtlRing<String> ring = new TtlRing<>(10);
        ring.add("a", 3);
        ring.add("b", 3);
        ring.advanceHead(); // now head = 1
        ring.advanceHead(); // now head = 2
        ring.advanceHead(); // now head = 3
        Queue<String> bucket = ring.drainBucket();
        assertTrue(bucket.contains("a"));
        assertTrue(bucket.contains("b"));
    }

    @Test
    void drainBucketReturnsEmptyQueueWhenEmpty() {
        TtlRing<String> ring = new TtlRing<>(10);
        Queue<String> bucket = ring.drainBucket();
        assertNotNull(bucket);
        assertTrue(bucket.isEmpty());
    }

    @Test
    void headWrapsAround() {
        TtlRing<String> ring = new TtlRing<>(3);
        ring.add("a", 2);
        ring.advanceHead(); // 1
        ring.advanceHead(); // 2
        ring.advanceHead(); // 0
        ring.add("b", 0);
        Queue<String> bucket = ring.drainBucket(); // bucket 0
        assertTrue(bucket.contains("b"));
    }

    @Test
    void bucketIndexOutOfBoundsThrows() {
        TtlRing<String> ring = new TtlRing<>(5);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> ring.add("x", 5));
    }
}

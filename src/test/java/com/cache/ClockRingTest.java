package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClockRingTest {
    @Test
    void addAndPeekHand() {
        ClockRing<String> ring = new ClockRing<>(8);
        assertNull(ring.peekHand());
        ring.add("a");
        ring.add("b");
        assertEquals("a", ring.peekHand());
    }

    @Test
    void advanceHandMovesPointer() {
        ClockRing<String> ring = new ClockRing<>(4);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        assertEquals("a", ring.peekHand());
        ring.advanceHand();
        assertEquals("b", ring.peekHand());
        ring.advanceHand();
        assertEquals("c", ring.peekHand());
    }

    @Test
    void handWrapsAround() {
        ClockRing<String> ring = new ClockRing<>(3);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        ring.advanceHand();
        ring.advanceHand();
        ring.advanceHand();
        assertEquals("a", ring.peekHand());
    }

    @Test
    void emptySlotsAreSkipped() {
        ClockRing<String> ring = new ClockRing<>(4);
        ring.add("a");
        ring.add("b");
        ring.advanceHand();
        ring.clearSlot(0);    // slot at hand becomes null
        ring.advanceHand();   // skip null
        assertEquals("b", ring.peekHand());
    }

    @Test
    void insertPtrWrapsAndSlotsAvailable() {
        ClockRing<Integer> ring = new ClockRing<>(4);
        for (int i = 0; i < 8; i++) {
            ring.add(i);
        }
        // after wrapping, old entries are overwritten
        assertNotNull(ring.peekHand());
    }

    @Test
    void addOverwritesOldKey() {
        ClockRing<String> ring = new ClockRing<>(2);
        ring.add("a");
        ring.add("b");
        ring.add("c"); // wraps, overwrites slot 0
        assertEquals("c", ring.peekHand());
    }
}

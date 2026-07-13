package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;

class NodeTest {
    @Test
    void constructorSetsValueAndExpiry() {
        long expiry = System.nanoTime() + 1_000_000_000L;
        Node<String> node = new Node<>("hello", expiry);
        assertEquals("hello", node.value);
        assertEquals(expiry, node.expiresAtNanos);
    }

    @Test
    void refBitDefaultsToFalse() {
        Node<String> node = new Node<>("x", Long.MAX_VALUE);
        assertFalse(node.refBit.get());
    }

    @Test
    void isExpiredReturnsTrueWhenPastExpiry() {
        Node<String> node = new Node<>("x", System.nanoTime() - 1);
        assertTrue(node.isExpired());
    }

    @Test
    void isExpiredReturnsFalseWhenBeforeExpiry() {
        Node<String> node = new Node<>("x", Long.MAX_VALUE);
        assertFalse(node.isExpired());
    }
}

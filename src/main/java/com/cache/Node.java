package com.cache;

import java.util.concurrent.atomic.AtomicBoolean;

class Node<V> {
    final V value;
    final AtomicBoolean refBit = new AtomicBoolean(false);
    final long expiresAtNanos;

    Node(V value, long expiresAtNanos) {
        this.value = value;
        this.expiresAtNanos = expiresAtNanos;
    }

    boolean isExpired() {
        return System.nanoTime() > expiresAtNanos;
    }
}

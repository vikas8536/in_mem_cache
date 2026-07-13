package com.cache;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.StampedLock;

class Segment<K, V> {
    private final ConcurrentHashMap<K, Node<V>> chm = new ConcurrentHashMap<>();
    private final ClockRing<K> clock;
    private final TtlRing<K> ttlRing;
    private final StampedLock lock = new StampedLock();
    private final int maxEntries;
    private final int ttlBuckets;
    private final long ttlTickMs;
    private final RefreshFunction<K, V> refreshFn;

    Segment(CacheConfig config, int segmentIndex, RefreshFunction<K, V> refreshFn) {
        this.maxEntries = config.maxEntries() / config.segments() + 1;
        this.ttlBuckets = config.ttlBuckets();
        this.ttlTickMs = config.ttlTickMs();
        this.clock = new ClockRing<>(this.maxEntries);
        this.ttlRing = new TtlRing<>(ttlBuckets);
        this.refreshFn = refreshFn;
    }

    V get(K key) {
        long stamp = lock.tryOptimisticRead();
        Node<V> node = chm.get(key);

        if (node == null || node.isExpired()) {
            if (!lock.validate(stamp)) {
                stamp = lock.readLock();
                node = chm.get(key);
                lock.unlockRead(stamp);
            }
            V value = refreshFn.load(key);
            if (value != null) {
                put(key, value, 60_000L);
            }
            return value;
        }

        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            node = chm.get(key);
            lock.unlockRead(stamp);
        }

        if (node != null) {
            node.refBit.set(true);
        }
        return node != null ? node.value : null;
    }

    void put(K key, V value, long ttlMs) {
        long stamp = lock.writeLock();
        try {
            while (chm.size() >= maxEntries) {
                evictOne();
            }
            long expiresAtNanos = ttlMs == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : System.nanoTime() + ttlMs * 1_000_000L;
            Node<V> node = new Node<>(value, expiresAtNanos);
            chm.put(key, node);
            clock.add(key);
            int bucketIdx = ttlMs == Long.MAX_VALUE ? ttlBuckets - 1
                : (int) (((System.currentTimeMillis() / ttlTickMs) + (ttlMs / ttlTickMs)) % ttlBuckets);
            ttlRing.add(key, bucketIdx);
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    V delete(K key) {
        long stamp = lock.writeLock();
        try {
            Node<V> node = chm.remove(key);
            return node != null ? node.value : null;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    int size() {
        return chm.size();
    }

    void clear() {
        long stamp = lock.writeLock();
        try {
            chm.clear();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    void purgeExpired() {
        long stamp = lock.writeLock();
        try {
            Queue<K> bucket = ttlRing.drainBucket();
            for (K key : bucket) {
                Node<V> node = chm.get(key);
                if (node != null && node.isExpired()) {
                    chm.remove(key);
                }
            }
            ttlRing.advanceHead();
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    private void evictOne() {
        while (true) {
            K key = clock.peekHand();
            if (key == null) {
                clock.advanceHand();
                continue;
            }
            Node<V> node = chm.get(key);
            if (node == null || node.isExpired()) {
                chm.remove(key);
                clock.clearCurrentSlot();
                clock.advanceHand();
                return;
            }
            if (node.refBit.getAndSet(false)) {
                clock.advanceHand();
                continue;
            }
            chm.remove(key);
            clock.clearCurrentSlot();
            clock.advanceHand();
            return;
        }
    }
}

package com.cache;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

class TtlRing<K> {
    private final ConcurrentLinkedQueue<K>[] buckets;
    private final int size;
    private int head;

    @SuppressWarnings("unchecked")
    TtlRing(int numBuckets) {
        this.size = numBuckets;
        this.buckets = new ConcurrentLinkedQueue[numBuckets];
        for (int i = 0; i < numBuckets; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
        }
        this.head = 0;
    }

    void add(K key, int bucketIndex) {
        buckets[bucketIndex].add(key);
    }

    Queue<K> drainBucket() {
        Queue<K> bucket = buckets[head];
        buckets[head] = new ConcurrentLinkedQueue<>();
        return bucket;
    }

    void advanceHead() {
        head = (head + 1) % size;
    }

    int head() { return head; }
    int size() { return size; }
}

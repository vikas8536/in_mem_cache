package com.cache;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class InMemCacheImpl<K, V> implements InMemCache<K, V> {
    private final Segment<K, V>[] segments;
    private final int segmentMask;
    private final ScheduledExecutorService purger;

    @SuppressWarnings("unchecked")
    InMemCacheImpl(CacheConfig config, RefreshFunction<K, V> refreshFn) {
        int segCount = config.segments();
        this.segments = new Segment[segCount];
        this.segmentMask = segCount - 1;
        for (int i = 0; i < segCount; i++) {
            segments[i] = new Segment<>(config, i, refreshFn);
        }
        this.purger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-ttl-purger");
            t.setDaemon(true);
            return t;
        });
        this.purger.scheduleAtFixedRate(
            this::purgeAll, config.ttlTickMs(), config.ttlTickMs(), TimeUnit.MILLISECONDS);
    }

    private Segment<K, V> segmentFor(K key) {
        int hash = key.hashCode() ^ (key.hashCode() >>> 16);
        return segments[hash & segmentMask];
    }

    @Override
    public V get(K key) {
        return segmentFor(key).get(key);
    }

    @Override
    public void put(K key, V value, long ttlMs) {
        segmentFor(key).put(key, value, ttlMs);
    }

    @Override
    public V delete(K key) {
        return segmentFor(key).delete(key);
    }

    @Override
    public long size() {
        long total = 0;
        for (Segment<K, V> seg : segments) {
            total += seg.size();
        }
        return total;
    }

    @Override
    public void clear() {
        for (Segment<K, V> seg : segments) {
            seg.clear();
        }
    }

    @Override
    public void close() {
        purger.shutdown();
    }

    private void purgeAll() {
        for (Segment<K, V> seg : segments) {
            seg.purgeExpired();
        }
    }
}

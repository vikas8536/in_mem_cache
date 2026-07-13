package com.cache;

public class InMemCacheBuilder {
    private int maxEntries = 1_000_000;
    private int segments = Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
    private long ttlTickMs = 10;
    private int ttlBuckets = 1000;

    private InMemCacheBuilder() {}

    public static InMemCacheBuilder newBuilder() {
        return new InMemCacheBuilder();
    }

    public InMemCacheBuilder maxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
        return this;
    }

    public InMemCacheBuilder segments(int segments) {
        this.segments = Integer.highestOneBit(segments);
        return this;
    }

    public InMemCacheBuilder ttlTickMs(long ttlTickMs) {
        this.ttlTickMs = ttlTickMs;
        return this;
    }

    public InMemCacheBuilder ttlBuckets(int ttlBuckets) {
        this.ttlBuckets = ttlBuckets;
        return this;
    }

    public <K, V> InMemCache<K, V> build(RefreshFunction<K, V> refreshFn) {
        CacheConfig config = new CacheConfig(maxEntries, segments, ttlTickMs, ttlBuckets);
        return new InMemCacheImpl<>(config, refreshFn);
    }
}

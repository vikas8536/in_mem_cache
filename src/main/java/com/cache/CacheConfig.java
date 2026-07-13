package com.cache;

public class CacheConfig {
    private final int maxEntries;
    private final int segments;
    private final long ttlTickMs;
    private final int ttlBuckets;

    CacheConfig(int maxEntries, int segments, long ttlTickMs, int ttlBuckets) {
        this.maxEntries = maxEntries;
        this.segments = segments;
        this.ttlTickMs = ttlTickMs;
        this.ttlBuckets = ttlBuckets;
    }

    public int maxEntries() { return maxEntries; }
    public int segments() { return segments; }
    public long ttlTickMs() { return ttlTickMs; }
    public int ttlBuckets() { return ttlBuckets; }
}

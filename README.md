# In-Memory Cache

A high-performance, lock-striped in-memory cache for Java 21.

## Features

- Lock-striped architecture for concurrent throughput
- StampedLock optimistic reads — CAS-free fast path
- CLOCK eviction (≈95% of true LRU)
- Bucket-based TTL purging (lazy + background)
- Load-on-miss refresh function

## Usage

```java
InMemCache<String, UserProfile> cache = InMemCacheBuilder
    .newBuilder()
    .maxEntries(10_000_000)
    .segments(16)
    .ttlTickMs(10)
    .ttlBuckets(1000)
    .build(key -> database.loadProfile(key));

// Put
cache.put("user-42", profile, 30_000);

// Get (hits cache or loads via refresh function)
UserProfile p = cache.get("user-42");

// Delete
cache.delete("user-42");

// Shutdown
cache.close();
```

## Performance

Target: 1M+ ops/sec (100:1 read:write ratio) on modern hardware.

## Design

See [docs/superpowers/specs/2026-07-14-in-mem-cache-design.md](docs/superpowers/specs/2026-07-14-in-mem-cache-design.md).

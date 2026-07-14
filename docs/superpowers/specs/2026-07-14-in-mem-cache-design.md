# In-Memory Cache Design

## Overview

A high-performance, scalable in-memory cache written in Java supporting `put(k,v,ttl)`, `get(k)`, `delete(k)`, and a user-provided `refreshFunction` for lazy load-on-miss. Target throughput: 1M+ ops/sec with 100:1 read-to-write ratio.

## Architecture

```
┌──────────────────────────────────────────────────┐
│                  InMemCache<K,V>                  │
│                                                  │
│  Segment[0..N-1]   (N = cores * 2, power of 2)  │
│           │                                      │
│  ┌────────▼─────────────────────────────────┐    │
│  │ Segment                                    │   │
│  │                                            │   │
│  │  ConcurrentHashMap<K, Node<V>>             │   │
│  │    Node { V val, AtomicBoolean refBit,     │   │
│  │            long expiresAtNanos }            │   │
│  │                                            │   │
│  │  CLOCK Ring (key[])                        │   │
│  │    size = maxCapacity / segments           │   │
│  │    insertPtr (put)                         │   │
│  │    hand (eviction sweep)                   │   │
│  │                                            │   │
│  │  TTL Ring (CircularBucket[])               │   │
│  │    1000 buckets × TICK_MS span             │   │
│  │    head (purger advances)                  │   │
│      │  bucket = ConcurrentLinkedQueue<K>       │   │
│  │                                            │   │
│  │  StampedLock                               │   │
│  │    optimistic: get() reads CHM + refBit    │   │
│  │    write-lock: put, delete, evict, purger  │   │
│  └────────────────────────────────────────────┘   │
│                                                  │
│  Global: ScheduledExecutor (purger tick),        │
│          SegmentSelector: hash & (N-1)           │
└──────────────────────────────────────────────────┘
```

## Components

### Segment

The cache is divided into N segments (N = `Runtime.availableProcessors() * 2`, rounded to power of 2). Each segment operates independently with its own data structures and lock. Segment selection: `segmentIndex = key.hashCode() & (N - 1)`.

### Node

```java
class Node<V> {
    V value;
    AtomicBoolean refBit;
    long expiresAtNanos;
}
```

The `refBit` is used by the CLOCK eviction algorithm — set on every read, cleared by the eviction sweep.

### ConcurrentHashMap

Primary data store. O(1) lookups, already thread-safe for reads. Used as the authoritative source of truth for all entries.

### CLOCK Eviction Ring

A circular array of keys (not nodes). Size equals the segment's max capacity. Two pointers:

- **insertPtr**: where the next `put()` writes its key. Advances on every insert.
- **hand**: where the eviction sweep starts. Advances on every eviction scan.

No hashing or collision — it's a plain ordered ring buffer.

### TTL Ring Buffer

1000 buckets arranged as a circular array. A scheduled purger advances `head` every `TICK_MS` (e.g., 10ms), draining the bucket at the current position. Each bucket is a `ConcurrentLinkedQueue<K>` of keys. Supports TTLs up to `BUCKETS * TICK_MS` (10s with defaults); entries with longer TTLs wrap — the lazy `get()` check catches the stale case.

## Operations

### get(k)

```
seg = segmentFor(k)
stamp = seg.lock.tryOptimisticRead()
node = seg.chm.get(k)
if (node == null || now > node.expiresAtNanos)
    → return refreshFunction.load(k)           // miss: load from source
if (!seg.lock.validate(stamp)) {
    stamp = seg.lock.readLock()
    node = seg.chm.get(k)
    seg.lock.unlockRead(stamp)
}
node.refBit.set(true)                          // CLOCK mark
return node.value
```

Fast path: zero lock acquisitions, just a volatile read on the CHM and one `AtomicBoolean.set(true)`.

### put(k, v, ttl)

```
seg = segmentFor(k)
seg.lock.writeLock()
try:
    // evict if full (this segment)
    while (seg.chm.size >= MAX_PER_SEGMENT)
        evictOne()
    // insert (old value silently overwritten in CHM;
    // stale CLOCK slot is cleaned lazily by evict hand)
    seg.chm.put(k, new Node(v, ttl, refBit=false))
    seg.clock.ring[seg.clock.insertPtr] = k
    seg.clock.insertPtr = (seg.clock.insertPtr + 1) % SIZE
    // TTL bucket
    bucketIdx = ((now / TICK_MS) + (ttl / TICK_MS)) % BUCKETS
    seg.ttlBuckets[bucketIdx].add(k)
finally:
    seg.lock.unlockWrite()
```

### delete(k)

```
seg = segmentFor(k)
seg.lock.writeLock()
try:
    node = seg.chm.remove(k)
    if (node != null) {
        // clear CLOCK slot
        seg.clock.ring[slotFor(k)] = null
        // skip TTL bucket removal: lazy cleanup via purger
    }
    return node != null ? node.value : null
finally:
    seg.lock.unlockWrite()
```

### evictOne()

```
while true:
    k = seg.clock.ring[seg.clock.hand]
    if (k == null) { seg.clock.hand = (hand + 1) % SIZE; continue }
    node = seg.chm.get(k)
    if (node == null || node.expired) { seg.clock.ring[hand] = null; advance hand; continue }
    if (node.refBit.getAndSet(false))    // second chance
        { advance hand; continue }
    seg.chm.remove(k)
    seg.clock.ring[hand] = null
    advance hand
    return
```

### TTL Purger

```
// Scheduled every TICK_MS
for each segment:
    seg.lock.writeLock()
    bucket = seg.ttlBuckets[head]
    for each k in bucket:
        node = seg.chm.get(k)
        if (node != null && now > node.expiresAtNanos)
            seg.chm.remove(k)
    head = (head + 1) % BUCKETS
    seg.lock.unlockWrite()
```

## Concurrency Model

| Operation | Lock | Rationale |
|---|---|---|
| `get()` (fast path) | StampedLock optimistic | No CAS, pure volatile read |
| `get()` (writer conflicted) | StampedLock readLock | Rare — only when a write is concurrent |
| `put()` / `delete()` / evict / purger | StampedLock writeLock | Serializes mutations within a segment |

Strict read-after-write consistency: `put()` holds write-lock on the segment, so a concurrent `get()` either sees the old value and the write hasn't happened, or the write has and the `get()` reads the new value.

## TTL Design

| Aspect | Choice |
|---|---|
| Tick span | 10ms |
| Number of buckets | 1000 |
| Max TTL before wrap | 10 seconds |
| Expired entries between ticks | Caught by lazy check on `get()` |
| Purger concurrency | Write-lock per segment, independent across segments |

## Performance Characteristics

- Read throughput: 1M+ ops/sec at 100:1 read:write on modern hardware
- Read latency: CAS-free path, single CHM get + atomic bit set (~20-50ns)
- Write latency: segment write-lock, O(1) insert + amortized O(1) eviction
- Eviction quality: ~95% of true LRU (CLOCK proven)

## Configuration

```java
InMemCacheBuilder<K,V>
    .maxEntries(10_000_000)
    .segments(16)
    .ttlTickMs(10)
    .ttlBuckets(1000)
    .refreshFunction(key -> loadFromSource(key))
    .build();
```

## Future Considerations

- **Object pooling**: Node and key array entries can be pooled to reduce GC pressure at very high throughput.
- **Write-combining ring**: If write throughput grows significantly, a Disruptor-style write buffer before segments can batch mutations.
- **TinyLFU**: If workload becomes scan-heavy (large one-shot reads), CLOCK degrades — TinyLFU is a drop-in replacement for the eviction policy.

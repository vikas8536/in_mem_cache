# In-Memory Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a high-performance, lock-striped in-memory cache in Java with CLOCK eviction, bucket-based TTL purging, and StampedLock concurrency.

**Architecture:** Lock-striped segment cache dividing entries across N segments. Each segment has a ConcurrentHashMap for data, a CLOCK ring for LRU eviction, a TTL ring for expiry, and a StampedLock. Reads use optimistic locking (CAS-free fast path). Writes take the write lock.

**Tech Stack:** Java 21, Maven, JUnit 5 (assertions only — no mocking framework), no external dependencies.

## Global Constraints

- Java 21 source/target (no modules required)
- Zero external runtime dependencies — no Guava, Caffeine, or any third-party lib
- Package: `com.cache`
- No Javadoc requirement (design is self-documenting; public API surface is minimal)
- All concurrency uses `java.util.concurrent` primitives only
- Maven project with `pom.xml`

---

### Task 1: Project Scaffolding

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/cache/package-info.java`

**Interfaces:**
- Consumes: nothing
- Produces: compilable Maven project with Java 21

- [ ] **Step 1: Create pom.xml**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.cache</groupId>
    <artifactId>in-mem-cache</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.11.4</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create package-info.java**

```java
/**
 * High-performance lock-striped in-memory cache with CLOCK eviction
 * and bucket-based TTL purging.
 */
package com.cache;
```

- [ ] **Step 3: Verify project compiles**

Run:
```
mvn compile -q
```
Expected: BUILD SUCCESS (no output with `-q`)

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/java/com/cache/package-info.java
git commit -m "chore: scaffold Maven project (Java 21)"
```

---

### Task 2: Core Types — Node, CacheConfig, RefreshFunction

**Files:**
- Create: `src/main/java/com/cache/Node.java`
- Create: `src/main/java/com/cache/CacheConfig.java`
- Create: `src/main/java/com/cache/RefreshFunction.java`
- Create: `src/test/java/com/cache/NodeTest.java`
- Create: `src/test/java/com/cache/CacheConfigTest.java`

**Interfaces:**
- Consumes: nothing
- Produces: `Node<V>`, `CacheConfig`, `RefreshFunction<K,V>`

- [ ] **Step 1: Write Node test**

```java
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
```

- [ ] **Step 2: Run test to see it fail**

Run: `mvn test -pl . -Dtest=NodeTest -q 2>&1 || true`
Expected: compilation error — Node does not exist

- [ ] **Step 3: Write Node implementation**

```java
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
```

- [ ] **Step 4: Run Node test to pass**

Run: `mvn test -Dtest=NodeTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Write CacheConfig**

```java
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
```

- [ ] **Step 6: Write CacheConfig test**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheConfigTest {
    @Test
    void holdsValues() {
        CacheConfig cfg = new CacheConfig(1000, 16, 10, 1000);
        assertEquals(1000, cfg.maxEntries());
        assertEquals(16, cfg.segments());
        assertEquals(10, cfg.ttlTickMs());
        assertEquals(1000, cfg.ttlBuckets());
    }
}
```

- [ ] **Step 7: Write RefreshFunction**

```java
package com.cache;

@FunctionalInterface
public interface RefreshFunction<K, V> {
    V load(K key);
}
```

- [ ] **Step 8: Run all tests**

Run: `mvn test -Dtest="NodeTest,CacheConfigTest" -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/cache/Node.java src/main/java/com/cache/CacheConfig.java src/main/java/com/cache/RefreshFunction.java src/test/java/com/cache/NodeTest.java src/test/java/com/cache/CacheConfigTest.java
git commit -m "feat: add core types (Node, CacheConfig, RefreshFunction)"
```

---

### Task 3: CLOCK Ring

**Files:**
- Create: `src/main/java/com/cache/ClockRing.java`
- Create: `src/test/java/com/cache/ClockRingTest.java`

**Interfaces:**
- Consumes: `K` (generic key type)
- Produces: `ClockRing<K>` — circular array of keys for CLOCK eviction

- [ ] **Step 1: Write ClockRing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ClockRingTest -q 2>&1 || true`
Expected: compilation error — ClockRing does not exist

- [ ] **Step 3: Write ClockRing implementation**

```java
package com.cache;

class ClockRing<K> {
    private final Object[] ring;
    private final int size;
    private int insertPtr;
    private int hand;

    ClockRing(int capacity) {
        this.size = capacity;
        this.ring = new Object[capacity];
        this.insertPtr = 0;
        this.hand = 0;
    }

    void add(K key) {
        ring[insertPtr] = key;
        insertPtr = (insertPtr + 1) % size;
    }

    @SuppressWarnings("unchecked")
    K peekHand() {
        return (K) ring[hand];
    }

    void advanceHand() {
        hand = (hand + 1) % size;
    }

    void clearSlot(int position) {
        ring[position % size] = null;
    }
}
```

- [ ] **Step 4: Run ClockRing test to pass**

Run: `mvn test -Dtest=ClockRingTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cache/ClockRing.java src/test/java/com/cache/ClockRingTest.java
git commit -m "feat: add CLOCK eviction ring buffer"
```

---

### Task 4: TTL Ring

**Files:**
- Create: `src/main/java/com/cache/TtlRing.java`
- Create: `src/test/java/com/cache/TtlRingTest.java`

**Interfaces:**
- Consumes: `K` (generic key type)
- Produces: `TtlRing<K>` — circular array of `ConcurrentLinkedQueue<K>` buckets

- [ ] **Step 1: Write TtlRing test**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Queue;

class TtlRingTest {
    @Test
    void addAndDrainBucket() {
        TtlRing<String> ring = new TtlRing<>(10);
        ring.add("a", 3);
        ring.add("b", 3);
        ring.advanceHead(); // now head = 1
        ring.advanceHead(); // now head = 2
        ring.advanceHead(); // now head = 3
        Queue<String> bucket = ring.drainBucket();
        assertTrue(bucket.contains("a"));
        assertTrue(bucket.contains("b"));
    }

    @Test
    void drainBucketReturnsEmptyQueueWhenEmpty() {
        TtlRing<String> ring = new TtlRing<>(10);
        Queue<String> bucket = ring.drainBucket();
        assertNotNull(bucket);
        assertTrue(bucket.isEmpty());
    }

    @Test
    void headWrapsAround() {
        TtlRing<String> ring = new TtlRing<>(3);
        ring.add("a", 2);
        ring.advanceHead(); // 1
        ring.advanceHead(); // 2
        ring.advanceHead(); // 0
        ring.advanceHead(); // 1
        ring.add("b", 0);
        Queue<String> bucket = ring.drainBucket(); // bucket 0
        assertTrue(bucket.contains("b"));
    }

    @Test
    void bucketIndexOutOfBoundsThrows() {
        TtlRing<String> ring = new TtlRing<>(5);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> ring.add("x", 5));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TtlRingTest -q 2>&1 || true`
Expected: compilation error

- [ ] **Step 3: Write TtlRing implementation**

```java
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
```

- [ ] **Step 4: Run TtlRing test to pass**

Run: `mvn test -Dtest=TtlRingTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/cache/TtlRing.java src/test/java/com/cache/TtlRingTest.java
git commit -m "feat: add TTL ring buffer"
```

---

### Task 5: Segment — Core Operations

**Files:**
- Create: `src/main/java/com/cache/Segment.java`
- Create: `src/test/java/com/cache/SegmentTest.java`

**Interfaces:**
- Consumes: `Node<V>`, `ClockRing<K>`, `TtlRing<K>`, `CacheConfig`, `RefreshFunction<K,V>`
- Produces: `Segment<K,V>` with public methods `get(key)`, `put(key, value, ttlMs)`, `delete(key)`, `size()`, `purgeExpired()`

- [ ] **Step 1: Write Segment test**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class SegmentTest {
    private final CacheConfig cfg = new CacheConfig(100, 4, 10, 10);

    @Test
    void putAndGet() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        assertEquals("apple", seg.get("a"));
    }

    @Test
    void getReturnsNullForMissingKey() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        assertNull(seg.get("missing"));
    }

    @Test
    void deleteRemovesEntry() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        assertEquals("apple", seg.delete("a"));
        assertNull(seg.get("a"));
    }

    @Test
    void deleteReturnsNullForMissing() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        assertNull(seg.delete("missing"));
    }

    @Test
    void refreshFunctionCalledOnMiss() {
        AtomicInteger calls = new AtomicInteger(0);
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> {
            calls.incrementAndGet();
            return "loaded:" + k;
        });
        assertEquals("loaded:x", seg.get("x"));
        assertEquals(1, calls.get());
    }

    @Test
    void refreshFunctionNotCalledOnHit() {
        AtomicInteger calls = new AtomicInteger(0);
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> {
            calls.incrementAndGet();
            return "loaded:" + k;
        });
        seg.put("x", "cached", 60_000);
        assertEquals("cached", seg.get("x"));
        assertEquals(0, calls.get());
    }

    @Test
    void expiredEntryReturnsNullAndTriggersMiss() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("x", "stale", -1); // already expired
        assertNull(seg.get("x"));
    }

    @Test
    void evictionFiresWhenFull() {
        Segment<Integer, String> seg = new Segment<>(
            new CacheConfig(10, 4, 10, 10), 0, k -> null);
        for (int i = 0; i < 20; i++) {
            seg.put(i, "v" + i, 60_000);
        }
        assertTrue(seg.size() <= 12); // some evicted (10 max + evict loop buffer)
    }

    @Test
    void purgeExpiredRemovesStaleEntries() {
        Segment<String, String> seg = new Segment<>(cfg, 0, k -> null);
        seg.put("a", "apple", 60_000);
        seg.put("b", "banana", 60_000);
        seg.put("c", "cherry", -1); // expired
        seg.purgeExpired();
        assertEquals(2, seg.size());
        assertNull(seg.get("c"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=SegmentTest -q 2>&1 || true`
Expected: compilation error

- [ ] **Step 3a: Add `clearCurrentSlot()` to ClockRing**

Add to `ClockRing.java`:
```java
void clearCurrentSlot() {
    ring[hand] = null;
}
```

- [ ] **Step 3b: Write Segment implementation**

```java
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
```

- [ ] **Step 4: Run Segment test to pass**

Run: `mvn test -Dtest=SegmentTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cache/ClockRing.java src/main/java/com/cache/Segment.java src/test/java/com/cache/SegmentTest.java
git commit -m "feat: add Segment with get/put/delete/evict/purge"
```

---

### Task 6: Public API — InMemCache Interface and Builder

**Files:**
- Create: `src/main/java/com/cache/InMemCache.java`
- Create: `src/main/java/com/cache/InMemCacheBuilder.java`
- Create: `src/main/java/com/cache/InMemCacheImpl.java`
- Create: `src/test/java/com/cache/InMemCacheTest.java`

**Interfaces:**
- Consumes: `Segment<K,V>`, `CacheConfig`, `RefreshFunction<K,V>`
- Produces: `InMemCache<K,V>` interface, `InMemCacheBuilder<K,V>` builder, `InMemCacheImpl<K,V>`

- [ ] **Step 1: Write InMemCache interface and test**

```java
// InMemCache.java
package com.cache;

public interface InMemCache<K, V> {
    V get(K key);
    void put(K key, V value, long ttlMs);
    V delete(K key);
    long size();
    void close();
    void clear();
}
```

```java
// InMemCacheTest.java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

class InMemCacheTest {
    @Test
    void putAndGet() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder()
            .maxEntries(100)
            .segments(4)
            .build(k -> null);
        cache.put("a", "apple", 60_000);
        assertEquals("apple", cache.get("a"));
        cache.close();
    }

    @Test
    void deleteReturnsValueAndRemoves() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "apple", 60_000);
        assertEquals("apple", cache.delete("a"));
        assertNull(cache.get("a"));
        cache.close();
    }

    @Test
    void getReturnsNullForMissingKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertNull(cache.get("missing"));
        cache.close();
    }

    @Test
    void refreshFunctionCalledOnMiss() {
        AtomicInteger calls = new AtomicInteger(0);
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4)
            .build(k -> { calls.incrementAndGet(); return "loaded:" + k; });
        assertEquals("loaded:x", cache.get("x"));
        assertEquals(1, calls.get());
        cache.close();
    }

    @Test
    void sizeReflectsEntryCount() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertEquals(0, cache.size());
        cache.put(1, "a", 60_000);
        cache.put(2, "b", 60_000);
        assertEquals(2, cache.size());
        cache.delete(1);
        assertEquals(1, cache.size());
        cache.close();
    }

    @Test
    void clearRemovesAll() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put(1, "a", 60_000);
        cache.put(2, "b", 60_000);
        cache.clear();
        assertEquals(0, cache.size());
        cache.close();
    }

    @Test
    void concurrentReadsDoNotBlock() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);
        for (int i = 0; i < 10_000; i++) {
            cache.put(i, i, 60_000);
        }
        AtomicInteger errors = new AtomicInteger(0);
        Thread[] readers = new Thread[8];
        for (int t = 0; t < 8; t++) {
            int threadId = t;
            readers[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    Integer v = cache.get(i);
                    if (v != null && v != i) {
                        errors.incrementAndGet();
                    }
                }
            });
            readers[t].start();
        }
        for (Thread r : readers) r.join();
        assertEquals(0, errors.get());
        cache.close();
    }

    @Test
    void readAfterWriteConsistency() {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(1000).segments(4).build(k -> null);
        cache.put(42, 100, 60_000);
        assertEquals(100, (int) cache.get(42));
        cache.put(42, 200, 60_000);
        assertEquals(200, (int) cache.get(42));
        cache.close();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=InMemCacheTest -q 2>&1 || true`
Expected: compilation errors

- [ ] **Step 3: Write InMemCacheBuilder and InMemCacheImpl**

```java
// InMemCacheBuilder.java
package com.cache;

public class InMemCacheBuilder<K, V> {
    private int maxEntries = 1_000_000;
    private int segments = Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
    private long ttlTickMs = 10;
    private int ttlBuckets = 1000;

    private InMemCacheBuilder() {}

    public static <K, V> InMemCacheBuilder<K, V> newBuilder() {
        return new InMemCacheBuilder<>();
    }

    public InMemCacheBuilder<K, V> maxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
        return this;
    }

    public InMemCacheBuilder<K, V> segments(int segments) {
        this.segments = Integer.highestOneBit(segments); // round to power of 2
        return this;
    }

    public InMemCacheBuilder<K, V> ttlTickMs(long ttlTickMs) {
        this.ttlTickMs = ttlTickMs;
        return this;
    }

    public InMemCacheBuilder<K, V> ttlBuckets(int ttlBuckets) {
        this.ttlBuckets = ttlBuckets;
        return this;
    }

    public InMemCache<K, V> build(RefreshFunction<K, V> refreshFn) {
        CacheConfig config = new CacheConfig(maxEntries, segments, ttlTickMs, ttlBuckets);
        return new InMemCacheImpl<>(config, refreshFn);
    }
}
```

```java
// InMemCacheImpl.java
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
        // Start TTL purger
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
```

- [ ] **Step 4: Run test to pass**

Run: `mvn test -Dtest=InMemCacheTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run all tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS (all tests pass)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/cache/InMemCache.java src/main/java/com/cache/InMemCacheBuilder.java src/main/java/com/cache/InMemCacheImpl.java src/main/java/com/cache/Segment.java src/test/java/com/cache/InMemCacheTest.java
git commit -m "feat: add public InMemCache API with builder"
```

---

### Task 7: Concurrent Consistency Tests

**Files:**
- Create: `src/test/java/com/cache/ConcurrentConsistencyTest.java`

**Interfaces:**
- Consumes: `InMemCache` (full API via builder)
- Produces: Concurrency correctness tests

- [ ] **Step 1: Write concurrent read-after-write test**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ConcurrentConsistencyTest {
    @Test
    void readAfterWriteConsistencyUnderConcurrentWrites() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(1000).segments(4).build(k -> null);

        int threads = 8;
        int opsPerThread = 10_000;
        AtomicBoolean failed = new AtomicBoolean(false);

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int base = t * opsPerThread;
            workers[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    int key = base + i;
                    cache.put(key, key, 60_000);
                    Integer v = cache.get(key);
                    if (v == null || v != key) {
                        failed.set(true);
                    }
                }
            });
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        assertFalse(failed.get());
        cache.close();
    }

    @Test
    void concurrentPutDoesNotLoseEntries() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(10_000).segments(8).build(k -> null);

        int threads = 10;
        int entriesPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int base = t * entriesPerThread;
            new Thread(() -> {
                for (int i = 0; i < entriesPerThread; i++) {
                    cache.put(base + i, base + i, 60_000);
                }
                latch.countDown();
            }).start();
        }
        latch.await(10, TimeUnit.SECONDS);

        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < entriesPerThread; i++) {
                Integer v = cache.get(t * entriesPerThread + i);
                assertNotNull(v, "Entry lost at key " + (t * entriesPerThread + i));
            }
        }
        cache.close();
    }

    @Test
    void concurrentReadsWithEvictionNeverReturnStaleData() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(500).segments(4).build(k -> null);

        // Fill to trigger eviction
        for (int i = 0; i < 1000; i++) {
            cache.put(i, i, 60_000);
        }

        AtomicBoolean sawWrongValue = new AtomicBoolean(false);
        Thread updater = new Thread(() -> {
            for (int round = 0; round < 100; round++) {
                for (int i = 0; i < 500; i++) {
                    cache.put(i, round, 60_000);
                }
            }
        });

        Thread reader = new Thread(() -> {
            for (int round = 0; round < 100; round++) {
                for (int i = 0; i < 500; i++) {
                    Integer v = cache.get(i);
                    if (v != null && (v < 0 || v > 99)) {
                        sawWrongValue.set(true);
                    }
                }
            }
        });

        updater.start();
        reader.start();
        updater.join();
        reader.join();

        assertFalse(sawWrongValue.get(), "Reader saw a value that was never written");
        cache.close();
    }
}
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=ConcurrentConsistencyTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/cache/ConcurrentConsistencyTest.java
git commit -m "test: add concurrent consistency tests"
```

---

### Task 8: Performance Smoke Test

**Files:**
- Create: `src/test/java/com/cache/PerformanceSmokeTest.java`

**Interfaces:**
- Consumes: `InMemCache` (full API)
- Produces: Throughput validation (informal, not CI-gated)

- [ ] **Step 1: Write throughput test**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.*;
import java.util.concurrent.*;

class PerformanceSmokeTest {
    @Test
    void readThroughputOneMillionOps() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);

        // Pre-populate
        for (int i = 0; i < 50_000; i++) {
            cache.put(i, i, 60_000);
        }

        int threads = 8;
        int opsPerThread = 125_000; // 1M total reads
        AtomicLong totalOps = new AtomicLong(0);

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                long count = 0;
                long start = System.nanoTime();
                for (int i = 0; i < opsPerThread; i++) {
                    cache.get(i % 50_000);
                    count++;
                }
                totalOps.addAndGet(count);
            });
        }

        long start = System.nanoTime();
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        long elapsed = System.nanoTime() - start;

        double opsPerSec = (double) totalOps.get() / (elapsed / 1_000_000_000.0);
        System.out.printf("Read throughput: %.2f ops/sec%n", opsPerSec);

        // We expect at least 500K ops/sec on any modern machine
        assertTrue(opsPerSec > 500_000, "Throughput too low: " + opsPerSec);
        cache.close();
    }

    @Test
    void mixedWorkloadThroughput() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);

        int threads = 8;
        int opsPerThread = 50_000;
        AtomicLong readOps = new AtomicLong(0);
        AtomicLong writeOps = new AtomicLong(0);

        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    int key = ThreadLocalRandom.current().nextInt(10_000);
                    if (ThreadLocalRandom.current().nextInt(100) < 99) {
                        // 99% reads
                        cache.get(key);
                        readOps.incrementAndGet();
                    } else {
                        // 1% writes
                        cache.put(key, i, 60_000);
                        writeOps.incrementAndGet();
                    }
                }
            });
        }

        long start = System.nanoTime();
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();
        long elapsed = System.nanoTime() - start;

        double totalOps = readOps.get() + writeOps.get();
        double opsPerSec = totalOps / (elapsed / 1_000_000_000.0);
        System.out.printf("Mixed throughput: %.2f ops/sec (reads=%d, writes=%d)%n",
            opsPerSec, readOps.get(), writeOps.get());

        assertTrue(opsPerSec > 500_000, "Mixed throughput too low: " + opsPerSec);
        cache.close();
    }
}
```

- [ ] **Step 2: Run smoke test (informal)**

Run: `mvn test -Dtest=PerformanceSmokeTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/cache/PerformanceSmokeTest.java
git commit -m "test: add performance smoke test"
```

---

### Task 9: Full Integration — Handling Edge Cases

**Files:**
- Create: `src/test/java/com/cache/EdgeCaseTest.java`
- Modify: `src/main/java/com/cache/Segment.java` (fix any edge case bugs)

- [ ] **Step 1: Write edge case tests**

```java
package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EdgeCaseTest {
    @Test
    void nullKeyThrows() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        assertThrows(NullPointerException.class, () -> cache.put(null, "x", 1000));
        assertThrows(NullPointerException.class, () -> cache.get(null));
        assertThrows(NullPointerException.class, () -> cache.delete(null));
        cache.close();
    }

    @Test
    void ttlOfZeroExpiresImmediately() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "apple", 0);
        assertNull(cache.get("a"));
        cache.close();
    }

    @Test
    void putOverwritesExistingKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "first", 60_000);
        cache.put("a", "second", 60_000);
        assertEquals("second", cache.get("a"));
        cache.close();
    }

    @Test
    void maxTtlDoesNotOverflow() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> null);
        cache.put("a", "value", Long.MAX_VALUE);
        assertEquals("value", cache.get("a"));
        cache.close();
    }

    @Test
    void refreshFunctionCalledWithCorrectKey() {
        InMemCache<String, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100).segments(4).build(k -> {
                assertEquals("expected-key", k);
                return "loaded";
            });
        assertEquals("loaded", cache.get("expected-key"));
        cache.close();
    }

    @Test
    void deleteOnEvictedEntryReturnsNull() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(10).segments(1).build(k -> null);
        for (int i = 0; i < 100; i++) {
            cache.put(i, "v" + i, 60_000);
        }
        // Many entries were evicted; delete should return null for evicted ones
        // At least some of the early entries should be gone
        boolean foundEvicted = false;
        for (int i = 0; i < 50; i++) {
            if (cache.delete(i) == null) {
                foundEvicted = true;
                break;
            }
        }
        assertTrue(foundEvicted, "Expected some entries to have been evicted");
        cache.close();
    }

    @Test
    void repeatedPutCyclesDoNotLeak() {
        InMemCache<Integer, String> cache = InMemCacheBuilder
            .newBuilder().maxEntries(1000).segments(4).build(k -> null);
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 2000; i++) {
                cache.put(i, "round" + round, 60_000);
            }
        }
        assertTrue(cache.size() <= 1000, "Cache exceeded max entries: " + cache.size());
        cache.close();
    }
}
```

- [ ] **Step 2: Run tests and fix any failures**

Run: `mvn test -Dtest=EdgeCaseTest -q`
Expected: BUILD SUCCESS. If any fail, fix the implementation and re-run.

Known edge cases:
- `ttl=0`: Node is created expired. `get()` node exists but `isExpired()` is true → calls `refreshFunction.load(k)`, which calls `put()` with default 60s TTL. This is correct behavior.
- `maxEntries=0`: Per-segment capacity = `0/4 + 1 = 1`. Each segment holds 1 entry max, so some entries are evicted immediately.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/cache/Segment.java src/test/java/com/cache/EdgeCaseTest.java
git commit -m "test: add edge case tests and fix discovered issues"
```

---

### Task 10: Documentation and Cleanup

**Files:**
- Create: `README.md`

**Interfaces:**
- Consumes: final implementation
- Produces: README with usage examples and architecture summary

- [ ] **Step 1: Write README**

```markdown
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
```

- [ ] **Step 2: Run full test suite**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add README with usage and architecture summary"
```

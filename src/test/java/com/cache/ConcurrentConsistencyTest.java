package com.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class ConcurrentConsistencyTest {
    @Test
    void readAfterWriteConsistencyUnderConcurrentWrites() throws Exception {
        InMemCache<Integer, Integer> cache = InMemCacheBuilder
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);

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
            .newBuilder().maxEntries(100_000).segments(8).build(k -> null);

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

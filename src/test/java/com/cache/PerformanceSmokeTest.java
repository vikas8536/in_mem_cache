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

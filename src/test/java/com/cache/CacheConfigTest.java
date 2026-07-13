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

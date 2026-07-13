package com.cache;

public interface InMemCache<K, V> {
    V get(K key);
    void put(K key, V value, long ttlMs);
    V delete(K key);
    long size();
    void close();
    void clear();
}

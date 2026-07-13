package com.cache;

@FunctionalInterface
public interface RefreshFunction<K, V> {
    V load(K key);
}

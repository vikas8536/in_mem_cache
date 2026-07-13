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
        int attempts = 0;
        do {
            hand = (hand + 1) % size;
            attempts++;
        } while (ring[hand] == null && attempts < size);
    }

    void clearCurrentSlot() {
        ring[hand] = null;
    }

    void clearSlot(int position) {
        ring[position % size] = null;
    }
}

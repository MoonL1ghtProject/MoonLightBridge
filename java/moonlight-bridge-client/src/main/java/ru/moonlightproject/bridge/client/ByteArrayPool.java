package ru.moonlightproject.bridge.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

final class ByteArrayPool {
    private static final int MIN_BUCKET_SHIFT = 8;
    private static final int MAX_BUCKET_SHIFT = 23;
    private static final int MAX_PER_BUCKET = 32;

    private final boolean enabled;
    private final ConcurrentLinkedQueue<byte[]>[] buckets;
    private final AtomicInteger[] sizes;

    @SuppressWarnings("unchecked")
    ByteArrayPool(boolean enabled) {
        this.enabled = enabled;
        int count = MAX_BUCKET_SHIFT - MIN_BUCKET_SHIFT + 1;
        buckets = new ConcurrentLinkedQueue[count];
        sizes = new AtomicInteger[count];
        for (int i = 0; i < count; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
            sizes[i] = new AtomicInteger();
        }
    }

    byte[] acquire(int minimumLength) {
        int index = bucket(minimumLength);
        if (!enabled || index < 0) return new byte[minimumLength];
        byte[] value = buckets[index].poll();
        if (value != null) {
            sizes[index].decrementAndGet();
            return value;
        }
        return new byte[1 << (index + MIN_BUCKET_SHIFT)];
    }

    void release(byte[] value) {
        if (!enabled) return;
        int index = bucket(value.length);
        if (index < 0 || value.length != 1 << (index + MIN_BUCKET_SHIFT)) return;
        if (sizes[index].incrementAndGet() <= MAX_PER_BUCKET) buckets[index].offer(value);
        else sizes[index].decrementAndGet();
    }

    private static int bucket(int length) {
        if (length <= 0 || length > 1 << MAX_BUCKET_SHIFT) return -1;
        int size = Math.max(length, 1 << MIN_BUCKET_SHIFT);
        int shift = 32 - Integer.numberOfLeadingZeros(size - 1);
        return shift - MIN_BUCKET_SHIFT;
    }
}

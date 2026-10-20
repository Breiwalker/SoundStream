package dev.breiwalker.soundstream.client.audio;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class PcmQueue {
    private static final int CAPACITY = 96;

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong bufferedBytes = new AtomicLong();
    private volatile boolean closed;
    private volatile boolean ended;

    public boolean offer(byte[] data) {
        if (closed) {
            return false;
        }
        try {
            while (!closed) {
                if (queue.offer(data, 100, TimeUnit.MILLISECONDS)) {
                    bufferedBytes.addAndGet(data.length);
                    return true;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
        }
        return false;
    }

    public byte[] take(long timeoutMillis) {
        try {
            byte[] data = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (data != null) {
                bufferedBytes.addAndGet(-data.length);
            }
            return data;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public long bufferedBytes() {
        return bufferedBytes.get();
    }

    public void end() {
        ended = true;
    }

    public boolean ended() {
        return ended;
    }

    public boolean endedAndEmpty() {
        return ended && queue.isEmpty();
    }

    public void close() {
        closed = true;
        ended = true;
        queue.clear();
        bufferedBytes.set(0);
    }
}

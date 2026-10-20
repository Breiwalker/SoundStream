package dev.breiwalker.soundstream.client.stream;

/**
 * Bounded ring buffer for encoded audio that has arrived but has not been
 * consumed by the decoder. It replaces the old whole-file array, so memory use
 * is independent of song length and the server can pace transfer with CREDIT.
 */
public final class EncodedBuffer {
    private final byte[] buffer;
    private int readIndex;
    private int writeIndex;
    private int count;
    private long consumed;
    private long credited;
    private boolean ended;
    private boolean closed;

    public EncodedBuffer(int capacity) {
        this.buffer = new byte[Math.max(1024, capacity)];
    }

    public synchronized boolean offer(byte[] source, int offset, int length) {
        if (closed || length <= 0) {
            return false;
        }
        if (length > buffer.length - count) {
            return false;
        }
        int first = Math.min(length, buffer.length - writeIndex);
        System.arraycopy(source, offset, buffer, writeIndex, first);
        System.arraycopy(source, offset + first, buffer, 0, length - first);
        writeIndex = (writeIndex + length) % buffer.length;
        count += length;
        notifyAll();
        return true;
    }

    /**
     * Blocks until at least one byte is available. Returns -1 once the stream
     * has ended (or was closed) and no buffered data remains.
     */
    public synchronized int read(byte[] target, int offset, int length) {
        if (length <= 0) {
            return 0;
        }
        while (count == 0 && !ended && !closed) {
            try {
                wait(100L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (closed || count == 0) {
            return -1;
        }
        int copied = Math.min(length, count);
        int first = Math.min(copied, buffer.length - readIndex);
        System.arraycopy(buffer, readIndex, target, offset, first);
        System.arraycopy(buffer, 0, target, offset + first, copied - first);
        readIndex = (readIndex + copied) % buffer.length;
        count -= copied;
        consumed += copied;
        return copied;
    }

    public synchronized int available() {
        return count;
    }

    /** Bytes the decoder has taken since the last credit report. */
    public synchronized int takeCredit() {
        long pending = consumed - credited;
        if (pending <= 0) {
            return 0;
        }
        credited = consumed;
        return (int) Math.min(Integer.MAX_VALUE, pending);
    }

    public synchronized void end() {
        ended = true;
        notifyAll();
    }

    public synchronized void close() {
        closed = true;
        count = 0;
        notifyAll();
    }
}

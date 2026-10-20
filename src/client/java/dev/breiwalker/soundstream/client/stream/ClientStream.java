package dev.breiwalker.soundstream.client.stream;

import dev.breiwalker.soundstream.client.audio.AudioDecoderThread;
import dev.breiwalker.soundstream.client.audio.AudioEngine;
import dev.breiwalker.soundstream.client.audio.PcmQueue;
import dev.breiwalker.soundstream.protocol.CodecDetector;
import dev.breiwalker.soundstream.protocol.Protocol;
import dev.breiwalker.soundstream.protocol.ProtocolMessages;

import java.util.Arrays;

public final class ClientStream {
    private static final int CODEC_SAMPLE_BYTES = 64 * 1024;

    private final AudioEngine audio;
    private final int id;
    private final int codec;
    private final int totalBytes;
    private final boolean positional;
    private final boolean loop;
    private final PcmQueue pcmQueue = new PcmQueue();
    private final AudioDecoderThread decoder;

    /**
     * Every stream, including loops, keeps only a bounded encoded ring buffer.
     * Loop passes are re-sent by the server and split by LOOP markers.
     */
    private final EncodedBuffer encoded;
    private final byte[] codecSample;
    private int codecSampleLength;

    private volatile float volume;
    private volatile float range;
    private volatile float x;
    private volatile float y;
    private volatile float z;

    private volatile int sampleRate;
    private volatile int audioChannels;
    private volatile boolean formatSet;
    private volatile boolean running = true;
    private volatile boolean closed;
    private volatile boolean playbackFinished;
    private volatile int closeReason = Protocol.REASON_STOPPED;
    private volatile String closeMessage = "";
    private volatile boolean sendDoneOnClose;
    private volatile int receivedBytes;
    private volatile boolean endReceived;

    private int loopBytesReceived;
    private int loopBytesRead;
    private int loopBoundariesPending;

    private int expectedSequence;
    private boolean headerChecked;

    public ClientStream(
            AudioEngine audio,
            int id,
            int codec,
            boolean positional,
            boolean loop,
            int totalBytes,
            int windowBytes,
            float volume,
            float range,
            float x,
            float y,
            float z
    ) {
        this.audio = audio;
        this.id = id;
        this.codec = codec;
        this.positional = positional;
        this.loop = loop;
        this.totalBytes = totalBytes;
        this.volume = clamp(volume);
        this.range = range;
        this.x = x;
        this.y = y;
        this.z = z;

        this.encoded = new EncodedBuffer(windowBytes);
        this.codecSample = loop ? null : new byte[Math.min(totalBytes, CODEC_SAMPLE_BYTES)];
        this.decoder = new AudioDecoderThread(this);
        this.decoder.start();
    }

    public int id() {
        return id;
    }

    public int codec() {
        return codec;
    }

    public int totalBytes() {
        return totalBytes;
    }

    public boolean positional() {
        return positional;
    }

    public boolean loop() {
        return loop;
    }

    public float volume() {
        return volume;
    }

    public float range() {
        return range;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public float z() {
        return z;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int audioChannels() {
        return audioChannels;
    }

    public PcmQueue pcmQueue() {
        return pcmQueue;
    }

    public boolean running() {
        return running;
    }

    public boolean closed() {
        return closed;
    }

    public boolean playbackFinished() {
        return playbackFinished;
    }

    public int closeReason() {
        return closeReason;
    }

    public String closeMessage() {
        return closeMessage;
    }

    public boolean sendDoneOnClose() {
        return sendDoneOnClose;
    }

    public void update(float volume, float x, float y, float z) {
        this.volume = clamp(volume);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void onData(ProtocolMessages.Data data) {
        onDataFrame(data.sequence(), data.payload(), 0, data.payload().length);
    }

    /**
     * Writes a DATA frame payload straight into the bounded encoded buffer
     * without allocating an intermediate payload array.
     */
    public void onDataFrame(int sequence, byte[] source, int offset, int length) {
        if (!running) {
            return;
        }
        if (sequence != expectedSequence++) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Unexpected chunk sequence");
            return;
        }
        if (length < 1) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Empty chunk");
            return;
        }
        if (loop) {
            if (loopBytesReceived + length > totalBytes) {
                fail(Protocol.REASON_PROTOCOL_ERROR, "Chunk exceeds declared pass size");
                return;
            }
            if (!headerChecked) {
                if (!CodecDetector.matches(codec, source, offset)) {
                    fail(Protocol.REASON_UNSUPPORTED, "Stream container does not match the codec");
                    return;
                }
                headerChecked = true;
            }
            if (!encoded.offer(source, offset, length)) {
                fail(Protocol.REASON_PROTOCOL_ERROR, "Client buffer window exceeded");
                return;
            }
            loopBytesReceived += length;
        } else {
            if (receivedBytes + length > totalBytes) {
                fail(Protocol.REASON_PROTOCOL_ERROR, "Chunk exceeds declared size");
                return;
            }
            if (!headerChecked) {
                if (!CodecDetector.matches(codec, source, offset)) {
                    fail(Protocol.REASON_UNSUPPORTED, "Stream container does not match the codec");
                    return;
                }
                headerChecked = true;
            }
            if (codecSample != null && codecSampleLength < codecSample.length) {
                int copy = Math.min(length, codecSample.length - codecSampleLength);
                System.arraycopy(source, offset, codecSample, codecSampleLength, copy);
                codecSampleLength += copy;
            }
            if (!encoded.offer(source, offset, length)) {
                fail(Protocol.REASON_PROTOCOL_ERROR, "Client buffer window exceeded");
                return;
            }
            receivedBytes += length;
        }
        synchronized (this) {
            notifyAll();
        }
    }

    public int receivedBytes() {
        return receivedBytes;
    }

    public boolean endReceived() {
        return endReceived;
    }

    /**
     * Reads encoded bytes for the decoder. Loop streams stop at the end of the
     * current pass and resume after the next LOOP marker, even if bytes for the
     * next pass are already buffered.
     */
    public int readEncoded(byte[] target, int offset, int length) {
        if (!running) {
            return -1;
        }
        if (loop) {
            int remaining = totalBytes - loopBytesRead;
            if (remaining <= 0) {
                return -1;
            }
            int read = encoded.read(target, offset, Math.min(length, remaining));
            if (read > 0) {
                loopBytesRead += read;
            }
            return read;
        }
        return encoded.read(target, offset, length);
    }

    public int availableEncoded() {
        if (loop) {
            return Math.max(0, Math.min(encoded.available(), totalBytes - loopBytesRead));
        }
        return encoded.available();
    }

    public boolean atPassEnd() {
        return loop && loopBytesRead >= totalBytes;
    }

    /** Waits until the server marks the end of the current loop pass. */
    public boolean awaitLoopBoundary() {
        synchronized (this) {
            while (running && loopBoundariesPending <= 0) {
                try {
                    wait(100L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return running;
        }
    }

    /** Called by the decoder after a pass drained and a LOOP marker arrived. */
    public void beginNextPass() {
        synchronized (this) {
            if (loopBoundariesPending > 0) {
                loopBoundariesPending--;
            }
            loopBytesRead = 0;
        }
    }

    /** Server finished one pass of a looped stream; more DATA follows. */
    public void onLoop() {
        if (!running) {
            return;
        }
        if (!loop) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Unexpected loop boundary");
            return;
        }
        if (loopBytesReceived != totalBytes) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Loop pass ended before all bytes arrived");
            return;
        }
        loopBytesReceived = 0;
        synchronized (this) {
            loopBoundariesPending++;
            notifyAll();
        }
    }

    /** Bytes to grant back to the server since the previous call. */
    public int takeCredit() {
        return encoded.takeCredit();
    }

    public boolean awaitEnd() {
        synchronized (this) {
            while (running && !endReceived) {
                try {
                    wait(100L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return running;
        }
    }

    public void onEnd() {
        if (!running) {
            return;
        }
        if (loop) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Unexpected END on a looped stream");
            return;
        }
        if (receivedBytes != totalBytes) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Stream ended before all bytes arrived");
            return;
        }
        try {
            byte[] sample = Arrays.copyOf(codecSample, codecSampleLength);
            int detected = CodecDetector.detect(sample);
            if (detected != codec) {
                fail(Protocol.REASON_UNSUPPORTED, "Audio container does not match the declared codec");
                return;
            }
        } catch (RuntimeException exception) {
            fail(Protocol.REASON_UNSUPPORTED, exception.getMessage());
            return;
        }
        endReceived = true;
        encoded.end();
        synchronized (this) {
            notifyAll();
        }
    }

    public void setFormat(int sampleRate, int channels) {
        if (!running) {
            return;
        }
        if (formatSet) {
            if (this.sampleRate != sampleRate || this.audioChannels != channels) {
                fail(Protocol.REASON_DECODE_ERROR, "Decoder changed audio format mid-stream");
            }
            return;
        }
        this.sampleRate = sampleRate;
        this.audioChannels = channels;
        this.formatSet = true;
        audio.attach(this);
    }

    public void onPlaybackFinished() {
        if (!running) {
            return;
        }
        playbackFinished = true;
        stopLocal(Protocol.REASON_FINISHED, "", true);
    }

    public void onDecodeError(Throwable throwable) {
        fail(Protocol.REASON_DECODE_ERROR, throwable.getMessage());
    }

    public void onAudioError(Throwable throwable) {
        fail(Protocol.REASON_AUDIO_UNAVAILABLE, throwable.getMessage());
    }

    public void onAudioUnavailable() {
        fail(Protocol.REASON_AUDIO_UNAVAILABLE, "No Java Sound output device");
    }

    public void serverClose(int reason, String message) {
        stopLocal(reason, message, false);
    }

    public void stopByManager(int reason, String message, boolean sendDone) {
        stopLocal(reason, message, sendDone);
    }

    private void fail(int reason, String message) {
        if (!running) {
            return;
        }
        stopLocal(reason, message == null ? "" : message, true);
    }

    private void stopLocal(int reason, String message, boolean sendDone) {
        if (closed) {
            return;
        }
        running = false;
        closed = true;
        closeReason = reason;
        closeMessage = message == null ? "" : message;
        sendDoneOnClose = sendDone;
        encoded.close();
        synchronized (this) {
            notifyAll();
        }
        decoder.stop();
        pcmQueue.close();
        audio.detach(this);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

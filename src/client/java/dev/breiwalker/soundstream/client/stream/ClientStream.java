package dev.breiwalker.soundstream.client.stream;

import dev.breiwalker.soundstream.client.audio.AudioDecoderThread;
import dev.breiwalker.soundstream.client.audio.AudioEngine;
import dev.breiwalker.soundstream.client.audio.PcmQueue;
import dev.breiwalker.soundstream.protocol.CodecDetector;
import dev.breiwalker.soundstream.protocol.Protocol;
import dev.breiwalker.soundstream.protocol.ProtocolMessages;

public final class ClientStream {
    private final AudioEngine audio;
    private final int id;
    private final int codec;
    private final int totalBytes;
    private final boolean positional;
    private final boolean loop;
    private final PcmQueue pcmQueue = new PcmQueue();
    private final byte[] fullBuffer;
    private final AudioDecoderThread decoder;

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

    private int receivedBytes;
    private int expectedSequence;
    private boolean headerChecked;

    public ClientStream(
            AudioEngine audio,
            int id,
            int codec,
            boolean positional,
            boolean loop,
            int totalBytes,
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

        // The server sends the complete song. Chunking exists only because a
        // Minecraft custom payload has a small size limit.
        this.fullBuffer = new byte[totalBytes];
        this.decoder = new AudioDecoderThread(this, fullBuffer);
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
     * Writes a DATA frame payload straight into the full-file buffer without
     * allocating an intermediate payload array.
     */
    public void onDataFrame(int sequence, byte[] source, int offset, int length) {
        if (!running) {
            return;
        }
        if (sequence != expectedSequence++) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Unexpected chunk sequence");
            return;
        }
        if (length < 1 || receivedBytes + length > totalBytes) {
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
        System.arraycopy(source, offset, fullBuffer, receivedBytes, length);
        receivedBytes += length;
    }

    public void onEnd() {
        if (!running) {
            return;
        }
        if (receivedBytes != totalBytes) {
            fail(Protocol.REASON_PROTOCOL_ERROR, "Stream ended before all bytes arrived");
            return;
        }
        try {
            int detected = CodecDetector.detect(fullBuffer);
            if (detected != codec) {
                fail(Protocol.REASON_UNSUPPORTED, "Audio container does not match the declared codec");
                return;
            }
        } catch (RuntimeException exception) {
            fail(Protocol.REASON_UNSUPPORTED, exception.getMessage());
            return;
        }
        decoder.start();
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
        decoder.stop();
        pcmQueue.close();
        audio.detach(this);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

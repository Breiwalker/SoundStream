package dev.breiwalker.soundstream.client.audio;

import dev.breiwalker.soundstream.client.stream.ClientStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * One Java Sound output line per stream. The decoder already wrote 16-bit
 * little-endian PCM; this class applies volume and, for 3D streams, distance
 * attenuation and equal-power panning.
 */
final class StreamingSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("SoundStream");

    private final ClientStream stream;
    private final AudioEngine engine;
    private final SourceDataLine line;
    private final Thread thread;
    private final int fadeFrames;

    private volatile boolean closed;
    private long framesRendered;

    StreamingSource(ClientStream stream, AudioEngine engine) throws Exception {
        this.stream = stream;
        this.engine = engine;
        AudioFormat format = new AudioFormat(stream.sampleRate(), 16, 2, true, false);
        this.line = AudioSystem.getSourceDataLine(format);
        // A larger output buffer prevents underruns and crackle on loud, dense
        // tracks while still keeping latency around one second.
        this.line.open(format, Math.max(32_768, stream.sampleRate() * 4));
        this.fadeFrames = Math.max(1, stream.sampleRate() / 50); // 20 ms declick
        this.thread = new Thread(this::run, "SoundStream output " + stream.id());
        this.thread.setDaemon(true);
        this.thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2));
    }

    void start() {
        thread.start();
    }

    private void run() {
        try {
            // Fill a small lead-in before starting the device. This absorbs
            // decoder and GC jitter and removes the start-of-stream click.
            long prebufferBytes = Math.max(16_384L, stream.sampleRate() * 2L);
            while (!closed && stream.running()
                    && !stream.pcmQueue().ended()
                    && stream.pcmQueue().bufferedBytes() < prebufferBytes) {
                Thread.sleep(10L);
            }
            if (closed || !stream.running()) {
                return;
            }
            line.start();

            while (!closed && stream.running()) {
                byte[] pcm = stream.pcmQueue().take(100L);
                if (pcm == null) {
                    if (stream.pcmQueue().endedAndEmpty()) {
                        break;
                    }
                    continue;
                }
                byte[] mixed = mix(pcm);
                if (mixed.length > 0) {
                    write(mixed);
                }
            }
            if (!closed) {
                line.drain();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Throwable throwable) {
            if (!closed) {
                LOGGER.warn("SoundStream output failed", throwable);
                stream.onAudioError(throwable);
            }
        } finally {
            closeLine();
            LOGGER.debug("SoundStream {} output thread exited", stream.id());
            if (!closed && stream.running()) {
                stream.onPlaybackFinished();
            }
        }
    }

    private void write(byte[] data) throws InterruptedException {
        int offset = 0;
        while (offset < data.length && !closed && stream.running()) {
            int available = line.available();
            if (available <= 0) {
                Thread.sleep(5L);
                continue;
            }
            int length = Math.min(available, data.length - offset);
            line.write(data, offset, length);
            offset += length;
        }
    }

    private byte[] mix(byte[] pcm) {
        int channels = Math.max(1, stream.audioChannels());
        int frameBytes = channels * 2;
        int frames = pcm.length / frameBytes;
        byte[] output = new byte[frames * 4];

        float[] gains = gains();
        float leftGain = gains[0];
        float rightGain = gains[1];
        long startFrame = framesRendered;

        for (int frame = 0; frame < frames; frame++) {
            long absoluteFrame = startFrame + frame;
            float fade = absoluteFrame >= fadeFrames
                    ? 1.0f
                    : absoluteFrame / (float) fadeFrames;
            int offset = frame * frameBytes;
            short left = readShort(pcm, offset);
            short right = channels == 2 ? readShort(pcm, offset + 2) : left;
            writeShort(output, frame * 4, sample(left * leftGain * fade));
            writeShort(output, frame * 4 + 2, sample(right * rightGain * fade));
        }
        framesRendered += frames;
        return output;
    }

    private float[] gains() {
        float base = clamp(stream.volume()) * clamp(engine.masterVolume()) * 0.92f;
        if (!stream.positional()) {
            return new float[]{base, base};
        }

        AudioEngine.ListenerState listener = engine.listener();
        float dx = stream.x() - listener.x();
        float dy = stream.y() - listener.y();
        float dz = stream.z() - listener.z();
        float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float attenuation = Math.max(0.0f, 1.0f - distance / Math.max(1.0f, stream.range()));
        attenuation *= attenuation;
        if (attenuation <= 0.0f) {
            return new float[]{0.0f, 0.0f};
        }

        // right = forward x up
        float rx = listener.forwardY() * listener.upZ() - listener.forwardZ() * listener.upY();
        float ry = listener.forwardZ() * listener.upX() - listener.forwardX() * listener.upZ();
        float rz = listener.forwardX() * listener.upY() - listener.forwardY() * listener.upX();
        float pan = 0.0f;
        if (distance > 0.0001f) {
            pan = (dx * rx + dy * ry + dz * rz) / distance;
        }
        pan = Math.max(-1.0f, Math.min(1.0f, pan));
        double angle = (pan + 1.0) * Math.PI / 4.0;
        return new float[]{
                base * attenuation * (float) Math.cos(angle),
                base * attenuation * (float) Math.sin(angle)
        };
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOGGER.debug("SoundStream {} stopping output", stream.id());
        try {
            line.stop();
            line.flush();
        } catch (RuntimeException ignored) {
            // already closed
        }
        thread.interrupt();
    }

    private void closeLine() {
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (RuntimeException ignored) {
            // already closed
        }
    }

    private static short readShort(byte[] data, int offset) {
        return (short) ((data[offset] & 0xFF) | (data[offset + 1] << 8));
    }

    private static short sample(float value) {
        int rounded = Math.round(value);
        if (rounded > Short.MAX_VALUE) {
            rounded = Short.MAX_VALUE;
        } else if (rounded < Short.MIN_VALUE) {
            rounded = Short.MIN_VALUE;
        }
        return (short) rounded;
    }

    private static void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}

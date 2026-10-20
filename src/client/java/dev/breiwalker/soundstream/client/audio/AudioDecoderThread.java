package dev.breiwalker.soundstream.client.audio;

import dev.breiwalker.soundstream.client.stream.ClientStream;
import dev.breiwalker.soundstream.protocol.Protocol;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes audio as it arrives from the bounded network buffer. Loop passes are
 * re-streamed by the server and restarted at each LOOP boundary.
 */
public final class AudioDecoderThread implements Runnable {
    private static final int TRANSFER_BYTES = 64 * 1024;

    private final ClientStream stream;
    private final Thread thread;

    public AudioDecoderThread(ClientStream stream) {
        this.stream = stream;
        this.thread = new Thread(this, "SoundStream decoder " + stream.id());
        this.thread.setDaemon(true);
        this.thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        thread.interrupt();
    }

    @Override
    public void run() {
        try {
            boolean firstPass = true;
            while (stream.running()) {
                if (!firstPass) {
                    if (!stream.awaitLoopBoundary()) {
                        break;
                    }
                    stream.beginNextPass();
                }
                firstPass = false;
                if (stream.codec() == Protocol.CODEC_OGG_VORBIS) {
                    decodeVorbisProgressive();
                } else {
                    decodeMp3(new StreamInputStream(stream));
                }
                if (!stream.loop() || !stream.running()) {
                    break;
                }
            }
            if (stream.running()) {
                stream.pcmQueue().end();
            }
        } catch (Throwable throwable) {
            if (stream.running()) {
                stream.onDecodeError(throwable);
            }
        }
    }

    private void decodeVorbisProgressive() throws Exception {
        long decoder = 0;
        ByteBuffer pending = ByteBuffer.allocateDirect(512 * 1024);
        byte[] transfer = new byte[TRANSFER_BYTES];
        int pendingLength = 0;
        try {
            STBVorbisInfo info = STBVorbisInfo.calloc();
            try {
                int inputChannels = 0;
                int outputChannels = 0;

                while (stream.running()) {
                    if (pendingLength == 0) {
                        pendingLength = append(pending, transfer, 0);
                        if (pendingLength < 0) {
                            return;
                        }
                    }

                    pending.position(0);
                    pending.limit(pendingLength);

                    if (decoder == 0) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            IntBuffer consumed = stack.mallocInt(1);
                            IntBuffer error = stack.mallocInt(1);
                            decoder = STBVorbis.stb_vorbis_open_pushdata(pending, consumed, error, null);
                            pendingLength = consume(pending, pendingLength, consumed.get(0));
                            if (decoder == 0) {
                                if (error.get(0) != STBVorbis.VORBIS_need_more_data) {
                                    throw new IOException("Invalid Ogg Vorbis stream");
                                }
                                if (stream.endReceived() && pendingLength == 0) {
                                    return;
                                }
                                if (pendingLength == pending.capacity()) {
                                    throw new IOException("Truncated Ogg Vorbis header");
                                }
                                pendingLength = append(pending, transfer, pendingLength);
                                if (pendingLength < 0) {
                                    return;
                                }
                                continue;
                            }
                        }
                        STBVorbis.stb_vorbis_get_info(decoder, info);
                        inputChannels = info.channels();
                        outputChannels = stream.positional() ? 1 : Math.min(2, inputChannels);
                        stream.setFormat(info.sample_rate(), outputChannels);
                        continue;
                    }

                    try (MemoryStack stack = MemoryStack.stackPush()) {
                        IntBuffer channelCount = stack.mallocInt(1);
                        IntBuffer samples = stack.mallocInt(1);
                        PointerBuffer output = stack.mallocPointer(1);
                        int used = STBVorbis.stb_vorbis_decode_frame_pushdata(
                                decoder, pending, channelCount, output, samples);
                        pendingLength = consume(pending, pendingLength, used);
                        int frames = samples.get(0);
                        if (frames <= 0) {
                            if (stream.endReceived() && pendingLength == 0) {
                                return;
                            }
                            if (used == 0) {
                                if (pendingLength == pending.capacity()) {
                                    throw new IOException("Truncated Ogg Vorbis frame");
                                }
                                pendingLength = append(pending, transfer, pendingLength);
                                if (pendingLength < 0) {
                                    return;
                                }
                            }
                            continue;
                        }

                        int decodedChannels = channelCount.get(0);
                        PointerBuffer channelPointers = MemoryUtil.memPointerBuffer(
                                output.get(0), decodedChannels);
                        byte[] pcm = PcmConverter.fromFloatChannels(
                                channelPointers, frames, decodedChannels, outputChannels);
                        if (!stream.pcmQueue().offer(pcm)) {
                            return;
                        }
                    }
                }
            } finally {
                info.free();
            }
        } finally {
            if (decoder != 0) {
                STBVorbis.stb_vorbis_close(decoder);
            }
        }
    }

    /**
     * Appends up to the remaining pending-buffer space, blocking until data
     * arrives. Returns the new pending length, or -1 at the end of the stream.
     */
    private int append(ByteBuffer pending, byte[] transfer, int pendingLength) {
        int space = pending.capacity() - pendingLength;
        if (space <= 0) {
            return pendingLength;
        }
        int read = stream.readEncoded(transfer, 0, Math.min(transfer.length, space));
        if (read < 0) {
            return -1;
        }
        pending.position(pendingLength);
        pending.limit(pending.capacity());
        pending.put(transfer, 0, read);
        return pendingLength + read;
    }

    private static int consume(ByteBuffer pending, int pendingLength, int used) {
        int consumed = Math.max(0, Math.min(pendingLength, used));
        pending.position(consumed);
        pending.compact();
        int remaining = pending.position();
        pending.position(remaining);
        return remaining;
    }

    private void decodeMp3(InputStream input) throws Exception {
        Bitstream bitstream = new Bitstream(input);
        Decoder decoder = new Decoder();
        try {
            while (stream.running()) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                SampleBuffer sample = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();
                if (sample == null) {
                    continue;
                }

                int inputChannels = Math.max(1, sample.getChannelCount());
                int outputChannels = stream.positional() ? 1 : Math.min(2, inputChannels);
                int totalShorts = sample.getBufferLength();
                int frames = totalShorts / inputChannels;
                if (frames <= 0) {
                    continue;
                }
                stream.setFormat(sample.getSampleFrequency(), outputChannels);
                byte[] pcm = PcmConverter.fromInterleavedShorts(
                        sample.getBuffer(), frames, inputChannels, outputChannels);
                if (!stream.pcmQueue().offer(pcm)) {
                    break;
                }
            }
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    /** Blocking InputStream over the bounded encoded buffer. */
    private static final class StreamInputStream extends InputStream {
        private final ClientStream stream;
        private final byte[] single = new byte[1];

        private StreamInputStream(ClientStream stream) {
            this.stream = stream;
        }

        @Override
        public int read() {
            int read = stream.readEncoded(single, 0, 1);
            return read <= 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (length == 0) {
                return 0;
            }
            return stream.readEncoded(target, targetOffset, length);
        }

        @Override
        public int available() {
            return Math.max(0, stream.availableEncoded());
        }
    }
}

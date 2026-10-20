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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes audio as it arrives. The first pass uses the growing network buffer;
 * loop passes reuse the complete file that is already in memory.
 */
public final class AudioDecoderThread implements Runnable {
    private final ClientStream stream;
    private final byte[] data;
    private final Thread thread;

    public AudioDecoderThread(ClientStream stream, byte[] data) {
        this.stream = stream;
        this.data = data;
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
                if (firstPass) {
                    firstPass = false;
                    if (stream.codec() == Protocol.CODEC_OGG_VORBIS) {
                        decodeVorbisProgressive();
                    } else {
                        decodeMp3(new StreamInputStream(stream));
                    }
                } else {
                    if (!stream.awaitEnd()) {
                        break;
                    }
                    if (stream.codec() == Protocol.CODEC_OGG_VORBIS) {
                        decodeVorbisMemory(data);
                    } else {
                        decodeMp3(new ByteArrayInputStream(data));
                    }
                }

                if (!stream.loop() || !stream.running()) {
                    break;
                }
                if (!stream.awaitEnd()) {
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
        int pendingLength = 0;
        int readPosition = 0;
        try {
            STBVorbisInfo info = STBVorbisInfo.calloc();
            try {
                int inputChannels = 0;
                int outputChannels = 0;

                while (stream.running()) {
                    int available = stream.receivedBytes();
                    if (readPosition < available && pendingLength < pending.capacity()) {
                        int copy = Math.min(available - readPosition, pending.capacity() - pendingLength);
                        pending.position(pendingLength);
                        pending.limit(pending.capacity());
                        pending.put(data, readPosition, copy);
                        pendingLength += copy;
                        readPosition += copy;
                    }

                    if (pendingLength == 0) {
                        if (stream.endReceived()) {
                            return;
                        }
                        if (!stream.awaitBytes(readPosition)) {
                            return;
                        }
                        continue;
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
                                if (stream.endReceived() && pendingLength == pending.capacity()) {
                                    throw new IOException("Truncated Ogg Vorbis header");
                                }
                                if (!stream.awaitBytes(readPosition)) {
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
                                if (stream.endReceived()) {
                                    throw new IOException("Truncated Ogg Vorbis frame");
                                }
                                if (!stream.awaitBytes(readPosition)) {
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

    private static int consume(ByteBuffer pending, int pendingLength, int used) {
        int consumed = Math.max(0, Math.min(pendingLength, used));
        pending.position(consumed);
        pending.compact();
        int remaining = pending.position();
        pending.position(remaining);
        return remaining;
    }

    private void decodeVorbisMemory(byte[] data) throws Exception {
        ByteBuffer nativeData = ByteBuffer.allocateDirect(data.length);
        nativeData.put(data).flip();
        long decoder = 0;
        try (STBVorbisInfo info = STBVorbisInfo.calloc()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer error = stack.mallocInt(1);
                decoder = STBVorbis.stb_vorbis_open_memory(nativeData, error, null);
                if (decoder == 0) {
                    throw new IOException("Could not open Ogg Vorbis stream (error " + error.get(0) + ")");
                }
                STBVorbis.stb_vorbis_get_info(decoder, info);
            }

            int inputChannels = info.channels();
            int outputChannels = stream.positional() ? 1 : Math.min(2, inputChannels);
            ByteBuffer pcmBytes = ByteBuffer.allocateDirect(info.max_frame_size() * inputChannels * 2)
                    .order(ByteOrder.nativeOrder());
            ShortBuffer pcm = pcmBytes.asShortBuffer();
            short[] samples = new short[info.max_frame_size() * inputChannels];
            stream.setFormat(info.sample_rate(), outputChannels);

            while (stream.running()) {
                pcm.clear();
                int frames = STBVorbis.stb_vorbis_get_frame_short_interleaved(decoder, inputChannels, pcm);
                if (frames == 0) {
                    break;
                }
                pcm.position(0);
                pcm.get(samples, 0, frames * inputChannels);
                if (!stream.pcmQueue().offer(PcmConverter.fromInterleavedShorts(
                        samples, frames, inputChannels, outputChannels))) {
                    break;
                }
            }
        } finally {
            if (decoder != 0) {
                STBVorbis.stb_vorbis_close(decoder);
            }
        }
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

    /**
     * Blocking InputStream over the growing full-file buffer. JLayer can parse
     * and decode frames while the rest of the song is still arriving.
     */
    private static final class StreamInputStream extends InputStream {
        private final ClientStream stream;
        private final byte[] data;
        private int position;

        private StreamInputStream(ClientStream stream) {
            this.stream = stream;
            this.data = stream.fullBuffer();
        }

        @Override
        public int read() {
            while (stream.running() && position >= stream.receivedBytes()) {
                if (stream.endReceived() || !stream.awaitData(position)) {
                    return -1;
                }
            }
            if (!stream.running()) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (length == 0) {
                return 0;
            }
            while (stream.running() && position >= stream.receivedBytes()) {
                if (stream.endReceived() || !stream.awaitData(position)) {
                    return -1;
                }
            }
            if (!stream.running()) {
                return -1;
            }
            int available = Math.min(length, stream.receivedBytes() - position);
            System.arraycopy(data, position, target, targetOffset, available);
            position += available;
            return available;
        }

        @Override
        public int available() {
            return Math.max(0, stream.receivedBytes() - position);
        }
    }
}

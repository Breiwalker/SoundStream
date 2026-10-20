package dev.breiwalker.soundstream.client.audio;

import dev.breiwalker.soundstream.client.stream.ClientStream;
import dev.breiwalker.soundstream.protocol.Protocol;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Decodes the complete encoded song into PCM. Playback only begins after the
 * whole file has been received.
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
            while (stream.running()) {
                if (stream.codec() == Protocol.CODEC_OGG_VORBIS) {
                    decodeVorbisMemory(data);
                } else {
                    decodeMp3(new ByteArrayInputStream(data));
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

    private void decodeMp3(ByteArrayInputStream input) throws Exception {
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
}

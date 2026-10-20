package dev.breiwalker.soundstream.client.audio;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;

public final class PcmConverter {
    public static byte[] fromInterleavedShorts(short[] samples, int frames, int inputChannels, int outputChannels) {
        int safeFrames = Math.max(0, frames);
        int sourceChannels = Math.max(1, inputChannels);
        int targetChannels = Math.max(1, Math.min(2, outputChannels));
        byte[] output = new byte[safeFrames * targetChannels * 2];

        for (int frame = 0; frame < safeFrames; frame++) {
            int base = frame * sourceChannels;
            if (targetChannels == 1) {
                int sum = 0;
                for (int channel = 0; channel < sourceChannels; channel++) {
                    sum += samples[base + channel];
                }
                writeShort(output, frame * 2, (short) (sum / sourceChannels));
            } else if (sourceChannels == 1) {
                short value = samples[base];
                writeShort(output, frame * 4, value);
                writeShort(output, frame * 4 + 2, value);
            } else {
                writeShort(output, frame * 4, samples[base]);
                writeShort(output, frame * 4 + 2, samples[base + 1]);
            }
        }
        return output;
    }

    public static byte[] fromFloatChannels(PointerBuffer channels, int frames, int inputChannels, int outputChannels) {
        int safeFrames = Math.max(0, frames);
        int sourceChannels = Math.max(1, inputChannels);
        int targetChannels = Math.max(1, Math.min(2, outputChannels));
        byte[] output = new byte[safeFrames * targetChannels * 2];

        for (int frame = 0; frame < safeFrames; frame++) {
            if (targetChannels == 1) {
                float sum = 0.0f;
                for (int channel = 0; channel < sourceChannels; channel++) {
                    sum += MemoryUtil.memGetFloat(channels.get(channel) + (long) frame * Float.BYTES);
                }
                writeShort(output, frame * 2, toShort(sum / sourceChannels));
            } else if (sourceChannels == 1) {
                short value = toShort(MemoryUtil.memGetFloat(channels.get(0) + (long) frame * Float.BYTES));
                writeShort(output, frame * 4, value);
                writeShort(output, frame * 4 + 2, value);
            } else {
                writeShort(output, frame * 4,
                        toShort(MemoryUtil.memGetFloat(channels.get(0) + (long) frame * Float.BYTES)));
                writeShort(output, frame * 4 + 2,
                        toShort(MemoryUtil.memGetFloat(channels.get(1) + (long) frame * Float.BYTES)));
            }
        }
        return output;
    }

    private static short toShort(float value) {
        float clamped = Math.max(-1.0f, Math.min(1.0f, value));
        return (short) (clamped * 32767.0f);
    }

    private static void writeShort(byte[] output, int offset, short value) {
        output[offset] = (byte) (value & 0xFF);
        output[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private PcmConverter() {
    }
}

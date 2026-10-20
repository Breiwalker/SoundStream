package dev.breiwalker.soundstream.client.audio;

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

    private static void writeShort(byte[] output, int offset, short value) {
        output[offset] = (byte) (value & 0xFF);
        output[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private PcmConverter() {
    }
}

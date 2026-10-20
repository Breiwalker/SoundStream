package dev.breiwalker.soundstream.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Server and client both use this before handing bytes to a decoder. Extensions
 * alone are not trusted.
 */
public final class CodecDetector {
    private static final byte[] OGG_MAGIC = {'O', 'g', 'g', 'S'};
    private static final byte[] VORBIS_MAGIC = {1, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] OPUS_MAGIC = "OpusHead".getBytes(StandardCharsets.US_ASCII);

    public static int detect(byte[] data, String filename) {
        String extension = extensionOf(filename);
        if (!extension.equals("ogg") && !extension.equals("mp3")) {
            throw new IllegalArgumentException("Only .ogg and .mp3 files are accepted");
        }

        int codec = detect(data);
        if (!extension.equals(codecExtension(codec))) {
            throw new IllegalArgumentException("File extension does not match its audio container");
        }
        return codec;
    }

    public static int detect(byte[] data) {
        if (data == null || data.length < 4) {
            throw new IllegalArgumentException("Audio file is too short");
        }
        if (startsWith(data, OGG_MAGIC)) {
            if (contains(data, OPUS_MAGIC, 64 * 1024)) {
                throw new IllegalArgumentException("Ogg Opus is not supported; use Ogg Vorbis");
            }
            if (!contains(data, VORBIS_MAGIC, 64 * 1024)) {
                throw new IllegalArgumentException("Ogg stream is not Vorbis");
            }
            return Protocol.CODEC_OGG_VORBIS;
        }
        if (startsWith(data, "ID3".getBytes(StandardCharsets.US_ASCII)) || isMpegFrameSync(data)) {
            return Protocol.CODEC_MP3;
        }
        throw new IllegalArgumentException("Unsupported audio container");
    }

    /**
     * Used for progressive OGG streams where only the first few chunks may have
     * arrived. A deferred result still requires the full header eventually.
     */
    public static boolean looksLikeKnownContainer(byte[] data) {
        return startsWith(data, OGG_MAGIC)
                || startsWith(data, "ID3".getBytes(StandardCharsets.US_ASCII))
                || isMpegFrameSync(data);
    }

    public static boolean matches(int codec, byte[] prefix) {
        return matches(codec, prefix, 0);
    }

    public static boolean matches(int codec, byte[] data, int offset) {
        if (data == null || data.length - offset < 4) {
            return false;
        }
        return switch (codec) {
            case Protocol.CODEC_OGG_VORBIS -> startsWith(data, offset, OGG_MAGIC);
            case Protocol.CODEC_MP3 -> startsWith(data, offset, "ID3".getBytes(StandardCharsets.US_ASCII))
                    || isMpegFrameSync(data, offset);
            default -> false;
        };
    }

    public static boolean isOggVorbisHeaderComplete(byte[] data) {
        return startsWith(data, OGG_MAGIC) && contains(data, VORBIS_MAGIC, 64 * 1024);
    }

    public static String codecExtension(int codec) {
        return switch (codec) {
            case Protocol.CODEC_OGG_VORBIS -> "ogg";
            case Protocol.CODEC_MP3 -> "mp3";
            default -> "";
        };
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isMpegFrameSync(byte[] data) {
        return isMpegFrameSync(data, 0);
    }

    private static boolean isMpegFrameSync(byte[] data, int offset) {
        if (data.length - offset < 3) {
            return false;
        }
        return (data[offset] & 0xFF) == 0xFF
                && (data[offset + 1] & 0xE0) == 0xE0
                && ((data[offset + 1] >> 1) & 0x03) == 0x01;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return startsWith(data, 0, prefix);
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        if (data.length - offset < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] data, byte[] needle, int limit) {
        int max = Math.min(data.length - needle.length, limit);
        for (int i = 0; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private CodecDetector() {
    }
}

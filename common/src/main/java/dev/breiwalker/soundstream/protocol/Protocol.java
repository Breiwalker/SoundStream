package dev.breiwalker.soundstream.protocol;

/**
 * Constants shared by the Fabric client and the Paper plugin.
 *
 * <p>The wire format is deliberately free of Minecraft and Paper types so both
 * implementations can compile the same source files and stay in sync.</p>
 */
public final class Protocol {
    public static final String CHANNEL = "soundstream:main";

    public static final int MAGIC = 0x5353;
    public static final int VERSION = 1;

    public static final int MAX_STRING_BYTES = 256;
    public static final int MAX_DATA_BYTES = 65_535;
    public static final int HARD_MAX_FRAME_BYTES = 1_048_576;

    public static final int CODEC_OGG_VORBIS = 1;
    public static final int CODEC_MP3 = 2;

    public static final int FLAG_POSITIONAL = 1;
    public static final int FLAG_LOOP = 1 << 1;

    public static final int REASON_FINISHED = 0;
    public static final int REASON_STOPPED = 1;
    public static final int REASON_DECODE_ERROR = 2;
    public static final int REASON_AUDIO_UNAVAILABLE = 3;
    public static final int REASON_PROTOCOL_ERROR = 4;
    public static final int REASON_UNSUPPORTED = 5;
    public static final int REASON_FILE_ERROR = 6;
    public static final int REASON_TOO_MANY_STREAMS = 7;
    public static final int REASON_CLIENT_UNSUPPORTED = 8;
    public static final int REASON_TIMEOUT = 9;

    public static final int TYPE_HELLO = 0x01;
    public static final int TYPE_CREDIT = 0x02;
    public static final int TYPE_DONE = 0x03;

    public static final int TYPE_HELLO_ACK = 0x81;
    public static final int TYPE_HELLO_QUERY = 0x82;
    public static final int TYPE_OPEN = 0x10;
    public static final int TYPE_DATA = 0x11;
    public static final int TYPE_END = 0x12;
    public static final int TYPE_UPDATE = 0x13;
    public static final int TYPE_CLOSE = 0x14;

    public static final int DEFAULT_CLIENT_WINDOW_BYTES = 512 * 1024;
    public static final int DEFAULT_CLIENT_CHUNK_BYTES = 32 * 1024;
    public static final int DEFAULT_CLIENT_MAX_STREAMS = 4;
    public static final int DEFAULT_FULL_BUFFER_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_LOOP_CACHE_BYTES = 8 * 1024 * 1024;
    public static final int DEFAULT_SERVER_MAX_FILE_BYTES = 32 * 1024 * 1024;
    public static final int DEFAULT_SERVER_MAX_STREAMS = 4;

    public static String reasonName(int reason) {
        return switch (reason) {
            case REASON_FINISHED -> "finished";
            case REASON_STOPPED -> "stopped";
            case REASON_DECODE_ERROR -> "decode-error";
            case REASON_AUDIO_UNAVAILABLE -> "audio-unavailable";
            case REASON_PROTOCOL_ERROR -> "protocol-error";
            case REASON_UNSUPPORTED -> "unsupported";
            case REASON_FILE_ERROR -> "file-error";
            case REASON_TOO_MANY_STREAMS -> "too-many-streams";
            case REASON_CLIENT_UNSUPPORTED -> "client-unsupported";
            case REASON_TIMEOUT -> "timeout";
            default -> "unknown-" + reason;
        };
    }

    private Protocol() {
    }
}

package dev.breiwalker.soundstream.protocol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolCodecTest {
    @Test
    void roundTripsAllMessages() throws Exception {
        assertRoundTrip(new ProtocolMessages.Hello(1, 2, 3, 4, 5, "client"));
        assertRoundTrip(new ProtocolMessages.Credit(7, 1024));
        assertRoundTrip(new ProtocolMessages.Done(7, Protocol.REASON_FINISHED, "done"));
        assertRoundTrip(new ProtocolMessages.HelloAck(2048, 4096, 4, "server"));
        assertRoundTrip(new ProtocolMessages.HelloQuery());
        assertRoundTrip(new ProtocolMessages.Open(9, Protocol.CODEC_OGG_VORBIS, true, false, 1234, 0.5f, 32f, 1f, 2f, 3f));
        assertRoundTrip(new ProtocolMessages.Data(9, 4, new byte[]{1, 2, 3, 4}));
        assertRoundTrip(new ProtocolMessages.End(9));
        assertRoundTrip(new ProtocolMessages.Update(9, 0.25f, 4f, 5f, 6f));
        assertRoundTrip(new ProtocolMessages.Close(9, Protocol.REASON_UNSUPPORTED, "nope"));
    }

    @Test
    void rejectsBadMagicAndVersion() {
        byte[] hello = ProtocolCodec.encode(new ProtocolMessages.Hello(1, 2, 3, 4, 5, "x"));
        hello[0] = 0;
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(hello));

        byte[] version = ProtocolCodec.encode(new ProtocolMessages.Hello(1, 2, 3, 4, 5, "x"));
        version[2] = 99;
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(version));
    }

    @Test
    void rejectsTruncatedAndTrailingBytes() {
        byte[] frame = ProtocolCodec.encode(new ProtocolMessages.Credit(1, 2));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(Arrays.copyOf(frame, frame.length - 1)));
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(Arrays.copyOf(frame, frame.length + 1)));
    }

    @Test
    void rejectsOversizedDataAndStrings() {
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolCodec.encode(new ProtocolMessages.Data(1, 0, new byte[Protocol.MAX_DATA_BYTES + 1])));

        String tooLong = "x".repeat(Protocol.MAX_STRING_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolCodec.encode(new ProtocolMessages.Done(1, 0, tooLong)));
    }

    @Test
    void rejectsUnknownFlags() {
        byte[] frame = ProtocolCodec.encode(new ProtocolMessages.Open(1, 1, false, false, 1, 1, 1, 0, 0, 0));
        frame[9] = (byte) 0x80;
        assertThrows(ProtocolException.class, () -> ProtocolCodec.decode(frame));
    }

    @Test
    void detectsSupportedContainers() {
        assertEquals(Protocol.CODEC_OGG_VORBIS, CodecDetector.detect("OggS....\u0001vorbis".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        assertEquals(Protocol.CODEC_MP3, CodecDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xFB, 0x10, 0x00}));
        assertEquals(Protocol.CODEC_MP3, CodecDetector.detect("ID3....".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> CodecDetector.detect("OggSOpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> CodecDetector.detect(new byte[]{1, 2, 3, 4}));
    }

    @Test
    void extensionMustMatchContainer() {
        assertEquals(Protocol.CODEC_MP3, CodecDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xFB, 1, 2}, "a.MP3"));
        assertThrows(IllegalArgumentException.class,
                () -> CodecDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xFB, 1, 2}, "a.ogg"));
        assertThrows(IllegalArgumentException.class,
                () -> CodecDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xFB, 1, 2}, "a.wav"));
    }

    private static void assertRoundTrip(ProtocolMessages message) throws Exception {
        byte[] encoded = ProtocolCodec.encode(message);
        ProtocolMessages decoded = ProtocolCodec.decode(encoded);
        assertEquals(message.getClass(), decoded.getClass());
        if (message instanceof ProtocolMessages.Data data) {
            ProtocolMessages.Data actual = (ProtocolMessages.Data) decoded;
            assertArrayEquals(data.payload(), actual.payload());
            assertEquals(data.sequence(), actual.sequence());
            assertEquals(data.streamId(), actual.streamId());
        } else {
            assertEquals(message, decoded);
        }
    }
}

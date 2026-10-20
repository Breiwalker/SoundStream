package dev.breiwalker.soundstream.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ProtocolCodec {
    public record DataHeader(int streamId, int sequence, int length, int payloadOffset) {
    }

    public static byte[] encode(ProtocolMessages message) {
        return encode(message, Protocol.VERSION);
    }

    /**
     * Encodes a message using an older protocol version. Paper uses this to
     * reply to clients that completed the handshake with an older mod.
     */
    public static byte[] encode(ProtocolMessages message, int version) {
        if (!Protocol.isSupportedVersion(version)) {
            throw new IllegalArgumentException("Unsupported protocol version " + version);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeShort(Protocol.MAGIC);
            out.writeByte(version);
            out.writeByte(typeOf(message));
            out.writeInt(streamIdOf(message));
            writeBody(out, message);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode protocol message", exception);
        }
    }

    /**
     * Encodes a DATA frame directly from a slice of the asset, avoiding a
     * per-chunk intermediate copy on the server.
     */
    public static byte[] encodeData(int streamId, int sequence, byte[] data, int offset, int length) {
        return encodeData(streamId, sequence, data, offset, length, Protocol.VERSION);
    }

    public static byte[] encodeData(int streamId, int sequence, byte[] data, int offset, int length, int version) {
        if (!Protocol.isSupportedVersion(version)) {
            throw new IllegalArgumentException("Unsupported protocol version " + version);
        }
        if (length < 1 || length > Protocol.MAX_DATA_BYTES) {
            throw new IllegalArgumentException("Invalid data length");
        }
        if (offset < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("Data slice out of bounds");
        }
        byte[] frame = new byte[14 + length];
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        buffer.putShort((short) Protocol.MAGIC);
        buffer.put((byte) version);
        buffer.put((byte) Protocol.TYPE_DATA);
        buffer.putInt(streamId);
        buffer.putInt(sequence);
        buffer.putShort((short) length);
        buffer.put(data, offset, length);
        return frame;
    }

    public static int peekVersion(byte[] frame) throws ProtocolException {
        checkHeader(frame);
        return frame[2] & 0xFF;
    }

    public static int peekType(byte[] frame) throws ProtocolException {
        checkHeader(frame);
        return frame[3] & 0xFF;
    }

    public static DataHeader peekDataHeader(byte[] frame) throws ProtocolException {
        checkHeader(frame);
        if (frame.length < 14 || (frame[3] & 0xFF) != Protocol.TYPE_DATA) {
            throw new ProtocolException("Not a DATA frame");
        }
        ByteBuffer buffer = ByteBuffer.wrap(frame);
        int streamId = buffer.getInt(4);
        int sequence = buffer.getInt(8);
        int length = buffer.getShort(12) & 0xFFFF;
        if (streamId <= 0) {
            throw new ProtocolException("DATA frame has an invalid stream id");
        }
        if (length < 1 || length > Protocol.MAX_DATA_BYTES) {
            throw new ProtocolException("DATA frame has an invalid length");
        }
        if (frame.length != 14 + length) {
            throw new ProtocolException("DATA frame length mismatch");
        }
        return new DataHeader(streamId, sequence, length, 14);
    }

    private static void checkHeader(byte[] frame) throws ProtocolException {
        if (frame == null || frame.length < 8) {
            throw new ProtocolException("Frame is too short");
        }
        if (frame.length > Protocol.HARD_MAX_FRAME_BYTES) {
            throw new ProtocolException("Frame is too large");
        }
        int magic = ((frame[0] & 0xFF) << 8) | (frame[1] & 0xFF);
        if (magic != Protocol.MAGIC) {
            throw new ProtocolException("Bad protocol magic");
        }
        int version = frame[2] & 0xFF;
        if (!Protocol.isSupportedVersion(version)) {
            throw new ProtocolException("Unsupported protocol version " + version);
        }
    }

    public static ProtocolMessages decode(byte[] frame) throws ProtocolException {
        if (frame == null || frame.length < 8) {
            throw new ProtocolException("Frame is too short");
        }
        if (frame.length > Protocol.HARD_MAX_FRAME_BYTES) {
            throw new ProtocolException("Frame is too large");
        }

        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
            int magic = in.readUnsignedShort();
            if (magic != Protocol.MAGIC) {
                throw new ProtocolException("Bad protocol magic");
            }
            int version = in.readUnsignedByte();
            if (!Protocol.isSupportedVersion(version)) {
                throw new ProtocolException("Unsupported protocol version " + version);
            }
            int type = in.readUnsignedByte();
            int streamId = in.readInt();
            ProtocolMessages message = readBody(in, type, streamId);
            if (in.available() != 0) {
                throw new ProtocolException("Trailing bytes in frame");
            }
            return message;
        } catch (ProtocolException exception) {
            throw exception;
        } catch (EOFException exception) {
            throw new ProtocolException("Truncated frame", exception);
        } catch (IOException exception) {
            throw new ProtocolException("Invalid frame", exception);
        }
    }

    private static void writeBody(DataOutputStream out, ProtocolMessages message) throws IOException {
        if (message instanceof ProtocolMessages.Hello hello) {
            out.writeInt(hello.windowBytes());
            out.writeShort(hello.maxChunkBytes());
            out.writeByte(hello.maxStreams());
            out.writeInt(hello.fullBufferBytes());
            out.writeInt(hello.loopCacheBytes());
            writeString(out, hello.clientVersion());
        } else if (message instanceof ProtocolMessages.Credit credit) {
            out.writeInt(credit.bytes());
        } else if (message instanceof ProtocolMessages.Done done) {
            out.writeByte(done.reason());
            writeString(out, done.message());
        } else if (message instanceof ProtocolMessages.HelloAck ack) {
            out.writeShort(ack.chunkBytes());
            out.writeInt(ack.windowBytes());
            out.writeByte(ack.maxStreams());
            writeString(out, ack.serverVersion());
        } else if (message instanceof ProtocolMessages.HelloQuery) {
            // no body
        } else if (message instanceof ProtocolMessages.Open open) {
            out.writeByte(open.codec());
            out.writeByte(flags(open));
            out.writeInt(open.totalBytes());
            out.writeFloat(open.volume());
            out.writeFloat(open.range());
            out.writeFloat(open.x());
            out.writeFloat(open.y());
            out.writeFloat(open.z());
        } else if (message instanceof ProtocolMessages.Data data) {
            if (data.payload().length > Protocol.MAX_DATA_BYTES) {
                throw new IllegalArgumentException("Data payload too large");
            }
            out.writeInt(data.sequence());
            out.writeShort(data.payload().length);
            out.write(data.payload());
        } else if (message instanceof ProtocolMessages.End) {
            // no body
        } else if (message instanceof ProtocolMessages.Update update) {
            out.writeFloat(update.volume());
            out.writeFloat(update.x());
            out.writeFloat(update.y());
            out.writeFloat(update.z());
        } else if (message instanceof ProtocolMessages.Close close) {
            out.writeByte(close.reason());
            writeString(out, close.message());
        } else if (message instanceof ProtocolMessages.Loop) {
            // no body
        } else {
            throw new IllegalArgumentException("Unknown message type " + message.getClass());
        }
    }

    private static ProtocolMessages readBody(DataInputStream in, int type, int streamId) throws IOException, ProtocolException {
        return switch (type) {
            case Protocol.TYPE_HELLO -> {
                requireStreamZero(streamId);
                yield new ProtocolMessages.Hello(
                        in.readInt(),
                        in.readUnsignedShort(),
                        in.readUnsignedByte(),
                        in.readInt(),
                        in.readInt(),
                        readString(in)
                );
            }
            case Protocol.TYPE_CREDIT -> new ProtocolMessages.Credit(streamId, in.readInt());
            case Protocol.TYPE_DONE -> new ProtocolMessages.Done(streamId, in.readUnsignedByte(), readString(in));
            case Protocol.TYPE_HELLO_ACK -> {
                requireStreamZero(streamId);
                yield new ProtocolMessages.HelloAck(
                        in.readUnsignedShort(),
                        in.readInt(),
                        in.readUnsignedByte(),
                        readString(in)
                );
            }
            case Protocol.TYPE_HELLO_QUERY -> {
                requireStreamZero(streamId);
                yield new ProtocolMessages.HelloQuery();
            }
            case Protocol.TYPE_OPEN -> {
                int codec = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                if ((flags & ~(Protocol.FLAG_POSITIONAL | Protocol.FLAG_LOOP)) != 0) {
                    throw new ProtocolException("Unknown OPEN flags");
                }
                yield new ProtocolMessages.Open(
                        streamId,
                        codec,
                        (flags & Protocol.FLAG_POSITIONAL) != 0,
                        (flags & Protocol.FLAG_LOOP) != 0,
                        in.readInt(),
                        in.readFloat(),
                        in.readFloat(),
                        in.readFloat(),
                        in.readFloat(),
                        in.readFloat()
                );
            }
            case Protocol.TYPE_DATA -> {
                int sequence = in.readInt();
                int length = in.readUnsignedShort();
                if (length > Protocol.MAX_DATA_BYTES) {
                    throw new ProtocolException("DATA frame too large");
                }
                byte[] payload = new byte[length];
                in.readFully(payload);
                yield new ProtocolMessages.Data(streamId, sequence, payload);
            }
            case Protocol.TYPE_END -> new ProtocolMessages.End(streamId);
            case Protocol.TYPE_UPDATE -> new ProtocolMessages.Update(
                    streamId,
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat(),
                    in.readFloat()
            );
            case Protocol.TYPE_CLOSE -> new ProtocolMessages.Close(
                    streamId,
                    in.readUnsignedByte(),
                    readString(in)
            );
            case Protocol.TYPE_LOOP -> new ProtocolMessages.Loop(streamId);
            default -> throw new ProtocolException("Unknown message type " + type);
        };
    }

    private static int typeOf(ProtocolMessages message) {
        if (message instanceof ProtocolMessages.Hello) {
            return Protocol.TYPE_HELLO;
        }
        if (message instanceof ProtocolMessages.Credit) {
            return Protocol.TYPE_CREDIT;
        }
        if (message instanceof ProtocolMessages.Done) {
            return Protocol.TYPE_DONE;
        }
        if (message instanceof ProtocolMessages.HelloAck) {
            return Protocol.TYPE_HELLO_ACK;
        }
        if (message instanceof ProtocolMessages.HelloQuery) {
            return Protocol.TYPE_HELLO_QUERY;
        }
        if (message instanceof ProtocolMessages.Open) {
            return Protocol.TYPE_OPEN;
        }
        if (message instanceof ProtocolMessages.Data) {
            return Protocol.TYPE_DATA;
        }
        if (message instanceof ProtocolMessages.End) {
            return Protocol.TYPE_END;
        }
        if (message instanceof ProtocolMessages.Update) {
            return Protocol.TYPE_UPDATE;
        }
        if (message instanceof ProtocolMessages.Close) {
            return Protocol.TYPE_CLOSE;
        }
        if (message instanceof ProtocolMessages.Loop) {
            return Protocol.TYPE_LOOP;
        }
        throw new IllegalArgumentException("Unknown message type " + message.getClass());
    }

    private static int streamIdOf(ProtocolMessages message) {
        if (message instanceof ProtocolMessages.Credit credit) {
            return credit.streamId();
        }
        if (message instanceof ProtocolMessages.Done done) {
            return done.streamId();
        }
        if (message instanceof ProtocolMessages.Open open) {
            return open.streamId();
        }
        if (message instanceof ProtocolMessages.Data data) {
            return data.streamId();
        }
        if (message instanceof ProtocolMessages.End end) {
            return end.streamId();
        }
        if (message instanceof ProtocolMessages.Update update) {
            return update.streamId();
        }
        if (message instanceof ProtocolMessages.Close close) {
            return close.streamId();
        }
        if (message instanceof ProtocolMessages.Loop loop) {
            return loop.streamId();
        }
        return 0;
    }

    private static int flags(ProtocolMessages.Open open) {
        int flags = 0;
        if (open.positional()) {
            flags |= Protocol.FLAG_POSITIONAL;
        }
        if (open.loop()) {
            flags |= Protocol.FLAG_LOOP;
        }
        return flags;
    }

    private static void requireStreamZero(int streamId) throws ProtocolException {
        if (streamId != 0) {
            throw new ProtocolException("Handshake message has a stream id");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Protocol.MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException, ProtocolException {
        int length = in.readUnsignedShort();
        if (length > Protocol.MAX_STRING_BYTES) {
            throw new ProtocolException("String too long");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private ProtocolCodec() {
    }
}

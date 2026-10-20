package dev.breiwalker.soundstream.protocol;

import java.util.Objects;

public sealed interface ProtocolMessages permits
        ProtocolMessages.Hello,
        ProtocolMessages.Credit,
        ProtocolMessages.Done,
        ProtocolMessages.HelloAck,
        ProtocolMessages.HelloQuery,
        ProtocolMessages.Open,
        ProtocolMessages.Data,
        ProtocolMessages.End,
        ProtocolMessages.Update,
        ProtocolMessages.Close,
        ProtocolMessages.Loop {

    record Hello(
            int windowBytes,
            int maxChunkBytes,
            int maxStreams,
            int fullBufferBytes,
            int loopCacheBytes,
            String clientVersion
    ) implements ProtocolMessages {
        public Hello {
            clientVersion = Objects.requireNonNullElse(clientVersion, "");
        }
    }

    record Credit(int streamId, int bytes) implements ProtocolMessages {
    }

    record Done(int streamId, int reason, String message) implements ProtocolMessages {
        public Done {
            message = Objects.requireNonNullElse(message, "");
        }
    }

    record HelloAck(
            int chunkBytes,
            int windowBytes,
            int maxStreams,
            String serverVersion
    ) implements ProtocolMessages {
        public HelloAck {
            serverVersion = Objects.requireNonNullElse(serverVersion, "");
        }
    }

    record HelloQuery() implements ProtocolMessages {
    }

    record Open(
            int streamId,
            int codec,
            boolean positional,
            boolean loop,
            int totalBytes,
            float volume,
            float range,
            float x,
            float y,
            float z
    ) implements ProtocolMessages {
    }

    record Data(int streamId, int sequence, byte[] payload) implements ProtocolMessages {
        public Data {
            Objects.requireNonNull(payload, "payload");
        }
    }

    record End(int streamId) implements ProtocolMessages {
    }

    record Update(int streamId, float volume, float x, float y, float z) implements ProtocolMessages {
    }

    record Close(int streamId, int reason, String message) implements ProtocolMessages {
        public Close {
            message = Objects.requireNonNullElse(message, "");
        }
    }

    /**
     * Marks the end of one pass of a looped stream. DATA for the next pass
     * follows immediately; the stream is not closed.
     */
    record Loop(int streamId) implements ProtocolMessages {
    }
}

package dev.breiwalker.soundstream.client.stream;

import dev.breiwalker.soundstream.client.audio.AudioEngine;
import dev.breiwalker.soundstream.client.network.StreamPayload;
import dev.breiwalker.soundstream.protocol.Protocol;
import dev.breiwalker.soundstream.protocol.ProtocolCodec;
import dev.breiwalker.soundstream.protocol.ProtocolException;
import dev.breiwalker.soundstream.protocol.ProtocolMessages;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class StreamManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("SoundStream");
    private static final String CLIENT_VERSION = "soundstream-fabric-1.1.0";
    private static Method cameraMethod;
    private static boolean cameraMethodResolved;

    private final Minecraft minecraft;
    private final AudioEngine audio = new AudioEngine();
    private final Map<Integer, ClientStream> streams = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<byte[]> incoming = new ConcurrentLinkedQueue<>();

    private volatile boolean connected;
    private volatile boolean handshaken;
    private int helloDelayTicks;

    private int serverChunkBytes = Protocol.DEFAULT_CLIENT_CHUNK_BYTES;
    private int serverWindowBytes = Protocol.DEFAULT_CLIENT_WINDOW_BYTES;
    private int serverMaxStreams = Protocol.DEFAULT_CLIENT_MAX_STREAMS;
    private int fullBufferBytes = Protocol.DEFAULT_FULL_BUFFER_BYTES;
    private int loopCacheBytes = Protocol.DEFAULT_LOOP_CACHE_BYTES;

    public StreamManager(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public void onJoin() {
        connected = true;
        handshaken = false;
        serverChunkBytes = Protocol.DEFAULT_CLIENT_CHUNK_BYTES;
        serverWindowBytes = Protocol.DEFAULT_CLIENT_WINDOW_BYTES;
        serverMaxStreams = Protocol.DEFAULT_CLIENT_MAX_STREAMS;
        // Fabric's JOIN callback runs before its minecraft:register packet. Sending
        // HELLO immediately makes Paper drop the reply because it does not know the
        // client listens on our channel yet.
        helloDelayTicks = 2;
        LOGGER.info("SoundStream joined; handshake scheduled");
    }

    public void onDisconnect() {
        connected = false;
        handshaken = false;
        helloDelayTicks = 0;
        for (ClientStream stream : streams.values()) {
            stream.stopByManager(Protocol.REASON_STOPPED, "Disconnected", false);
        }
        streams.clear();
        incoming.clear();
        audio.stopAll();
    }

    public void onPayload(byte[] frame) {
        if (frame != null) {
            LOGGER.debug("Received SoundStream frame ({} bytes)", frame.length);
            incoming.add(frame);
        }
    }

    public void tick() {
        if (!connected) {
            incoming.clear();
            return;
        }

        int processed = 0;
        byte[] frame;
        while ((frame = incoming.poll()) != null && processed++ < 1024) {
            try {
                if (ProtocolCodec.peekVersion(frame) != Protocol.VERSION) {
                    continue;
                }
                if (ProtocolCodec.peekType(frame) == Protocol.TYPE_DATA) {
                    ProtocolCodec.DataHeader header = ProtocolCodec.peekDataHeader(frame);
                    ClientStream stream = streams.get(header.streamId());
                    if (stream != null) {
                        if (header.length() > serverChunkBytes) {
                            stream.stopByManager(Protocol.REASON_PROTOCOL_ERROR,
                                    "Chunk exceeds negotiated size", true);
                        } else {
                            stream.onDataFrame(header.sequence(), frame, header.payloadOffset(), header.length());
                        }
                    }
                } else {
                    handle(ProtocolCodec.decode(frame));
                }
            } catch (ProtocolException exception) {
                LOGGER.warn("Ignoring malformed SoundStream frame: {}", exception.getMessage());
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not handle SoundStream frame", exception);
            }
        }

        updateListener();
        sendCredits();
        cleanClosedStreams();

        if (!handshaken) {
            if (helloDelayTicks > 0) {
                helloDelayTicks--;
            } else {
                sendHello();
                helloDelayTicks = 100;
            }
        }
    }

    public void shutdown() {
        onDisconnect();
        audio.shutdown();
    }

    private void handle(ProtocolMessages message) {
        if (message instanceof ProtocolMessages.HelloAck ack) {
            if (ack.chunkBytes() <= 0 || ack.chunkBytes() > Protocol.MAX_DATA_BYTES
                    || ack.windowBytes() <= 0 || ack.maxStreams() <= 0) {
                LOGGER.warn("Server sent invalid SoundStream capabilities");
                return;
            }
            serverChunkBytes = Math.min(Protocol.DEFAULT_CLIENT_CHUNK_BYTES, ack.chunkBytes());
            serverWindowBytes = Math.min(Protocol.DEFAULT_CLIENT_WINDOW_BYTES, ack.windowBytes());
            serverMaxStreams = Math.min(Protocol.DEFAULT_CLIENT_MAX_STREAMS, ack.maxStreams());
            handshaken = true;
            helloDelayTicks = 0;
            LOGGER.info("SoundStream ready (server {})", ack.serverVersion());
            return;
        }

        if (message instanceof ProtocolMessages.HelloQuery) {
            sendHello();
            helloDelayTicks = 100;
            return;
        }

        if (!handshaken) {
            return;
        }

        if (message instanceof ProtocolMessages.Open open) {
            handleOpen(open);
        } else if (message instanceof ProtocolMessages.End end) {
            ClientStream stream = streams.get(end.streamId());
            if (stream != null) {
                stream.onEnd();
            }
        } else if (message instanceof ProtocolMessages.Loop loop) {
            ClientStream stream = streams.get(loop.streamId());
            if (stream != null) {
                stream.onLoop();
            }
        } else if (message instanceof ProtocolMessages.Update update) {
            ClientStream stream = streams.get(update.streamId());
            if (stream != null && finite(update.volume(), update.x(), update.y(), update.z())) {
                stream.update(Math.max(0.0f, Math.min(1.0f, update.volume())), update.x(), update.y(), update.z());
            }
        } else if (message instanceof ProtocolMessages.Close close) {
            ClientStream stream = streams.remove(close.streamId());
            if (stream != null) {
                LOGGER.debug("SoundStream CLOSE id={} reason={}", close.streamId(), close.reason());
                stream.serverClose(close.reason(), close.message());
            }
        }
    }

    private void handleOpen(ProtocolMessages.Open open) {
        if (streams.containsKey(open.streamId())) {
            sendDone(open.streamId(), Protocol.REASON_PROTOCOL_ERROR, "Stream id already active");
            return;
        }
        if (streams.size() >= Math.min(serverMaxStreams, Protocol.DEFAULT_CLIENT_MAX_STREAMS)) {
            sendDone(open.streamId(), Protocol.REASON_TOO_MANY_STREAMS, "Too many concurrent streams");
            return;
        }
        if (open.codec() != Protocol.CODEC_OGG_VORBIS && open.codec() != Protocol.CODEC_MP3) {
            sendDone(open.streamId(), Protocol.REASON_UNSUPPORTED, "Unsupported codec");
            return;
        }
        if (open.totalBytes() <= 0 || open.totalBytes() > Protocol.MAX_FILE_BYTES) {
            sendDone(open.streamId(), Protocol.REASON_FILE_ERROR, "Invalid stream size");
            return;
        }
        if (!finite(open.volume(), open.range(), open.x(), open.y(), open.z())) {
            sendDone(open.streamId(), Protocol.REASON_PROTOCOL_ERROR, "Non-finite stream metadata");
            return;
        }

        LOGGER.debug("SoundStream OPEN id={} codec={} loop={} 3d={} bytes={}", open.streamId(),
                open.codec(), open.loop(), open.positional(), open.totalBytes());

        float volume = Math.max(0.0f, Math.min(1.0f, open.volume()));
        float range = open.positional() ? Math.max(1.0f, open.range()) : Math.max(1.0f, open.range());
        ClientStream stream = new ClientStream(
                audio,
                open.streamId(),
                open.codec(),
                open.positional(),
                open.loop(),
                open.totalBytes(),
                serverWindowBytes,
                volume,
                range,
                open.x(),
                open.y(),
                open.z()
        );
        streams.put(open.streamId(), stream);
    }

    private void sendCredits() {
        if (!handshaken) {
            return;
        }
        for (ClientStream stream : streams.values()) {
            int credit = stream.takeCredit();
            if (credit > 0) {
                send(new ProtocolMessages.Credit(stream.id(), credit));
            }
        }
    }

    private void cleanClosedStreams() {
        Iterator<Map.Entry<Integer, ClientStream>> iterator = streams.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ClientStream> entry = iterator.next();
            ClientStream stream = entry.getValue();
            if (!stream.closed()) {
                continue;
            }
            if (stream.closeReason() != Protocol.REASON_FINISHED
                    && stream.closeReason() != Protocol.REASON_STOPPED) {
                LOGGER.warn("SoundStream {} ended: {} ({})", stream.id(),
                        Protocol.reasonName(stream.closeReason()), stream.closeMessage());
            }
            if (stream.sendDoneOnClose()) {
                sendDone(stream.id(), stream.closeReason(), stream.closeMessage());
            }
            iterator.remove();
        }
    }

    private void updateListener() {
        try {
            String device = minecraft.options.soundDevice().get();
            audio.setDeviceName(device);

            LocalPlayer player = minecraft.player;
            Camera camera = mainCamera(minecraft.gameRenderer);
            float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);

            if (camera != null && camera.isInitialized()) {
                Vec3 position = camera.position();
                Vector3fc forward = camera.forwardVector();
                Vector3fc up = camera.upVector();
                audio.setListener(new AudioEngine.ListenerState(
                        (float) position.x, (float) position.y, (float) position.z,
                        forward.x(), forward.y(), forward.z(),
                        up.x(), up.y(), up.z()
                ), master);
            } else if (player != null) {
                Vec3 position = player.getEyePosition(1.0f);
                Vec3 forward = player.getViewVector(1.0f);
                audio.setListener(new AudioEngine.ListenerState(
                        (float) position.x, (float) position.y, (float) position.z,
                        (float) forward.x, (float) forward.y, (float) forward.z,
                        0.0f, 1.0f, 0.0f
                ), master);
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Could not update SoundStream listener", exception);
        }
    }

    private void sendHello() {
        if (!connected) {
            return;
        }
        send(new ProtocolMessages.Hello(
                Protocol.DEFAULT_CLIENT_WINDOW_BYTES,
                Protocol.DEFAULT_CLIENT_CHUNK_BYTES,
                Protocol.DEFAULT_CLIENT_MAX_STREAMS,
                fullBufferBytes,
                loopCacheBytes,
                CLIENT_VERSION
        ));
    }

    private void sendDone(int streamId, int reason, String message) {
        if (!connected || !handshaken) {
            return;
        }
        send(new ProtocolMessages.Done(streamId, reason, truncate(message)));
    }

    private void send(ProtocolMessages message) {
        try {
            ClientPlayNetworking.send(new StreamPayload(ProtocolCodec.encode(message)));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not send SoundStream message", exception);
        }
    }

    private static boolean finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static Camera mainCamera(GameRenderer renderer) {
        if (!cameraMethodResolved) {
            for (String name : new String[]{"mainCamera", "getMainCamera"}) {
                try {
                    cameraMethod = GameRenderer.class.getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try the other mapping
                }
            }
            cameraMethodResolved = true;
        }
        if (cameraMethod == null) {
            return null;
        }
        try {
            return (Camera) cameraMethod.invoke(renderer);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}


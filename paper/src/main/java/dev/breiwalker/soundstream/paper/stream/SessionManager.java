package dev.breiwalker.soundstream.paper.stream;

import dev.breiwalker.soundstream.paper.SoundStreamPlugin;
import dev.breiwalker.soundstream.paper.api.PlayOptions;
import dev.breiwalker.soundstream.paper.api.SoundStreamService;
import dev.breiwalker.soundstream.paper.api.StreamHandle;
import dev.breiwalker.soundstream.paper.audio.SoundAsset;
import dev.breiwalker.soundstream.paper.audio.SoundLibrary;
import dev.breiwalker.soundstream.protocol.Protocol;
import dev.breiwalker.soundstream.protocol.ProtocolCodec;
import dev.breiwalker.soundstream.protocol.ProtocolMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SessionManager implements SoundStreamService {
    private final SoundStreamPlugin plugin;
    private final SoundLibrary library;
    private final Map<UUID, PlayerSession> sessions = new HashMap<>();

    private int configuredChunkBytes;
    private int configuredWindowBytes;
    private int configuredMaxStreams;
    private int configuredMaxLoopBytes;
    private float defaultVolume;
    private float defaultRange;
    private boolean missingActionBarEnabled;
    private String missingActionBarMessage;
    private int missingCheckDelayTicks;

    public SessionManager(SoundStreamPlugin plugin, SoundLibrary library) {
        this.plugin = plugin;
        this.library = library;
        configure();
    }

    public void configure() {
        configuredChunkBytes = clamp(plugin.getConfig().getInt("chunk-bytes", 32 * 1024), 1024, 65_535);
        configuredWindowBytes = clamp(plugin.getConfig().getInt("window-bytes", 512 * 1024), 16 * 1024, 8 * 1024 * 1024);
        configuredMaxStreams = clamp(plugin.getConfig().getInt("max-streams-per-player", 4), 1, 16);
        configuredMaxLoopBytes = clamp(plugin.getConfig().getInt("max-loop-bytes", 8 * 1024 * 1024), 0, 64 * 1024 * 1024);
        defaultVolume = clamp((float) plugin.getConfig().getDouble("default-volume", 1.0), 0.0f, 1.0f);
        defaultRange = Math.max(1.0f, (float) plugin.getConfig().getDouble("default-range", 32.0));
        missingActionBarEnabled = plugin.getConfig().getBoolean("missing-client-actionbar", true);
        missingActionBarMessage = plugin.getConfig().getString("missing-client-message",
                "&eYou currently dont have SoundStream installed, means you cant hear the special music/sounds!");
        missingCheckDelayTicks = clamp(plugin.getConfig().getInt("missing-client-delay-ticks", 60), 1, 1200);
    }

    public void onHello(Player player, ProtocolMessages.Hello hello) {
        PlayerSession session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerSession());
        int chunk = Math.min(configuredChunkBytes, hello.maxChunkBytes());
        int window = Math.min(configuredWindowBytes, hello.windowBytes());
        int maxStreams = Math.min(configuredMaxStreams, hello.maxStreams());
        if (chunk < 1 || window < 1 || maxStreams < 1) {
            return;
        }
        session.capabilities = new ClientCapabilities(
                chunk,
                window,
                maxStreams,
                Math.max(0, hello.fullBufferBytes()),
                Math.max(0, hello.loopCacheBytes())
        );
        send(player, new ProtocolMessages.HelloAck(
                chunk,
                window,
                maxStreams,
                plugin.getPluginMeta().getVersion()
        ));
        plugin.getLogger().info("Accepted SoundStream handshake from " + player.getName()
                + " (chunk=" + chunk + ", window=" + window + ", streams=" + maxStreams + ")");
    }

    public void query(Player player) {
        send(player, new ProtocolMessages.HelloQuery());
    }

    public void onCredit(Player player, int streamId, int bytes) {
        // Kept as a no-op for older clients. The full song is sent immediately.
    }

    public void onDone(Player player, int streamId) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        StreamSession stream = session.streams.remove(streamId);
        if (stream != null) {
            stream.closed = true;
            plugin.getLogger().info("SoundStream " + streamId + " finished for " + player.getName());
        }
    }

    public void remove(Player player) {
        PlayerSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        for (StreamSession stream : session.streams.values()) {
            stream.closeQuietly();
        }
        session.streams.clear();
    }

    public void closeAll() {
        for (PlayerSession session : sessions.values()) {
            for (StreamSession stream : session.streams.values()) {
                stream.closeQuietly();
            }
            session.streams.clear();
        }
        sessions.clear();
    }

    public void tickUpdates() {
        for (PlayerSession session : sessions.values()) {
            for (StreamSession stream : session.streams.values()) {
                stream.pump();
                stream.flushUpdate();
            }
        }
    }

    public float defaultVolume() {
        return defaultVolume;
    }

    public float defaultRange() {
        return defaultRange;
    }

    @Override
    public boolean isAvailable(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return session != null && session.capabilities != null;
    }

    /**
     * Gives the client time to complete its handshake before showing the
     * "mod missing" action bar on join.
     */
    public void scheduleMissingCheck(Player player) {
        if (!missingActionBarEnabled) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> notifyMissing(player), missingCheckDelayTicks);
    }

    public void notifyMissing(Player player) {
        if (!missingActionBarEnabled || player == null || !player.isOnline() || isAvailable(player)) {
            return;
        }
        Component message = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(missingActionBarMessage == null ? "" : missingActionBarMessage);
        player.sendActionBar(message);
    }

    @Override
    public CompletableFuture<StreamHandle> play(Player player, String soundId, PlayOptions options) {
        if (!Bukkit.isPrimaryThread()) {
            CompletableFuture<StreamHandle> result = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> play(player, soundId, options)
                    .whenComplete((handle, throwable) -> {
                        if (throwable != null) {
                            result.completeExceptionally(throwable);
                        } else {
                            result.complete(handle);
                        }
                    }));
            return result;
        }

        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.capabilities == null) {
            notifyMissing(player);
            return CompletableFuture.failedFuture(new IllegalStateException("Player does not have SoundStream"));
        }
        if (session.streams.size() >= session.capabilities.maxStreams) {
            return CompletableFuture.failedFuture(new IllegalStateException("Too many active SoundStreams"));
        }

        PlayOptions effective = options == null ? PlayOptions.twoD() : options;
        float volume = clamp(effective.getVolume(), 0.0f, 1.0f);
        float range = Math.max(1.0f, effective.getRange());
        Location location = effective.getLocation();
        boolean positional = effective.isPositional();
        if (positional && location == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Positional stream requires a location"));
        }

        int id = session.nextId++;
        StreamSession stream = new StreamSession(player, session, id, effective.isLoop(), positional, volume, range);
        if (positional) {
            stream.x = (float) location.getX();
            stream.y = (float) location.getY();
            stream.z = (float) location.getZ();
        }
        session.streams.put(id, stream);

        library.load(soundId).whenComplete((asset, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (session.streams.get(id) != stream) {
                        stream.future.completeExceptionally(new IllegalStateException("Stream was stopped"));
                        return;
                    }
                    if (throwable != null) {
                        session.streams.remove(id);
                        stream.future.completeExceptionally(rootCause(throwable));
                        return;
                    }
                    if (stream.loop && asset.data().length > Math.min(
                            session.capabilities.loopCacheBytes,
                            configuredMaxLoopBytes)) {
                        session.streams.remove(id);
                        stream.future.completeExceptionally(
                                new IllegalArgumentException("Sound is too large to loop"));
                        return;
                    }
                    stream.start(asset);
                    stream.future.complete(stream.handle);
                }));
        return stream.future;
    }

    @Override
    public void stop(Player player, int streamId) {
        onMain(() -> {
            PlayerSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            StreamSession stream = session.streams.remove(streamId);
            if (stream != null) {
                plugin.getLogger().info("Stopping SoundStream " + streamId + " for " + player.getName());
                stream.sendClose(Protocol.REASON_STOPPED, "Stopped");
                stream.closeQuietly();
            }
        });
    }

    @Override
    public void stopAll(Player player) {
        onMain(() -> {
            PlayerSession session = sessions.get(player.getUniqueId());
            if (session == null) {
                return;
            }
            for (StreamSession stream : new ArrayList<>(session.streams.values())) {
                stream.sendClose(Protocol.REASON_STOPPED, "Stopped");
                stream.closeQuietly();
            }
            session.streams.clear();
        });
    }

    void setVolume(Player player, int streamId, float volume) {
        onMain(() -> {
            StreamSession stream = find(player, streamId);
            if (stream != null) {
                stream.volume = clamp(volume, 0.0f, 1.0f);
                stream.dirty = true;
            }
        });
    }

    void moveTo(Player player, int streamId, Location location) {
        onMain(() -> {
            StreamSession stream = find(player, streamId);
            if (stream != null && stream.positional && location != null) {
                stream.x = (float) location.getX();
                stream.y = (float) location.getY();
                stream.z = (float) location.getZ();
                stream.dirty = true;
            }
        });
    }

    boolean isActive(Player player, int streamId) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return player.isOnline() && session != null && session.streams.containsKey(streamId);
    }

    private StreamSession find(Player player, int streamId) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return session == null ? null : session.streams.get(streamId);
    }

    private void send(Player player, ProtocolMessages message) {
        sendFrame(player, ProtocolCodec.encode(message));
    }

    private void sendFrame(Player player, byte[] frame) {
        try {
            player.sendPluginMessage(plugin, Protocol.CHANNEL, frame);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not send SoundStream packet to " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private void onMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PlayerSession {
        private ClientCapabilities capabilities;
        private final Map<Integer, StreamSession> streams = new LinkedHashMap<>();
        private int nextId = 1;
    }

    private record ClientCapabilities(
            int chunkBytes,
            int windowBytes,
            int maxStreams,
            int fullBufferBytes,
            int loopCacheBytes
    ) {
    }

    private final class StreamSession {
        private final Player player;
        private final PlayerSession session;
        private final int id;
        private final boolean loop;
        private final boolean positional;
        private final StreamHandle handle;
        private final CompletableFuture<StreamHandle> future = new CompletableFuture<>();

        private SoundAsset asset;
        private int offset;
        private int sequence;
        private boolean opened;
        private boolean ended;
        private boolean closed;
        private boolean dirty;
        private float volume;
        private float range;
        private float x;
        private float y;
        private float z;

        private StreamSession(
                Player player,
                PlayerSession session,
                int id,
                boolean loop,
                boolean positional,
                float volume,
                float range
        ) {
            this.player = player;
            this.session = session;
            this.id = id;
            this.loop = loop;
            this.positional = positional;
            this.volume = volume;
            this.range = range;
            this.handle = new Handle(this);
        }

        private void start(SoundAsset asset) {
            this.asset = asset;
            send(player, new ProtocolMessages.Open(
                    id,
                    asset.codec(),
                    positional,
                    loop,
                    asset.data().length,
                    volume,
                    range,
                    x,
                    y,
                    z
            ));
            opened = true;
            pump();
        }

        private void pump() {
            if (closed || !opened || asset == null || ended) {
                return;
            }
            int chunkBytes = session.capabilities.chunkBytes;
            int budget = 1024 * 1024;
            while (offset < asset.data().length && budget > 0) {
                int length = Math.min(chunkBytes, Math.min(budget, asset.data().length - offset));
                if (length <= 0) {
                    break;
                }
                sendFrame(player, ProtocolCodec.encodeData(id, sequence++, asset.data(), offset, length));
                offset += length;
                budget -= length;
            }
            if (offset >= asset.data().length) {
                send(player, new ProtocolMessages.End(id));
                ended = true;
            }
        }

        private void flushUpdate() {
            if (!opened || closed || !dirty) {
                return;
            }
            send(player, new ProtocolMessages.Update(id, volume, x, y, z));
            dirty = false;
        }

        private void sendClose(int reason, String message) {
            if (!closed) {
                send(player, new ProtocolMessages.Close(id, reason, message));
            }
        }

        private void closeQuietly() {
            if (closed) {
                return;
            }
            closed = true;
            future.completeExceptionally(new IllegalStateException("Stream closed"));
        }
    }

    private final class Handle implements StreamHandle {
        private final StreamSession stream;

        private Handle(StreamSession stream) {
            this.stream = stream;
        }

        @Override
        public int id() {
            return stream.id;
        }

        @Override
        public void stop() {
            SessionManager.this.stop(stream.player, stream.id);
        }

        @Override
        public void setVolume(float volume) {
            SessionManager.this.setVolume(stream.player, stream.id, volume);
        }

        @Override
        public void moveTo(Location location) {
            SessionManager.this.moveTo(stream.player, stream.id, location);
        }

        @Override
        public boolean isActive() {
            return SessionManager.this.isActive(stream.player, stream.id);
        }
    }
}

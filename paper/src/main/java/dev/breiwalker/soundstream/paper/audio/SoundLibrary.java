package dev.breiwalker.soundstream.paper.audio;

import dev.breiwalker.soundstream.protocol.CodecDetector;
import dev.breiwalker.soundstream.protocol.Protocol;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Resolves sound IDs and validates their container header. Only small metadata
 * objects are cached; files of hundreds of megabytes are streamed from disk by
 * each session instead of being loaded into the plugin's heap.
 */
public final class SoundLibrary {
    private static final int HEADER_BYTES = 64 * 1024;
    private static final int MAX_CACHE_ENTRIES = 512;

    private final Plugin plugin;
    private final Map<String, SoundAsset> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, CompletableFuture<SoundAsset>> loading = new ConcurrentHashMap<>();
    private Path root;
    private int maxFileBytes;

    public SoundLibrary(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        applyConfig(config);
        createRoot();
    }

    public Path root() {
        return root;
    }

    public void reload(FileConfiguration config) {
        applyConfig(config);
        createRoot();
        invalidate();
    }

    private void applyConfig(FileConfiguration config) {
        this.root = Path.of(config.getString("sounds-directory", "sounds"))
                .toAbsolutePath().normalize();
        long configured = config.getLong("max-file-bytes", Protocol.DEFAULT_SERVER_MAX_FILE_BYTES);
        this.maxFileBytes = (int) Math.max(1, Math.min(Protocol.MAX_FILE_BYTES, configured));
    }

    private void createRoot() {
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create SoundStream sounds directory: " + root);
        }
    }

    public CompletableFuture<SoundAsset> load(String id) {
        final String normalized;
        try {
            normalized = normalizeId(id);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        SoundAsset cached = getCached(normalized);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<SoundAsset> existing = loading.get(normalized);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<SoundAsset> future = new CompletableFuture<>();
        CompletableFuture<SoundAsset> previous = loading.putIfAbsent(normalized, future);
        if (previous != null) {
            return previous;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                SoundAsset asset = readMetadata(normalized);
                putCached(normalized, asset);
                future.complete(asset);
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            } finally {
                loading.remove(normalized, future);
            }
        });
        return future;
    }

    private SoundAsset readMetadata(String id) throws IOException {
        Path path = resolve(id);
        long size = Files.size(path);
        if (size <= 0 || size > maxFileBytes) {
            throw new IllegalArgumentException("Sound is empty or exceeds max-file-bytes");
        }
        byte[] header = new byte[(int) Math.min(size, HEADER_BYTES)];
        try (InputStream input = Files.newInputStream(path)) {
            int read = 0;
            while (read < header.length) {
                int count = input.read(header, read, header.length - read);
                if (count < 0) {
                    break;
                }
                read += count;
            }
            if (read < header.length) {
                header = java.util.Arrays.copyOf(header, read);
            }
        }
        int codec = CodecDetector.detect(header, path.getFileName().toString());
        return new SoundAsset(id, path, (int) size, codec);
    }

    public Path resolve(String id) throws IOException {
        String normalized = normalizeId(id);
        Path candidate = root.resolve(normalized).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("Sound path escapes the sounds directory");
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("Unknown sound: " + id);
        }
        String lower = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ogg") && !lower.endsWith(".mp3")) {
            throw new IllegalArgumentException("Only .ogg and .mp3 files are accepted");
        }
        Path realRoot = root.toRealPath();
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot) || Files.isSymbolicLink(candidate)) {
            throw new IllegalArgumentException("Sound path escapes the sounds directory");
        }
        return realCandidate;
    }

    public List<String> list() {
        List<String> sounds = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".ogg") || name.endsWith(".mp3");
                    })
                    .forEach(path -> {
                        try {
                            Path real = path.toRealPath();
                            if (real.startsWith(root.toRealPath())) {
                                sounds.add(root.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException ignored) {
                            // skip unreadable entries
                        }
                    });
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list SoundStream sounds: " + exception.getMessage());
        }
        sounds.sort(Comparator.naturalOrder());
        return sounds;
    }

    public void invalidate() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Sound id is empty");
        }
        String normalized = id.replace('\\', '/').trim();
        if (normalized.startsWith("/") || normalized.contains("\0")) {
            throw new IllegalArgumentException("Invalid sound id");
        }
        if (normalized.startsWith("../") || normalized.contains("/../") || normalized.endsWith("/..")) {
            throw new IllegalArgumentException("Sound id contains path traversal");
        }
        return normalized;
    }

    private SoundAsset getCached(String id) {
        synchronized (cache) {
            return cache.get(id);
        }
    }

    private void putCached(String id, SoundAsset asset) {
        synchronized (cache) {
            cache.put(id, asset);
            while (cache.size() > MAX_CACHE_ENTRIES) {
                var iterator = cache.entrySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }
}

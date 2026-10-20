package dev.breiwalker.soundstream.paper.audio;

import dev.breiwalker.soundstream.protocol.CodecDetector;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
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

public final class SoundLibrary {
    private static final long MAX_CACHE_BYTES = 64L * 1024 * 1024;

    private final Plugin plugin;
    private final Map<String, SoundAsset> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, CompletableFuture<SoundAsset>> loading = new ConcurrentHashMap<>();
    private Path root;
    private int maxFileBytes;

    private long cacheBytes;

    public SoundLibrary(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.root = Path.of(config.getString("sounds-directory", "sounds"))
                .toAbsolutePath().normalize();
        this.maxFileBytes = config.getInt("max-file-bytes", 32 * 1024 * 1024);
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create SoundStream sounds directory: " + root);
        }
    }

    public Path root() {
        return root;
    }

    public void reload(FileConfiguration config) {
        this.root = Path.of(config.getString("sounds-directory", "sounds"))
                .toAbsolutePath().normalize();
        this.maxFileBytes = config.getInt("max-file-bytes", 32 * 1024 * 1024);
        try {
            Files.createDirectories(root);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create SoundStream sounds directory: " + root);
        }
        invalidate();
    }

    public CompletableFuture<SoundAsset> load(String id) {
        final String normalized;
        try {
            normalized = normalizeId(id);
            Path path = resolve(normalized);
            long size = Files.size(path);
            if (size <= 0 || size > maxFileBytes) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Sound is empty or exceeds max-file-bytes"));
            }
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
                Path path = resolve(normalized);
                byte[] data = Files.readAllBytes(path);
                if (data.length == 0 || data.length > maxFileBytes) {
                    throw new IllegalArgumentException("Sound is empty or exceeds max-file-bytes");
                }
                int codec = CodecDetector.detect(data, path.getFileName().toString());
                SoundAsset asset = new SoundAsset(normalized, data, codec);
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
            cacheBytes = 0;
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
            if (asset.data().length > MAX_CACHE_BYTES) {
                return;
            }
            SoundAsset previous = cache.put(id, asset);
            if (previous != null) {
                cacheBytes -= previous.data().length;
            }
            cacheBytes += asset.data().length;
            var iterator = cache.entrySet().iterator();
            while (cacheBytes > MAX_CACHE_BYTES && iterator.hasNext()) {
                SoundAsset removed = iterator.next().getValue();
                cacheBytes -= removed.data().length;
                iterator.remove();
            }
        }
    }
}

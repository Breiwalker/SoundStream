package dev.breiwalker.soundstream.paper.audio;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Validated metadata for a sound file. The encoded audio is never held in
 * memory; sessions open the path and stream it from disk.
 */
public record SoundAsset(String id, Path path, int size, int codec) {
    public SoundAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(path, "path");
        if (size <= 0) {
            throw new IllegalArgumentException("Sound size must be positive");
        }
    }
}

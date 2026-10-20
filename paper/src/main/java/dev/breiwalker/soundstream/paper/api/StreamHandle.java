package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Location;

public interface StreamHandle {
    int id();

    void stop();

    void setVolume(float volume);

    /**
     * Moves an already positional stream. This has no effect on a 2D stream.
     */
    void moveTo(Location location);

    boolean isActive();
}

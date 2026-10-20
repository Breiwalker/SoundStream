package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Location;

/**
 * Options for one server-streamed sound.
 *
 * <p>The default is a 2D, non-positional, non-looping stream at full volume.</p>
 */
public final class PlayOptions {
    private boolean positional;
    private Location location;
    private boolean loop;
    private float volume = 1.0f;
    private float range = 32.0f;

    public static PlayOptions twoD() {
        return new PlayOptions();
    }

    public static PlayOptions positional(Location location) {
        PlayOptions options = new PlayOptions();
        options.positional = true;
        options.location = location == null ? null : location.clone();
        return options;
    }

    public PlayOptions loop(boolean loop) {
        this.loop = loop;
        return this;
    }

    public PlayOptions volume(float volume) {
        this.volume = volume;
        return this;
    }

    public PlayOptions range(float range) {
        this.range = range;
        return this;
    }

    public boolean isPositional() {
        return positional;
    }

    public Location getLocation() {
        return location;
    }

    public boolean isLoop() {
        return loop;
    }

    public float getVolume() {
        return volume;
    }

    public float getRange() {
        return range;
    }
}

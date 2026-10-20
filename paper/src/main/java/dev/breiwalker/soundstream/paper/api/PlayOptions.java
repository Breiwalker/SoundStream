package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Location;

/**
 * Options for one server-streamed sound.
 *
 * <p>The default is a 2D, non-positional, non-looping stream at full volume.
 * Instances are mutable builders; every setter returns {@code this}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * PlayOptions.twoD().volume(0.8f);
 * PlayOptions.positional(player.getLocation()).range(48.0f).loop(true);
 * }</pre>
 *
 * @since 1.0.2
 */
public final class PlayOptions {
    private boolean positional;
    private Location location;
    private boolean loop;
    private float volume = 1.0f;
    private float range = 32.0f;

    private PlayOptions() {
    }

    /**
     * Creates a non-positional stream. The client plays it directly at the
     * server-provided volume.
     */
    public static PlayOptions twoD() {
        return new PlayOptions();
    }

    /**
     * Creates a positional stream at {@code location}. The client applies
     * distance attenuation and stereo panning relative to the camera.
     *
     * @param location fixed world location of the sound; {@code null} makes
     *                 {@link SoundStreamService#play} fail
     */
    public static PlayOptions positional(Location location) {
        PlayOptions options = new PlayOptions();
        options.positional = true;
        options.location = location == null ? null : location.clone();
        return options;
    }

    /**
     * Enables looping. Loop passes are re-streamed continuously and split by
     * protocol LOOP markers, so even very large files loop without the client
     * retaining the whole file.
     */
    public PlayOptions loop(boolean loop) {
        this.loop = loop;
        return this;
    }

    /**
     * Sets the stream volume. Values outside {@code 0..1} are clamped by the
     * implementation.
     */
    public PlayOptions volume(float volume) {
        this.volume = volume;
        return this;
    }

    /**
     * Sets the audible range for a positional stream. Values below {@code 1}
     * are raised to {@code 1}. Has no effect on a 2D stream.
     */
    public PlayOptions range(float range) {
        this.range = range;
        return this;
    }

    public boolean isPositional() {
        return positional;
    }

    public Location getLocation() {
        return location == null ? null : location.clone();
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

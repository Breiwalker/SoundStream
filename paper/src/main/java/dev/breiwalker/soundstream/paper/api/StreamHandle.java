package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Location;

/**
 * Handle for one active server stream.
 *
 * <p>Methods are safe to call from any thread. Calls are marshalled to the
 * server thread.</p>
 *
 * @since 1.0.2
 */
public interface StreamHandle {
    /**
     * Server-assigned stream id, unique per player connection.
     */
    int id();

    /**
     * Stops the stream immediately and releases the player's stream slot.
     */
    void stop();

    /**
     * Changes volume for the rest of the stream. Values outside {@code 0..1}
     * are clamped.
     */
    void setVolume(float volume);

    /**
     * Moves an already positional stream. This has no effect on a 2D stream.
     */
    void moveTo(Location location);

    /**
     * Returns true while the server still has an active session for this
     * stream. A naturally finished stream becomes inactive after the client
     * reports completion.
     */
    boolean isActive();
}

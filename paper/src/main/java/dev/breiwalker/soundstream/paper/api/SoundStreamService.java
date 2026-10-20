package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Public service registered with Bukkit's {@code ServicesManager}.
 *
 * <p>Consumers should declare {@code depend: [SoundStream]} in their
 * {@code plugin.yml} and compile against the SoundStream plugin jar. The API
 * classes are provided by the running SoundStream plugin; do not shade or
 * relocate them.</p>
 *
 * <p>{@link #play} performs file lookup and loading asynchronously. The
 * returned future completes after the file is validated and the OPEN message
 * has been sent, or exceptionally if the sound cannot be played.</p>
 *
 * @since 1.0.2
 */
public interface SoundStreamService {
    /**
     * Returns true once the player's SoundStream client has completed the
     * protocol handshake. Players without the Fabric mod return false.
     *
     * @param player online player
     */
    boolean isAvailable(Player player);

    /**
     * Streams one file to one player.
     *
     * <p>The future completes on the server thread. Failures include a missing
     * file, invalid container, an oversized file, a loop file above the cache
     * limit, too many concurrent streams, or a client without SoundStream.</p>
     *
     * @param player  target player
     * @param soundId relative id under {@code plugins/SoundStream/sounds}, for
     *                example {@code music/theme.ogg}; never an absolute path
     * @param options playback options; may be {@code null} for 2D defaults
     * @return future containing the stream handle
     */
    CompletableFuture<StreamHandle> play(Player player, String soundId, PlayOptions options);

    /**
     * Streams the same file to several players.
     *
     * <p>Players that fail individually (for example, clients without the mod)
     * are omitted from the result. The returned future completes after every
     * attempt has settled and never fails because of an individual player.</p>
     */
    default CompletableFuture<List<StreamHandle>> play(
            Collection<? extends Player> players,
            String soundId,
            PlayOptions options
    ) {
        Objects.requireNonNull(players, "players");
        List<CompletableFuture<StreamHandle>> futures = new ArrayList<>();
        for (Player player : players) {
            if (player != null) {
                futures.add(play(player, soundId, options));
            }
        }
        CompletableFuture<?>[] settled = futures.stream()
                .map(future -> future.exceptionally(ignored -> null))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(settled).thenApply(ignored -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList());
    }

    /**
     * Streams the same file to every player currently online. Unsupported
     * clients are skipped as described by
     * {@link #play(Collection, String, PlayOptions)}.
     */
    default CompletableFuture<List<StreamHandle>> playAll(String soundId, PlayOptions options) {
        return play(Bukkit.getOnlinePlayers(), soundId, options);
    }

    /**
     * Stops all streams for each supplied player.
     */
    default void stopAll(Collection<? extends Player> players) {
        Objects.requireNonNull(players, "players");
        for (Player player : players) {
            if (player != null) {
                stopAll(player);
            }
        }
    }

    /**
     * Stops one stream by id. Unknown ids are ignored.
     */
    void stop(Player player, int streamId);

    /**
     * Stops every stream for one player.
     */
    void stopAll(Player player);
}

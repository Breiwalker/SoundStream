package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Public service registered with Bukkit's ServicesManager.
 *
 * <p>Consumers should declare {@code depend: [SoundStream]} in their plugin.yml
 * and compile against the SoundStream plugin jar.</p>
 */
public interface SoundStreamService {
    /**
     * Returns true once the player's SoundStream client has completed the
     * protocol handshake.
     */
    boolean isAvailable(Player player);

    /**
     * Streams one file to one player.
     *
     * @param soundId relative id under plugins/SoundStream/sounds, for example
     *                {@code music/theme.ogg}
     */
    CompletableFuture<StreamHandle> play(Player player, String soundId, PlayOptions options);

    /**
     * Streams the same file to several players.
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
     * Streams the same file to every player currently online.
     */
    default CompletableFuture<List<StreamHandle>> playAll(String soundId, PlayOptions options) {
        return play(Bukkit.getOnlinePlayers(), soundId, options);
    }

    default void stopAll(Collection<? extends Player> players) {
        Objects.requireNonNull(players, "players");
        for (Player player : players) {
            if (player != null) {
                stopAll(player);
            }
        }
    }

    void stop(Player player, int streamId);

    void stopAll(Player player);
}

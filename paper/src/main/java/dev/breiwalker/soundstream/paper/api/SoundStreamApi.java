package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Convenience lookup for the SoundStream service.
 *
 * <p>Other plugins should declare {@code depend: [SoundStream]} in their
 * {@code plugin.yml} so the service is registered before their own enable
 * method runs. The API classes live in the SoundStream plugin jar and must not
 * be shaded or relocated.</p>
 *
 * @since 1.0.2
 */
public final class SoundStreamApi {
    /**
     * Returns the registered service, or {@code null} if SoundStream is not
     * installed or has not enabled yet.
     */
    public static SoundStreamService get() {
        RegisteredServiceProvider<SoundStreamService> registration =
                Bukkit.getServicesManager().getRegistration(SoundStreamService.class);
        return registration == null ? null : registration.getProvider();
    }

    /**
     * Returns the registered service or throws if it is unavailable.
     *
     * @throws IllegalStateException when SoundStream is missing or disabled
     */
    public static SoundStreamService require() {
        SoundStreamService service = get();
        if (service == null) {
            throw new IllegalStateException("SoundStream is not installed or has not enabled");
        }
        return service;
    }

    private SoundStreamApi() {
    }
}

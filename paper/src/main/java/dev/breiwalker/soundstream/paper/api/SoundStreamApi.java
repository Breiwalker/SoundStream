package dev.breiwalker.soundstream.paper.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Convenience lookup for the SoundStream service.
 *
 * <p>Other plugins should declare {@code depend: [SoundStream]} so the service
 * is registered before their own enable method runs.</p>
 */
public final class SoundStreamApi {
    public static SoundStreamService get() {
        RegisteredServiceProvider<SoundStreamService> registration =
                Bukkit.getServicesManager().getRegistration(SoundStreamService.class);
        return registration == null ? null : registration.getProvider();
    }

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

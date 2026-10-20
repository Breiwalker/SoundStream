package dev.breiwalker.soundstream.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Client-side settings persisted to {@code config/soundstream.json}.
 */
public final class SoundStreamConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("SoundStream");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("soundstream.json");

    private static SoundStreamConfig instance = new SoundStreamConfig();

    private volatile boolean enabled = true;
    private volatile float volume = 1.0f;
    private volatile int prebufferMillis = 200;

    private SoundStreamConfig() {
    }

    public static SoundStreamConfig get() {
        return instance;
    }

    public static synchronized void load() {
        if (!Files.isRegularFile(PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(PATH)) {
            SoundStreamConfig loaded = GSON.fromJson(reader, SoundStreamConfig.class);
            if (loaded != null) {
                loaded.clamp();
                instance = loaded;
            }
        } catch (Exception exception) {
            LOGGER.warn("Could not read SoundStream config; using defaults", exception);
        }
    }

    public synchronized void save() {
        try {
            Path parent = PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save SoundStream config", exception);
        }
    }

    public void reset() {
        enabled = true;
        volume = 1.0f;
        prebufferMillis = 200;
    }

    private void clamp() {
        volume = Math.max(0.0f, Math.min(1.0f, volume));
        prebufferMillis = Math.max(20, Math.min(2000, prebufferMillis));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getVolume() {
        return volume;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
    }

    public int getPrebufferMillis() {
        return prebufferMillis;
    }

    public void setPrebufferMillis(int prebufferMillis) {
        this.prebufferMillis = Math.max(20, Math.min(2000, prebufferMillis));
    }
}

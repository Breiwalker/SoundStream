package dev.breiwalker.soundstream.client.audio;

import dev.breiwalker.soundstream.client.stream.ClientStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mixes server-streamed audio through Java Sound. Using Java Sound avoids
 * touching Minecraft's OpenAL context and resource-pack sound engine.
 */
public final class AudioEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("SoundStream");

    private final Map<ClientStream, StreamingSource> sources = new ConcurrentHashMap<>();

    private volatile ListenerState listener = ListenerState.DEFAULT;
    private volatile float masterVolume = 1.0f;

    public void setDeviceName(String ignored) {
        // Java Sound uses the system default output device.
    }

    public void setListener(ListenerState listener, float masterVolume) {
        this.listener = listener == null ? ListenerState.DEFAULT : listener;
        this.masterVolume = Math.max(0.0f, Math.min(1.0f, masterVolume));
    }

    ListenerState listener() {
        return listener;
    }

    float masterVolume() {
        return masterVolume;
    }

    public void attach(ClientStream stream) {
        sources.computeIfAbsent(stream, key -> {
            try {
                StreamingSource source = new StreamingSource(stream, this);
                source.start();
                LOGGER.debug("SoundStream {} attached at {} Hz, {} channel(s)", stream.id(),
                        stream.sampleRate(), stream.audioChannels());
                return source;
            } catch (Throwable throwable) {
                LOGGER.warn("Could not open Java Sound output for SoundStream " + stream.id(), throwable);
                stream.onAudioUnavailable();
                return null;
            }
        });
    }

    public void detach(ClientStream stream) {
        StreamingSource source = sources.remove(stream);
        if (source != null) {
            LOGGER.debug("SoundStream {} detaching output", stream.id());
            source.close();
        }
    }

    public void stopAll() {
        for (StreamingSource source : sources.values()) {
            source.close();
        }
        sources.clear();
    }

    public void shutdown() {
        stopAll();
    }

    public record ListenerState(
            float x, float y, float z,
            float forwardX, float forwardY, float forwardZ,
            float upX, float upY, float upZ
    ) {
        public static final ListenerState DEFAULT = new ListenerState(
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, -1.0f,
                0.0f, 1.0f, 0.0f
        );
    }
}

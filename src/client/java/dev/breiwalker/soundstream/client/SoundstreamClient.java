package dev.breiwalker.soundstream.client;

import dev.breiwalker.soundstream.client.network.StreamPayload;
import dev.breiwalker.soundstream.client.stream.StreamManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SoundstreamClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("SoundStream");
    private final StreamManager manager = new StreamManager(Minecraft.getInstance());

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.clientboundPlay().register(StreamPayload.TYPE, StreamPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StreamPayload.TYPE, StreamPayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(StreamPayload.TYPE,
                (payload, context) -> manager.onPayload(payload.data()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> manager.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> manager.onDisconnect());
        ClientTickEvents.END_CLIENT_TICK.register(client -> manager.tick());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.shutdown());
        LOGGER.info("SoundStream client initialized");
    }
}

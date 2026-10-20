package dev.breiwalker.soundstream.client.network;

import dev.breiwalker.soundstream.protocol.Protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StreamPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<StreamPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("soundstream", "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StreamPayload> CODEC =
            CustomPacketPayload.codec(StreamPayload::write, StreamPayload::read);

    public StreamPayload {
        if (data.length > Protocol.HARD_MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Payload too large");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(StreamPayload payload, RegistryFriendlyByteBuf buffer) {
        buffer.writeBytes(payload.data);
    }

    private static StreamPayload read(RegistryFriendlyByteBuf buffer) {
        int length = buffer.readableBytes();
        if (length > Protocol.HARD_MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Payload too large");
        }
        byte[] data = new byte[length];
        buffer.readBytes(data);
        return new StreamPayload(data);
    }
}

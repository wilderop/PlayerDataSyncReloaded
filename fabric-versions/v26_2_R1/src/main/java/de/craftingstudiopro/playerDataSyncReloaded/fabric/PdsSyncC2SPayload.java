package de.craftingstudiopro.playerDataSyncReloaded.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;

/**
 * Reads remaining payload bytes as UTF-8 so Velocity plugin messages
 * ({@code save:<uuid>} with no VarInt prefix) do not kick the client.
 */
public record PdsSyncC2SPayload(String message) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PdsSyncC2SPayload> PACKET_ID =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("pds", "sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PdsSyncC2SPayload> CODEC =
        StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.message().getBytes(StandardCharsets.UTF_8)),
            buf -> {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new PdsSyncC2SPayload(new String(bytes, StandardCharsets.UTF_8));
            }
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }
}

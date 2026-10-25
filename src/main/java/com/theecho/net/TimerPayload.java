package com.theecho.net;

import com.theecho.TheEcho;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Servidor -> cliente: segundos que quedan del apocalipsis del día 5 (-1 = ocultar el contador). */
public record TimerPayload(int seconds) implements CustomPayload {
    public static final CustomPayload.Id<TimerPayload> ID = new CustomPayload.Id<>(TheEcho.id("timer"));
    public static final PacketCodec<RegistryByteBuf, TimerPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TimerPayload::seconds,
            TimerPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}

package com.theecho.net;

import com.theecho.TheEcho;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * Servidor -> cliente.
 * hud: 0 = ocultar HUD, 1 = mostrar, -1 = sin cambio.
 * dread / paranoia: 0..100 (-1 = sin cambio). day: día del Eco a mostrar (1, 2, 3...).
 * stage: 0 normal, 1 tensión, 2 corrupción (cielo sepia), 3 final.
 * scare: 0 nada, 1 susto corto, 2 susto fuerte con cara.
 */
public record DreadPayload(int hud, int dread, int paranoia, int day, int stage, int scare) implements CustomPayload {
    public static final CustomPayload.Id<DreadPayload> ID = new CustomPayload.Id<>(TheEcho.id("dread"));
    public static final PacketCodec<RegistryByteBuf, DreadPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, DreadPayload::hud,
            PacketCodecs.VAR_INT, DreadPayload::dread,
            PacketCodecs.VAR_INT, DreadPayload::paranoia,
            PacketCodecs.VAR_INT, DreadPayload::day,
            PacketCodecs.VAR_INT, DreadPayload::stage,
            PacketCodecs.VAR_INT, DreadPayload::scare,
            DreadPayload::new);

    public static DreadPayload scare(int level) {
        return new DreadPayload(-1, -1, -1, -1, -1, level);
    }

    /** Muestra el título "Día N de ▒▒▒▒" con campana. */
    public static DreadPayload title(int day) {
        return new DreadPayload(-1, -1, -1, day, -1, 3);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}

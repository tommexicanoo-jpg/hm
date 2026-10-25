package com.theecho;

import com.theecho.entity.EchoEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Paranoia auditiva: sonidos "inversos" (pasos a tu espalda, un cofre que se abre detrás de ti, tus propias pisadas)
 * y falsos eventos (un "aldeano" que te habla y en realidad es un bloque de lana raro). Cuanto más llevas sin dormir,
 * más frecuentes.
 */
public final class Paranoia {
    private static final Random RNG = new Random();

    private Paranoia() {}

    /** 0..1: cuánto llevas sin dormir (2 días de juego sin cama = 1). */
    public static double insomnia(ServerPlayerEntity p) {
        int rest = p.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.TIME_SINCE_REST));
        return Math.max(0.0, Math.min(1.0, rest / 48000.0));
    }

    public static void playTo(ServerPlayerEntity p, SoundEvent s, SoundCategory c, Vec3d pos, float vol, float pitch) {
        if (p.isRemoved()) return;
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(s), c,
                pos.x, pos.y, pos.z, vol, pitch, p.getRandom().nextLong()));
    }

    public static Vec3d back(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d b = new Vec3d(-look.x, 0, -look.z);
        return b.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : b.normalize();
    }

    public static Vec3d ring(Vec3d base, double dist) {
        double a = RNG.nextDouble() * Math.PI * 2;
        return base.add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
    }

    /** Una vez por segundo. */
    public static void tick(MinecraftServer server, ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, int day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (day < cfg.audioStartDay) return;
        double ins = insomnia(p);
        double chance = Math.min(0.20, 0.02 + d.paranoia * 0.0012 + ins * 0.06 + day * 0.004) * cfg.eventIntensity;
        if (RNG.nextDouble() > chance) return;

        boolean underground = !w.isSkyVisible(p.getBlockPos()) && p.getY() < 55;
        int max = 6;
        if (day >= 2) max = 8;
        if (day >= cfg.fakeBlockDay) max = 10;
        if (d.dread > 60 || ins > 0.7) max = 11;
        int roll = RNG.nextInt(max);
        if (underground && RNG.nextInt(3) == 0) roll = 0;
        Vec3d back = back(p);
        Vec3d base = p.getPos();
        int t = server.getTicks();

        switch (roll) {
            case 0 -> { // pasos justo detrás de ti
                SoundEvent step = w.getBlockState(p.getBlockPos().down()).getSoundGroup().getStepSound();
                for (int i = 0; i < 6; i++) {
                    final int k = i;
                    Sched.at(server, i * 10, () -> playTo(p, step, SoundCategory.NEUTRAL,
                            base.add(back.multiply(6.0 - k * 0.9)), 0.8f, 0.9f + RNG.nextFloat() * 0.2f));
                }
            }
            case 1 -> { // un cofre abriéndose a tu espalda
                Vec3d pos = base.add(back.multiply(4));
                playTo(p, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, pos, 0.7f, 0.95f);
                Sched.at(server, 50, () -> playTo(p, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, pos, 0.7f, 0.95f));
            }
            case 2 -> { // una puerta
                Vec3d pos = ring(base, 6);
                playTo(p, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, pos, 0.8f, 0.9f);
                Sched.at(server, 40, () -> playTo(p, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, pos, 0.8f, 0.9f));
            }
            case 3 -> { // el crujido de tus propias pisadas, de hace unos minutos
                SoundEvent s = EchoManager.recordedSound(p);
                playTo(p, s, SoundCategory.NEUTRAL, base.add(back.multiply(4)), 0.7f, 0.6f);
            }
            case 4 -> // un creeper que no está ahí
                playTo(p, SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, base.add(back.multiply(3)), 1.0f, 1.0f);
            case 5 -> // un grito lejano
                playTo(p, RNG.nextBoolean() ? SoundEvents.ENTITY_GHAST_SCREAM : SoundEvents.ENTITY_ENDERMAN_SCREAM,
                        SoundCategory.HOSTILE, ring(base, 28), 0.6f, 0.6f + RNG.nextFloat() * 0.2f);
            case 6 -> // un susurro grave pegado a tu espalda
                playTo(p, SoundEvents.ENTITY_WARDEN_AMBIENT, SoundCategory.AMBIENT, base.add(back.multiply(2)), 0.7f, 0.5f);
            case 7 -> // algo enorme respira cerca
                playTo(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.HOSTILE, ring(base, 8), 0.8f, 0.7f);
            case 8 -> EchoManager.glimpse(p, w, d, day);
            case 9 -> fakeVillager(server, p, w);
            default -> { // grito del sculk + sobresalto
                playTo(p, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, base.add(back.multiply(5)), 1.0f, 0.7f);
                EchoManager.scare(p, 1);
                d.dread += 1.5;
            }
        }
        d.paranoia = Math.min(100, d.paranoia + 0.6);
        EchoManager.markDirty(server);
    }

    // ------------------------------------------------------------------ falsos bloques (solo los ve el jugador)

    /**
     * Crees que un aldeano te habla... pero al acercarte es un bloque de lana con una textura extraña.
     * El bloque falso solo existe en el cliente de ese jugador: no toca el mundo.
     */
    private static void fakeVillager(MinecraftServer server, ServerPlayerEntity p, ServerWorld w) {
        BlockPos spot = findSpot(w, p, 7, 14);
        if (spot == null) return;
        Vec3d center = Vec3d.ofBottomCenter(spot);
        BlockState fake = switch (RNG.nextInt(3)) {
            case 0 -> Blocks.WHITE_WOOL.getDefaultState();
            case 1 -> Blocks.GRAY_WOOL.getDefaultState();
            default -> Blocks.LIGHT_GRAY_WOOL.getDefaultState();
        };
        // Primero el sonido: un aldeano que murmura
        playTo(p, SoundEvents.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, center, 1.0f, 0.75f);
        Sched.at(server, 30, () -> {
            if (p.isRemoved()) return;
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(spot, fake));
            p.networkHandler.sendPacket(new BlockUpdateS2CPacket(spot.up(), fake));
            playTo(p, SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, center, 1.0f, 0.7f);
            watchFake(server, p, w, spot, 0);
        });
    }

    private static void watchFake(MinecraftServer server, ServerPlayerEntity p, ServerWorld w, BlockPos spot, int age) {
        Sched.at(server, 10, () -> {
            if (p.isRemoved()) return;
            boolean close = p.squaredDistanceTo(Vec3d.ofBottomCenter(spot)) < 3.5 * 3.5;
            if (close || age > 60 || p.getWorld() != w) {
                // al acercarte, el engaño se deshace
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(spot, w.getBlockState(spot)));
                p.networkHandler.sendPacket(new BlockUpdateS2CPacket(spot.up(), w.getBlockState(spot.up())));
                if (close) {
                    playTo(p, SoundEvents.BLOCK_WOOL_BREAK, SoundCategory.BLOCKS, Vec3d.ofBottomCenter(spot), 1.0f, 0.5f);
                    playTo(p, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, p.getPos(), 0.5f, 0.6f);
                    EchoManager.scare(p, 1);
                }
            } else {
                watchFake(server, p, w, spot, age + 1);
            }
        });
    }

    private static BlockPos findSpot(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        for (int i = 0; i < 14; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = min + RNG.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            BlockPos pos = new BlockPos(x, w.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (Math.abs(pos.getY() - p.getY()) > 6) continue;
            if (!w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) continue;
            if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) continue;
            return pos;
        }
        return null;
    }
}

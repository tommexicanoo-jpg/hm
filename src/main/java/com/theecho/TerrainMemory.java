package com.theecho;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Memoria del Terreno" + "La Decadencia Silenciosa".
 * <p>
 * Cada vez que rompes o colocas un bloque en el Overworld, el mod recuerda cómo era ese lugar ANTES de que llegaras.
 * Con los días, lo que modificaste se va revirtiendo a ese estado original cuando no lo estás mirando (una ventana
 * desaparece, una puerta aparece abierta, el suelo recupera su pasto). Un Espejo del Eco protege su radio.
 * Los bloques con inventario (cofres, hornos...) nunca se tocan, para no destruir tus objetos.
 */
public final class TerrainMemory {
    private static final Random RNG = new Random();

    private TerrainMemory() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity)) return;
            if (be != null) return; // no recordamos cofres ni hornos
            remember(w, pos, state);
        });

        // Colocar bloques / cubos: se compara el entorno 2 ticks después para saber qué cambió y cómo era antes
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity)) return ActionResult.PASS;
            if (!w.getRegistryKey().equals(World.OVERWORLD)) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof BlockItem) && !(stack.getItem() instanceof BucketItem)) return ActionResult.PASS;

            BlockPos clicked = hit.getBlockPos();
            BlockPos placed = clicked.offset(hit.getSide());
            List<BlockPos> watch = new ArrayList<>();
            List<BlockState> before = new ArrayList<>();
            for (BlockPos center : new BlockPos[]{clicked, placed}) {
                for (BlockPos p : BlockPos.iterate(center.add(-1, -1, -1), center.add(1, 1, 1))) {
                    BlockPos im = p.toImmutable();
                    if (watch.contains(im)) continue;
                    watch.add(im);
                    before.add(w.getBlockState(im));
                }
            }
            MinecraftServer server = w.getServer();
            Sched.at(server, 2, () -> {
                for (int i = 0; i < watch.size(); i++) {
                    BlockPos p = watch.get(i);
                    BlockState now = w.getBlockState(p);
                    if (now != before.get(i) && !w.getBlockState(p).hasBlockEntity()) {
                        remember(w, p, before.get(i));
                    }
                }
            });
            return ActionResult.PASS;
        });

        // Al volver a un lugar lejano, lo que dejaste ya se ha ido deshaciendo
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!world.getRegistryKey().equals(World.OVERWORLD)) return;
            MinecraftServer server = world.getServer();
            EchoState st = EchoState.get(server);
            if (st.memory.isEmpty()) return;
            var set = st.byChunk.get(ChunkPos.toLong(chunk.getPos().x, chunk.getPos().z));
            if (set == null || set.isEmpty()) return;
            List<Long> keys = new ArrayList<>(set);
            Sched.at(server, 5, () -> catchUp(server, world, keys));
        });
    }

    // ------------------------------------------------------------------ recordar

    public static void remember(ServerWorld w, BlockPos pos, BlockState original) {
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return;
        EchoState st = EchoState.get(w.getServer());
        long key = pos.asLong();
        if (st.memory.containsKey(key)) return; // se conserva el estado más antiguo: cómo era de verdad
        st.putMemory(key, new EchoState.Mem(original, w.getTime()));
    }

    // ------------------------------------------------------------------ decadencia

    /** Cada ~5 s por jugador activo. */
    public static void tick(MinecraftServer server, ServerWorld w, ServerPlayerEntity p, int day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        if (st.memory.isEmpty()) return;

        long now = w.getTime();
        long grace = (long) (cfg.decayGraceDays * 24000L);
        boolean decaying = cfg.enableDecay && day >= cfg.decayStartDay;
        Vec3d eye = p.getEyePos();
        Vec3d look = p.getRotationVec(1.0f).normalize();

        List<Long> resolved = new ArrayList<>();
        List<Long> candidates = new ArrayList<>();
        // Solo se recorren los chunks cercanos (índice por chunk), no toda la memoria del mundo
        int pcx = p.getChunkPos().x, pcz = p.getChunkPos().z;
        for (int cx = pcx - 8; cx <= pcx + 8; cx++) {
            for (int cz = pcz - 8; cz <= pcz + 8; cz++) {
                var set = st.byChunk.get(ChunkPos.toLong(cx, cz));
                if (set == null || set.isEmpty() || !w.isChunkLoaded(cx, cz)) continue;
                for (long k : set) {
                    EchoState.Mem mem = st.memory.get(k);
                    if (mem == null) continue;
                    BlockState cur = w.getBlockState(BlockPos.fromLong(k));
                    if (cur == mem.original()) { resolved.add(k); continue; } // ya está como al principio
                    if (!decaying || now - mem.time() < grace) continue;
                    if (cur.hasBlockEntity()) continue;
                    candidates.add(k);
                }
            }
        }
        for (long k : resolved) st.removeMemory(k);
        if (candidates.isEmpty()) return;

        int budget = Math.min(10, 1 + day / 2);
        int guard = 0;
        while (budget > 0 && !candidates.isEmpty() && guard++ < 40) {
            long k = candidates.remove(RNG.nextInt(candidates.size()));
            BlockPos pos = BlockPos.fromLong(k);
            Vec3d to = Vec3d.ofCenter(pos).subtract(eye);
            double dist = to.length();
            boolean inView = dist < 90 && look.dotProduct(to.normalize()) > 0.35; // solo cuando NO lo miras
            if (inView) continue;
            if (EchoManager.isProtected(w, pos)) continue;
            decayOne(server, st, w, pos, st.memory.get(k));
            budget--;
        }
    }

    private static void decayOne(MinecraftServer server, EchoState st, ServerWorld w, BlockPos pos, EchoState.Mem mem) {
        if (mem == null) return;
        BlockState cur = w.getBlockState(pos);
        // Las puertas primero se abren solas: "la puerta está abierta"
        if (cur.isIn(BlockTags.DOORS) && cur.contains(DoorBlock.OPEN) && !cur.get(DoorBlock.OPEN)
                && cur.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && RNG.nextInt(3) != 0) {
            ((DoorBlock) cur.getBlock()).setOpen(null, w, cur, pos, true);
            return;
        }
        // Sin NOTIFY_NEIGHBORS: nada suelta objetos ni hace ruido
        w.setBlockState(pos, mem.original(), Block.NOTIFY_LISTENERS);
        st.removeMemory(pos.asLong());
    }

    /** Decadencia "ocurrida mientras estabas lejos": se aplica al cargar el chunk. */
    private static void catchUp(MinecraftServer server, ServerWorld w, List<Long> keys) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (!cfg.enableDecay) return;
        EchoState st = EchoState.get(server);
        long now = w.getTime();
        int day = EchoManager.globalDay(server, st);
        if (day < cfg.decayStartDay) return;
        long grace = (long) (cfg.decayGraceDays * 24000L);
        for (long k : keys) {
            EchoState.Mem mem = st.memory.get(k);
            if (mem == null) continue;
            BlockPos pos = BlockPos.fromLong(k);
            if (!w.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState cur = w.getBlockState(pos);
            if (cur == mem.original()) { st.removeMemory(k); continue; }
            if (cur.hasBlockEntity()) continue;
            long age = now - mem.time() - grace;
            if (age <= 0) continue;
            double ageDays = age / 24000.0;
            double chance = Math.min(0.9, ageDays * 0.07 * (1.0 + day * 0.12));
            if (RNG.nextDouble() > chance) continue;
            if (EchoManager.isProtected(w, pos)) continue;
            decayOne(server, st, w, pos, mem);
        }
    }

    /** Bloques tuyos que aún no han vuelto a su estado original (para el Rito y la corrupción). */
    public static int pending(MinecraftServer server) {
        return EchoState.get(server).memory.size();
    }
}

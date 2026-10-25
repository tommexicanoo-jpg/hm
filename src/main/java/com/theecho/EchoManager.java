package com.theecho;

import com.theecho.entity.EchoEntity;
import com.theecho.net.DreadPayload;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

public final class EchoManager {

    public static final RegistryKey<World> REALM = RegistryKey.of(RegistryKeys.WORLD, TheEcho.id("mirror_realm"));
    public static final String STARE_TAG = "the_echo_stare";
    public static final String SILENT_TAG = "the_echo_silenced";
    public static final String RABID_TAG = "the_echo_rabid";

    private static final Random RNG = new Random();

    private static final Map<UUID, List<EchoEntity>> ACTIVE = new HashMap<>();
    private static final Map<UUID, EchoEntity> REGISTRAR = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_ECHO = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_REGISTRAR = new HashMap<>();
    private static final Map<UUID, Boolean> ACTIVE_FLAG = new HashMap<>();
    private static final Map<UUID, Integer> STAGE = new HashMap<>();
    private static final Map<UUID, ArrayDeque<Rec>> RECORDINGS = new HashMap<>();
    private static final Map<UUID, Flicker> FLICKERS = new HashMap<>();
    private static final Map<UUID, Integer> REALM_TICKS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_HIJACK = new HashMap<>();
    private static final Set<MobEntity> FROZEN = new HashSet<>();
    private static final Map<UUID, List<BlockPos>> TRAPS = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> REALM_CELLS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_FOOD = new HashMap<>();
    private static final Map<UUID, Long> LAST_GAZE = new HashMap<>();
    private static final Map<UUID, int[]> LAST_HUD = new HashMap<>();
    private static final Map<UUID, List<MobEntity>> STARERS = new HashMap<>();
    private static final Map<UUID, List<PassiveEntity>> RABIDS = new HashMap<>();
    private static final Set<UUID> SILENCING = new HashSet<>();
    private static final Set<UUID> READING_FINAL = new HashSet<>();

    private record Rec(long time, SoundEvent sound) {}
    private record Flicker(int until, List<BlockPos> torches) {}
    private record ChopSpot(BlockPos log, BlockPos stand) {}

    private enum Kind { STARE, GLIMPSE, WALK, RUSH, SCREAM, FLY_WATCH, FLY_SWOOP }

    private EchoManager() {}

    // ------------------------------------------------------------------ registro

    public static void register() {
        TerrainMemory.register();
        ChatHaunt.register();
        ServerTickEvents.END_SERVER_TICK.register(EchoManager::tick);
        PlayerBlockBreakEvents.AFTER.register(EchoManager::onBlockBroken);
        // En el laberinto no se puede romper nada (salvo en creativo)
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !world.getRegistryKey().equals(REALM) || player.isCreative());
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
                world.getRegistryKey().equals(REALM) && !player.isCreative() ? ActionResult.FAIL : ActionResult.PASS);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity p) record(p, SoundEvents.ENTITY_PLAYER_HURT);
            return true;
        });
        EntitySleepEvents.STOP_SLEEPING.register(EchoManager::onWake);
        UseEntityCallback.EVENT.register(EchoManager::onUseEntity);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && hand == Hand.MAIN_HAND) {
                BlockState bs = world.getBlockState(hit.getBlockPos());
                if (bs.isOf(Blocks.CHEST) || bs.isOf(Blocks.TRAPPED_CHEST) || bs.isOf(Blocks.ENDER_CHEST)) {
                    record(sp, SoundEvents.BLOCK_CHEST_OPEN);
                } else if (bs.isOf(Blocks.FURNACE)) {
                    record(sp, SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE);
                } else if (bs.isIn(BlockTags.DOORS)) {
                    record(sp, SoundEvents.BLOCK_WOODEN_DOOR_OPEN);
                }
            }
            return ActionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof MobEntity m && m.getCommandTags().contains(STARE_TAG)) release(m);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(server, handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            dismissAll(id);
            ACTIVE_FLAG.remove(id);
            STAGE.remove(id);
            FLICKERS.remove(id);
            REALM_TICKS.remove(id);
            PENDING_HIJACK.remove(id);
            TRAPS.remove(id);
            REALM_CELLS.remove(id);
            NEXT_ECHO.remove(id);
            NEXT_REGISTRAR.remove(id);
            LAST_FOOD.remove(id);
            LAST_GAZE.remove(id);
            LAST_HUD.remove(id);
            STARERS.remove(id);
            RABIDS.remove(id);
            SILENCING.remove(id);
            READING_FINAL.remove(id);
            ChatHaunt.forget(id);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.clear(); REGISTRAR.clear(); FROZEN.clear(); ACTIVE_FLAG.clear(); RECORDINGS.clear(); FLICKERS.clear();
            REALM_TICKS.clear(); PENDING_HIJACK.clear(); TRAPS.clear(); REALM_CELLS.clear();
            NEXT_ECHO.clear(); NEXT_REGISTRAR.clear();
            READING_FINAL.clear(); LAST_FOOD.clear(); LAST_GAZE.clear(); LAST_HUD.clear(); STARERS.clear(); RABIDS.clear(); SILENCING.clear();
            ChatHaunt.clearAll();
            EchoState.clearCache();
            Sched.clear();
        });
    }

    // ------------------------------------------------------------------ días

    /** Días del mundo transcurridos desde que el mod vio este mundo por primera vez. */
    public static int globalDay(MinecraftServer server, EchoState st) {
        if (st.startDay < 0) return 0;
        long wd = server.getOverworld().getTimeOfDay() / 24000L;
        return (int) Math.max(0, wd - st.startDay);
    }

    /** El día del Eco: el que se muestra en pantalla. Las ofrendas lo reducen. */
    public static int effectiveDay(MinecraftServer server, EchoState st, EchoState.PlayerData d) {
        return Math.max(0, globalDay(server, st) - d.relief);
    }

    public static void markDirty(MinecraftServer server) {
        EchoState.get(server).markDirty();
    }

    // ------------------------------------------------------------------ tick principal

    private static void tick(MinecraftServer server) {
        Sched.run(server);
        int t = server.getTicks();
        EchoState state = EchoState.get(server);
        if (state.startDay < 0) {
            state.startDay = server.getOverworld().getTimeOfDay() / 24000L;
            state.markDirty();
        }
        if (t % 100 == 0 && !FROZEN.isEmpty()) sweepFrozen(server);
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            BlockPos hij = PENDING_HIJACK.remove(p.getUuid());
            if (hij != null) hijack(server, state, p, hij);
            tickPlayer(server, state, p, t);
        }
    }

    private static void sweepFrozen(MinecraftServer server) {
        FROZEN.removeIf(m -> {
            boolean near = false;
            if (!m.isRemoved() && m.isAlive()) {
                for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                    if (pl.getWorld() == m.getWorld() && pl.squaredDistanceTo(m) < 56 * 56) { near = true; break; }
                }
            }
            if (!near) {
                m.removeCommandTag(STARE_TAG);
                m.setAiDisabled(false);
                return true;
            }
            return false;
        });
    }

    private static void tickPlayer(MinecraftServer server, EchoState state, ServerPlayerEntity p, int t) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState.PlayerData d = state.data(p.getUuid());
        ServerWorld w = p.getServerWorld();
        ServerWorld ow = server.getOverworld();
        long gameTime = ow.getTime();
        int day = effectiveDay(server, state, d);
        boolean inRealm = w.getRegistryKey().equals(REALM);
        int phase = (t + p.getId()) % 20;

        // Salió del laberinto por otra vía (muerte, comando...)
        if (d.inRealm && !inRealm) {
            d.inRealm = false;
            d.dread = Math.min(d.dread, 30);
            d.banishedUntil = gameTime + 24000L;
            REALM_TICKS.remove(p.getUuid());
            state.markDirty();
        }
        if (inRealm) {
            if (d.inRealm) tickRealm(server, state, p, w, d, day);
            return;
        }
        if (p.isSpectator()) return;

        if (d.shrines >= Shrines.NOTE_COUNT && !d.apocTriggered && !d.freed) tickFinalBook(state, p, d);

        // Foto del inventario del primer día (para las ofrendas)
        if (!d.snapshotLocked && t % 100 == 0) {
            snapshotInventory(p, d);
            if (globalDay(server, state) >= 1) d.snapshotLocked = true;
            state.markDirty();
        }

        if (phase == 0) secondTick(server, state, p, w, d, day, t);

        boolean active = ACTIVE_FLAG.getOrDefault(p.getUuid(), false);
        tickStares(p, w, d, active && day >= cfg.stareAnimalsDay, t);

        Integer stg = STAGE.get(p.getUuid());
        if (stg != null && stg >= 3 && t % 2 == 0 && w.getRegistryKey().equals(World.OVERWORLD)) tickRabid(p, w, t);

        Flicker f = FLICKERS.get(p.getUuid());
        if (f != null) {
            if (t > f.until()) {
                FLICKERS.remove(p.getUuid());
            } else if (t % 3 == 0) {
                for (BlockPos pos : f.torches()) {
                    w.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
                            2, 0.04, 0.04, 0.04, 0.0);
                }
            }
        }
    }

    /** Al llegar al último lugar: cuando cierras el libro del atril, todo empieza a romperse. */
    private static void tickFinalBook(EchoState st, ServerPlayerEntity p, EchoState.PlayerData d) {
        if (st.sites.size() < Shrines.NOTE_COUNT) return;
        BlockPos last = st.sites.get(Shrines.NOTE_COUNT - 1);
        double dx = last.getX() + 0.5 - p.getX(), dz = last.getZ() + 0.5 - p.getZ();
        boolean near = dx * dx + dz * dz < 14.0 * 14.0;
        boolean reading = p.currentScreenHandler instanceof LecternScreenHandler;
        if (reading && near) {
            READING_FINAL.add(p.getUuid());
        } else if (!reading && READING_FINAL.remove(p.getUuid())) {
            triggerApocalypse(p);
        }
    }

    /** Empieza el día 5 de verdad (también para /echo apocalipsis). */
    public static void triggerApocalypse(ServerPlayerEntity p) {
        EchoState st = EchoState.get(p.getServer());
        EchoState.PlayerData d = st.data(p.getUuid());
        if (d.apocTriggered) return;
        d.apocTriggered = true;
        st.markDirty();
        scare(p, 2);
        Paranoia.playTo(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, p.getPos(), 1.0f, 0.5f);
    }

    /** 0 normal, 1 tensión, 2 corrupción (cielo sepia), 3 apocalipsis del día 5. No depende de lo que hayas construido. */
    private static int stageOf(MinecraftServer server, EchoState.PlayerData d, int day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (d.freed) return 0;
        // El apocalipsis solo empieza cuando cierras el libro del último lugar (o con /echo apocalipsis)
        if (d.apocTriggered && cfg.corruptionEnding) return 3;
        if (day >= cfg.corruptionDay) return 2;
        return day >= 2 ? 1 : 0;
    }

    private static void secondTick(MinecraftServer server, EchoState state, ServerPlayerEntity p, ServerWorld w,
                                   EchoState.PlayerData d, int day, int t) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        long sec = t / 20;
        boolean overworld = w.getRegistryKey().equals(World.OVERWORLD);
        int stage = stageOf(server, d, day);
        STAGE.put(p.getUuid(), stage);

        // El título "Día N de ▒▒▒▒" cada vez que empieza un día nuevo
        checkDayTitle(state, p, d, day);
        // El chat falso comenta tus logros (se comprueba cada ~3 s)
        if (sec % 3 == 0) ChatHaunt.pollAdvancements(server, p, d, day);

        boolean apoc = stage >= 3 && !d.freed;
        boolean active = overworld && (apoc || isActive(server, w, p.getBlockPos(), d));
        ACTIVE_FLAG.put(p.getUuid(), active);
        boolean prot = overworld && isProtected(w, p.getBlockPos());

        if (!overworld) {
            sendHud(p, 0, 0, 0, 0, 0, sec);
            dismissAll(p.getUuid());
            return;
        }

        // Las estructuras del diario aparecen en cadena (también cuando estás dentro de una: ahí estás protegido)
        if (sec % 2 == 0 && !d.freed) Shrines.tick(server, w, p, d, state, day);

        // --- miedo y paranoia ---
        double ins = Paranoia.insomnia(p);
        if (active) {
            d.dread = Math.min(120, d.dread + cfg.dreadPerMinute / 60.0 * (1.0 + day * 0.08));
            if (w.getLightLevel(p.getBlockPos()) < 5) d.dread = Math.min(120, d.dread + 0.08);
            d.paranoia = Math.min(100, d.paranoia + 0.03 + ins * 0.12 + (d.dread > 60 ? 0.03 : 0.0));
        } else if (prot) {
            d.dread = Math.max(0, d.dread - 0.2);
            d.paranoia = Math.max(0, d.paranoia - 0.15);
        }
        tickCalm(p, d); // comer te calma un poquito
        state.markDirty();
        sendHud(p, 1, (int) Math.min(100, d.dread), (int) Math.min(100, d.paranoia), day + 1, stage, sec);

        if (!active) {
            dismissAll(p.getUuid());
            return;
        }

        // --- el Rito, la corrupción y el final ---
        tickRite(server, state, p, d);
        tickCorruption(server, state, p, w, d, stage, sec);

        // --- apariciones ---
        director(server, w, p, d, day, stage);
        registrarDirector(server, w, p, d, day, stage);

        // --- chat, sonidos, falsos eventos ---
        ChatHaunt.tick(server, p, d, day);
        Paranoia.tick(server, p, w, d, day);
        if (d.dread >= cfg.echoDreadThreshold && RNG.nextDouble() < Math.min(0.06, 0.008 + d.dread * 0.0004)) glimpse(p, w, d, day);
        if (d.dread > 70 && RNG.nextDouble() < 0.02) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false, false));
        }
        if (day >= cfg.interfaceDay) {
            if (RNG.nextDouble() < Math.min(0.04, 0.01 + d.dread * 0.0003)) startFlicker(p, w, t);
            if (sec % 10 == 0 && RNG.nextDouble() < Math.min(0.5, 0.15 + d.dread * 0.004)) mutateNearbySign(p, w);
        }

        // --- terreno ---
        if (sec % 5 == 0) TerrainMemory.tick(server, w, p, day);
    }

    /** Si ya es un día nuevo (en pantalla), muestra el título con campana: "Día 2 de ▒▒▒▒", "Día 3 de ▒▒▒▒"... */
    private static void checkDayTitle(EchoState st, ServerPlayerEntity p, EchoState.PlayerData d, int day) {
        int shown = day + 1;
        if (d.lastDayShown == shown) return;
        boolean up = shown > d.lastDayShown;
        d.lastDayShown = shown;
        st.markDirty();
        if (up && EchoConfig.INSTANCE.dayTitle && !d.doomed) send(p, DreadPayload.title(shown));
    }

    /** Comer baja un poquito el miedo y la paranoia (se detecta cuando sube tu barra de hambre). */
    private static void tickCalm(ServerPlayerEntity p, EchoState.PlayerData d) {
        int food = p.getHungerManager().getFoodLevel();
        Integer last = LAST_FOOD.put(p.getUuid(), food);
        if (last != null && food > last) {
            EchoConfig cfg = EchoConfig.INSTANCE;
            calm(d, cfg.eatDreadRelief, cfg.eatParanoiaRelief);
        }
    }

    private static void calm(EchoState.PlayerData d, double dread, double paranoia) {
        d.dread = Math.max(0, d.dread - dread);
        d.paranoia = Math.max(0, d.paranoia - paranoia);
    }

    private static boolean isActive(MinecraftServer server, ServerWorld w, BlockPos pos, EchoState.PlayerData d) {
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return false;
        if (d.freed) return false;
        return server.getOverworld().getTime() >= d.banishedUntil && !isProtected(w, pos);
    }

    private static void snapshotInventory(ServerPlayerEntity p, EchoState.PlayerData d) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty()) d.day1Items.add(Registries.ITEM.getId(s.getItem()).toString());
        }
    }

    // ------------------------------------------------------------------ HUD / sustos

    private static void send(ServerPlayerEntity p, DreadPayload payload) {
        if (ServerPlayNetworking.canSend(p, DreadPayload.ID)) ServerPlayNetworking.send(p, payload);
    }

    /** Solo manda el HUD si cambió (o cada 10 s de todos modos): menos paquetes. */
    private static void sendHud(ServerPlayerEntity p, int hud, int dread, int paranoia, int day, int stage, long sec) {
        int[] cur = {hud, dread, paranoia, day, stage};
        int[] prev = LAST_HUD.get(p.getUuid());
        if (prev != null && Arrays.equals(prev, cur) && sec % 10 != 0) return;
        LAST_HUD.put(p.getUuid(), cur);
        send(p, new DreadPayload(hud, dread, paranoia, day, stage, 0));
    }

    public static void scare(ServerPlayerEntity p, int level) {
        send(p, DreadPayload.scare(level));
    }

    public static void addDread(ServerPlayerEntity p, double amount) {
        EchoState st = EchoState.get(p.getServer());
        EchoState.PlayerData d = st.data(p.getUuid());
        d.dread = Math.min(120, d.dread + amount);
        st.markDirty();
    }

    // ------------------------------------------------------------------ Rito, corrupción, final

    private static void tickRite(MinecraftServer server, EchoState st, ServerPlayerEntity p, EchoState.PlayerData d) {
        if (d.freed) return;
        if (!d.riteStarted && Shrines.hasNote(p)) {
            d.riteStarted = true;
            st.markDirty();
            p.sendMessage(Text.literal("La nota pide que deshagas todo lo que has construido.")
                    .formatted(Formatting.GRAY, Formatting.ITALIC), true);
        }
        if (d.riteStarted && TerrainMemory.pending(server) <= EchoConfig.INSTANCE.riteTolerance) completeRite(server, st, p, d);
    }

    private static void completeRite(MinecraftServer server, EchoState st, ServerPlayerEntity p, EchoState.PlayerData d) {
        d.freed = true;
        d.doomed = false;
        d.dread = 0;
        d.paranoia = 0;
        st.markDirty();
        dismissAll(p.getUuid());
        LAST_HUD.remove(p.getUuid());
        if (ServerPlayNetworking.canSend(p, com.theecho.net.TimerPayload.ID)) ServerPlayNetworking.send(p, new com.theecho.net.TimerPayload(-1));
        send(p, new DreadPayload(1, 0, 0, effectiveDay(server, st, d) + 1, 0, 0));
        Paranoia.playTo(p, SoundEvents.BLOCK_BELL_USE, SoundCategory.AMBIENT, p.getPos(), 1.0f, 0.5f);
        p.sendMessage(Text.literal("El mundo vuelve a ser el que era. Ya no te recuerda.")
                .formatted(Formatting.WHITE, Formatting.ITALIC), true);
        Sched.at(server, 120, () -> p.sendMessage(Text.literal("<" + p.getName().getString() + "> ...gracias"), false));
    }

    private static void tickCorruption(MinecraftServer server, EchoState st, ServerPlayerEntity p, ServerWorld w,
                                       EchoState.PlayerData d, int stage, long sec) {
        Box box = p.getBoundingBox().expand(64);
        if (stage == 2) {
            // Los animales dejan de hacer sonidos
            if (sec % 5 == 0) {
                SILENCING.add(p.getUuid());
                for (PassiveEntity e : w.getEntitiesByClass(PassiveEntity.class, box, x -> !x.isSilent())) {
                    e.setSilent(true);
                    e.addCommandTag(SILENT_TAG);
                }
            }
        } else if (sec % 10 == 0 && SILENCING.remove(p.getUuid())) {
            for (PassiveEntity e : w.getEntitiesByClass(PassiveEntity.class, box, x -> x.getCommandTags().contains(SILENT_TAG))) {
                e.setSilent(false);
                e.removeCommandTag(SILENT_TAG);
            }
        }
        if (stage >= 3 && !d.doomed) tickApocalypse(server, st, p, w, d, sec);
    }

    private static void infect(ServerWorld w, ServerPlayerEntity p) {
        List<PassiveEntity> rabids = RABIDS.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
        rabids.removeIf(e -> e.isRemoved() || !e.isAlive());
        if (rabids.size() >= 20) return;
        List<PassiveEntity> all = w.getEntitiesByClass(PassiveEntity.class, p.getBoundingBox().expand(48), e -> e.isAlive());
        int added = 0;
        for (PassiveEntity e : all) {
            if (rabids.size() >= 20 || added >= 6) break;
            if (e.getCommandTags().contains(RABID_TAG)) {
                if (!rabids.contains(e)) rabids.add(e);
                continue;
            }
            e.addCommandTag(RABID_TAG);
            e.setSilent(false);
            e.setAiDisabled(false);
            if (e instanceof MobEntity m) release(m);
            rabids.add(e);
            w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.0f, 0.5f + RNG.nextFloat() * 0.3f);
            added++;
        }
    }

    /** Los mobs no hostiles corren hacia ti y te muerden (sin consultar el mundo en cada tick: usa la lista guardada). */
    private static void tickRabid(ServerPlayerEntity p, ServerWorld w, int t) {
        List<PassiveEntity> l = RABIDS.get(p.getUuid());
        if (l == null) return;
        for (PassiveEntity e : l) {
            if (e.isRemoved() || !e.isAlive()) continue;
            if ((t + e.getId()) % 8 == 0) e.getNavigation().startMovingTo(p, 1.4);
            if (e.squaredDistanceTo(p) < 2.8 * 2.8 && (t + e.getId()) % 16 == 0) {
                p.damage(w.getDamageSources().mobAttack(e), 3.0f);
                double dx = p.getX() - e.getX(), dz = p.getZ() - e.getZ();
                p.takeKnockback(0.5, -dx, -dz);
                w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.0f, 0.7f);
            }
        }
    }

    private static void cosmeticLightning(ServerWorld w, ServerPlayerEntity p) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt == null) return;
        double ang = RNG.nextDouble() * Math.PI * 2;
        double dist = 10 + RNG.nextDouble() * 18;
        bolt.refreshPositionAfterTeleport(p.getX() + Math.cos(ang) * dist, p.getY(), p.getZ() + Math.sin(ang) * dist);
        bolt.setCosmetic(true);
        w.spawnEntity(bolt);
    }

    /** DÍA 5: todo se va a la mierda. Dura apocalypseSeconds; después te echa del mundo. */
    private static void tickApocalypse(MinecraftServer server, EchoState st, ServerPlayerEntity p, ServerWorld w,
                                       EchoState.PlayerData d, long sec) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        ServerWorld ow = server.getOverworld();
        long now = ow.getTime();
        if (d.apocStart < 0) {
            d.apocStart = now;
            st.markDirty();
            long dayStart = (ow.getTimeOfDay() / 24000L) * 24000L;
            ow.setTimeOfDay(dayStart + 14000L);
            ow.setWeather(0, 24000, true, true);
            scare(p, 2);
            Paranoia.playTo(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, p.getPos(), 1.0f, 0.5f);
            p.sendMessage(Text.literal("DÍA 5").formatted(Formatting.DARK_RED, Formatting.BOLD), true);
        }
        d.dread = 100;
        d.paranoia = 100;
        int day = effectiveDay(server, st, d);

        ChatHaunt.burst(server, p, 4, day);
        infect(w, p);
        if (RNG.nextInt(10) < 7) cosmeticLightning(w, p);
        if (sec % 2 == 0) scare(p, RNG.nextInt(4) == 0 ? 2 : 1);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 160, 0, false, false, false));
        if (RNG.nextInt(8) == 0) p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 25, 0, false, false, false));
        // Ecos que solo avanzan cuando no los miras y te alcanzan de verdad
        List<EchoEntity> list = activeList(p.getUuid());
        long stalkers = list.stream().filter(e -> e.getMode() == EchoEntity.Mode.STALK).count();
        if (stalkers < 5) {
            BlockPos s = findSurfaceSpot(w, p, 14, 24, -1);
            if (s != null) spawn(w, p, s, EchoEntity.Mode.STALK, RNG.nextInt(10) < 8 ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL);
        }
        Paranoia.playTo(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, p.getPos(), 1.0f, 0.8f);

        int remaining = (int) Math.max(0, cfg.apocalypseSeconds - (now - d.apocStart) / 20L);
        if (ServerPlayNetworking.canSend(p, com.theecho.net.TimerPayload.ID)) {
            ServerPlayNetworking.send(p, new com.theecho.net.TimerPayload(remaining));
        }
        if (now - d.apocStart >= cfg.apocalypseSeconds * 20L) beginEnding(server, st, p, d);
    }

    private static Path worldRoot(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
    }

    /** El final: el mundo te echa y debes borrarlo tú desde la carpeta saves. (El mod no borra nada.) */
    private static void beginEnding(MinecraftServer server, EchoState st, ServerPlayerEntity p, EchoState.PlayerData d) {
        d.doomed = true;
        st.markDirty();
        dismissAll(p.getUuid());
        ThanksWatcher.arm(worldRoot(server));
        String n = p.getName().getString();
        String world = server.getSaveProperties().getLevelName();
        Sched.at(server, 0, () -> p.sendMessage(Text.literal("<" + n + "> BORRA EL MUNDO"), false));
        Sched.at(server, 30, () -> p.sendMessage(Text.literal("<" + n + "> saves/" + world), false));
        Sched.at(server, 60, () -> p.sendMessage(Text.literal("El mundo ya no te recuerda.")
                .formatted(Formatting.WHITE, Formatting.ITALIC), false));
        Sched.at(server, 100, () -> kickEnding(p)); // 5 segundos después: fuera
    }

    private static void kickEnding(ServerPlayerEntity p) {
        if (p.isRemoved() || p.networkHandler == null) return;
        String world = p.getServer().getSaveProperties().getLevelName();
        p.networkHandler.disconnect(Text.literal("El mundo ya no te recuerda.\n\nBórralo tú mismo:\nsaves/" + world)
                .formatted(Formatting.GRAY));
    }

    private static void onJoin(MinecraftServer server, ServerPlayerEntity p) {
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        LAST_HUD.remove(p.getUuid());
        if (d.doomed && !d.freed && EchoConfig.INSTANCE.corruptionEnding) {
            ThanksWatcher.arm(worldRoot(server));
            Sched.at(server, 15, () -> p.sendMessage(Text.literal("Esto no debería seguir existiendo.")
                    .formatted(Formatting.DARK_RED, Formatting.ITALIC), false));
            Sched.at(server, 50, () -> {
                EchoState.PlayerData dd = EchoState.get(server).data(p.getUuid());
                if (dd.doomed && !dd.freed && EchoConfig.INSTANCE.corruptionEnding) kickEnding(p);
            });
        }
    }

    /** El título de bienvenida del día actual (también para /echo intro). */
    public static void intro(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        send(p, DreadPayload.title(effectiveDay(server, st, st.data(p.getUuid())) + 1));
    }

    // ------------------------------------------------------------------ director de apariciones

    private static List<EchoEntity> activeList(UUID id) {
        List<EchoEntity> l = ACTIVE.computeIfAbsent(id, k -> new ArrayList<>());
        l.removeIf(e -> e.isRemoved() || !e.isAlive());
        return l;
    }

    public static void dismissAll(UUID id) {
        List<EchoEntity> l = ACTIVE.remove(id);
        if (l != null) for (EchoEntity e : l) if (e.isAlive()) e.vanish();
        EchoEntity r = REGISTRAR.remove(id);
        if (r != null && r.isAlive()) r.discard();
    }

    /** Cuántos Ecos puede haber a la vez con este miedo: 0 por debajo del umbral, y uno más cada echoDreadStep. */
    private static int echoCap(EchoState.PlayerData d, boolean apoc) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (apoc) return 8;
        if (d.dread < cfg.echoDreadThreshold) return 0;
        return Math.min(cfg.maxEchoes, 1 + (int) ((d.dread - cfg.echoDreadThreshold) / cfg.echoDreadStep));
    }

    /** Los Ecos aparecen según el MIEDO (desde 15) y cuanto más miedo, más a la vez y más seguido. */
    private static void director(MinecraftServer server, ServerWorld w, ServerPlayerEntity p, EchoState.PlayerData d,
                                 int day, int stage) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        UUID id = p.getUuid();
        boolean apoc = stage >= 3;
        int cap = echoCap(d, apoc);
        List<EchoEntity> list = activeList(id);
        if (cap == 0) {
            // El miedo bajó (espejo, comida...): los Ecos se retiran
            if (!list.isEmpty() && d.dread < cfg.echoDreadThreshold - 5) {
                for (EchoEntity e : list) if (e.isAlive()) e.vanish();
                list.clear();
            }
            return;
        }
        if (list.size() >= cap) return;
        int now = server.getTicks();
        Integer next = NEXT_ECHO.get(id);
        if (next != null && now < next) return;

        double chance = Math.min(0.85, 0.35 + (d.dread - cfg.echoDreadThreshold) * 0.008 + d.paranoia * 0.002) * cfg.eventIntensity;
        if (w.isRaining()) chance *= 1.3;
        if (w.getTimeOfDay() % 24000L > 13000L) chance *= 1.3;
        if (stage >= 2) chance *= 1.3;
        if (apoc) chance = 0.9;
        if (RNG.nextDouble() > Math.min(0.95, chance)) return;

        Kind kind = pickKind(d.dread);
        if (spawnKind(w, p, d, day, kind)) {
            double gapSeconds = apoc ? 3 : Math.max(4.0, 28.0 - d.dread * 0.24);
            NEXT_ECHO.put(id, now + (int) (gapSeconds * (0.7 + RNG.nextDouble() * 0.6) * 20.0));
        }
    }

    private static Kind pickKind(double dread) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        List<Kind> pool = new ArrayList<>();
        add(pool, Kind.STARE, 4);
        add(pool, Kind.GLIMPSE, 3);
        if (dread >= cfg.walkDread) add(pool, Kind.WALK, 2);
        if (dread >= cfg.rushDread) add(pool, Kind.RUSH, 3);
        if (dread >= cfg.screamDread) add(pool, Kind.SCREAM, 2);
        if (dread >= cfg.flyDread) add(pool, Kind.FLY_WATCH, 2);
        if (dread >= cfg.flyDread) add(pool, Kind.FLY_SWOOP, 2);
        return pool.get(RNG.nextInt(pool.size()));
    }

    private static void add(List<Kind> pool, Kind k, int weight) {
        for (int i = 0; i < weight; i++) pool.add(k);
    }

    /** Con más miedo (y más días), más Ecos de skin negra. */
    private static int pickVariant(int day, double dread) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (dread < cfg.shadowDread) return EchoEntity.V_NORMAL;
        double shadow = Math.min(0.8, 0.25 + (dread - cfg.shadowDread) * 0.01 + day * 0.05);
        return RNG.nextDouble() < shadow ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL;
    }

    private static boolean spawnKind(ServerWorld w, ServerPlayerEntity p, EchoState.PlayerData d, int day, Kind kind) {
        boolean cave = !w.isSkyVisible(p.getBlockPos()) && p.getY() < 55;
        int variant = pickVariant(day, d.dread);
        BlockPos s;
        switch (kind) {
            case STARE -> {
                s = cave ? findCavePos(w, p, 14, 24) : findSurfaceSpot(w, p, 26, 40, -1);
                return s != null && spawn(w, p, s, EchoEntity.Mode.WATCH, variant) != null;
            }
            case GLIMPSE -> {
                return glimpse(p, w, d, day);
            }
            case WALK -> {
                s = cave ? findCavePos(w, p, 16, 26) : findSurfaceSpot(w, p, 26, 40, -1);
                return s != null && spawn(w, p, s, EchoEntity.Mode.WALK, variant) != null;
            }
            case RUSH -> {
                s = cave ? findCavePos(w, p, 14, 24) : findSurfaceSpot(w, p, 20, 30, 45);
                return s != null && spawn(w, p, s, EchoEntity.Mode.RUSH, variant) != null;
            }
            case SCREAM -> {
                s = cave ? findCavePos(w, p, 8, 14) : findSurfaceSpot(w, p, 9, 15, 60);
                return s != null && spawn(w, p, s, EchoEntity.Mode.SCREAM, RNG.nextBoolean() ? EchoEntity.V_SHADOW : variant) != null;
            }
            case FLY_WATCH, FLY_SWOOP -> {
                if (cave) return false;
                boolean swoop = kind == Kind.FLY_SWOOP;
                s = swoop ? findSurfaceSpot(w, p, 24, 34, 70) : findSurfaceSpot(w, p, 14, 24, -1);
                if (s == null) return false;
                int h = swoop ? 11 : 8;
                while (h > 3 && !columnFree(w, s, h)) h--;
                if (h <= 3) return false;
                return spawn(w, p, s.up(h), swoop ? EchoEntity.Mode.FLY_SWOOP : EchoEntity.Mode.FLY_WATCH, variant) != null;
            }
        }
        return false;
    }

    private static boolean columnFree(ServerWorld w, BlockPos base, int h) {
        for (int i = 1; i <= h + 1; i++) if (!w.getBlockState(base.up(i)).isAir()) return false;
        return true;
    }

    private static EchoEntity spawn(ServerWorld w, ServerPlayerEntity p, BlockPos pos, EchoEntity.Mode mode, int variant) {
        EchoEntity e = ModEntities.ECHO.create(w);
        if (e == null) return null;
        e.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        e.init(p, variant, mode);
        w.spawnEntity(e);
        if (!w.getRegistryKey().equals(REALM)) activeList(p.getUuid()).add(e);
        return e;
    }

    /** cone > 0: dentro de ese ángulo (grados) a cada lado de hacia donde miras. cone <= 0: cualquier dirección. */
    private static BlockPos findSurfaceSpot(ServerWorld w, ServerPlayerEntity p, int min, int max, double cone) {
        Vec3d look = p.getRotationVec(1.0f);
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 14; i++) {
            double ang = cone > 0 ? base + Math.toRadians((RNG.nextDouble() * 2 - 1) * cone) : RNG.nextDouble() * Math.PI * 2;
            double dist = min + RNG.nextDouble() * (max - min);
            BlockPos pos = surfaceAt(w, p, (int) Math.floor(p.getX() + Math.cos(ang) * dist),
                    (int) Math.floor(p.getZ() + Math.sin(ang) * dist), 12);
            if (pos != null) return pos;
        }
        return null;
    }

    private static BlockPos surfaceAt(ServerWorld w, ServerPlayerEntity p, int x, int z, int maxDy) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - p.getY()) > maxDy) return null;
        BlockPos pos = new BlockPos(x, y, z);
        if (!w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) return null;
        if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) return null;
        return pos;
    }

    /** Al fondo de un pasillo recto de cueva, en línea con tu mirada. */
    private static BlockPos findCavePos(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        for (int attempt = 0; attempt < 16; attempt++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double cx = Math.cos(ang), cz = Math.sin(ang);
            BlockPos last = null;
            int lastDist = 0;
            for (int d = 2; d <= max; d++) {
                BlockPos feet = BlockPos.ofFloored(p.getX() + cx * d, p.getY(), p.getZ() + cz * d);
                if (!w.getBlockState(feet).isAir() || !w.getBlockState(feet.up()).isAir()
                        || !w.getBlockState(feet.down()).isSolidBlock(w, feet.down())) break;
                last = feet;
                lastDist = d;
            }
            if (last != null && lastDist >= min) return last;
        }
        return null;
    }

    /** De reojo: aparece fuera del centro de tu visión y desaparece en cuanto lo miras. */
    public static boolean glimpse(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, int day) {
        if (activeList(p.getUuid()).size() >= Math.max(1, echoCap(d, false))) return false;
        Vec3d look = p.getRotationVec(1.0f);
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 10; i++) {
            double off = Math.toRadians(55 + RNG.nextDouble() * 35) * (RNG.nextBoolean() ? 1 : -1);
            double dist = 7 + RNG.nextDouble() * 9;
            BlockPos pos = surfaceAt(w, p, (int) Math.floor(p.getX() + Math.cos(base + off) * dist),
                    (int) Math.floor(p.getZ() + Math.sin(base + off) * dist), 5);
            if (pos != null) {
                return spawn(w, p, pos, EchoEntity.Mode.GLIMPSE, pickVariant(day, d.dread)) != null;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ El Registrador

    private static void registrarDirector(MinecraftServer server, ServerWorld w, ServerPlayerEntity p,
                                          EchoState.PlayerData d, int day, int stage) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (day < cfg.registrarDay) return;
        UUID id = p.getUuid();
        EchoEntity cur = REGISTRAR.get(id);
        if (cur != null) {
            if (cur.isAlive() && !cur.isRemoved()) return;
            REGISTRAR.remove(id);
        }
        int now = server.getTicks();
        Integer next = NEXT_REGISTRAR.get(id);
        if (next != null && now < next) return;
        double chance = Math.min(0.35, 0.06 + day * 0.04 + d.paranoia * 0.001) * cfg.eventIntensity;
        if (stage >= 2) chance *= 2;
        if (RNG.nextDouble() > chance) return;

        boolean cave = !w.isSkyVisible(p.getBlockPos()) && p.getY() < 55;
        BlockPos s = cave ? findCavePos(w, p, 18, 34) : findPeripheralFar(w, p, 28, 46);
        if (s == null) return;
        EchoEntity e = spawn(w, p, s, EchoEntity.Mode.REGISTRAR, EchoEntity.V_PALE);
        if (e != null) {
            REGISTRAR.put(id, e);
            NEXT_REGISTRAR.put(id, now + 20 * (60 + RNG.nextInt(60)));
        }
    }

    /** En el límite de tu campo de visión: lejos, ligeramente a un lado de donde miras (montañas, detrás de un árbol...). */
    private static BlockPos findPeripheralFar(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        Vec3d look = p.getRotationVec(1.0f);
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 18; i++) {
            double off = Math.toRadians(40 + RNG.nextDouble() * 45) * (RNG.nextBoolean() ? 1 : -1);
            double dist = min + RNG.nextDouble() * (max - min);
            BlockPos pos = surfaceAt(w, p, (int) Math.floor(p.getX() + Math.cos(base + off) * dist),
                    (int) Math.floor(p.getZ() + Math.sin(base + off) * dist), 25);
            if (pos != null) return pos;
        }
        return null;
    }

    public static void registrarGone(ServerPlayerEntity p) {
        REGISTRAR.remove(p.getUuid());
        NEXT_REGISTRAR.put(p.getUuid(), p.getServer().getTicks() + 20 * (45 + RNG.nextInt(60)));
        EchoState st = EchoState.get(p.getServer());
        EchoState.PlayerData d = st.data(p.getUuid());
        d.paranoia = Math.min(100, d.paranoia + 1.0);
        st.markDirty();
    }

    // ------------------------------------------------------------------ sustos de los Ecos

    /** Un Eco que corre/vuela hacia ti y desaparece justo antes de tocarte. */
    public static void rushEnd(ServerPlayerEntity p, EchoEntity e) {
        Paranoia.playTo(p, SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, e.getPos(), 1.0f, 0.55f);
        scare(p, 1);
        addDread(p, 2.5);
        e.vanish();
    }

    /** Un Eco que grita para asustarte. */
    public static void screamAt(ServerPlayerEntity p, EchoEntity e) {
        SoundEvent[] pool = {SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundEvents.ENTITY_GHAST_SCREAM, SoundEvents.ENTITY_WARDEN_ANGRY};
        SoundEvent s = pool[RNG.nextInt(pool.length)];
        Paranoia.playTo(p, s, SoundCategory.HOSTILE, e.getPos(), 1.4f, 0.5f + RNG.nextFloat() * 0.3f);
        scare(p, 1);
        addDread(p, 3.0);
    }

    // ------------------------------------------------------------------ imitación (talar, dormir)

    public static boolean debugSpawn(ServerPlayerEntity p, String kind) {
        ServerWorld w = p.getServerWorld();
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        int day = Math.max(effectiveDay(server, st, d), 8);
        dismissAll(p.getUuid());
        EchoEntity.Mode mode;
        int variant = EchoEntity.V_NORMAL;
        switch (kind) {
            case "registrador" -> {
                BlockPos s = findPeripheralFar(w, p, 22, 34);
                if (s == null) return false;
                EchoEntity e = spawn(w, p, s, EchoEntity.Mode.REGISTRAR, EchoEntity.V_PALE);
                if (e != null) REGISTRAR.put(p.getUuid(), e);
                return e != null;
            }
            case "sombra" -> { mode = EchoEntity.Mode.WATCH; variant = EchoEntity.V_SHADOW; }
            case "corre" -> mode = EchoEntity.Mode.RUSH;
            case "grita" -> mode = EchoEntity.Mode.SCREAM;
            case "vuela" -> mode = EchoEntity.Mode.FLY_WATCH;
            case "lanza" -> mode = EchoEntity.Mode.FLY_SWOOP;
            case "camina" -> mode = EchoEntity.Mode.WALK;
            case "reojo" -> { return glimpse(p, w, d, day); }
            default -> mode = EchoEntity.Mode.WATCH;
        }
        BlockPos s;
        if (mode == EchoEntity.Mode.FLY_WATCH || mode == EchoEntity.Mode.FLY_SWOOP) {
            s = findSurfaceSpot(w, p, 14, 22, 60);
            if (s == null) return false;
            int h = 9;
            while (h > 3 && !columnFree(w, s, h)) h--;
            s = s.up(h);
        } else {
            s = findSurfaceSpot(w, p, 14, 22, 60);
        }
        return s != null && spawn(w, p, s, mode, variant) != null;
    }

    private static void onBlockBroken(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity be) {
        if (!(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity sp)) return;
        record(sp, state.getSoundGroup().getBreakSound());
        EchoEntity reg = REGISTRAR.get(sp.getUuid());
        if (reg != null && reg.isAlive()) reg.swingHand(Hand.MAIN_HAND);
        boolean log = state.isIn(BlockTags.LOGS);
        boolean crop = state.getBlock() instanceof CropBlock cb && cb.isMature(state);
        if (!log && !crop) return;

        MinecraftServer server = w.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(sp.getUuid());
        EchoConfig cfg = EchoConfig.INSTANCE;
        // Cortar leña y cosechar son cosas tranquilas: bajan un poquito el miedo y la paranoia
        if (log) calm(d, cfg.chopDreadRelief, cfg.chopParanoiaRelief);
        else calm(d, cfg.farmDreadRelief, cfg.farmParanoiaRelief);
        st.markDirty();
        if (!log) return;
        int day = effectiveDay(server, st, d);
        if (day < EchoConfig.INSTANCE.mimicChopDay) return;
        if (!isActive(server, w, sp.getBlockPos(), d) || !activeList(sp.getUuid()).isEmpty()) return;
        if (RNG.nextDouble() > Math.min(0.8, 0.3 + d.dread * 0.004)) return;

        ChopSpot spot = findChopSpot(w, sp, 22, 38);
        if (spot == null) return;
        EchoEntity e = spawn(w, sp, spot.stand(), EchoEntity.Mode.CHOP, pickVariant(day, d.dread) == EchoEntity.V_SHADOW ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL);
        if (e != null) e.setChopTarget(spot.log());
    }

    private static ChopSpot findChopSpot(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        for (int i = 0; i < 40; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = min + RNG.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
            BlockPos.Mutable m = new BlockPos.Mutable(x, top - 1, z);
            if (!w.getBlockState(m).isIn(BlockTags.LOGS)) continue;
            while (w.getBlockState(m.down()).isIn(BlockTags.LOGS)) m.move(Direction.DOWN);
            BlockPos log = m.toImmutable();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos s = log.offset(dir);
                if (w.getBlockState(s).isAir() && w.getBlockState(s.up()).isAir()
                        && w.getBlockState(s.down()).isSolidBlock(w, s.down())) {
                    return new ChopSpot(log, s);
                }
            }
        }
        return null;
    }

    private static void onWake(LivingEntity entity, BlockPos sleepingPos) {
        if (!(entity instanceof ServerPlayerEntity p)) return;
        ServerWorld w = p.getServerWorld();
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return;
        if (w.getTimeOfDay() % 24000L > 2000L) return; // no es una mañana real

        MinecraftServer server = w.getServer();
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        int day = effectiveDay(server, st, d);

        // Dormir calma la paranoia por insomnio
        d.paranoia *= 0.4;
        d.dread *= 0.85;
        st.markDirty();

        if (!isActive(server, w, sleepingPos, d)) return;
        // Cada vez que duermes hay una probabilidad de despertar en el laberinto: 20 % el día 1, 40 % el 2... 100 % el 5
        if (cfg.enableRealm && !d.inRealm && RNG.nextDouble() < hijackChance(day)) {
            PENDING_HIJACK.put(p.getUuid(), sleepingPos.toImmutable());
            return;
        }
        if (day >= cfg.mimicChopDay && RNG.nextDouble() < 0.7) leaveTraces(w, sleepingPos);
    }

    public static double hijackChance(int day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        return Math.max(0.0, Math.min(1.0, cfg.hijackChanceBase + day * cfg.hijackChancePerDay));
    }

    private static void leaveTraces(ServerWorld w, BlockPos bed) {
        double ang = RNG.nextDouble() * Math.PI * 2;
        double cos = Math.cos(ang), sin = Math.sin(ang);
        double px = -sin, pz = cos;
        for (int i = 0; i < 10; i++) {
            double dist = 12 - i * 1.1;
            double side = (i % 2 == 0) ? 0.45 : -0.45;
            int x = (int) Math.floor(bed.getX() + 0.5 + cos * dist + px * side);
            int z = (int) Math.floor(bed.getZ() + 0.5 + sin * dist + pz * side);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos g = new BlockPos(x, y, z);
            BlockState gs = w.getBlockState(g);
            if ((gs.isOf(Blocks.GRASS_BLOCK) || gs.isOf(Blocks.DIRT)) && w.getBlockState(g.up()).isAir()) {
                w.setBlockState(g, Blocks.COARSE_DIRT.getDefaultState());
            }
        }
        for (BlockPos pos : BlockPos.iterate(bed.add(-8, -2, -8), bed.add(8, 4, 8))) {
            BlockState s = w.getBlockState(pos);
            if (s.isIn(BlockTags.DOORS) && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && RNG.nextInt(3) == 0) {
                ((DoorBlock) s.getBlock()).setOpen(null, w, s, pos.toImmutable(), !s.get(DoorBlock.OPEN));
            }
        }
    }

    // ------------------------------------------------------------------ aldeanos y animales que miran

    private static void tickStares(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, boolean enabled, int t) {
        List<MobEntity> staring = STARERS.get(p.getUuid());
        if (staring != null && !staring.isEmpty()) {
            for (Iterator<MobEntity> it = staring.iterator(); it.hasNext(); ) {
                MobEntity m = it.next();
                if (m.isRemoved() || !m.isAlive()) { it.remove(); continue; }
                if (!enabled || m.getWorld() != w || m.squaredDistanceTo(p) > 52 * 52) {
                    release(m);
                    it.remove();
                } else if (t % 2 == 0) {
                    faceTo(m, p);
                }
            }
        }
        if (!enabled || p.age % 40 != 0) return;
        if (staring == null) staring = STARERS.computeIfAbsent(p.getUuid(), k -> new ArrayList<>());
        if (staring.size() >= 6) return;
        if (RNG.nextDouble() > Math.min(0.5, 0.08 + d.dread * 0.003)) return;
        List<MobEntity> cands = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24),
                m -> (m instanceof VillagerEntity || m instanceof AnimalEntity) && m.isAlive()
                        && !m.getCommandTags().contains(STARE_TAG) && !m.isLeashed());
        if (cands.isEmpty()) return;
        MobEntity pick = cands.get(RNG.nextInt(cands.size()));
        pick.addCommandTag(STARE_TAG);
        pick.getNavigation().stop();
        pick.setAiDisabled(true);
        FROZEN.add(pick);
        staring.add(pick);
    }

    private static void faceTo(MobEntity m, ServerPlayerEntity p) {
        double dx = p.getX() - m.getX();
        double dy = p.getEyeY() - m.getEyeY();
        double dz = p.getZ() - m.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        m.setYaw(yaw);
        m.setPitch(pitch);
        m.setHeadYaw(yaw);
        m.setBodyYaw(yaw);
    }

    private static void release(MobEntity m) {
        m.removeCommandTag(STARE_TAG);
        m.setAiDisabled(false);
        FROZEN.remove(m);
    }

    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity, @Nullable EntityHitResult hit) {
        if (world.isClient || !(entity instanceof MobEntity m) || !m.getCommandTags().contains(STARE_TAG)) {
            return ActionResult.PASS;
        }
        if (player instanceof ServerPlayerEntity sp && world instanceof ServerWorld w) {
            replayRecording(sp, w, m);
            EchoState st = EchoState.get(w.getServer());
            st.data(sp.getUuid()).dread += 1.0;
            st.markDirty();
        }
        return ActionResult.SUCCESS; // cancela el comercio
    }

    // ------------------------------------------------------------------ grabación de tus propias acciones

    private static void replayRecording(ServerPlayerEntity p, ServerWorld w, MobEntity m) {
        SoundEvent s = recordedSound(p);
        double x = m.getX(), y = m.getY() + 1.4, z = m.getZ();
        MinecraftServer server = w.getServer();
        w.playSound(null, x, y, z, s, SoundCategory.NEUTRAL, 1.0f, 0.55f);
        Sched.at(server, 4, () -> w.playSound(null, x, y, z, s, SoundCategory.NEUTRAL, 0.9f, 0.5f));
        Sched.at(server, 9, () -> w.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.NEUTRAL, 0.7f, 0.5f));
    }

    private static void record(ServerPlayerEntity p, SoundEvent sound) {
        ArrayDeque<Rec> q = RECORDINGS.computeIfAbsent(p.getUuid(), k -> new ArrayDeque<>());
        q.addLast(new Rec(p.getServer().getOverworld().getTime(), sound));
        while (q.size() > 16) q.removeFirst();
    }

    /** Un sonido real que hiciste hace poco (o tus pasos si no hay nada). */
    public static SoundEvent recordedSound(ServerPlayerEntity p) {
        ArrayDeque<Rec> q = RECORDINGS.get(p.getUuid());
        long now = p.getServer().getOverworld().getTime();
        List<Rec> recent = new ArrayList<>();
        if (q != null) for (Rec r : q) if (now - r.time() < 6000) recent.add(r);
        if (!recent.isEmpty()) return recent.get(RNG.nextInt(recent.size())).sound();
        return p.getServerWorld().getBlockState(p.getBlockPos().down()).getSoundGroup().getStepSound();
    }

    // ------------------------------------------------------------------ interfaz: carteles y antorchas

    private static void startFlicker(ServerPlayerEntity p, ServerWorld w, int t) {
        List<BlockPos> torches = new ArrayList<>();
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-8, -4, -8), c.add(8, 4, 8))) {
            BlockState s = w.getBlockState(pos);
            if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH)) {
                torches.add(pos.toImmutable());
                if (torches.size() >= 10) break;
            }
        }
        if (!torches.isEmpty()) FLICKERS.put(p.getUuid(), new Flicker(t + 60 + RNG.nextInt(60), torches));
    }

    private static void mutateNearbySign(ServerPlayerEntity p, ServerWorld w) {
        int cx = p.getChunkPos().x, cz = p.getChunkPos().z;
        List<SignBlockEntity> signs = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (!w.isChunkLoaded(cx + dx, cz + dz)) continue;
                for (BlockEntity be : w.getChunk(cx + dx, cz + dz).getBlockEntities().values()) {
                    if (be instanceof SignBlockEntity s) signs.add(s);
                }
            }
        }
        if (signs.isEmpty()) return;
        SignBlockEntity sign = signs.get(RNG.nextInt(signs.size()));
        sign.changeText(text -> {
            List<Integer> lines = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (!text.getMessage(i, false).getString().trim().isEmpty()) lines.add(i);
            }
            if (lines.isEmpty()) return text;
            int line = lines.get(RNG.nextInt(lines.size()));
            Text original = text.getMessage(line, false);
            String str = original.getString();
            List<Integer> letters = new ArrayList<>();
            for (int i = 0; i < str.length(); i++) if (Character.isLetter(str.charAt(i))) letters.add(i);
            if (letters.isEmpty()) return text;
            int idx = letters.get(RNG.nextInt(letters.size()));
            String mutated = str.substring(0, idx) + mutate(str.charAt(idx)) + str.substring(idx + 1);
            return text.withMessage(line, Text.literal(mutated).setStyle(original.getStyle()));
        }, true);
    }

    private static char mutate(char c) {
        String vowels = "aeiou";
        char lc = Character.toLowerCase(c);
        char n;
        if (vowels.indexOf(lc) >= 0) {
            do { n = vowels.charAt(RNG.nextInt(5)); } while (n == lc);
        } else {
            n = lc == 'z' ? 'a' : (char) (lc + 1);
        }
        return Character.isUpperCase(c) ? Character.toUpperCase(n) : n;
    }

    // ------------------------------------------------------------------ espejo y ofrendas

    public static void registerMirror(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld w)) return;
        EchoState st = EchoState.get(w.getServer());
        st.mirrors.add(new EchoState.MirrorPos(w.getRegistryKey().getValue().toString(), pos.toImmutable()));
        st.markDirty();
    }

    /** ¿Hay un espejo que proteja esta posición? (de los Ecos, de los eventos y de la Decadencia) */
    public static boolean isProtected(ServerWorld w, BlockPos pos) {
        EchoState st = EchoState.get(w.getServer());
        if (st.mirrors.isEmpty()) return false;
        String dim = w.getRegistryKey().getValue().toString();
        long r = EchoConfig.INSTANCE.mirrorRadius;
        boolean dirty = false;
        boolean result = false;
        Iterator<EchoState.MirrorPos> it = st.mirrors.iterator();
        while (it.hasNext()) {
            EchoState.MirrorPos m = it.next();
            if (!dim.equals(m.dim())) continue;
            if (pos.getSquaredDistance(m.pos()) > r * r) continue;
            if (w.isChunkLoaded(m.pos().getX() >> 4, m.pos().getZ() >> 4)
                    && !w.getBlockState(m.pos()).isOf(ModBlocks.ECHO_MIRROR)) {
                it.remove();
                dirty = true;
                continue;
            }
            result = true;
            break;
        }
        if (dirty) st.markDirty();
        return result;
    }

    /** Dar algo al espejo baja el miedo, la paranoia Y el contador de días. */
    public static void offer(ServerPlayerEntity p, ItemStack stack, BlockPos mirrorPos) {
        ServerWorld w = p.getServerWorld();
        MinecraftServer server = w.getServer();
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        String id = Registries.ITEM.getId(stack.getItem()).toString();

        if (!d.day1Items.contains(id)) {
            p.sendMessage(Text.literal("El espejo no reconoce esto. No viene de tu primer día.").formatted(Formatting.GRAY), true);
            return;
        }
        if (d.offered.contains(id)) {
            p.sendMessage(Text.literal("Ya le diste esto. Quiere algo distinto.").formatted(Formatting.GRAY), true);
            return;
        }
        double value = offeringValue(stack); // cuánto calma este objeto (nunca todo de golpe)
        stack.decrementUnlessCreative(1, p);
        d.offered.add(id);
        long now = server.getOverworld().getTime();
        long base = Math.max(d.banishedUntil, now) + (long) (cfg.offeringBanishDays * 24000L * (value / 30.0));
        d.banishedUntil = Math.min(base, now + cfg.maxBanishDays * 24000L);
        d.dread = Math.max(0, d.dread - Math.min(cfg.offeringDreadReduction, value));
        d.paranoia = Math.max(0, d.paranoia - Math.min(cfg.offeringParanoiaReduction, value * 0.9));
        if (value >= 18) d.relief = Math.min(d.relief + cfg.offeringDayRelief, globalDay(server, st));
        st.markDirty();
        dismissAll(p.getUuid());
        int day = effectiveDay(server, st, d);
        LAST_HUD.remove(p.getUuid());
        send(p, new DreadPayload(1, (int) Math.min(100, d.dread), (int) Math.min(100, d.paranoia), day + 1, stageOf(server, d, day), 0));

        w.spawnParticles(ParticleTypes.SCULK_SOUL, mirrorPos.getX() + 0.5, mirrorPos.getY() + 0.8, mirrorPos.getZ() + 0.5,
                30, 0.3, 0.5, 0.3, 0.1);
        w.playSound(null, mirrorPos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 0.7f);
        String effect = value < 8 ? "El reflejo se estremece apenas." : value < 16 ? "El reflejo retrocede un poco."
                : "El reflejo retrocede bastante... pero no se va.";
        p.sendMessage(Text.literal(effect).formatted(Formatting.AQUA, Formatting.ITALIC), true);
    }

    /** Cuánto calma un objeto: lo común poco, lo valioso bastante; el máximo es 30 (el miedo llega a 100). */
    public static double offeringValue(ItemStack st) {
        String path = Registries.ITEM.getId(st.getItem()).getPath();
        if (path.contains("netherite")) return 30;
        if (path.contains("diamond")) return 26;
        if (path.equals("golden_apple") || path.equals("enchanted_golden_apple")) return 22;
        if (path.contains("emerald")) return 20;
        if (path.contains("gold")) return 16;
        if (path.contains("iron")) return 12;
        if (path.equals("book") || path.contains("bed") || path.equals("map") || path.equals("compass")) return 10;
        if (st.contains(DataComponentTypes.FOOD)) return 9;
        if (path.contains("torch") || path.contains("lantern")) return 7;
        if (path.contains("log") || path.contains("planks")) return 5;
        if (path.contains("stone") || path.equals("dirt")) return 4;
        if (path.equals("stick")) return 3;
        return 6;
    }

    /** Mirar el espejo con la mano vacía: baja un poco el miedo y la paranoia (con enfriamiento). */
    public static Text mirrorMessage(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        long now = server.getOverworld().getTime();
        Long last = LAST_GAZE.get(p.getUuid());
        boolean ready = last == null || now - last >= cfg.mirrorGazeCooldownSeconds * 20L;
        String s;
        if (ready && (d.dread > 0 || d.paranoia > 0)) {
            LAST_GAZE.put(p.getUuid(), now);
            calm(d, cfg.mirrorGazeRelief, cfg.mirrorGazeRelief);
            st.markDirty();
            LAST_HUD.remove(p.getUuid());
            s = "Tu reflejo te sostiene la mirada. Respiras un poco mejor.";
        } else if (d.dread < 30) s = "Tu reflejo te devuelve la mirada... un instante tarde.";
        else if (d.dread < 70) s = "Tu reflejo sonríe un momento antes que tú.";
        else s = "No eres tú quien mira. Ofrécele algo de tu primer día.";
        return Text.literal(s).formatted(Formatting.GRAY, Formatting.ITALIC);
    }

    // ------------------------------------------------------------------ clímax: el laberinto del reino espejo

    private static void hijack(MinecraftServer server, EchoState st, ServerPlayerEntity p, BlockPos sleepPos) {
        ServerWorld realm = server.getWorld(REALM);
        if (realm == null) {
            TheEcho.LOGGER.warn("La dimensión the_echo:mirror_realm no está cargada; se cancela el secuestro.");
            p.sendMessage(Text.literal("[El Eco] La dimensión the_echo:mirror_realm no está cargada. Prueba en un mundo nuevo.")
                    .formatted(Formatting.RED), false);
            return;
        }
        ServerWorld src = p.getServerWorld();
        EchoState.PlayerData d = st.data(p.getUuid());
        if (d.slot < 0) d.slot = st.nextSlot++;
        BlockPos dstOrigin = new BlockPos(d.slot * 160, 20, 0);

        d.returnPos = sleepPos;
        d.returnDim = src.getRegistryKey().getValue().toString();
        RealmBuilder.Result res = RealmBuilder.build(realm, dstOrigin, RNG);
        d.realmBase = res.start();
        d.realmOriginal = res.original();
        TRAPS.put(p.getUuid(), new ArrayList<>(res.traps()));
        REALM_CELLS.put(p.getUuid(), res.cells());
        d.inRealm = true;
        d.offered.clear();
        st.markDirty();

        REALM_TICKS.put(p.getUuid(), 0);
        dismissAll(p.getUuid());
        BlockPos st0 = res.start();
        p.teleport(realm, st0.getX() + 0.5, st0.getY(), st0.getZ() + 0.5, p.getYaw(), p.getPitch());
        scare(p, 1);
        for (int i = 0; i < 3; i++) {
            BlockPos c = pickCell(res.cells(), st0, 14);
            if (c != null) spawn(realm, p, c, EchoEntity.Mode.STALK, i == 0 ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL);
        }
        p.sendMessage(Text.literal("Despiertas.").formatted(Formatting.WHITE, Formatting.ITALIC), true);
    }

    public static void forceHijack(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        hijack(server, EchoState.get(server), p, p.getBlockPos());
    }

    private static BlockPos pickCell(List<BlockPos> cells, BlockPos from, int minDist) {
        if (cells == null || cells.isEmpty()) return null;
        for (int i = 0; i < 30; i++) {
            BlockPos c = cells.get(RNG.nextInt(cells.size()));
            if (c.getSquaredDistance(from) >= (double) minDist * minDist) return c;
        }
        return null;
    }

    private static void tickRealm(MinecraftServer server, EchoState st, ServerPlayerEntity p, ServerWorld w,
                                  EchoState.PlayerData d, int day) {
        int rt = REALM_TICKS.merge(p.getUuid(), 1, Integer::sum);
        if (rt % 20 == 0) send(p, new DreadPayload(1, 100, 100, day + 1, 2, 0));
        // Antes la oscuridad era constante y no se veía nada; ahora solo golpea de vez en cuando (hay antorchas)
        if (rt % 700 == 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 70, 0, false, false, false));
        }
        if (d.realmBase != null && p.getY() < d.realmBase.getY() - 6) {
            p.teleport(w, d.realmBase.getX() + 0.5, d.realmBase.getY(), d.realmBase.getZ() + 0.5, p.getYaw(), p.getPitch());
        }
        // Trampas de susto en los callejones
        if (rt % 5 == 0) {
            List<BlockPos> traps = TRAPS.get(p.getUuid());
            if (traps != null) {
                for (Iterator<BlockPos> it = traps.iterator(); it.hasNext(); ) {
                    BlockPos tp = it.next();
                    double dx = p.getX() - (tp.getX() + 0.5), dz = p.getZ() - (tp.getZ() + 0.5);
                    if (dx * dx + dz * dz < 2.5 * 2.5 && Math.abs(p.getY() - tp.getY()) < 3) {
                        it.remove();
                        jumpscare(p, w);
                        break;
                    }
                }
            }
        }
        // Más Ecos con el tiempo (hasta 5)
        if (rt % 240 == 0) {
            List<BlockPos> cells = REALM_CELLS.get(p.getUuid());
            int want = Math.min(5, 3 + rt / 2400);
            int have = w.getEntitiesByClass(EchoEntity.class, p.getBoundingBox().expand(150),
                    e -> p.getUuid().equals(e.getOwnerUuid())).size();
            for (int i = have; i < want; i++) {
                BlockPos c = pickCell(cells, p.getBlockPos(), 14);
                if (c != null) spawn(w, p, c, EchoEntity.Mode.STALK, RNG.nextInt(3) == 0 ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL);
            }
        }
        if (rt % 160 == 0 && RNG.nextInt(2) == 0) realmAmbient(server, p, w);
        // Después de 2 minutos, el Original empieza a llamarte
        if (rt > 2400 && rt % 80 == 0 && d.realmOriginal != null) {
            BlockPos o = d.realmOriginal;
            w.spawnParticles(p, ParticleTypes.SOUL, true, o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 0.5,
                    10, 0.15, 1.5, 0.15, 0.01);
        }
    }

    private static void realmAmbient(MinecraftServer server, ServerPlayerEntity p, ServerWorld w) {
        Vec3d back = Paranoia.back(p);
        Vec3d base = p.getPos();
        SoundEvent[] pool = {
                SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, SoundEvents.ENTITY_WARDEN_AMBIENT, SoundEvents.ENTITY_WARDEN_HEARTBEAT,
                SoundEvents.ENTITY_ENDERMAN_STARE, SoundEvents.ENTITY_GHAST_WARN, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE
        };
        if (RNG.nextInt(3) == 0) {
            SoundEvent step = SoundEvents.BLOCK_DEEPSLATE_STEP;
            for (int i = 0; i < 5; i++) {
                final int k = i;
                Sched.at(server, i * 8, () -> Paranoia.playTo(p, step, SoundCategory.NEUTRAL,
                        base.add(back.multiply(5.0 - k * 0.8)), 0.9f, 0.8f));
            }
        } else {
            Paranoia.playTo(p, pool[RNG.nextInt(pool.length)], SoundCategory.AMBIENT,
                    Paranoia.ring(base, 6 + RNG.nextInt(8)), 0.8f, 0.5f + RNG.nextFloat() * 0.3f);
        }
    }

    /** Susto: pantallazo con cara + grito + un Eco aparece justo frente a ti un instante. */
    private static void jumpscare(ServerPlayerEntity p, ServerWorld w) {
        scare(p, 2);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 1.0f, 0.9f);
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d h = new Vec3d(look.x, 0, look.z);
        h = h.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : h.normalize();
        double[] dists = {2.6, 2.0, 1.5};
        for (double dist : dists) {
            BlockPos spot = BlockPos.ofFloored(p.getX() + h.x * dist, p.getY(), p.getZ() + h.z * dist);
            if (w.getBlockState(spot).isAir() && w.getBlockState(spot.up()).isAir()
                    && w.getBlockState(spot.down()).isSolidBlock(w, spot.down())) {
                spawn(w, p, spot, EchoEntity.Mode.JUMP, RNG.nextBoolean() ? EchoEntity.V_SHADOW : EchoEntity.V_NORMAL);
                break;
            }
        }
    }

    /** Un Eco te alcanzó en el laberinto: susto y vuelves al inicio. */
    public static void caught(ServerPlayerEntity p, EchoEntity e) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        ServerWorld w = p.getServerWorld();
        e.vanish();
        scare(p, 2);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.5f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.7f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 0, false, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false, false));
        if (w.getRegistryKey().equals(REALM)) {
            BlockPos b = d.realmBase;
            if (b != null) p.teleport(w, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, p.getYaw(), p.getPitch());
        } else if (p.getHealth() > 7.0f) { // en el apocalipsis te alcanzan de verdad
            p.damage(w.getDamageSources().generic(), 6.0f);
        }
    }

    public static void leaveRealm(ServerPlayerEntity p, boolean found) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());

        ServerWorld dest = server.getOverworld();
        if (d.returnDim != null) {
            Identifier id = Identifier.tryParse(d.returnDim);
            if (id != null) {
                ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
                if (w != null) dest = w;
            }
        }
        BlockPos r = d.returnPos != null ? d.returnPos : dest.getSpawnPos();
        p.removeStatusEffect(StatusEffects.DARKNESS);
        p.teleport(dest, r.getX() + 0.5, r.getY() + 0.6, r.getZ() + 0.5, p.getYaw(), p.getPitch());

        d.inRealm = false;
        REALM_TICKS.remove(p.getUuid());
        TRAPS.remove(p.getUuid());
        REALM_CELLS.remove(p.getUuid());
        if (found) {
            d.dread = Math.min(d.dread, 25);
            d.paranoia = Math.min(d.paranoia, 25);
            d.banishedUntil = server.getOverworld().getTime() + 2 * 24000L;
        }
        st.markDirty();
        p.sendMessage(Text.literal(found ? "Encontraste el original." : "Estás de vuelta.")
                .formatted(Formatting.GRAY, Formatting.ITALIC), true);
    }

    // ------------------------------------------------------------------ utilidades para comandos

    public static String status(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        ServerWorld ow = server.getOverworld();
        int day = effectiveDay(server, st, d);
        long calm = Math.max(0, d.banishedUntil - ow.getTime());
        return String.format("Día del Eco %d (mundo: %d, alivio: %d) | miedo %.1f | paranoia %.1f | calma %.1f días | " +
                        "laberinto: %s | bloques por deshacer: %d | nota: %s | libre: %s | etapa: %d | protegido: %s | prob. laberinto al dormir: %.0f%%",
                day + 1, globalDay(server, st), d.relief, d.dread, d.paranoia, calm / 24000.0, d.inRealm,
                TerrainMemory.pending(server), d.riteStarted, d.freed, stageOf(server, d, day),
                isProtected(p.getServerWorld(), p.getBlockPos()), hijackChance(day) * 100.0);
    }

    public static void setDread(ServerPlayerEntity p, double value) {
        EchoState st = EchoState.get(p.getServer());
        st.data(p.getUuid()).dread = value;
        st.markDirty();
    }

    public static void setParanoia(ServerPlayerEntity p, double value) {
        EchoState st = EchoState.get(p.getServer());
        st.data(p.getUuid()).paranoia = value;
        st.markDirty();
    }

    /** Fija el día del Eco (1 = primer día). */
    public static void setDay(ServerPlayerEntity p, int echoDay) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        st.data(p.getUuid()).relief = globalDay(server, st) - Math.max(0, echoDay - 1);
        st.markDirty();
    }

    public static void forceChat(ServerPlayerEntity p, int tier) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        ChatHaunt.force(server, p, tier, effectiveDay(server, st, st.data(p.getUuid())));
    }

    /** /echo ruina [1-7]: sin número, coloca la siguiente de la cadena cerca de ti; con número, ese tipo suelto. */
    public static boolean forceShrine(ServerPlayerEntity p, Integer which) {
        ServerWorld w = p.getServerWorld();
        EchoState st = EchoState.get(p.getServer());
        boolean chain = which == null && st.sites.size() < Shrines.NOTE_COUNT;
        int idx = which != null ? which - 1 : chain ? st.sites.size() : RNG.nextInt(Shrines.NOTE_COUNT);
        Shrines.Kind k = Shrines.kindFor(idx);
        for (int i = 0; i < 60; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = 14 + RNG.nextDouble() * 18;
            BlockPos c = Shrines.tryPlace(w, (int) (p.getX() + Math.cos(ang) * dist) - k.w / 2,
                    (int) (p.getZ() + Math.sin(ang) * dist) - k.d / 2, idx);
            if (c != null) {
                if (chain) {
                    st.sites.add(c);
                    st.siteHeading = ang;
                    st.markDirty();
                }
                p.sendMessage(Text.literal("Estructura " + (idx + 1) + " (" + k.label + ") en " + c.getX() + " " + c.getY() + " " + c.getZ()
                        + (chain ? " [añadida a la cadena]" : "")), false);
                return true;
            }
        }
        return false;
    }

    public static void listSites(ServerPlayerEntity p) {
        EchoState st = EchoState.get(p.getServer());
        if (st.sites.isEmpty()) p.sendMessage(Text.literal("Aún no hay estructuras."), false);
        for (int i = 0; i < st.sites.size(); i++) {
            BlockPos c = st.sites.get(i);
            p.sendMessage(Text.literal((i + 1) + ". " + Shrines.kindFor(i).label + ": " + c.getX() + " " + c.getY() + " " + c.getZ()), false);
        }
    }

    public static void forceDecay(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        TerrainMemory.tick(server, p.getServerWorld(), p, Math.max(10, effectiveDay(server, st, st.data(p.getUuid()))));
    }

    public static void free(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        completeRite(server, st, p, st.data(p.getUuid()));
    }

    public static void reset(ServerPlayerEntity p) {
        EchoState.get(p.getServer()).reset(p.getUuid());
        dismissAll(p.getUuid());
    }
}

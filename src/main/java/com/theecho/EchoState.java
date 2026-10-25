package com.theecho;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;

import java.util.*;

/** Estado persistente del mod (se guarda con el mundo). */
public class EchoState extends PersistentState {

    public static final int MAX_MEMORY = 30000;

    public static final class PlayerData {
        public final Set<String> day1Items = new HashSet<>();
        public final Set<String> offered = new HashSet<>();
        public boolean snapshotLocked = false;
        public double dread = 0;
        public double paranoia = 0;
        public int relief = 0;              // días del Eco que las ofrendas han "devuelto"
        public long banishedUntil = 0;
        public boolean inRealm = false;
        public int slot = -1;
        public boolean riteStarted = false; // ha encontrado una nota abandonada
        public boolean freed = false;       // completó el Rito: la presencia se va para siempre
        public boolean doomed = false;      // el final: el mundo lo echa
        public int shrines = 0;             // estructuras del diario ya visitadas (0-5)
        public int revealed = 0;            // estructuras cuyas coordenadas ya te dio el chat
        public boolean apocTriggered = false; // cerraste el último libro: empieza el día 5 de verdad
        public boolean introShown = false;  // (antiguo) ya vio el título "Día 1 de ..."
        public int lastDayShown = 0;        // último día (en pantalla) cuyo título ya vio
        public long apocStart = -1;         // cuándo empezó el apocalipsis del día 5
        public BlockPos returnPos = null;
        public String returnDim = null;
        public BlockPos realmBase = null;
        public BlockPos realmOriginal = null;
    }

    public record MirrorPos(String dim, BlockPos pos) {}

    /** Cómo era un bloque antes de que lo tocaras. */
    public record Mem(BlockState original, long time) {}

    public final Map<UUID, PlayerData> players = new HashMap<>();
    public final List<MirrorPos> mirrors = new ArrayList<>();
    public int nextSlot = 0;
    /** Día del mundo en el que el mod vio este mundo por primera vez (-1 = sin fijar). */
    public long startDay = -1;
    /** Estructuras del diario de Elías, en orden: cada una te lleva a la siguiente (centro de cada una). */
    public final List<BlockPos> sites = new ArrayList<>();
    /** Hacia dónde (radianes) avanza la cadena de estructuras. */
    public double siteHeading = 0;

    /** Memoria del Terreno (solo Overworld): posición -> estado original. */
    public final Map<Long, Mem> memory = new HashMap<>();
    /** Índice por chunk para la decadencia al cargar. */
    public final Map<Long, Set<Long>> byChunk = new HashMap<>();

    private static final PersistentState.Type<EchoState> TYPE =
            new PersistentState.Type<>(EchoState::new, EchoState::fromNbt, null);

    private static EchoState cached;
    private static MinecraftServer cachedServer;

    /** Se consulta muchas veces por tick: se guarda la referencia mientras el servidor sea el mismo. */
    public static EchoState get(MinecraftServer server) {
        if (cached != null && cachedServer == server) return cached;
        cached = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "the_echo");
        cachedServer = server;
        return cached;
    }

    public static void clearCache() {
        cached = null;
        cachedServer = null;
    }

    /** ¿Hay bloques modificados por el jugador en este chunk? (para no construir encima de su base) */
    public boolean hasPlayerBlocksInChunk(int chunkX, int chunkZ) {
        Set<Long> s = byChunk.get(ChunkPos.toLong(chunkX, chunkZ));
        return s != null && !s.isEmpty();
    }

    public PlayerData data(UUID id) {
        return players.computeIfAbsent(id, k -> new PlayerData());
    }

    public void reset(UUID id) {
        players.remove(id);
        markDirty();
    }

    // ---- memoria del terreno ----

    public void putMemory(long pos, Mem m) {
        if (memory.size() >= MAX_MEMORY && !memory.containsKey(pos)) return;
        memory.put(pos, m);
        byChunk.computeIfAbsent(chunkKey(pos), k -> new HashSet<>()).add(pos);
        markDirty();
    }

    public void removeMemory(long pos) {
        if (memory.remove(pos) != null) {
            long ck = chunkKey(pos);
            Set<Long> s = byChunk.get(ck);
            if (s != null) {
                s.remove(pos);
                if (s.isEmpty()) byChunk.remove(ck);
            }
            markDirty();
        }
    }

    public static long chunkKey(long blockPos) {
        BlockPos p = BlockPos.fromLong(blockPos);
        return ChunkPos.toLong(p.getX() >> 4, p.getZ() >> 4);
    }

    // ---- NBT ----

    private static void putPos(NbtCompound c, String key, BlockPos pos) {
        if (pos != null) c.putLong(key, pos.asLong());
    }

    private static BlockPos getPos(NbtCompound c, String key) {
        return c.contains(key) ? BlockPos.fromLong(c.getLong(key)) : null;
    }

    private static NbtList strings(Collection<String> col) {
        NbtList l = new NbtList();
        for (String s : col) l.add(NbtString.of(s));
        return l;
    }

    private static void readStrings(NbtCompound c, String key, Set<String> out) {
        NbtList l = c.getList(key, NbtElement.STRING_TYPE);
        for (int i = 0; i < l.size(); i++) out.add(l.getString(i));
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putInt("nextSlot", nextSlot);
        nbt.putLong("startDay", startDay);
        NbtList pl = new NbtList();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            PlayerData d = e.getValue();
            NbtCompound c = new NbtCompound();
            c.putString("uuid", e.getKey().toString());
            c.putBoolean("locked", d.snapshotLocked);
            c.putDouble("dread", d.dread);
            c.putDouble("paranoia", d.paranoia);
            c.putInt("relief", d.relief);
            c.putLong("banished", d.banishedUntil);
            c.putBoolean("inRealm", d.inRealm);
            c.putInt("slot", d.slot);
            c.putBoolean("rite", d.riteStarted);
            c.putBoolean("freed", d.freed);
            c.putBoolean("doomed", d.doomed);
            c.putInt("shrines", d.shrines);
            c.putInt("revealed", d.revealed);
            c.putBoolean("apocTrig", d.apocTriggered);
            c.putBoolean("intro", d.introShown);
            c.putInt("lastDay", d.lastDayShown);
            c.putLong("apoc", d.apocStart);
            c.put("day1", strings(d.day1Items));
            c.put("offered", strings(d.offered));
            putPos(c, "ret", d.returnPos);
            putPos(c, "base", d.realmBase);
            putPos(c, "orig", d.realmOriginal);
            if (d.returnDim != null) c.putString("retDim", d.returnDim);
            pl.add(c);
        }
        nbt.put("players", pl);

        NbtList ml = new NbtList();
        for (MirrorPos m : mirrors) {
            NbtCompound c = new NbtCompound();
            c.putString("dim", m.dim());
            c.putLong("pos", m.pos().asLong());
            ml.add(c);
        }
        nbt.put("mirrors", ml);

        long[] siteArr = new long[sites.size()];
        for (int i = 0; i < siteArr.length; i++) siteArr[i] = sites.get(i).asLong();
        nbt.putLongArray("sites", siteArr);
        nbt.putDouble("siteHeading", siteHeading);

        // Memoria del terreno: paleta de estados + arrays paralelos
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        int n = memory.size();
        long[] pos = new long[n];
        long[] times = new long[n];
        int[] idx = new int[n];
        int i = 0;
        for (Map.Entry<Long, Mem> e : memory.entrySet()) {
            pos[i] = e.getKey();
            times[i] = e.getValue().time();
            final int next = palette.size();
            idx[i] = palette.computeIfAbsent(e.getValue().original(), k -> next);
            i++;
        }
        NbtList pal = new NbtList();
        for (BlockState bs : palette.keySet()) pal.add(NbtHelper.fromBlockState(bs));
        nbt.put("memPalette", pal);
        nbt.putLongArray("memPos", pos);
        nbt.putLongArray("memTime", times);
        nbt.putIntArray("memIdx", idx);
        return nbt;
    }

    public static EchoState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        EchoState s = new EchoState();
        s.nextSlot = nbt.getInt("nextSlot");
        s.startDay = nbt.contains("startDay") ? nbt.getLong("startDay") : -1;
        NbtList pl = nbt.getList("players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < pl.size(); i++) {
            NbtCompound c = pl.getCompound(i);
            PlayerData d = new PlayerData();
            d.snapshotLocked = c.getBoolean("locked");
            d.dread = c.getDouble("dread");
            d.paranoia = c.getDouble("paranoia");
            d.relief = c.getInt("relief");
            d.banishedUntil = c.getLong("banished");
            d.inRealm = c.getBoolean("inRealm");
            d.slot = c.contains("slot") ? c.getInt("slot") : -1;
            d.riteStarted = c.getBoolean("rite");
            d.freed = c.getBoolean("freed");
            d.doomed = c.getBoolean("doomed");
            d.shrines = c.getInt("shrines");
            d.revealed = c.getInt("revealed");
            d.apocTriggered = c.getBoolean("apocTrig");
            d.introShown = c.getBoolean("intro");
            d.lastDayShown = c.getInt("lastDay");
            d.apocStart = c.contains("apoc") ? c.getLong("apoc") : -1;
            readStrings(c, "day1", d.day1Items);
            readStrings(c, "offered", d.offered);
            d.returnPos = getPos(c, "ret");
            d.realmBase = getPos(c, "base");
            d.realmOriginal = getPos(c, "orig");
            d.returnDim = c.contains("retDim") ? c.getString("retDim") : null;
            s.players.put(UUID.fromString(c.getString("uuid")), d);
        }
        NbtList ml = nbt.getList("mirrors", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < ml.size(); i++) {
            NbtCompound c = ml.getCompound(i);
            s.mirrors.add(new MirrorPos(c.getString("dim"), BlockPos.fromLong(c.getLong("pos"))));
        }

        for (long l : nbt.getLongArray("sites")) s.sites.add(BlockPos.fromLong(l));
        s.siteHeading = nbt.getDouble("siteHeading");

        NbtList pal = nbt.getList("memPalette", NbtElement.COMPOUND_TYPE);
        List<BlockState> states = new ArrayList<>();
        var blocks = lookup.getWrapperOrThrow(RegistryKeys.BLOCK);
        for (int i = 0; i < pal.size(); i++) states.add(NbtHelper.toBlockState(blocks, pal.getCompound(i)));
        long[] pos = nbt.getLongArray("memPos");
        long[] times = nbt.getLongArray("memTime");
        int[] idx = nbt.getIntArray("memIdx");
        int n = Math.min(pos.length, Math.min(times.length, idx.length));
        for (int i = 0; i < n; i++) {
            if (idx[i] < 0 || idx[i] >= states.size()) continue;
            s.putMemory(pos[i], new Mem(states.get(idx[i]), times[i]));
        }
        s.setDirty(false); // putMemory marcó sucio al cargar: no hace falta volver a guardar
        return s;
    }
}

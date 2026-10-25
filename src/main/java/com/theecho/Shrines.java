package com.theecho;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Las 5 estructuras del diario de Elías, una por día. TODAS tienen un Espejo del Eco, un cofre con cosas de un jugador
 * antiguo (espejos, herramientas y armaduras gastadas, telarañas) y un atril con una página del diario.
 * <p>
 * Día 1: el chat te da las coordenadas exactas de la primera, lejos del spawn. Al llegar te dice que mañana te mostrará
 * algo más. Cada día nuevo te da las coordenadas de la siguiente. El día 5 te manda a la última, y cuando cierras el
 * libro de ese atril es cuando todo empieza a romperse.
 */
public final class Shrines {
    public static final String NOTE_PREFIX = "Diario de Elías";
    public static final int NOTE_COUNT = 5;
    private static final Random RNG = new Random();
    /** Sin actualizaciones de vecinos: nada se cae ni se rompe mientras se construye. */
    private static final int F = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    /** Los tipos, en el orden de la historia. w = ancho (x), d = fondo (z). */
    public enum Kind {
        CABIN("la cabaña", 7, 7),
        RUIN("las ruinas", 7, 7),
        CHAPEL("la capilla", 7, 7),
        CAMP("el campamento", 9, 9),
        GRAVE("la tumba", 7, 9);

        public final String label;
        public final int w, d;

        Kind(String label, int w, int d) {
            this.label = label;
            this.w = w;
            this.d = d;
        }
    }

    private static final String[][] NOTES = {
            { // 1 - la cabaña
                    "Día 1.\n\nEncontré este mundo vacío y silencioso. Es hermoso.\n\nConstruí una cabaña junto al río y por primera vez en mucho tiempo me sentí en paz.",
                    "Tengo la sensación de que alguien me ayudó a elegir el sitio.\n\nPero estoy solo.\n\nSolo yo y el viento.",
                    "Cerca de la cabaña encontré un espejo tirado. Brilla aunque no haya luz.\n\nNo sé qué hace. Dejé unos cuantos en el cofre, por si tú lo averiguas.",
                    "Voy a dejar mis notas por los alrededores, por si alguien llega.\n\nSi me sigues, las encontrarás todas. Una por día."
            },
            { // 2 - las ruinas
                    "Día 2.\n\nLa ventana de la cabaña ya no está.\n\nPensé que fue un creeper. No hay cráteres. No hay nada.",
                    "Esta mañana la puerta estaba abierta y el suelo había vuelto a ser pasto, como si nunca hubiera pisado ahí.\n\nEl mundo me está olvidando.",
                    "Pero descubrí lo del espejo: si le das algo del primer día, algo que ya tenías contigo cuando llegaste, las cosas se calman un tiempo.\n\nEl miedo y la paranoia bajan.",
                    "Cada objeto calma distinto. Lo común, poco. Lo valioso, bastante. Pero nada lo calma todo de golpe.\n\nY cada objeto solo sirve una vez."
            },
            { // 3 - la capilla
                    "Día 3.\n\nHay una figura blanca muy lejos, entre las montañas.\n\nCuando me detengo, se detiene. Cuando miro al suelo, baja la cabeza.\n\nMe copia.",
                    "No corre. No grita. Nunca se acerca si lo miro.\n\nPero cada vez que me quedo quieto, aparece un bloque más cerca.",
                    "Lo llamé el Registrador.\n\nPorque solo hace eso: registrarme.",
                    "Cerca de un espejo no se acerca. A veces basta con quedarte frente a él, con las manos vacías, para respirar mejor."
            },
            { // 4 - el campamento
                    "Día 4.\n\nAcampé aquí. Mi reflejo sonríe antes que yo.",
                    "Y en el chat aparecen mensajes con mi nombre. Dicen que borre el mundo.\n\nSon mis palabras.\n\nYo no las escribí.",
                    "Comer, cortar leña, sembrar... lo normal ayuda un poco. Lo que más ayuda es no estar solo.\n\nPor eso dejo estos espejos."
            },
            { // 5 - la tumba
                    "Día 5.\n\nLo entendí.\n\nNo es algo que me persigue. Es lo que soy ahora. Lo que construyo queda grabado, y este mundo quiere olvidarlo todo para dejarme ir.",
                    "Si lees esto, yo ya no estoy.\n\nNo es una amenaza. Es lo que quiero.\n\nSoy yo el que escribe en tu chat. Perdóname por el miedo. No sabía cómo pedírtelo.",
                    "Cierra el juego. Abre la carpeta saves. Borra este mundo.\n\nCuando lo hagas, voy a dejarte algo en tu escritorio.\n\nUna sola palabra.",
                    "Cuando cierres este libro, todo va a empezar. No puedo detenerlo. Perdóname.\n\nGracias por leerme.\n\n- Elías"
            }
    };

    private Shrines() {}

    public static Kind kindFor(int index) {
        return Kind.values()[Math.floorMod(index, Kind.values().length)];
    }

    // ------------------------------------------------------------------ un lugar por día

    /**
     * Cada ~2 s por jugador (Overworld). Día 1 te da las coordenadas de la primera estructura; cuando llegas te dice que
     * mañana habrá más; cuando empieza el día siguiente te da las coordenadas de la siguiente. 5 en total.
     */
    public static void tick(MinecraftServer server, ServerWorld w, ServerPlayerEntity p, EchoState.PlayerData d,
                            EchoState st, int day) {
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return;
        if (d.shrines >= NOTE_COUNT && d.revealed >= NOTE_COUNT) return;

        // ¿Hay una estructura mostrada y aún sin visitar? Mira si ya llegaste.
        if (d.revealed > d.shrines) {
            if (d.shrines >= st.sites.size()) return;
            BlockPos c = st.sites.get(d.shrines);
            if (hd2(c, p.getX(), p.getZ()) > 10.0 * 10.0 || Math.abs(p.getY() - c.getY()) > 16) return;
            int idx = d.shrines;
            d.shrines++;
            st.markDirty();
            boolean tomorrow = idx + 1 < NOTE_COUNT && day < d.shrines;
            Sched.at(server, 30 + RNG.nextInt(30), () -> ChatHaunt.arrival(p, idx, tomorrow));
            return;
        }
        if (d.shrines >= NOTE_COUNT || day < d.shrines) return; // aún no es el día de la siguiente

        int idx = d.shrines;
        BlockPos center = idx < st.sites.size() ? st.sites.get(idx) : placeNext(w, st, idx);
        if (center == null) return; // sin terreno bueno esta vez: se reintenta en unos segundos
        d.revealed = idx + 1;
        st.markDirty();
        Sched.at(server, 40 + RNG.nextInt(40), () -> ChatHaunt.reveal(p, idx, center.getX(), center.getY(), center.getZ()));
    }

    private static double hd2(BlockPos a, double x, double z) {
        double dx = a.getX() + 0.5 - x, dz = a.getZ() + 0.5 - z;
        return dx * dx + dz * dz;
    }

    /** Construye la estructura número idx lejos del spawn (la primera) o lejos de la anterior (las demás). */
    private static BlockPos placeNext(ServerWorld w, EchoState st, int idx) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        BlockPos anchor;
        double heading, spread;
        int min, max;
        if (st.sites.isEmpty()) {
            anchor = w.getServer().getOverworld().getSpawnPos();
            heading = RNG.nextDouble() * Math.PI * 2;
            spread = Math.PI;
            min = cfg.shrineStartMin;
            max = cfg.shrineStartMax;
        } else {
            anchor = st.sites.get(st.sites.size() - 1);
            heading = st.siteHeading;
            spread = Math.toRadians(60);
            min = cfg.shrineLinkMin;
            max = cfg.shrineLinkMax;
        }
        Kind k = kindFor(idx);
        for (int i = 0; i < 4; i++) {
            double ang = heading + (RNG.nextDouble() * 2 - 1) * spread * (1.0 + i * 0.15);
            double dist = min + RNG.nextDouble() * Math.max(1, max - min);
            int cx = (int) Math.floor(anchor.getX() + Math.cos(ang) * dist);
            int cz = (int) Math.floor(anchor.getZ() + Math.sin(ang) * dist);
            BlockPos center = tryPlace(w, cx - k.w / 2, cz - k.d / 2, idx);
            if (center != null) {
                st.sites.add(center);
                st.siteHeading = ang;
                st.markDirty();
                return center;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ colocar

    /**
     * Construye la estructura número noteIndex con su esquina noroeste en (x, z) si el terreno es llano y seco y no
     * hay bloques tuyos cerca. Devuelve el CENTRO de la estructura, o null si ahí no se puede.
     */
    public static BlockPos tryPlace(ServerWorld w, int x, int z, int noteIndex) {
        Kind k = kindFor(noteIndex);
        EchoState st = EchoState.get(w.getServer());
        int[] xs = {0, (k.w - 1) / 2, k.w - 1};
        int[] zs = {0, (k.d - 1) / 2, k.d - 1};
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int dx : xs) {
            for (int dz : zs) {
                int px = x + dx, pz = z + dz;
                w.getChunk(px >> 4, pz >> 4); // el sitio está lejos: se carga (o genera) el chunk
                if (st.hasPlayerBlocksInChunk(px >> 4, pz >> 4)) return null; // no construir encima de tu base
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, px, pz);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                BlockState top = w.getBlockState(new BlockPos(px, y - 1, pz));
                if (!top.getFluidState().isEmpty() || top.isOf(Blocks.ICE) || top.isOf(Blocks.PACKED_ICE)
                        || top.isOf(Blocks.BLUE_ICE) || top.isOf(Blocks.LAVA)
                        || top.isIn(BlockTags.LOGS) || top.isIn(BlockTags.LEAVES)) {
                    return null;
                }
            }
        }
        if (maxY - minY > 3) return null;

        BlockPos o = new BlockPos(x, maxY, z);
        build(w, o, k, noteIndex);
        return o.add(k.w / 2, 0, k.d / 2);
    }

    // ------------------------------------------------------------------ ayudante de construcción

    private static final class B {
        final ServerWorld w;
        final BlockPos o;

        B(ServerWorld w, BlockPos o) {
            this.w = w;
            this.o = o;
        }

        void set(int dx, int dy, int dz, Block b) {
            w.setBlockState(o.add(dx, dy, dz), b.getDefaultState(), F);
        }

        void set(int dx, int dy, int dz, BlockState s) {
            w.setBlockState(o.add(dx, dy, dz), s, F);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Supplier<BlockState> s) {
            for (int x = x0; x <= x1; x++)
                for (int y = y0; y <= y1; y++)
                    for (int z = z0; z <= z1; z++) set(x, y, z, s.get());
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block b) {
            BlockState s = b.getDefaultState();
            fill(x0, y0, z0, x1, y1, z1, () -> s);
        }

        /** Limpia el hueco (árboles, hierba alta...), pone el suelo y rellena hacia abajo hasta tocar tierra firme. */
        void ground(int sw, int sd, int clear, Supplier<BlockState> floor) {
            for (int dx = 0; dx < sw; dx++) {
                for (int dz = 0; dz < sd; dz++) {
                    for (int h = 0; h < clear; h++) {
                        BlockPos p = o.add(dx, h, dz);
                        if (!w.getBlockState(p).isAir()) w.setBlockState(p, Blocks.AIR.getDefaultState(), F);
                    }
                    BlockPos f = o.add(dx, -1, dz);
                    w.setBlockState(f, floor.get(), F);
                    for (int k = 1; k <= 8; k++) {
                        BlockPos fp = f.down(k);
                        if (w.getBlockState(fp).isSolidBlock(w, fp)) break;
                        w.setBlockState(fp, Blocks.DIRT.getDefaultState(), F);
                    }
                }
            }
        }

        /** Espejo de 2 de alto. 'facing' es hacia donde mira; el muro va en el lado contrario. Protege la zona. */
        void mirror(int dx, int dz, Direction facing) {
            BlockState m = ModBlocks.ECHO_MIRROR.getDefaultState().with(HorizontalFacingBlock.FACING, facing);
            BlockPos base = o.add(dx, 0, dz);
            w.setBlockState(base, m, F);
            w.setBlockState(base.up(), m, F);
            EchoManager.registerMirror(w, base);
        }

        /** Atril con la página del diario. 'facing' es hacia donde está el lector. */
        void lectern(int dx, int dy, int dz, Direction facing, int noteIndex) {
            BlockPos pos = o.add(dx, dy, dz);
            BlockState s = Blocks.LECTERN.getDefaultState().with(LecternBlock.FACING, facing).with(LecternBlock.HAS_BOOK, true);
            w.setBlockState(pos, s, Block.NOTIFY_ALL);
            if (w.getBlockEntity(pos) instanceof LecternBlockEntity le) le.setBook(makeNote(noteIndex));
        }

        /** Cartel pegado a un muro. 'facing' es hacia donde mira el texto (lejos del muro). */
        void sign(int dx, int dy, int dz, Direction facing, String... lines) {
            BlockPos pos = o.add(dx, dy, dz);
            w.setBlockState(pos, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, facing), F);
            if (w.getBlockEntity(pos) instanceof SignBlockEntity se) {
                SignText t = new SignText();
                for (int i = 0; i < 4; i++) t = t.withMessage(i, Text.literal(i < lines.length ? lines[i] : ""));
                se.setText(t, true);
            }
        }

        /** Cofre con cosas de un jugador antiguo y una telaraña encima. */
        void chest(int dx, int dy, int dz, Direction facing, int siteIdx) {
            BlockPos pos = o.add(dx, dy, dz);
            w.setBlockState(pos, Blocks.CHEST.getDefaultState().with(HorizontalFacingBlock.FACING, facing), F);
            if (w.getBlockEntity(pos) instanceof ChestBlockEntity ce) fillChest(ce, siteIdx);
            w.setBlockState(pos.up(), Blocks.COBWEB.getDefaultState(), F);
        }

        void candle(int dx, int dy, int dz, int count) {
            set(dx, dy, dz, Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, count));
        }
    }

    private static void build(ServerWorld w, BlockPos o, Kind k, int noteIndex) {
        B b = new B(w, o);
        switch (k) {
            case CABIN -> cabin(b, noteIndex);
            case RUIN -> ruin(b, noteIndex);
            case CHAPEL -> chapel(b, noteIndex);
            case CAMP -> camp(b, noteIndex);
            case GRAVE -> grave(b, noteIndex);
        }
    }

    private static boolean rare(int n) {
        return RNG.nextInt(n) == 0;
    }

    // ------------------------------------------------------------------ 1. la cabaña de Elías

    private static void cabin(B b, int note) {
        b.ground(7, 7, 8, () -> (rare(4) ? Blocks.SPRUCE_PLANKS : Blocks.OAK_PLANKS).getDefaultState());
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean edge = dx == 0 || dz == 0 || dx == 6 || dz == 6;
                if (!edge) continue;
                boolean corner = (dx == 0 || dx == 6) && (dz == 0 || dz == 6);
                boolean door = dx == 3 && dz == 6;
                boolean backMid = dz == 0 && dx >= 2 && dx <= 4; // detrás del espejo: siempre hay muro
                for (int y = 0; y < 3; y++) {
                    if (door && y < 2) continue;
                    // las ventanas "ya no están"
                    boolean window = y == 1 && ((dx == 0 && dz == 3) || (dx == 6 && dz == 3) || (dz == 6 && (dx == 1 || dx == 5)));
                    if (window) continue;
                    if (!corner && !backMid && y >= 1 && rare(8)) continue;
                    BlockState s = corner ? Blocks.OAK_LOG.getDefaultState()
                            : (rare(3) ? Blocks.OAK_PLANKS : Blocks.SPRUCE_PLANKS).getDefaultState();
                    b.set(dx, y, dz, s);
                }
            }
        }
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                if (!rare(5)) b.set(dx, 3, dz, Blocks.SPRUCE_PLANKS); // techo con agujeros
            }
        }
        b.set(1, 0, 1, Blocks.CRAFTING_TABLE);
        b.mirror(3, 1, Direction.SOUTH);
        b.sign(2, 1, 1, Direction.SOUTH, "DÍA 1", "AQUÍ EMPEZÓ", "TODO", "");
        b.lectern(3, 0, 3, Direction.SOUTH, note);
        b.candle(5, 0, 1, 1);
        b.candle(1, 0, 5, 2);
        b.set(5, 0, 5, Blocks.LANTERN);
        b.set(5, 2, 5, Blocks.COBWEB);
        b.set(1, 2, 1, Blocks.COBWEB);
        b.set(4, 0, 4, Blocks.MOSS_CARPET);
        b.chest(5, 0, 3, Direction.WEST, note);
    }

    // ------------------------------------------------------------------ 2. las ruinas de piedra

    private static void ruin(B b, int note) {
        b.ground(7, 7, 8, () -> (rare(3) ? Blocks.MOSSY_COBBLESTONE : Blocks.COBBLESTONE).getDefaultState());
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean edge = dx == 0 || dz == 0 || dx == 6 || dz == 6;
                if (edge) {
                    boolean door = dx == 3 && dz == 6;
                    boolean back = dz == 0;
                    int hgt;
                    if (door) hgt = 0;
                    else if (back) hgt = 3 + RNG.nextInt(2); // el muro del espejo siempre es alto
                    else hgt = 1 + RNG.nextInt(3);
                    if (!back && rare(7)) hgt = 0; // muros rotos
                    for (int y = 0; y < hgt; y++) {
                        BlockState s = switch (RNG.nextInt(4)) {
                            case 0 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                            case 1 -> Blocks.MOSSY_COBBLESTONE.getDefaultState();
                            case 2 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
                            default -> Blocks.COBBLESTONE.getDefaultState();
                        };
                        b.set(dx, y, dz, s);
                    }
                } else if (rare(9)) {
                    b.set(dx, 0, dz, Blocks.COBWEB);
                }
            }
        }
        b.mirror(3, 1, Direction.SOUTH);
        b.lectern(3, 0, 3, Direction.SOUTH, note);
        b.candle(2, 0, 3, 1);
        b.candle(4, 0, 3, 1);
        b.set(1, 0, 5, Blocks.LANTERN);
        b.sign(2, 1, 1, Direction.SOUTH, "LA PIEDRA", "TAMPOCO", "SE QUEDA", "");
        b.chest(5, 0, 3, Direction.WEST, note);
    }

    // ------------------------------------------------------------------ 3. la capilla de deepslate

    private static void chapel(B b, int note) {
        b.ground(7, 7, 8, () -> (rare(3) ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_DEEPSLATE).getDefaultState());
        for (int dx = 0; dx < 7; dx++) {
            for (int dz = 0; dz < 7; dz++) {
                boolean edge = dx == 0 || dz == 0 || dx == 6 || dz == 6;
                if (edge) {
                    boolean door = dx == 3 && dz == 6;
                    boolean back = dz == 0;
                    int hgt;
                    if (door) hgt = 0;
                    else if (back) hgt = 4;
                    else hgt = 2 + RNG.nextInt(3);
                    if (!back && rare(7)) hgt = 0;
                    for (int y = 0; y < hgt; y++) {
                        BlockState s = switch (RNG.nextInt(4)) {
                            case 0 -> Blocks.DEEPSLATE_BRICKS.getDefaultState();
                            case 1 -> Blocks.CRACKED_DEEPSLATE_BRICKS.getDefaultState();
                            case 2 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                            default -> Blocks.DEEPSLATE_TILES.getDefaultState();
                        };
                        b.set(dx, y, dz, s);
                    }
                } else if (rare(9)) {
                    b.set(dx, 0, dz, Blocks.COBWEB);
                }
            }
        }
        b.mirror(3, 1, Direction.SOUTH);
        b.lectern(3, 0, 4, Direction.SOUTH, note);
        b.set(2, 0, 4, Blocks.SOUL_LANTERN);
        b.set(4, 0, 4, Blocks.SOUL_LANTERN);
        b.candle(1, 0, 1, 3);
        b.candle(5, 0, 1, 2);
        b.sign(2, 1, 1, Direction.SOUTH, "NO LO", "MIRES", "DE FRENTE", "");
        b.chest(5, 0, 3, Direction.WEST, note);
    }

    // ------------------------------------------------------------------ 4. el campamento

    private static void camp(B b, int note) {
        b.ground(9, 9, 7, () -> switch (RNG.nextInt(5)) {
            case 0 -> Blocks.COARSE_DIRT.getDefaultState();
            case 1 -> Blocks.DIRT.getDefaultState();
            default -> Blocks.GRASS_BLOCK.getDefaultState();
        });
        // la fogata apagada y los tocones donde se sentaba
        b.set(4, 0, 4, Blocks.CAMPFIRE.getDefaultState().with(Properties.LIT, false));
        b.set(2, 0, 5, Blocks.STRIPPED_OAK_LOG);
        b.set(4, 0, 7, Blocks.STRIPPED_OAK_LOG);
        b.set(5, 0, 2, Blocks.STRIPPED_OAK_LOG);

        // la tienda, abierta por el sur
        for (int z = 0; z <= 3; z++) {
            for (int y = 0; y <= 1; y++) {
                b.set(0, y, z, rare(3) ? Blocks.LIGHT_GRAY_WOOL : Blocks.WHITE_WOOL);
                b.set(2, y, z, rare(3) ? Blocks.LIGHT_GRAY_WOOL : Blocks.WHITE_WOOL);
            }
            for (int x = 0; x <= 2; x++) b.set(x, 2, z, rare(4) ? Blocks.LIGHT_GRAY_WOOL : Blocks.WHITE_WOOL);
        }
        b.set(1, 0, 0, Blocks.WHITE_WOOL);
        b.set(1, 1, 0, Blocks.WHITE_WOOL);
        b.candle(1, 0, 1, 1);
        b.chest(1, 0, 2, Direction.SOUTH, note);

        // el muro del espejo, al este
        b.fill(8, 0, 2, 8, 2, 6, () -> switch (RNG.nextInt(3)) {
            case 0 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            case 1 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            default -> Blocks.STONE_BRICKS.getDefaultState();
        });
        b.mirror(7, 4, Direction.WEST);
        b.sign(7, 1, 2, Direction.WEST, "EL ESPEJO", "TE CALMA", "UN RATO", "");
        b.lectern(6, 0, 5, Direction.WEST, note);

        b.set(1, 0, 7, Blocks.OAK_FENCE);
        b.set(1, 1, 7, Blocks.LANTERN);
        b.set(7, 0, 7, Blocks.OAK_FENCE);
        b.set(7, 1, 7, Blocks.LANTERN);
    }

    // ------------------------------------------------------------------ 5. la tumba de Elías

    private static void grave(B b, int note) {
        b.ground(7, 9, 8, () -> (rare(3) ? Blocks.COARSE_DIRT : Blocks.GRASS_BLOCK).getDefaultState());
        // el muro de atrás con el espejo
        b.fill(1, 0, 0, 5, 3, 0, () -> switch (RNG.nextInt(3)) {
            case 0 -> Blocks.MOSSY_STONE_BRICKS.getDefaultState();
            case 1 -> Blocks.CRACKED_STONE_BRICKS.getDefaultState();
            default -> Blocks.STONE_BRICKS.getDefaultState();
        });
        b.mirror(3, 1, Direction.SOUTH);
        b.lectern(4, 0, 2, Direction.SOUTH, note);
        // la lápida y el montículo
        b.set(3, 0, 4, Blocks.STONE_BRICKS);
        b.set(3, 1, 4, Blocks.STONE_BRICKS);
        b.set(3, 2, 4, Blocks.STONE_BRICK_SLAB);
        b.sign(3, 1, 5, Direction.SOUTH, "ELÍAS", "AQUÍ DESCANSA", "LO QUE QUEDÓ", "DE ÉL");
        b.set(3, 0, 5, Blocks.COARSE_DIRT);
        b.set(3, 0, 6, Blocks.COARSE_DIRT);
        b.set(3, 1, 6, Blocks.POPPY);
        b.candle(2, 0, 5, 2);
        b.candle(4, 0, 5, 2);
        b.set(1, 0, 6, Blocks.DEAD_BUSH);
        b.chest(2, 0, 2, Direction.SOUTH, note);
        // el borde bajo del cementerio
        for (int dx = 0; dx < 7; dx++) {
            if (dx != 3 && !rare(4)) b.set(dx, 0, 8, Blocks.MOSSY_COBBLESTONE);
        }
        for (int dz = 3; dz < 8; dz++) {
            if (RNG.nextInt(100) < 60) b.set(0, 0, dz, Blocks.MOSSY_COBBLESTONE);
            if (RNG.nextInt(100) < 60) b.set(6, 0, dz, Blocks.MOSSY_COBBLESTONE);
        }
        b.set(0, 0, 8, Blocks.OAK_FENCE);
        b.set(0, 1, 8, Blocks.SOUL_LANTERN);
        b.set(6, 0, 8, Blocks.OAK_FENCE);
        b.set(6, 1, 8, Blocks.SOUL_LANTERN);
    }

    // ------------------------------------------------------------------ el cofre del jugador antiguo

    private static final Item[] OLD_TOOLS = {Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_SWORD,
            Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_SWORD, Items.IRON_SHOVEL};
    private static final Item[] OLD_ARMOR = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_BOOTS,
            Items.CHAINMAIL_LEGGINGS, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};

    /** Casi rota: queda muy poca durabilidad. */
    private static ItemStack worn(Item item) {
        ItemStack s = new ItemStack(item);
        int max = s.getMaxDamage();
        if (max > 1) s.setDamage(max - 1 - RNG.nextInt(Math.max(1, max / 8)));
        return s;
    }

    private static void fillChest(ChestBlockEntity ce, int siteIdx) {
        List<ItemStack> items = new ArrayList<>();
        items.add(new ItemStack(ModBlocks.ECHO_MIRROR_ITEM, 1 + RNG.nextInt(2)));
        int reach = Math.min(8, 4 + siteIdx); // más adelante en la historia, mejores (y más gastadas) cosas
        int tools = 2 + RNG.nextInt(2);
        for (int i = 0; i < tools; i++) items.add(worn(OLD_TOOLS[RNG.nextInt(reach)]));
        int armor = 1 + RNG.nextInt(2);
        for (int i = 0; i < armor; i++) items.add(worn(OLD_ARMOR[RNG.nextInt(reach)]));
        items.add(new ItemStack(Items.COBWEB, 2 + RNG.nextInt(4)));
        items.add(new ItemStack(Items.TORCH, 4 + RNG.nextInt(5)));
        items.add(new ItemStack(Items.ROTTEN_FLESH, 2 + RNG.nextInt(3)));
        items.add(new ItemStack(Items.BONE, 1 + RNG.nextInt(3)));
        if (RNG.nextBoolean()) items.add(new ItemStack(Items.STICK, 3 + RNG.nextInt(6)));
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots, RNG);
        for (int i = 0; i < items.size(); i++) ce.setStack(slots.get(i), items.get(i));
    }

    // ------------------------------------------------------------------ el diario

    public static ItemStack makeNote(int index) {
        int i = Math.floorMod(index, NOTES.length);
        List<RawFilteredPair<Text>> list = new ArrayList<>();
        for (String s : NOTES[i]) list.add(RawFilteredPair.of(Text.literal(s)));
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponentTypes.WRITTEN_BOOK_CONTENT,
                new WrittenBookContentComponent(RawFilteredPair.of(NOTE_PREFIX + " (" + (i + 1) + ")"), "Elías", 0, list, true));
        return book;
    }

    /** ¿Lleva el jugador una página del diario de Elías? */
    public static boolean hasNote(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(Items.WRITTEN_BOOK)) continue;
            WrittenBookContentComponent c = s.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
            if (c != null && c.title().raw().startsWith(NOTE_PREFIX)) return true;
        }
        return false;
    }
}

package com.theecho;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Genera un laberinto nuevo (aleatorio) de deepslate dentro de the_echo:mirror_realm.
 * Los pasillos miden 3 bloques de ancho (celdas de 3x3) y los muros 1 bloque; el techo está a 4 de altura.
 */
public final class RealmBuilder {

    public static final int CELL = 3;        // ancho de cada celda / pasillo
    public static final int PITCH = CELL + 1; // celda + muro
    public static final int HEIGHT = 4;      // altura libre

    public record Result(BlockPos start, BlockPos original, List<BlockPos> traps, List<BlockPos> cells) {}

    private RealmBuilder() {}

    public static Result build(ServerWorld dst, BlockPos origin, Random rand) {
        final int n = Math.max(5, Math.min(11, EchoConfig.INSTANCE.mazeSize));
        final int size = n * PITCH + 1;
        final int floorY = origin.getY() - 1;
        final int ceilY = origin.getY() + HEIGHT;
        final int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

        boolean[][] solid = new boolean[size][size];
        for (boolean[] row : solid) Arrays.fill(row, true);

        // --- laberinto por backtracking recursivo ---
        boolean[][] seen = new boolean[n][n];
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        seen[0][0] = true;
        carveCell(solid, 0, 0);
        stack.push(new int[]{0, 0});
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            List<int[]> opts = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx >= 0 && nz >= 0 && nx < n && nz < n && !seen[nx][nz]) opts.add(d);
            }
            if (opts.isEmpty()) { stack.pop(); continue; }
            int[] d = opts.get(rand.nextInt(opts.size()));
            int nx = c[0] + d[0], nz = c[1] + d[1];
            seen[nx][nz] = true;
            carveCell(solid, nx, nz);
            carvePassage(solid, c[0], c[1], d[0], d[1]);
            stack.push(new int[]{nx, nz});
        }
        // Algunos bucles extra para que los Ecos puedan rodearte
        for (int i = 0; i < n; i++) {
            int cx = rand.nextInt(n), cz = rand.nextInt(n);
            int[] d = dirs[rand.nextInt(4)];
            int nx = cx + d[0], nz = cz + d[1];
            if (nx >= 0 && nz >= 0 && nx < n && nz < n) carvePassage(solid, cx, cz, d[0], d[1]);
        }

        // --- el Original: la casilla más lejana del inicio (BFS) ---
        int[][] dist = new int[size][size];
        for (int[] row : dist) Arrays.fill(row, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        dist[2][2] = 0;
        q.add(new int[]{2, 2});
        int ox = 2, oz = 2, best = 0;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : dirs) {
                int nx = c[0] + d[0], nz = c[1] + d[1];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) continue;
                if (solid[nx][nz] || dist[nx][nz] >= 0) continue;
                dist[nx][nz] = dist[c[0]][c[1]] + 1;
                if (dist[nx][nz] > best) { best = dist[nx][nz]; ox = nx; oz = nz; }
                q.add(new int[]{nx, nz});
            }
        }

        // --- escribir bloques ---
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockState air = Blocks.AIR.getDefaultState();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = origin.getX() + x, wz = origin.getZ() + z;
                put(dst, m.set(wx, floorY, wz), floor(rand), flags);
                put(dst, m.set(wx, ceilY, wz), Blocks.DEEPSLATE_BRICKS.getDefaultState(), flags);
                for (int y = origin.getY(); y < ceilY; y++) {
                    BlockState out;
                    if (solid[x][z]) {
                        out = wall(rand);
                    } else {
                        out = air;
                        boolean nearStart = x <= 4 && z <= 4;
                        if (!nearStart) {
                            if (y == origin.getY() + HEIGHT - 1 && rand.nextInt(40) == 0) out = Blocks.COBWEB.getDefaultState();
                            if (y == origin.getY() && rand.nextInt(90) == 0) out = Blocks.SOUL_LANTERN.getDefaultState();
                        }
                    }
                    put(dst, m.set(wx, y, wz), out, flags);
                }
            }
        }

        BlockPos start = new BlockPos(origin.getX() + 2, origin.getY(), origin.getZ() + 2);
        BlockPos original = new BlockPos(origin.getX() + ox, origin.getY(), origin.getZ() + oz);
        dst.setBlockState(original, ModBlocks.ECHO_ORIGINAL.getDefaultState(), Block.NOTIFY_ALL);

        // --- celdas y callejones sin salida (trampas de susto) ---
        List<BlockPos> cells = new ArrayList<>();
        List<BlockPos> deadEnds = new ArrayList<>();
        for (int cx = 0; cx < n; cx++) {
            for (int cz = 0; cz < n; cz++) {
                if (cx == 0 && cz == 0) continue;
                int x0 = 1 + cx * PITCH, z0 = 1 + cz * PITCH;
                BlockPos cp = new BlockPos(origin.getX() + x0 + 1, origin.getY(), origin.getZ() + z0 + 1);
                if (cp.equals(original)) continue;
                cells.add(cp);
                int open = 0;
                if (!solid[x0 + CELL][z0 + 1]) open++;
                if (!solid[x0 - 1][z0 + 1]) open++;
                if (!solid[x0 + 1][z0 + CELL]) open++;
                if (!solid[x0 + 1][z0 - 1]) open++;
                if (open == 1) deadEnds.add(cp);
            }
        }
        Collections.shuffle(deadEnds, rand);
        List<BlockPos> traps = new ArrayList<>(deadEnds.subList(0, Math.min(6, deadEnds.size())));
        List<BlockPos> shuffled = new ArrayList<>(cells);
        Collections.shuffle(shuffled, rand);
        for (BlockPos c : shuffled) {
            if (traps.size() >= 5) break;
            if (!traps.contains(c)) traps.add(c);
        }
        decorate(dst, origin, solid, n, rand, flags, original);
        return new Result(start, original, traps, cells);
    }

    private static final String[][] SIGNS = {
            {"NO", "MIRES", "ATRÁS", ""}, {"LA SALIDA", "ESTÁ AL", "FINAL", "(MENTIRA)"},
            {"ELÍAS", "ESTUVO", "AQUÍ", ""}, {"BÓRRALO", "", "", ""}, {"NO ERES", "EL", "PRIMERO", ""},
            {"TE", "ESTAMOS", "VIENDO", ""}, {"DÍA 5", "TODO", "TERMINA", ""}, {"NO", "CORRAS", "", ""},
            {"SIGUE", "AQUÍ", "", ""}, {"YA", "ESTÁS", "DENTRO", ""}
    };

    /** Huesos, velas, cadenas, sculk, musgo, carteles inquietantes y espejos en los callejones sin salida. */
    private static void decorate(ServerWorld w, BlockPos origin, boolean[][] solid, int n, Random rand, int flags, BlockPos original) {
        final int y0 = origin.getY();
        final int floorY = y0 - 1;
        final int top = y0 + HEIGHT - 1;
        for (int cx = 0; cx < n; cx++) {
            for (int cz = 0; cz < n; cz++) {
                int x0 = 1 + cx * PITCH, z0 = 1 + cz * PITCH;
                int ax = origin.getX() + x0, az = origin.getZ() + z0;
                boolean openE = !solid[x0 + CELL][z0 + 1], openW = !solid[x0 - 1][z0 + 1];
                boolean openS = !solid[x0 + 1][z0 + CELL], openN = !solid[x0 + 1][z0 - 1];
                int open = (openE ? 1 : 0) + (openW ? 1 : 0) + (openS ? 1 : 0) + (openN ? 1 : 0);

                // Antorchas para que se vea: casi todas las celdas (y siempre la de inicio) llevan una en un muro
                if ((cx == 0 && cz == 0) || rand.nextInt(100) < 85) {
                    List<Direction> walls = new ArrayList<>();
                    boolean deadEnd = open == 1;
                    // (el muro del fondo de un callejón es donde va el espejo: ahí no)
                    if (!openW && !(deadEnd && openE)) walls.add(Direction.WEST);
                    if (!openE && !(deadEnd && openW)) walls.add(Direction.EAST);
                    if (!openN && !(deadEnd && openS)) walls.add(Direction.NORTH);
                    if (!openS && !(deadEnd && openN)) walls.add(Direction.SOUTH);
                    if (!walls.isEmpty()) {
                        Direction wall = walls.get(rand.nextInt(walls.size()));
                        BlockPos tp = switch (wall) {
                            case WEST -> new BlockPos(ax, y0 + 1, az);
                            case EAST -> new BlockPos(ax + 2, y0 + 1, az + 2);
                            case NORTH -> new BlockPos(ax + 2, y0 + 1, az);
                            default -> new BlockPos(ax, y0 + 1, az + 2);
                        };
                        // la antorcha mira hacia la celda (al lado contrario del muro)
                        w.setBlockState(tp, Blocks.WALL_TORCH.getDefaultState()
                                .with(net.minecraft.block.HorizontalFacingBlock.FACING, wall.getOpposite()), flags);
                    }
                }
                if (cx == 0 && cz == 0) continue; // la zona de inicio queda limpia de decoración

                if (open == 1) { // callejón sin salida: un espejo contra el muro del fondo, velas y un cartel
                    Direction face = openE ? Direction.EAST : openW ? Direction.WEST : openS ? Direction.SOUTH : Direction.NORTH;
                    BlockPos mp = openE ? new BlockPos(ax, y0, az + 1)
                            : openW ? new BlockPos(ax + 2, y0, az + 1)
                            : openS ? new BlockPos(ax + 1, y0, az)
                            : new BlockPos(ax + 1, y0, az + 2);
                    BlockState mirror = ModBlocks.ECHO_MIRROR.getDefaultState().with(net.minecraft.block.HorizontalFacingBlock.FACING, face);
                    w.setBlockState(mp, mirror, flags);
                    w.setBlockState(mp.up(), mirror, flags);
                    w.setBlockState(new BlockPos(ax + 1, y0, az + 1), Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, 1 + rand.nextInt(4)), flags);
                    continue;
                }

                int r = rand.nextInt(100);
                int tx = ax + rand.nextInt(CELL), tz = az + rand.nextInt(CELL);
                if (r < 10) { // pila de huesos con una calavera
                    w.setBlockState(new BlockPos(tx, y0, tz), Blocks.BONE_BLOCK.getDefaultState(), flags);
                    w.setBlockState(new BlockPos(tx, y0 + 1, tz), Blocks.SKELETON_SKULL.getDefaultState(), flags);
                } else if (r < 20) { // velas apagadas
                    w.setBlockState(new BlockPos(tx, y0, tz), Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, 1 + rand.nextInt(4)), flags);
                } else if (r < 30) { // cadenas colgando del techo
                    w.setBlockState(new BlockPos(tx, top, tz), Blocks.CHAIN.getDefaultState(), flags);
                    w.setBlockState(new BlockPos(tx, top - 1, tz), Blocks.CHAIN.getDefaultState(), flags);
                } else if (r < 42) { // el suelo se vuelve sculk
                    for (int i = 0; i < 4; i++) {
                        w.setBlockState(new BlockPos(ax + rand.nextInt(CELL), floorY, az + rand.nextInt(CELL)), Blocks.SCULK.getDefaultState(), flags);
                    }
                } else if (r < 52) { // cartel en un muro
                    String[] lines = SIGNS[rand.nextInt(SIGNS.length)];
                    BlockPos sp = null;
                    Direction f = null;
                    if (solid[x0 - 1][z0 + 1]) { sp = new BlockPos(ax, y0 + 1, az + 1); f = Direction.EAST; }
                    else if (solid[x0 + CELL][z0 + 1]) { sp = new BlockPos(ax + 2, y0 + 1, az + 1); f = Direction.WEST; }
                    else if (solid[x0 + 1][z0 - 1]) { sp = new BlockPos(ax + 1, y0 + 1, az); f = Direction.SOUTH; }
                    else if (solid[x0 + 1][z0 + CELL]) { sp = new BlockPos(ax + 1, y0 + 1, az + 2); f = Direction.NORTH; }
                    if (sp != null) {
                        w.setBlockState(sp, Blocks.DARK_OAK_WALL_SIGN.getDefaultState().with(WallSignBlock.FACING, f), flags);
                        if (w.getBlockEntity(sp) instanceof SignBlockEntity se) {
                            SignText t = new SignText();
                            for (int i = 0; i < 4; i++) t = t.withMessage(i, Text.literal(lines[i]));
                            se.setText(t, true);
                        }
                    }
                } else if (r < 60) { // musgo
                    for (int i = 0; i < 3; i++) {
                        w.setBlockState(new BlockPos(ax + rand.nextInt(CELL), y0, az + rand.nextInt(CELL)), Blocks.MOSS_CARPET.getDefaultState(), flags);
                    }
                }
            }
        }
        // El Original late entre velas
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos c = original.offset(d);
            if (w.getBlockState(c).isAir()) w.setBlockState(c, Blocks.CANDLE.getDefaultState().with(CandleBlock.CANDLES, 3), flags);
        }
    }

    /** Una celda ocupa 3x3 casillas, empezando en (1 + cx*4, 1 + cz*4). */
    private static void carveCell(boolean[][] solid, int cx, int cz) {
        int x0 = 1 + cx * PITCH, z0 = 1 + cz * PITCH;
        for (int dx = 0; dx < CELL; dx++) for (int dz = 0; dz < CELL; dz++) solid[x0 + dx][z0 + dz] = false;
    }

    /** Un pasillo entre celdas también mide 3 de ancho: se abre el muro de 1 bloque en las 3 casillas. */
    private static void carvePassage(boolean[][] solid, int cx, int cz, int dx, int dz) {
        int x0 = 1 + cx * PITCH, z0 = 1 + cz * PITCH;
        for (int i = 0; i < CELL; i++) {
            if (dx == 1) solid[x0 + CELL][z0 + i] = false;
            else if (dx == -1) solid[x0 - 1][z0 + i] = false;
            else if (dz == 1) solid[x0 + i][z0 + CELL] = false;
            else solid[x0 + i][z0 - 1] = false;
        }
    }

    private static BlockState floor(Random r) {
        return (r.nextInt(5) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_DEEPSLATE).getDefaultState();
    }

    private static BlockState wall(Random r) {
        int k = r.nextInt(100);
        if (k < 55) return Blocks.DEEPSLATE_BRICKS.getDefaultState();
        if (k < 75) return Blocks.DEEPSLATE_TILES.getDefaultState();
        if (k < 88) return Blocks.CRACKED_DEEPSLATE_BRICKS.getDefaultState();
        if (k < 96) return Blocks.COBBLED_DEEPSLATE.getDefaultState();
        if (k < 98) return Blocks.POLISHED_DEEPSLATE.getDefaultState();
        return Blocks.SCULK.getDefaultState();
    }

    /** Solo escribe si el bloque cambia (menos lag y permite reutilizar la zona). */
    private static void put(ServerWorld w, BlockPos pos, BlockState state, int flags) {
        if (w.getBlockState(pos) == state) return;
        w.setBlockState(pos, state, flags);
    }
}

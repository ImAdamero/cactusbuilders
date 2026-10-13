package pl.cactusbuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plan budowy jednego chunka (16x16), pietro po pietrze.
 * Pietro i zajmuje wysokosci baseY+2i+1 (kaktus / doniczka) oraz baseY+2i+2 (piasek na doniczce).
 * Pietro 0 ma dodatkowo podloze z piasku na wysokosci baseY.
 */
final class ChunkPlan {
    static final int NONE = Integer.MIN_VALUE;

    enum Kind {
        CACTUS(Items.CACTUS, Blocks.CACTUS),
        SAND(Items.SAND, Blocks.SAND),
        POT(Items.FLOWER_POT, Blocks.FLOWER_POT);

        final Item item;
        final Block block;

        Kind(Item item, Block block) {
            this.item = item;
            this.block = block;
        }
    }

    record Target(BlockPos pos, Kind kind) {
    }

    /** need = minimalna wysokosc stop gracza nad baseY, od ktorej wolno stawiac ten klocek (NONE = bez limitu). */
    record Entry(Target t, int need) {
    }

    final ChunkPos chunk;
    final int minX;
    final int minZ;
    final int baseY;
    final int maxLayers;
    final boolean flipZ;
    final boolean lowFirst;
    private final List<Entry> entries = new ArrayList<>();

    ChunkPlan(ChunkPos chunk, int baseY, int maxLayers) {
        this(chunk, baseY, maxLayers, null, null);
    }

    /** flipZIn / lowFirstIn != null wymusza orientacje (kazde pietro konczy sie w tym samym rogu). */
    ChunkPlan(ChunkPos chunk, int baseY, int maxLayers, Boolean flipZIn, Boolean lowFirstIn) {
        this.chunk = chunk;
        this.minX = chunk.getStartX();
        this.minZ = chunk.getStartZ();
        this.baseY = baseY;
        this.maxLayers = maxLayers;
        boolean fz = false;
        boolean lf = true;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                fz = mc.player.getZ() < minZ + 8;
                lf = mc.player.getX() < minX + 8;
            }
        } catch (Throwable ignored) {
        }
        if (flipZIn != null) fz = flipZIn;
        if (lowFirstIn != null) lf = lowFirstIn;
        this.flipZ = fz;
        this.lowFirst = lf;
        build();
    }

    /** Czy punkt (x,z) lezy w chunku, z marginesem w blokach. */
    boolean contains(double x, double z, double margin) {
        return x >= minX - margin && x < minX + 16 + margin && z >= minZ - margin && z < minZ + 16 + margin;
    }

    /** Co ma stac na wzglednej wysokosci h (0 = podloze) w kolumnie (x,z). null = pusto. */
    static Kind expected(int x, int z, int h) {
        int parity = (x + z) & 1;
        if (h == 0) {
            return parity == 0 ? Kind.SAND : null;
        }
        int floor = (h - 1) / 2;
        boolean upper = (h - 1) % 2 == 1;
        int q = floor & 1;
        if (!upper) {
            return parity == q ? Kind.CACTUS : Kind.POT;
        }
        return parity != q ? Kind.SAND : null;
    }

    static boolean missing(World w, BlockPos pos, Kind kind) {
        BlockState st = w.getBlockState(pos);
        return !st.isOf(kind.block) && st.isReplaceable();
    }

    private static boolean present(World w, BlockPos pos) {
        return !w.getBlockState(pos).isReplaceable();
    }

    private int needFor(int y) {
        int rel = y - baseY;
        if (rel <= 0) {
            return NONE;
        }
        int floor = (rel - 1) / 2;
        return floor == 0 ? NONE : 2 * floor + 1;
    }

    private void add(Kind kind, int x, int y, int z) {
        entries.add(new Entry(new Target(new BlockPos(x, y, z), kind), needFor(y)));
    }

    private void cell(int layer, int h, int x, int z) {
        if (layer == 0 && expected(x, z, 0) == Kind.SAND) {
            add(Kind.SAND, x, baseY, z);
        }
        Kind k = expected(x, z, h);
        if (k == Kind.CACTUS) {
            add(Kind.CACTUS, x, baseY + h, z);
        } else if (k == Kind.POT) {
            add(Kind.POT, x, baseY + h, z);
            add(Kind.SAND, x, baseY + h + 1, z);
        }
    }

    private void build() {
        int edgeX = lowFirst ? minX : minX + 15;
        int dirX = lowFirst ? 1 : -1;
        int edgeZ = flipZ ? minZ : minZ + 15;
        int nextZ = edgeZ + (flipZ ? 1 : -1);
        for (int layer = 0; layer < maxLayers; layer++) {
            int h = 2 * layer + 1;
            // 14 rzedow "weza"
            for (int row = 0; row < 14; row++) {
                for (int c = 0; c < 16; c++) {
                    boolean forward = ((row & 1) == 0) == lowFirst;
                    int x = minX + (forward ? c : 15 - c);
                    int z = minZ + (flipZ ? 15 - row : row);
                    cell(layer, h, x, z);
                }
            }
            // dwa ostatnie rzedy (od strony gracza)
            for (int c = 15; c >= 0; c--) {
                int x = edgeX + dirX * c;
                if (expected(x, nextZ, h) == Kind.CACTUS) {
                    cell(layer, h, x, nextZ);
                    cell(layer, h, x, edgeZ);
                } else {
                    cell(layer, h, x, edgeZ);
                    cell(layer, h, x, nextZ);
                }
            }
        }
    }

    private static boolean supported(World w, Entry e) {
        BlockPos p = e.t().pos();
        int x = p.getX();
        int y = p.getY();
        int z = p.getZ();
        switch (e.t().kind()) {
            case SAND:
                return present(w, new BlockPos(x, y - 1, z));
            case CACTUS: {
                BlockState below = w.getBlockState(new BlockPos(x, y - 1, z));
                return below.isOf(Blocks.SAND) || below.isOf(Blocks.RED_SAND) || below.isOf(Blocks.CACTUS);
            }
            default:
                return present(w, new BlockPos(x + 1, y, z)) || present(w, new BlockPos(x - 1, y, z))
                        || present(w, new BlockPos(x, y, z + 1)) || present(w, new BlockPos(x, y, z - 1));
        }
    }

    /**
     * Nastepny klocek do postawienia.
     *
     * @param n wysokosc stop gracza nad baseY (albo NONE gdy nieznana)
     */
    Target next(World w, Set<BlockPos> skipped, int n) {
        for (Entry e : entries) {
            BlockPos p = e.t().pos();
            if (skipped.contains(p)) continue;
            if (!missing(w, p, e.t().kind())) continue;
            if (e.need() != NONE && (n == NONE || n < e.need())) continue;
            if (!supported(w, e)) continue;
            return e.t();
        }
        return null;
    }

    /** Rodzaj klocka przewidziany przez plan na tej pozycji (null = nic / poza planem). */
    Kind plannedAt(BlockPos pos) {
        for (Entry e : entries) {
            if (e.t().pos().equals(pos)) return e.t().kind();
        }
        return null;
    }

    /** {kaktusy, piasek, doniczki} - ile jeszcze brakuje. */
    int[] remaining(World w) {
        int[] r = new int[3];
        for (Entry e : entries) {
            if (missing(w, e.t().pos(), e.t().kind())) {
                r[e.t().kind().ordinal()]++;
            }
        }
        return r;
    }

    /** Ile piasku podloza (y = baseY) jeszcze brakuje. */
    int missingBase(World w) {
        int n = 0;
        for (Entry e : entries) {
            if (e.t().pos().getY() == baseY && e.t().kind() == Kind.SAND && missing(w, e.t().pos(), Kind.SAND)) {
                n++;
            }
        }
        return n;
    }
}

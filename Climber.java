package pl.cactusbuilder;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Podbudowa na wyzsze pietro.
 *
 * Gdy budowa stanie ("Nic wiecej nie da sie postawic"), bot:
 *  1. (GOTO)  idzie - zawsze wewnatrz chunka - na pole, gdzie jest miejsce nad glowa i skad da sie potem zejsc na gore,
 *  2. (JUMP/AIR/LAND) 2-3 razy skacze i stawia piasek pod soba (slupek),
 *  3. (STEP)  wchodzi na sasiednie pole pietro wyzej (tez wewnatrz chunka),
 *  4. (BREAK) rozbija slupek od gory (zeby piasek nie spadal),
 *  5. wznawia budowe - plan sam postawi na tych polach wlasciwy wzor (kaktus / doniczka + piasek).
 */
final class Climber {
    enum S {IDLE, GOTO, JUMP, AIR, LAND, STEP, BREAK}

    private static S s = S.IDLE;
    private static int cool;
    private static int timer;
    private static int need;
    private static int baseFeet;
    private static int cx;
    private static int cz;
    private static int gx;
    private static int gy;
    private static int gz;
    private static int stepX = Integer.MIN_VALUE;
    private static int stepZ;
    private static int startN;
    private static int lastClimbFeet = Integer.MIN_VALUE;
    private static final ArrayList<BlockPos> pillar = new ArrayList<>();

    private Climber() {
    }

    static boolean active() {
        return s != S.IDLE;
    }

    static void resetSession() {
        lastClimbFeet = Integer.MIN_VALUE;
    }

    static void hardReset() {
        s = S.IDLE;
        pillar.clear();
        cool = 0;
    }

    static void cancel(MinecraftClient mc, String why) {
        keys(mc, false, false);
        CactusBuilderClient.stopWalking(mc);
        s = S.IDLE;
        pillar.clear();
        CactusBuilderClient.stalled = false;
        CactusBuilderClient.stop(mc, "Podbudowa: " + why);
    }

    private static void abort(MinecraftClient mc, String why) {
        cancel(mc, why);
        lastClimbFeet = Integer.MIN_VALUE; // pozwol sprobowac ponownie po wcisnieciu G
        cool = 60;
    }

    private static void keys(MinecraftClient mc, boolean jump, boolean attack) {
        mc.options.jumpKey.setPressed(jump);
        mc.options.attackKey.setPressed(attack);
    }

    private static boolean air(ClientWorld w, int x, int y, int z) {
        return w.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    private static boolean solid(ClientWorld w, int x, int y, int z) {
        return !w.getBlockState(new BlockPos(x, y, z)).isReplaceable();
    }

    private static void aimAt(ClientPlayerEntity p, double x, double y, double z) {
        Vec3d eye = p.getEyePos();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = h < 0.001 ? p.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4))));
        p.setYaw(yaw);
        p.setPitch(Math.max(-90.0f, Math.min(90.0f, pitch)));
    }

    /** Pole (w chunku), na ktore da sie wejsc po zbudowaniu slupka konczacego sie na wysokosci feet. */
    private static BlockPos findStep(ClientWorld w, int x, int z, int feet) {
        for (int[] d : CactusBuilderClient.DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!CactusBuilderClient.plan.contains(nx + 0.5, nz + 0.5, 0.0)) continue;
            if (!solid(w, nx, feet - 1, nz)) continue;
            if (w.getBlockState(new BlockPos(nx, feet - 1, nz)).isOf(Blocks.CACTUS)) continue;
            if (!w.getFluidState(new BlockPos(nx, feet - 1, nz)).isEmpty()) continue;
            if (!air(w, nx, feet, nz) || !air(w, nx, feet + 1, nz)) continue;
            if (d[0] != 0 && d[1] != 0) {
                if (!air(w, x + d[0], feet, z) || !air(w, x, feet, z + d[1])
                        || !air(w, x + d[0], feet + 1, z) || !air(w, x, feet + 1, z + d[1])) {
                    continue;
                }
            }
            return new BlockPos(nx, feet, nz);
        }
        return null;
    }

    /** Czy z pola c da sie zbudowac slupek wysokosci n i wejsc na gore. */
    private static boolean qualifies(ClientWorld w, BlockPos c, int n) {
        if (!CactusBuilderClient.plan.contains(c.getX() + 0.5, c.getZ() + 0.5, 0.0)) return false;
        for (int i = 1; i <= n + 2; i++) {
            if (!air(w, c.getX(), c.getY() + i, c.getZ())) return false;
        }
        return findStep(w, c.getX(), c.getZ(), c.getY() + n) != null;
    }

    /** @return true gdy Climber zajal ten tick (wtedy reszta moda nic nie robi). */
    static boolean tick(MinecraftClient mc) {
        try {
            ClientPlayerEntity p = mc.player;
            ClientWorld w = mc.world;
            if (p == null || w == null) {
                s = S.IDLE;
                return false;
            }
            if (cool > 0) cool--;
            ChunkPlan plan = CactusBuilderClient.plan;

            if (s == S.IDLE) {
                if (!CactusBuilderClient.stalled || CactusBuilderClient.state != CactusBuilderClient.State.IDLE
                        || cool > 0 || plan == null) {
                    return false;
                }
                if (!p.isOnGround()) return false;
                int n2 = p.getBlockPos().getY() - plan.baseY;
                if (n2 >= 2 * plan.maxLayers || n2 == lastClimbFeet) {
                    CactusBuilderClient.stalled = false;
                    return false;
                }
                need = n2 <= 0 ? 3 : 2;
                final int nn = need;
                BlockPos start = p.getBlockPos();
                List<BlockPos> r = CactusBuilderClient.findPath(w, start, g -> qualifies(w, g, nn), false);
                if (r == null || r.isEmpty()) {
                    abort(mc, "brak miejsca na slupek w chunku");
                    return true;
                }
                BlockPos goal = r.get(r.size() - 1);
                gx = goal.getX();
                gy = goal.getY();
                gz = goal.getZ();
                CactusBuilderClient.path.clear();
                CactusBuilderClient.path.addAll(r);
                CactusBuilderClient.pathIdx = r.get(0).equals(start) ? 1 : 0;
                CactusBuilderClient.stalled = false;
                CactusBuilderClient.status = "Podbudowa";
                lastClimbFeet = n2;
                startN = n2;
                pillar.clear();
                s = S.GOTO;
                timer = 0;
                return true;
            }

            CactusBuilderClient.status = "Podbudowa: " + s.name().toLowerCase();
            timer++;
            switch (s) {
                case GOTO: {
                    boolean moving = CactusBuilderClient.followPath(mc);
                    double dx = gx + 0.5 - p.getX();
                    double dz = gz + 0.5 - p.getZ();
                    boolean there = !moving && Math.sqrt(dx * dx + dz * dz) < 0.35 && p.isOnGround()
                            && p.getBlockPos().getY() == gy;
                    if (there) {
                        CactusBuilderClient.stopWalking(mc);
                        cx = gx;
                        cz = gz;
                        baseFeet = gy;
                        s = S.JUMP;
                        timer = 0;
                    } else if (!moving) {
                        // dotarl do ostatniego punktu - dokrec srodek pola
                        CactusBuilderClient.walkTo(mc, Vec3d.ofBottomCenter(new BlockPos(gx, gy, gz)), false);
                        if (timer > 300) abort(mc, "nie doszedl na pole podbudowy");
                    } else if (timer > 300) {
                        abort(mc, "nie doszedl na pole podbudowy");
                    }
                    return true;
                }
                case JUMP: {
                    aimAt(p, cx + 0.5, baseFeet, cz + 0.5);
                    keys(mc, true, false);
                    s = S.AIR;
                    timer = 0;
                    return true;
                }
                case AIR: {
                    keys(mc, false, false);
                    BlockPos pos = new BlockPos(cx, baseFeet, cz);
                    ChunkPlan.Target t = new ChunkPlan.Target(pos, ChunkPlan.Kind.SAND);
                    Vec3d hv = AimHelper.hitVec(w, t);
                    if (hv != null) aimAt(p, hv.x, hv.y, hv.z);
                    if (p.getY() >= baseFeet + 1.03) {
                        CactusBuilderClient.Res res = CactusBuilderClient.place(mc, t);
                        if (res == CactusBuilderClient.Res.DONE) {
                            pillar.add(pos);
                            s = S.LAND;
                            timer = 0;
                        } else if (res != CactusBuilderClient.Res.WAIT) {
                            abort(mc, "nie moge postawic klocka pod soba (" + res + ")");
                        }
                    }
                    if (s == S.AIR && timer > 30) abort(mc, "skok nie wyszedl");
                    return true;
                }
                case LAND: {
                    if (p.isOnGround() && p.getY() >= baseFeet + 0.99 && solid(w, cx, baseFeet, cz)) {
                        baseFeet++;
                        timer = 0;
                        if (--need > 0) {
                            s = S.JUMP;
                        } else {
                            s = S.STEP;
                            stepX = Integer.MIN_VALUE;
                        }
                    } else if (timer > 40) {
                        abort(mc, "nie wyladowal na klocku");
                    }
                    return true;
                }
                case STEP: {
                    if (stepX == Integer.MIN_VALUE) {
                        BlockPos st = findStep(w, cx, cz, baseFeet);
                        if (st == null) {
                            abort(mc, "brak pola, na ktore mozna wejsc na gorze");
                            return true;
                        }
                        stepX = st.getX();
                        stepZ = st.getZ();
                    }
                    Vec3d goal = new Vec3d(stepX + 0.5, baseFeet, stepZ + 0.5);
                    double dx = goal.x - p.getX();
                    double dz = goal.z - p.getZ();
                    if (Math.sqrt(dx * dx + dz * dz) < 0.2 && p.isOnGround()) {
                        CactusBuilderClient.stopWalking(mc);
                        trimPillar();
                        s = S.BREAK;
                        timer = 0;
                    } else {
                        CactusBuilderClient.walkTo(mc, goal, false);
                        if (timer > 60) abort(mc, "nie doszedl na wyzsze pietro");
                    }
                    return true;
                }
                case BREAK: {
                    if (pillar.isEmpty()) {
                        finish(mc);
                        return true;
                    }
                    BlockPos top = pillar.get(pillar.size() - 1);
                    if (w.getBlockState(top).isAir()) {
                        pillar.remove(pillar.size() - 1);
                        keys(mc, false, false);
                        timer = 0;
                        return true;
                    }
                    aimAt(p, top.getX() + 0.5, top.getY() + 0.95, top.getZ() + 0.5);
                    boolean onTarget = false;
                    HitResult hr = mc.crosshairTarget;
                    if (hr instanceof BlockHitResult) {
                        onTarget = top.equals(((BlockHitResult) hr).getBlockPos());
                    }
                    keys(mc, false, onTarget);
                    if (timer > 80) {
                        // ten klocek jest nie do rozbicia z tego miejsca (zaslania go blok, na ktorym stoimy).
                        // Dolne klocki slupka zostawiamy - nie przeszkadzaja kaktusom - i budujemy dalej.
                        pillar.clear();
                        finish(mc);
                    }
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            try {
                abort(mc, "blad " + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
            return true;
        }
    }

    /**
     * Zostawia w slupku tylko to, co trzeba rozbic: od gory w dol do najnizszego klocka niezgodnego z planem.
     * Klocki na poziomie podlogi (y <= baseY) zostaja - nie przeszkadzaja, a z gory i tak sa za daleko.
     */
    private static void trimPillar() {
        ChunkPlan plan = CactusBuilderClient.plan;
        if (plan == null) return;
        int lowest = -1;
        for (int i = 0; i < pillar.size(); i++) {
            BlockPos b = pillar.get(i);
            if (b.getY() <= plan.baseY) continue;
            if (plan.plannedAt(b) != ChunkPlan.Kind.SAND) {
                lowest = i;
                break;
            }
        }
        if (lowest < 0) {
            pillar.clear();
        } else {
            pillar.subList(0, lowest).clear();
        }
    }

    private static void finish(MinecraftClient mc) {
        keys(mc, false, false);
        CactusBuilderClient.stopWalking(mc);
        s = S.IDLE;
        cool = 100;
        pillar.clear();
        CactusBuilderClient.keepOrientation = true;
        CactusBuilderClient.start(mc);
    }
}

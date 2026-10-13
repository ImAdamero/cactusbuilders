package pl.cactusbuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Celowanie w stawiany klocek + antyzastoj (watchdog). */
final class AimHelper {
    private static BlockPos lastPos;
    private static int stuck;
    private static int bumps;
    private static int nudge;
    private static double lx;
    private static double lz;
    private static Vec3d nudgeGoal;

    private AimHelper() {
    }

    private static float wrap(float f) {
        f %= 360.0f;
        if (f >= 180.0f) f -= 360.0f;
        if (f < -180.0f) f += 360.0f;
        return f;
    }

    /** Punkt na scianie sasiada, w ktory trzeba "kliknac", zeby postawic klocek w t.pos(). */
    static Vec3d hitVec(ClientWorld w, ChunkPlan.Target t) {
        BlockPos pos = t.pos();
        for (Direction d : Direction.values()) {
            BlockPos nb = pos.offset(d);
            BlockState ns = w.getBlockState(nb);
            if (ns.isAir() || ns.isReplaceable() || !ns.getFluidState().isEmpty()) continue;
            if (t.kind().item == Items.CACTUS && ns.isOf(Blocks.FLOWER_POT)) continue;
            Direction face = d.getOpposite();
            return Vec3d.ofCenter(nb).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        }
        return null;
    }

    private static boolean watchdog(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        if (nudge > 0) {
            CactusBuilderClient.walkTo(mc, nudgeGoal, false);
            if (--nudge == 0) CactusBuilderClient.stopWalking(mc);
            return true;
        }
        boolean running = CactusBuilderClient.state == CactusBuilderClient.State.RUNNING;
        double dx = p.getX() - lx;
        double dz = p.getZ() - lz;
        lx = p.getX();
        lz = p.getZ();
        ChunkPlan.Target target = CactusBuilderClient.target;
        if (!running || target == null) {
            stuck = 0;
            lastPos = null;
            return false;
        }
        BlockPos tp = target.pos();
        if (!tp.equals(lastPos) || CactusBuilderClient.healing || Math.sqrt(dx * dx + dz * dz) > 0.02) {
            stuck = 0;
            lastPos = tp;
            return false;
        }
        if (++stuck >= 25 && stuck % 10 == 0) {
            Vec3d eye = p.getEyePos();
            double ex = tp.getX() + 0.5 - eye.x;
            double ey = tp.getY() + 0.5 - eye.y;
            double ez = tp.getZ() + 0.5 - eye.z;
            String msg = "STOI " + stuck + "t cel=" + target.kind() + tp
                    + " dist=" + String.format("%.1f", Math.sqrt(ex * ex + ey * ey + ez * ez))
                    + " idzie=" + CactusBuilderClient.walking
                    + " brakDrogi=" + CactusBuilderClient.pathFailed
                    + " cd=" + CactusBuilderClient.cooldown
                    + " kuca=" + p.isSneaking()
                    + " krokow=" + CactusBuilderClient.walkTicks;
            p.sendMessage(Text.literal(msg), true);
        }
        if (stuck < 70) return false;

        stuck = 0;
        lastPos = null;
        bumps = 0;
        CactusBuilderClient.skip(tp);
        p.sendMessage(Text.literal("ANTYZASTOJ: pomijam " + target.kind() + tp), true);

        int x = p.getBlockPos().getX();
        int y = p.getBlockPos().getY();
        int z = p.getBlockPos().getZ();
        int[][] d4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int off = (int) (System.nanoTime() & 3L);
        for (int i = 0; i < 4; i++) {
            int[] d = d4[(off + i) & 3];
            int nx = x + d[0];
            int nz = z + d[1];
            // tylko pola w chunku
            if (!CactusBuilderClient.plan.contains(nx + 0.5, nz + 0.5, 0.0)) continue;
            BlockPos here = new BlockPos(nx, y, nz);
            BlockPos under = here.down();
            if (!w.getBlockState(here).isAir() || !w.getBlockState(here.up()).isAir()) continue;
            if (w.getBlockState(under).isReplaceable() || w.getBlockState(under).isOf(Blocks.CACTUS)) continue;
            nudgeGoal = new Vec3d(nx + 0.5, y, nz + 0.5);
            nudge = 12;
            break;
        }
        return nudge > 0;
    }

    static void tick(MinecraftClient mc) {
        try {
            ClientPlayerEntity p = mc.player;
            ClientWorld w = mc.world;
            if (p == null || w == null) return;
            if (watchdog(mc, p, w)) return;
            if (CactusBuilderClient.state != CactusBuilderClient.State.RUNNING) return;
            ChunkPlan.Target target = CactusBuilderClient.target;
            if (target == null) return;
            Vec3d hv = hitVec(w, target);
            if (hv == null) return;
            Vec3d eye = p.getEyePos();
            BlockPos tp = target.pos();
            double ex = tp.getX() + 0.5 - eye.x;
            double ey = tp.getY() + 0.5 - eye.y;
            double ez = tp.getZ() + 0.5 - eye.z;
            if (Math.sqrt(ex * ex + ey * ey + ez * ez) > CactusBuilderClient.REACH) return;
            // Podczas chodzenia nie celujemy: inaczej kamera wraca na cel i bot nie moze odejsc (cofnac sie) z pola celu.
            if (CactusBuilderClient.walking) return;

            double dx = hv.x - eye.x;
            double dy = hv.y - eye.y;
            double dz = hv.z - eye.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            float yaw = h < 0.3 ? p.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.max(h, 1.0E-4))));
            pitch = Math.max(-90.0f, Math.min(90.0f, pitch));
            float curYaw = p.getYaw();
            float curPitch = p.getPitch();
            float dyaw = wrap(yaw - curYaw);
            float dpitch = pitch - curPitch;
            if (Math.abs(dyaw) > 2.0f || Math.abs(dpitch) > 2.0f) {
                p.setYaw(curYaw + dyaw);
                p.setPitch(pitch);
                if (bumps < 5) {
                    bumps++;
                    if (CactusBuilderClient.cooldown < 2) CactusBuilderClient.cooldown = 2;
                }
            } else {
                bumps = 0;
            }
        } catch (Throwable ignored) {
        }
    }
}

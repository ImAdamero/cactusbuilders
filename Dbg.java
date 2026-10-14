package pl.cactusbuilder;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;

/** Prosty log do pliku .minecraft/cactusbuilder-debug.log (do diagnozy, gdy bot sie zatnie). */
final class Dbg {
    private static Path file;
    private static boolean init;

    private Dbg() {
    }

    static void log(String msg) {
        try {
            if (!init) {
                init = true;
                file = FabricLoader.getInstance().getGameDir().resolve("cactusbuilder-debug.log");
                if (Files.exists(file) && Files.size(file) > 1_500_000L) Files.delete(file);
                Files.writeString(file, "---- start " + LocalTime.now().withNano(0) + " ----\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(file, LocalTime.now().withNano(0) + " " + msg + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }
}

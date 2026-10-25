package com.theecho;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vigila la carpeta del mundo condenado. Cuando el jugador la borra desde "saves", crea un archivo
 * "gracias.txt" en su escritorio. El mod NUNCA borra nada: solo escribe ese archivo, una vez.
 * La lista de mundos vigilados se guarda en config/the_echo_pending.txt, así funciona aunque cierres el juego.
 */
public final class ThanksWatcher {
    private static final Set<Path> WATCHED = new LinkedHashSet<>();
    private static Thread thread;

    private ThanksWatcher() {}

    private static Path pendingFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("the_echo_pending.txt");
    }

    /** Al iniciar el juego: retoma los mundos pendientes y entrega el archivo si alguno ya fue borrado. */
    public static synchronized void startup() {
        try {
            Path f = pendingFile();
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) WATCHED.add(Path.of(line.trim()));
                }
            }
        } catch (IOException | RuntimeException e) {
            TheEcho.LOGGER.warn("No se pudo leer la lista de mundos vigilados", e);
        }
        checkAll();
        if (!WATCHED.isEmpty()) ensureThread();
    }

    /** El mundo quedó condenado: empieza a vigilar su carpeta. */
    public static synchronized void arm(Path worldRoot) {
        if (!EchoConfig.INSTANCE.writeThanksFile) return;
        Path p = worldRoot.toAbsolutePath().normalize();
        if (WATCHED.add(p)) save();
        ensureThread();
    }

    private static void ensureThread() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (ThanksWatcher.class) {
                    checkAll();
                    if (WATCHED.isEmpty()) return;
                }
            }
        }, "the_echo-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private static void checkAll() {
        List<Path> gone = new ArrayList<>();
        for (Path p : WATCHED) if (!Files.exists(p)) gone.add(p);
        if (gone.isEmpty()) return;
        for (Path p : gone) {
            WATCHED.remove(p);
            writeThanks();
        }
        save();
    }

    private static void save() {
        try {
            List<String> lines = new ArrayList<>();
            for (Path p : WATCHED) lines.add(p.toString());
            Files.write(pendingFile(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            TheEcho.LOGGER.warn("No se pudo guardar la lista de mundos vigilados", e);
        }
    }

    private static void writeThanks() {
        try {
            Path desktop = findDesktop();
            Path out = desktop.resolve("gracias.txt");
            int n = 1;
            while (Files.exists(out)) out = desktop.resolve("gracias (" + (n++) + ").txt"); // nunca pisa nada
            Files.writeString(out, "gracias" + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            TheEcho.LOGGER.warn("No se pudo crear gracias.txt", e);
        }
    }

    private static Path findDesktop() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> candidates = new ArrayList<>();
        String oneDrive = System.getenv("OneDrive");
        if (oneDrive != null) {
            candidates.add(Path.of(oneDrive, "Desktop"));
            candidates.add(Path.of(oneDrive, "Escritorio"));
        }
        candidates.add(home.resolve("Desktop"));
        candidates.add(home.resolve("Escritorio"));
        candidates.add(home.resolve("OneDrive").resolve("Desktop"));
        candidates.add(home.resolve("OneDrive").resolve("Escritorio"));
        for (Path c : candidates) if (Files.isDirectory(c)) return c;
        return home;
    }
}

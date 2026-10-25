package com.theecho;

import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;

/** Tareas retrasadas por ticks del servidor. */
public final class Sched {
    private record Task(int tick, Runnable action) {}

    private static final List<Task> TASKS = new ArrayList<>();

    private Sched() {}

    public static void at(MinecraftServer server, int delayTicks, Runnable r) {
        TASKS.add(new Task(server.getTicks() + Math.max(0, delayTicks), r));
    }

    public static void run(MinecraftServer server) {
        if (TASKS.isEmpty()) return;
        int t = server.getTicks();
        List<Task> due = new ArrayList<>();
        TASKS.removeIf(k -> {
            if (k.tick() <= t) { due.add(k); return true; }
            return false;
        });
        for (Task k : due) {
            try {
                k.action().run();
            } catch (Exception e) {
                TheEcho.LOGGER.warn("Tarea retrasada falló", e);
            }
        }
    }

    public static void clear() {
        TASKS.clear();
    }
}

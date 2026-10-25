package com.theecho;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Comandos de depuración (requieren permiso nivel 2). */
public final class EchoCommands {
    private EchoCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("echo")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.literal("status").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayer();
                            ctx.getSource().sendFeedback(() -> Text.literal(EchoManager.status(p)), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("dread")
                                .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0, 1000))
                                        .executes(ctx -> {
                                            double v = DoubleArgumentType.getDouble(ctx, "value");
                                            EchoManager.setDread(ctx.getSource().getPlayer(), v);
                                            ctx.getSource().sendFeedback(() -> Text.literal("Miedo = " + v), false);
                                            return 1;
                                        })))
                        .then(CommandManager.literal("paranoia")
                                .then(CommandManager.argument("value", DoubleArgumentType.doubleArg(0, 100))
                                        .executes(ctx -> {
                                            double v = DoubleArgumentType.getDouble(ctx, "value");
                                            EchoManager.setParanoia(ctx.getSource().getPlayer(), v);
                                            ctx.getSource().sendFeedback(() -> Text.literal("Paranoia = " + v), false);
                                            return 1;
                                        })))
                        .then(CommandManager.literal("day")
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> {
                                            int n = IntegerArgumentType.getInteger(ctx, "n");
                                            EchoManager.setDay(ctx.getSource().getPlayer(), n);
                                            ctx.getSource().sendFeedback(() -> Text.literal("Día del Eco = " + n), false);
                                            return 1;
                                        })))
                        // /echo spawn [observa|reojo|sombra|camina|corre|grita|vuela|lanza|registrador]
                        .then(CommandManager.literal("spawn")
                                .executes(ctx -> spawn(ctx.getSource().getPlayer(), "observa", ctx.getSource()))
                                .then(CommandManager.argument("tipo", StringArgumentType.word())
                                        .suggests((c, b) -> {
                                            for (String s : new String[]{"observa", "reojo", "sombra", "camina", "corre", "grita", "vuela", "lanza", "registrador"})
                                                b.suggest(s);
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> spawn(ctx.getSource().getPlayer(),
                                                StringArgumentType.getString(ctx, "tipo"), ctx.getSource()))))
                        .then(CommandManager.literal("chat")
                                .then(CommandManager.argument("nivel", IntegerArgumentType.integer(0, 4))
                                        .executes(ctx -> {
                                            EchoManager.forceChat(ctx.getSource().getPlayer(), IntegerArgumentType.getInteger(ctx, "nivel"));
                                            return 1;
                                        })))
                        .then(CommandManager.literal("intro").executes(ctx -> {
                            EchoManager.intro(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        // /echo ruina        -> la siguiente estructura de la cadena, cerca de ti
                        // /echo ruina 1..5   -> ese tipo suelto (1 cabaña ... 5 tumba)
                        .then(CommandManager.literal("ruina")
                                .executes(ctx -> ruina(ctx.getSource(), null))
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(1, 5))
                                        .executes(ctx -> ruina(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "n")))))
                        .then(CommandManager.literal("apocalipsis").executes(ctx -> {
                            EchoManager.triggerApocalypse(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        .then(CommandManager.literal("sitios").executes(ctx -> {
                            EchoManager.listSites(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        .then(CommandManager.literal("decadencia").executes(ctx -> {
                            EchoManager.forceDecay(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        .then(CommandManager.literal("liberar").executes(ctx -> {
                            EchoManager.free(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        .then(CommandManager.literal("hijack").executes(ctx -> {
                            EchoManager.forceHijack(ctx.getSource().getPlayer());
                            return 1;
                        }))
                        .then(CommandManager.literal("escape").executes(ctx -> {
                            EchoManager.leaveRealm(ctx.getSource().getPlayer(), false);
                            return 1;
                        }))
                        .then(CommandManager.literal("reset").executes(ctx -> {
                            EchoManager.reset(ctx.getSource().getPlayer());
                            ctx.getSource().sendFeedback(() -> Text.literal("Estado del Eco reiniciado."), false);
                            return 1;
                        }))));
    }

    private static int ruina(net.minecraft.server.command.ServerCommandSource src, Integer n) {
        boolean ok = EchoManager.forceShrine(src.getPlayer(), n);
        if (!ok) src.sendFeedback(() -> Text.literal("No hay terreno llano cerca."), false);
        return ok ? 1 : 0;
    }

    private static int spawn(ServerPlayerEntity p, String tipo, net.minecraft.server.command.ServerCommandSource src) {
        boolean ok = EchoManager.debugSpawn(p, tipo);
        src.sendFeedback(() -> Text.literal(ok ? "Eco (" + tipo + ")." : "No hay sitio para ese Eco aquí."), false);
        return ok ? 1 : 0;
    }
}

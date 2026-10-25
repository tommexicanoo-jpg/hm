package com.theecho.client;

import com.theecho.ModBlocks;
import com.theecho.ModEntities;
import com.theecho.net.DreadPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.Random;

public class TheEchoClient implements ClientModInitializer {

    private static final Random R = new Random();
    private static final String[] WHISPERS = {"NO MIRES", "ATRÁS", "TE VEO", "SIGO AQUÍ", "NO ESTÁS SOLO", "CORRE", "MÍRAME"};

    private static final Text BAR_LABEL = Text.literal("MIEDO / PARANOIA");
    private static Text dayText = Text.literal("DÍA 1");
    private static int dayTextFor = 1;
    private static int timer = -1; // segundos del apocalipsis (-1 = oculto)
    private static boolean hud = false;
    private static int targetDread = 0, targetPar = 0, dayShown = 1, stage = 0;
    private static float shown = 0f, shownPar = 0f, sepia = 0f;
    private static int flash = 0, flashMax = 1, flashKind = 1;
    private static int heartbeat = 0, breath = 0;
    private static int eyes = 0;
    private static float eyeX = 0.5f, eyeY = 0.4f;
    private static int whisper = 0, whisperIdx = 0;
    private static float whisperX = 0.1f, whisperY = 0.1f;
    private static int glitch = 0;
    private static int blackout = 0;

    // Título de bienvenida: "Día 1 de ▒▒▒▒" con letras que cambian sin parar
    private static final int INTRO_LEN = 170;
    private static final String GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#%&@$?!/<>[]{}=+*~^";
    private static int introTicks = 0;
    private static String introScramble = "?????????";

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.ECHO, EchoEntityRenderer::new);
        // El espejo tiene transparencia: sin esto se ve con fondo negro
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ECHO_MIRROR, RenderLayer.getTranslucent());

        ClientPlayNetworking.registerGlobalReceiver(com.theecho.net.TimerPayload.ID, (payload, context) -> timer = payload.seconds());
        ClientPlayNetworking.registerGlobalReceiver(DreadPayload.ID, (payload, context) -> {
            if (payload.hud() >= 0) hud = payload.hud() == 1;
            if (payload.dread() >= 0) targetDread = Math.min(100, payload.dread());
            if (payload.paranoia() >= 0) targetPar = Math.min(100, payload.paranoia());
            if (payload.day() >= 0) dayShown = Math.max(1, payload.day());
            if (payload.stage() >= 0) stage = payload.stage();
            if (payload.scare() == 3) {
                introTicks = INTRO_LEN;
                var mcc = MinecraftClient.getInstance();
                mcc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_BELL_USE, 0.6f, 1.0f));
            } else if (payload.scare() > 0) {
                flashKind = payload.scare();
                flashMax = payload.scare() >= 2 ? 34 : 14;
                flash = flashMax;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity pl = client.player;
            if (pl == null) {
                timer = -1; hud = false; targetDread = 0; targetPar = 0; shown = 0f; shownPar = 0f; flash = 0; stage = 0; sepia = 0f;
                return;
            }
            if (client.isPaused()) return;
            if (introTicks > 0) {
                introTicks--;
                if (introTicks % 2 == 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 9; i++) sb.append(GLYPHS.charAt(R.nextInt(GLYPHS.length())));
                    introScramble = sb.toString();
                }
            }
            if (stage >= 3) {
                if (blackout > 0) blackout--;
                else if (R.nextInt(45) == 0) blackout = 2 + R.nextInt(3);
            }
            shown += (targetDread - shown) * 0.04f;
            shownPar += (targetPar - shownPar) * 0.04f;
            float sepiaTarget = stage >= 3 ? 0.5f : stage == 2 ? 0.33f : stage == 1 ? 0.07f : 0f;
            sepia += (sepiaTarget - sepia) * 0.01f;
            if (flash > 0) flash--;

            float intensity = Math.max(shown, shownPar * 0.8f);

            // La cámara tiembla cuando el miedo es alto
            if (shown > 30f && R.nextInt(1000) < shown / 3f) {
                float pitch = pl.getPitch() + (R.nextFloat() - 0.5f) * shown * 0.04f;
                pl.setPitch(Math.max(-90f, Math.min(90f, pitch)));
                pl.setYaw(pl.getYaw() + (R.nextFloat() - 0.5f) * shown * 0.07f);
            }
            // Latido que se acelera
            if (intensity > 35f) {
                if (--heartbeat <= 0) {
                    float vol = 0.55f + intensity / 160f;
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, vol));
                    heartbeat = (int) (82 - intensity * 0.6f);
                }
            } else {
                heartbeat = 0;
            }
            // Respiración agitada
            if (shown > 65f) {
                if (--breath <= 0) {
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_PLAYER_BREATH, 0.8f, 0.6f));
                    breath = 90 + R.nextInt(60);
                }
            }
            // Ojos que parpadean en los bordes de la pantalla
            if (eyes > 0) {
                eyes--;
            } else if (intensity > 40f && R.nextInt(600) < intensity / 9f) {
                eyes = 6 + R.nextInt(8);
                eyeX = R.nextBoolean() ? 0.05f + R.nextFloat() * 0.2f : 0.75f + R.nextFloat() * 0.2f;
                eyeY = 0.15f + R.nextFloat() * 0.6f;
            }
            // Palabras que casi no se ven
            if (whisper > 0) {
                whisper--;
            } else if (intensity > 55f && R.nextInt(900) < intensity / 12f) {
                whisper = 8 + R.nextInt(10);
                whisperIdx = R.nextInt(WHISPERS.length);
                whisperX = 0.05f + R.nextFloat() * 0.7f;
                whisperY = 0.1f + R.nextFloat() * 0.7f;
            }
            // Fallos de imagen
            if (glitch > 0) glitch--;
            else if (shown > 50f && R.nextInt(700) < shown / 14f) glitch = 3 + R.nextInt(4);
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || mc.options.hudHidden) return;
            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            TextRenderer tr = mc.textRenderer;
            float s = Math.max(shown, shownPar * 0.7f);

            // Cielo gris sepia perpetuo (corrupción)
            if (sepia > 0.01f) {
                int a = (int) (255 * Math.min(0.6f, sepia));
                ctx.fill(0, 0, w, h, (a << 24) | 0x8A6F4A);
            }

            // Apocalipsis (día 5): rojo oscuro, apagones y parpadeo constante
            if (stage >= 3) {
                float fl = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 140.0);
                ctx.fill(0, 0, w, h, ((int) (70 * fl) << 24) | 0x480000);
                if (blackout > 0) ctx.fill(0, 0, w, h, 0xDD000000);
            }

            if (s > 8f) {
                // Viñeta negra que se cierra con el miedo
                float a = Math.min(0.9f, s / 100f * 0.9f);
                int steps = 10, tw = Math.max(1, w / 60), th = Math.max(1, h / 40);
                for (int i = 0; i < steps; i++) {
                    float k = 1f - i / (float) steps;
                    int col = ((int) (255 * a * k * k)) << 24;
                    ctx.fill(i * tw, 0, (i + 1) * tw, h, col);
                    ctx.fill(w - (i + 1) * tw, 0, w - i * tw, h, col);
                    ctx.fill(0, i * th, w, (i + 1) * th, col);
                    ctx.fill(0, h - (i + 1) * th, w, h - i * th, col);
                }
                // Pulso rojo al ritmo del corazón
                double period = Math.max(240.0, 800.0 - s * 5.5);
                float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / period * Math.PI));
                int ra = (int) (255 * 0.2f * (s / 100f) * pulse);
                ctx.fill(0, 0, w, h, (ra << 24) | 0x00600000);
            }
            // Estática en pantalla con mucho miedo
            if (shown > 60f) {
                int n = (int) ((shown - 55f) * 0.9f);
                for (int i = 0; i < n; i++) {
                    int x = R.nextInt(w), y = R.nextInt(h);
                    int g = 40 + R.nextInt(120);
                    ctx.fill(x, y, x + 1 + R.nextInt(3), y + 1, (60 << 24) | (g << 16) | (g << 8) | g);
                }
            }
            if (glitch > 0) {
                int y = R.nextInt(h);
                ctx.fill(0, y, w, y + 2 + R.nextInt(6), 0x55000000 | (R.nextInt(0x1000000) & 0x00FFFFFF));
            }
            if (eyes > 0) {
                int cx = (int) (w * eyeX), cy = (int) (h * eyeY);
                ctx.fill(cx - 14, cy, cx - 6, cy + 3, 0xFFFFFFFF);
                ctx.fill(cx + 6, cy, cx + 14, cy + 3, 0xFFFFFFFF);
            }
            if (whisper > 0) {
                ctx.drawText(tr, Text.literal(WHISPERS[whisperIdx]), (int) (w * whisperX), (int) (h * whisperY), 0x66B0B0B0, false);
            }

            // ---- Cuenta atrás del apocalipsis (arriba al centro, en rojo) ----
            if (timer >= 0) {
                String tt = String.format("%d:%02d", timer / 60, timer % 60);
                boolean blink = timer <= 10 && (System.currentTimeMillis() / 250) % 2 == 0;
                Text tx = Text.literal(tt);
                var mt = ctx.getMatrices();
                mt.push();
                mt.translate(w / 2f, 8, 0);
                mt.scale(2.5f, 2.5f, 1f);
                ctx.drawTextWithShadow(tr, tx, -tr.getWidth(tx) / 2, 0, blink ? 0xFFFFFFFF : 0xFFFF1A1A);
                mt.pop();
            }

            // ---- Contador de días (arriba a la derecha) ----
            if (hud) {
                float t = Math.min(1f, (dayShown - 1) / 18f);
                int r = 255, g = (int) (255 * (1f - t * 0.85f)), b = (int) (255 * (1f - t * 0.85f));
                int col = 0xFF000000 | (r << 16) | (g << 8) | b;
                if (stage >= 2) col = 0xFFB89868;
                if (dayShown != dayTextFor) { dayTextFor = dayShown; dayText = Text.literal("DÍA " + dayShown); }
                Text day = dayText;
                var ms = ctx.getMatrices();
                ms.push();
                float jx = shown > 70f && R.nextInt(8) == 0 ? (R.nextFloat() - 0.5f) * 3f : 0f;
                ms.translate(w - 8 + jx, 6, 0);
                ms.scale(1.5f, 1.5f, 1f);
                ctx.drawTextWithShadow(tr, day, -tr.getWidth(day), 0, col);
                ms.pop();

                // ---- Barra de miedo y paranoia (encima de la barra de vida) ----
                int bx = w / 2 - 91, by = h - 64;
                ctx.fill(bx - 1, by - 1, bx + 82, by + 10, 0x99000000);
                int fw = (int) (81 * Math.min(100f, shown) / 100f);
                int pw = (int) (81 * Math.min(100f, shownPar) / 100f);
                float flick = shown > 70f ? 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 90.0) : 1f;
                int red = (int) (190 * flick);
                ctx.fill(bx, by, bx + fw, by + 4, 0xFF000000 | (red << 16) | 0x101010);
                ctx.fill(bx, by + 5, bx + pw, by + 9, 0xFF7A3FD0);
                ctx.drawText(tr, BAR_LABEL, bx, by - 10, 0xFFA0A0A0, true);
            }

            // Título de bienvenida en el centro de la pantalla
            if (introTicks > 0) {
                float fade = Math.min(1f, Math.min(introTicks, INTRO_LEN - introTicks) / 20f);
                int a = (int) (255 * fade);
                if (a >= 8) {
                    Text title = Text.literal("Día " + dayShown + " de " + introScramble);
                    var ms2 = ctx.getMatrices();
                    ms2.push();
                    ms2.translate(w / 2f, h / 2f - 20, 0);
                    ms2.scale(3f, 3f, 1f);
                    ctx.drawTextWithShadow(tr, title, -tr.getWidth(title) / 2, 0, (a << 24) | 0xFFFFFF);
                    ms2.pop();
                }
            }

            if (flash > 0) {
                float f = flash / (float) flashMax;
                if (f > 0.8f) {
                    ctx.fill(0, 0, w, h, 0xE6FFFFFF);
                } else {
                    int al = (int) (255 * Math.min(1f, f * 1.4f));
                    ctx.fill(0, 0, w, h, al << 24);
                    int ew = w / 9, eh = h / 16;
                    int white = ((int) (255 * Math.min(1f, f * 1.6f)) << 24) | 0xFFFFFF;
                    ctx.fill(w * 36 / 100, h * 42 / 100, w * 36 / 100 + ew, h * 42 / 100 + eh, white);
                    ctx.fill(w * 64 / 100 - ew, h * 42 / 100, w * 64 / 100, h * 42 / 100 + eh, white);
                    if (flashKind >= 2) { // la boca: una línea larga y dentada
                        int my = h * 62 / 100, mx0 = w * 34 / 100, mx1 = w * 66 / 100;
                        ctx.fill(mx0, my, mx1, my + h / 22, white);
                        for (int x = mx0; x < mx1; x += Math.max(4, w / 40)) {
                            ctx.fill(x, my - h / 40, x + Math.max(2, w / 80), my, white);
                            ctx.fill(x + Math.max(2, w / 80), my + h / 22, x + Math.max(4, w / 40), my + h / 22 + h / 40, white);
                        }
                    }
                }
            }
        });
    }
}

package com.theecho;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Se guarda en config/the_echo.json.
 * Los "Day" son DÍAS DEL ECO (el contador de la esquina superior derecha, empezando en 0),
 * no días del mundo: así un mundo viejo no empieza con el terror ya a tope, y las ofrendas al espejo los reducen.
 * Los "Dread" son umbrales de MIEDO (0-100) a partir de los cuales aparece cada cosa.
 */
public final class EchoConfig {
    public int configVersion = 5;

    // --- Arco de 5 días. 0 = DÍA 1 en pantalla, 4 = DÍA 5 (el final) ---
    public int chatStartDay = 0;       // DÍA 1: mensajes amables que ya insinúan borrar el mundo
    public int audioStartDay = 1;      // pasos / puertas / cofres falsos
    public int registrarDay = 2;       // aparece El Registrador
    public int decayStartDay = 2;      // la Decadencia Silenciosa empieza a revertir tus bloques
    public int fakeBlockDay = 2;       // "aldeano" que en realidad es lana
    public int stareAnimalsDay = 2;    // aldeanos/animales que te miran fijamente
    public int mimicChopDay = 2;       // un Eco imita que tala un árbol
    public int interfaceDay = 2;       // carteles que mutan, antorchas que parpadean
    public int corruptionDay = 4;      // DÍA 5: cielo sepia, todo se corrompe
    public int endingDay = 4;          // DÍA 5: el apocalipsis y el final

    // --- Ecos: aparecen según el MIEDO, no según el día ---
    public int echoDreadThreshold = 15;   // con menos miedo que esto no aparece ningún Eco
    public double echoDreadStep = 14.0;   // cada tanto miedo extra se permite un Eco más a la vez
    public int maxEchoes = 6;             // tope de Ecos simultáneos (fuera del día 5)
    public int walkDread = 25;            // miedo a partir del cual hay Ecos que caminan hacia ti
    public int rushDread = 35;            // ...que corren y desaparecen
    public int shadowDread = 40;          // ...de skin negra
    public int screamDread = 50;          // ...que gritan
    public int flyDread = 60;             // ...que vuelan

    // --- el final ---
    public boolean corruptionEnding = true;   // al terminar el apocalipsis, te echa y debes borrar el mundo
    public int apocalypseSeconds = 120;       // cuánto dura el caos del día 5 antes de echarte (2 minutos)
    public boolean writeThanksFile = true;    // al borrar la carpeta del mundo, crea gracias.txt en el escritorio

    // --- dormir y el laberinto ---
    public boolean enableRealm = true;
    public double hijackChanceBase = 0.20;     // probabilidad de acabar en el laberinto cada vez que duermes (día 1)
    public double hijackChancePerDay = 0.20;   // ...y sube esto por cada día que pasa (día 2 = 40 %, ... día 5 = 100 %)

    // --- sistema de terreno ---
    public boolean enableDecay = true;
    public double decayGraceDays = 0.5;   // días del mundo antes de que un bloque tuyo pueda revertirse
    public int riteTolerance = 6;         // bloques "tuyos" que pueden quedar y aun así completar el Rito

    // --- miedo / espejo ---
    public double eventIntensity = 1.0;   // multiplicador global de frecuencia de eventos
    public double dreadPerMinute = 5.0;
    public double offeringDreadReduction = 30.0;   // tope por ofrenda: nunca baja todo de golpe
    public double offeringParanoiaReduction = 30.0;
    public int offeringDayRelief = 1;     // cuántos días baja el contador al ofrendar
    public int offeringBanishDays = 1;
    public int maxBanishDays = 5;
    public int mirrorRadius = 24;
    public double mirrorGazeRelief = 8.0;     // mirar el espejo con la mano vacía baja un poco el miedo y la paranoia
    public int mirrorGazeCooldownSeconds = 45;

    // --- actividades que calman un poquito ---
    public double eatDreadRelief = 2.0;       // por comer algo
    public double eatParanoiaRelief = 1.5;
    public double chopDreadRelief = 0.8;      // por cada tronco que talas
    public double chopParanoiaRelief = 0.6;
    public double farmDreadRelief = 0.5;      // por cada cultivo maduro que cosechas
    public double farmParanoiaRelief = 0.4;

    // --- estructuras con el diario de Elías ---
    public int shrineStartMin = 110;      // la primera aparece a esta distancia (mín-máx) del spawn del mundo
    public int shrineStartMax = 160;
    public int shrineLinkMin = 90;        // cada una nueva aparece a esta distancia de la anterior
    public int shrineLinkMax = 140;

    public int mazeSize = 7;              // celdas por lado del laberinto (5 a 11); los pasillos miden 3 de ancho
    public boolean dayTitle = true;       // el título "Día N de ▒▒▒▒" al empezar cada día

    public static EchoConfig INSTANCE = new EchoConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("the_echo.json");
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    EchoConfig c = GSON.fromJson(r, EchoConfig.class);
                    // Una config anterior usaba otros valores: se descarta para usar los nuevos
                    if (c != null && c.configVersion >= 5) INSTANCE = c;
                }
            }
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(INSTANCE, w);
            }
        } catch (IOException | RuntimeException e) {
            TheEcho.LOGGER.error("No se pudo leer/escribir la config del Eco", e);
        }
    }
}

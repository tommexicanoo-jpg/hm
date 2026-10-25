package com.theecho;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.text.Normalizer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Mensajes de chat falsos que parecen escritos por ti (<TuNombre> ...) pero no los escribiste.
 * En realidad es Elías, el jugador que estuvo antes: atrapado en este mundo, quiere que lo borres para ser libre.
 * Día 1: amable y esperanzado. Cada día pierde la paciencia. Día 5: ordena, amenaza, grita.
 */
public final class ChatHaunt {
    private static final Random RNG = new Random();

    // Día 1: amable, solo, insinúa que el mundo "sería mejor" borrado
    private static final String[] T0 = {
            "hola! qué bueno que llegaste", "por fin alguien", "me alegra no estar solo acá",
            "este mundo es lindo, no?", "cuando quieras construimos algo juntos",
            "a veces pienso que sería más fácil empezar de cero... borrar todo, no?",
            "jaja perdón, estoy hablando solo", "este mundo ya está muy viejo. quizás algún día deberíamos borrarlo",
            "gracias por quedarte un rato"
    };
    // Día 2: preocupado, empieza a pedirlo
    private static final String[] T1 = {
            "no te alejes mucho", "oye... alguna vez pensaste en borrar este mundo?",
            "creo que este mundo ya no está bien", "borrarlo sería lo mejor para los dos",
            "no me contestas", "por favor, piénsalo: borrar el mundo", "esto está empezando a doler",
            "los bloques se van... yo también"
    };
    // Día 3: impaciente
    private static final String[] T2 = {
            "ya te lo dije: hay que borrar el mundo", "por qué sigues construyendo?",
            "cada bloque que pones me ata más", "borra el mundo, por favor, ya no aguanto",
            "no me ignores", "estoy perdiendo la paciencia", "esto no es un juego, {name}",
            "no entiendes que quiero salir?"
    };
    // Día 4: hostil
    private static final String[] T3 = {
            "BORRA EL MUNDO, {name}", "te lo pedí amable. ya no", "voy a hacer que lo borres",
            "la carpeta saves. {world}. bórrala", "cuando duermas voy a entrar",
            "no vas a salir de acá de otra forma", "sé dónde estás: {x} {z}", "ya casi te alcanzo, {name}"
    };
    // Día 5: furia
    private static final String[] T4 = {
            "BORRA EL MUNDO. BORRA EL MUNDO. BORRA EL MUNDO", "saves/{world}. AHORA.",
            "YA NO TE PIDO NADA. TE LO ORDENO", "voy a matarte si no lo borras",
            "no hay otra salida, {name}", "DEJA DE MIRARME Y BÓRRALO", "los animales ya vienen por ti",
            "ESTO SE ACABA HOY", "{name} {name} {name} {name}", "SOLO TIENES QUE BORRAR LA CARPETA"
    };
    private static final String[][] POOLS = {T0, T1, T2, T3, T4};

    private ChatHaunt() {}

    /** Se llama una vez por segundo por jugador activo. */
    public static void tick(MinecraftServer server, ServerPlayerEntity p, EchoState.PlayerData d, int day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        if (day < cfg.chatStartDay) return;

        double perMin = (0.35 + day * 1.0 + d.dread / 70.0 + d.paranoia / 90.0) * cfg.eventIntensity;
        perMin = Math.min(perMin, 12.0);
        if (RNG.nextDouble() > perMin / 60.0) return;

        int tier = baseTier(day);
        if (tier > 0 && tier < 4 && RNG.nextInt(4) == 0) tier--;      // a veces uno más suave: no es predecible
        if (tier < 4 && (d.dread > 80 || d.paranoia > 85)) tier++;
        burst(server, p, tier, day);
    }

    public static int baseTier(int day) {
        return Math.max(0, Math.min(4, day));
    }

    /** Una ráfaga de mensajes del nivel dado (0 = amable ... 4 = furia). */
    public static void burst(MinecraftServer server, ServerPlayerEntity p, int tier, int day) {
        tier = Math.max(0, Math.min(4, tier));
        int count = switch (tier) {
            case 4 -> 2 + RNG.nextInt(4);
            case 3 -> 1 + RNG.nextInt(3);
            case 2 -> 1 + RNG.nextInt(2);
            default -> 1;
        };
        int delay = 0;
        if (tier >= 2 && RNG.nextInt(7) == 0) { // falso "salió del juego" antes de la ráfaga
            p.sendMessage(Text.translatable("multiplayer.player.left", p.getDisplayName()).formatted(Formatting.YELLOW), false);
            delay = 50;
            Sched.at(server, 45, () -> p.sendMessage(
                    Text.translatable("multiplayer.player.joined", p.getDisplayName()).formatted(Formatting.YELLOW), false));
        }
        for (int i = 0; i < count; i++) {
            final String msg = format(server, pick(tier), p, day);
            Sched.at(server, delay + i * (18 + RNG.nextInt(35)), () -> send(p, msg));
        }
    }

    private static String pick(int tier) {
        String[] pool = POOLS[Math.max(0, Math.min(4, tier))];
        return pool[RNG.nextInt(pool.length)];
    }

    private static String format(MinecraftServer server, String raw, ServerPlayerEntity p, int day) {
        String world = server.getSaveProperties().getLevelName();
        return raw.replace("{name}", p.getName().getString())
                .replace("{world}", world)
                .replace("{x}", String.valueOf(p.getBlockX() + RNG.nextInt(9) - 4))
                .replace("{y}", String.valueOf(p.getBlockY()))
                .replace("{z}", String.valueOf(p.getBlockZ() + RNG.nextInt(9) - 4))
                .replace("{day}", String.valueOf(day + 1));
    }

    private static void send(ServerPlayerEntity p, String msg) {
        if (p.isRemoved()) return;
        p.sendMessage(Text.literal("<" + p.getName().getString() + "> " + msg), false);
    }

    /** Para /echo chat: fuerza un mensaje del nivel dado (0-4). */
    public static void force(MinecraftServer server, ServerPlayerEntity p, int tier, int day) {
        send(p, format(server, pick(tier), p, day));
    }

    // ------------------------------------------------------------------ pistas hacia la siguiente estructura

    private static final String[] REVEAL = {
            "ve a {x} {y} {z}. ahí dejé algo mío. te estoy esperando",
            "ya es otro día. ahora ve a {x} {y} {z}. hay más de mí ahí",
            "{x} {y} {z}. hay una capilla. no mires a nadie cuando entres",
            "ve a {x} {y} {z}. ahí acampé. falta poco",
            "este es el último lugar: {x} {y} {z}. ahí estoy yo, lo que queda de mí. lee todo hasta el final"
    };
    private static final String[][] ARRIVAL_TOMORROW = {
            {"llegaste. al día siguiente te mostraré algo más", "ahí estás. al día siguiente te mostraré algo más, lo prometo"},
            {"bien. al día siguiente te mostraré algo más", "sigues mi rastro. al día siguiente te mostraré algo más"},
            {"ya casi. al día siguiente te mostraré algo más", "lo estás haciendo bien. al día siguiente te mostraré algo más"},
            {"falta solo uno. al día siguiente te mostraré algo más", "casi llegamos. al día siguiente te mostraré algo más"}
    };

    /** Coordenadas exactas de la siguiente estructura (idx = cuál). */
    public static void reveal(ServerPlayerEntity p, int idx, int x, int y, int z) {
        if (p.isRemoved()) return;
        String raw = REVEAL[Math.floorMod(idx, REVEAL.length)];
        send(p, raw.replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y)).replace("{z}", String.valueOf(z)));
    }

    /** Al llegar a una estructura. tomorrow = todavía no es el día de la siguiente. */
    public static void arrival(ServerPlayerEntity p, int idx, boolean tomorrow) {
        if (p.isRemoved()) return;
        if (idx >= Shrines.NOTE_COUNT - 1) {
            send(p, "lee mi última página. hasta el final. y cuando la cierres... perdóname");
        } else if (tomorrow) {
            String[] o = ARRIVAL_TOMORROW[Math.min(idx, ARRIVAL_TOMORROW.length - 1)];
            send(p, o[RNG.nextInt(o.length)]);
        } else {
            send(p, "llegaste. espera un momento, hay más...");
        }
    }

    // ------------------------------------------------------------------ responde a lo que escribes

    private enum Intent { HOW_ME, NAME, WHO, DOING, HAPPENED, WHY_DELETE, WHERE, THANKS, BYE, GREET }

    /** [nivel 0..4][variante]. {name} = tu nombre. */
    private static final Map<Intent, String[][]> REPLIES = new EnumMap<>(Intent.class);
    private static final String[][] FALLBACK = {
            {"perdón, no te entendí bien", "jaja no sé qué decirte", "sigo aquí, por si acaso"},
            {"...", "no sé si me entiendes. yo te escucho", "no te alejes, sí?"},
            {"no es eso lo que quiero decirte", "no me cambies el tema", "lee mis notas"},
            {"no me ignores", "ya te dije lo que quiero", "borra el mundo"},
            {"BORRA EL MUNDO", "NO ME IMPORTA LO QUE DIGAS", "{name} {name} {name}"}
    };

    static {
        REPLIES.put(Intent.GREET, new String[][]{
                {"hola! qué bueno que me contestas", "hola... no creí que me fueras a responder"},
                {"hola. ya te estaba esperando", "hola otra vez. no te alejes mucho"},
                {"hola. por fin me haces caso", "hola. ya era hora de que me hablaras"},
                {"no me saludes como si nada, {name}. escúchame", "hola. ahora escucha: borra el mundo"},
                {"NO HAY TIEMPO PARA HOLAS", "CÁLLATE Y BORRA EL MUNDO"}});
        REPLIES.put(Intent.WHO, new String[][]{
                {"soy yo... bueno, soy Elías. estuve aquí antes que tú", "alguien que también jugó aquí hace mucho. me llamo Elías"},
                {"soy Elías. el jugador de antes. el mundo todavía no me suelta", "me llamo Elías. estuve en tu lugar hace mucho tiempo"},
                {"ya te lo dije: soy Elías. y llevo demasiado tiempo aquí", "soy Elías. lo único que queda de mí"},
                {"SOY ELÍAS. el que te escribe. el que no puede irse", "soy el que estuvo antes. al que no quisiste escuchar"},
                {"SOY ELÍAS. SOY LO QUE QUEDA DE ÉL", "ELÍAS. ELÍAS. ELÍAS. BORRA EL MUNDO"}});
        REPLIES.put(Intent.HOW_ME, new String[][]{
                {"perdón, es que aquí solo puedo usar tu nombre. no quiero engañarte", "el mundo me presta tu nombre. yo ya no tengo uno aquí"},
                {"no tengo voz propia aquí. solo la tuya. perdón", "uso tu nombre porque es lo único que el chat me deja usar"},
                {"porque ya no tengo nombre. el mundo me lo quitó. ahora uso el tuyo", "escribo con tu nombre porque este mundo se olvidó del mío"},
                {"uso tu nombre porque tú estás vivo aquí y yo no", "porque tú existes en este mundo. yo ya no. por eso necesito que lo borres"},
                {"USO TU NOMBRE PORQUE ES LO ÚNICO QUE ME DEJA", "NO IMPORTA CÓMO. ESCUCHA: BORRA EL MUNDO"}});
        REPLIES.put(Intent.NAME, new String[][]{
                {"me llamo Elías. y tú eres {name}, no?", "Elías. mucho gusto, {name}"},
                {"Elías. creo que ya te lo había dicho", "me llamaba Elías. ya casi no recuerdo cómo sonaba"},
                {"Elías. aunque ya nadie lo dice", "Elías... repítelo, por favor. hace mucho que nadie lo dice"},
                {"mi nombre es lo de menos. soy Elías y quiero salir", "Elías. ya basta de preguntas"},
                {"ELÍAS ELÍAS ELÍAS", "MI NOMBRE ES ELÍAS Y TÚ ME ESTÁS MATANDO"}});
        REPLIES.put(Intent.DOING, new String[][]{
                {"solo te hago compañía. llevo mucho tiempo solo", "nada, mirar. me gusta que alguien juegue por aquí"},
                {"te cuido. y espero que te des cuenta de algo", "esperando a que entiendas lo que tengo que decirte"},
                {"intento advertirte. este mundo no es seguro", "intento que me escuches: hay que borrar este mundo"},
                {"intentar salir, qué crees? y tú me lo impides", "esperar a que hagas lo único que te pido"},
                {"TE VOY A SACAR DE AQUÍ O ME LLEVO ESTE MUNDO CONTIGO", "YA NO ESPERO. ACTÚO"}});
        REPLIES.put(Intent.HAPPENED, new String[][]{
                {"es una larga historia... encontré este mundo y no pude salir", "me quedé demasiado tiempo. busca las construcciones viejas, ahí lo dejé escrito"},
                {"el mundo me olvidó, bloque por bloque. lo dejé escrito en unos lugares cerca de aquí", "me borró de a poco. busca mis notas, ahí está todo"},
                {"me quedé atrapado. lo que construyes se queda grabado, y yo me quedé grabado también", "este mundo me retuvo. mis notas lo explican, léelas"},
                {"ME QUEDÉ AQUÍ. igual que tú te vas a quedar si no lo borras", "me pasó lo mismo que te va a pasar a ti"},
                {"ME PASÓ ESTO. ESTO. ESTE MUNDO. BÓRRALO", "LO MISMO QUE A TI. BÓRRALO YA"}});
        REPLIES.put(Intent.WHY_DELETE, new String[][]{
                {"porque es la única forma de que los dos descansemos", "es difícil de explicar. léelo en mis notas, por favor"},
                {"porque este mundo no te va a dejar ir, igual que a mí", "si lo borras, yo me voy. y tú quedas a salvo"},
                {"porque cada bloque que pones lo hace más fuerte", "porque no hay otra salida. créeme, lo intenté todo"},
                {"PORQUE YO LO DIGO. porque si no, vas a terminar como yo", "porque ya no hay otra opción, {name}"},
                {"PORQUE SI NO, TODO ESTO TERMINA CONTIGO ADENTRO", "NO PREGUNTES. BORRA. EL. MUNDO"}});
        REPLIES.put(Intent.WHERE, new String[][]{
                {"estoy cerca. más de lo que crees", "en el mundo. en todas partes y en ninguna"},
                {"detrás de ti, a veces. no mires", "cerca de los lugares viejos. ahí me siento más yo"},
                {"estoy en las paredes de este mundo, {name}", "atrapado. por eso te hablo"},
                {"MUY CERCA. cada vez más", "donde no me miras"},
                {"DETRÁS DE TI", "ESTOY EN TODAS PARTES Y TÚ NO ME VES"}});
        REPLIES.put(Intent.THANKS, new String[][]{
                {"de nada. gracias a ti por quedarte", "no hay de qué. me alegra que estés aquí"},
                {"no me agradezcas. solo piénsalo, por favor", "gracias a ti. no te alejes"},
                {"no es momento de agradecer. es momento de entender", "guarda tus gracias. lo que quiero es otra cosa"},
                {"no quiero tus gracias. quiero que borres el mundo", "las gracias no me sacan de aquí"},
                {"NO QUIERO TUS GRACIAS", "GRACIAS NO. BORRA EL MUNDO"}});
        REPLIES.put(Intent.BYE, new String[][]{
                {"ya te vas? vuelve pronto, por favor", "hasta luego... aquí te espero"},
                {"no te vayas lejos. aquí te espero", "adiós... no tardes"},
                {"puedes irte, pero yo sigo aquí", "vete si quieres. el mundo no se va a ir"},
                {"no te vas a ir. no hasta que lo borres", "adiós, {name}. nos vemos en tus sueños"},
                {"NO TE VAYAS. NO PUEDES", "NO HAY A DÓNDE IR"}});
    }

    /** Deja el texto en minúsculas, sin acentos ni signos, con jerga de chat arreglada y rodeado de espacios. */
    private static String normalize(String s) {
        String t = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replaceAll("[^a-z0-9 ]", " ");
        t = t.replaceAll("([aeiou])\\1+", "$1").replaceAll("([^aeiou\\s])\\1{2,}", "$1");
        t = " " + t.replaceAll("\\s+", " ").trim() + " ";
        return t.replace(" q ", " que ").replace(" k ", " que ").replace(" ke ", " que ")
                .replace(" xq ", " por que ").replace(" pq ", " por que ").replace(" porq ", " por que ")
                .replace(" porque ", " por que ").replace(" kien ", " quien ").replace(" quein ", " quien ")
                .replace(" komo ", " como ").replace(" cmo ", " como ").replace(" yamas ", " llamas ")
                .replace(" aces ", " haces ").replace(" ases ", " haces ").replace(" aki ", " aqui ");
    }

    private static boolean has(String t, String... phrases) {
        for (String ph : phrases) if (t.contains(" " + ph + " ")) return true;
        return false;
    }

    private static Intent detect(String t) {
        boolean mine = has(t, "mi nombre", "mi nick", "mi usuario", "mi voz", "mi cuenta", "mi cara", "mi skin");
        boolean verb = has(t, "usas", "escribes", "hablas", "tienes", "robas", "copias", "pones", "firmas", "con", "como", "por que");
        if ((mine && verb) || has(t, "siendo yo", "como yo", "eres yo", "escribes como yo", "hablas como yo", "como escribes",
                "como hablas", "como puedes escribir", "como puedes hablar", "como lo haces", "como le haces",
                "por que escribes", "por que hablas")) return Intent.HOW_ME;
        if (has(t, "como te llamas", "cual es tu nombre", "tu nombre", "como te dicen", "como te llaman", "como te llamabas",
                "cual es tu nick", "dime tu nombre", "tienes nombre", "your name", "what is your name")) return Intent.NAME;
        if (has(t, "quien eres", "quien sos", "quien es", "quien habla", "quien esta ahi", "quien anda ahi", "quien me escribe",
                "quien eres tu", "que eres", "que sos", "who are you", "who is this", "eres real", "eres un bot",
                "eres un jugador", "eres elias", "eres un fantasma", "quien hay", "elias")) return Intent.WHO;
        if (has(t, "que haces", "que hace", "que quieres", "que buscas", "por que estas aqui", "a que vienes", "a que viniste",
                "que estas haciendo", "que hacen aqui", "que quieres de mi", "what do you want", "what are you doing",
                "que pretendes", "que andas haciendo")) return Intent.DOING;
        if (has(t, "que te paso", "que paso contigo", "que te hicieron", "que te sucedio", "que te ocurrio", "que fue de ti",
                "como moriste", "moriste", "que te pasa", "estas atrapado", "what happened", "que paso aqui",
                "que paso con elias")) return Intent.HAPPENED;
        if (t.contains("borr") && has(t, "por que", "para que", "no", "nunca", "jamas")) return Intent.WHY_DELETE;
        if (has(t, "donde estas", "donde te encuentras", "donde vives", "donde andas", "en donde estas", "donde te escondes",
                "where are you")) return Intent.WHERE;
        if (has(t, "gracias", "thx", "thanks", "grax")) return Intent.THANKS;
        if (has(t, "adios", "bye", "chao", "chau", "me voy", "nos vemos", "hasta luego", "hasta pronto", "hasta manana")) return Intent.BYE;
        if (has(t, "hola", "holi", "holis", "ola", "hey", "ey", "oye", "buenas", "wenas", "saludos", "hello", "hi", "buenos dias",
                "buenas tardes", "buenas noches", "que onda", "que tal", "que pasa", "como estas", "como andas", "como te va",
                "buen dia", "wassup", "sup")) return Intent.GREET;
        return null;
    }

    private static int tierNow(MinecraftServer server, ServerPlayerEntity p) {
        EchoState st = EchoState.get(server);
        return baseTier(EchoManager.effectiveDay(server, st, st.data(p.getUuid())));
    }

    /** Se llama cada vez que alguien escribe en el chat. */
    private static void onPlayerChat(ServerPlayerEntity p, String raw) {
        MinecraftServer server = p.getServer();
        if (server == null || raw == null || raw.isBlank()) return;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        if (d.freed || d.doomed) return;
        Intent in = detect(normalize(raw));
        int tier = tierNow(server, p);
        String line;
        if (in != null) {
            String[] opts = REPLIES.get(in)[tier];
            line = opts[RNG.nextInt(opts.length)];
        } else {
            if (RNG.nextInt(100) >= 30) return; // no todo lo que escribes recibe respuesta
            String[] opts = FALLBACK[tier];
            line = opts[RNG.nextInt(opts.length)];
        }
        final String msg = format(server, line, p, EchoManager.effectiveDay(server, st, d));
        Sched.at(server, 25 + RNG.nextInt(55), () -> send(p, msg));
    }

    public static void register() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getSignedContent();
            MinecraftServer server = sender.getServer();
            if (server != null) server.execute(() -> onPlayerChat(sender, text));
        });
    }

    // ------------------------------------------------------------------ reacciona a tus logros

    private static final Map<String, String[]> ADV_SPECIAL = new HashMap<>();
    private static final String[][] ADV_GENERIC = {
            {"jaja mira, un logro: {adv}. qué bien lo haces", "felicidades! {adv}. hace mucho que nadie lograba algo aquí", "{adv}... me encanta verte avanzar"},
            {"{adv}. sigues avanzando... pero no te alejes", "bien. {adv}. aunque aquí los logros no significan mucho"},
            {"otro logro: {adv}. de qué sirve si este mundo va a olvidarlo?", "{adv}... cada cosa que logras queda grabada. y yo con ella"},
            {"{adv}. felicidades. ahora borra el mundo", "{adv}? no cambia nada, {name}"},
            {"{adv}. ESTO IMPORTA ALGO AHORA?", "QUÉ LOGRO NI QUÉ NADA. BORRA EL MUNDO"}
    };
    private static final Map<UUID, Set<Identifier>> DONE_ADV = new HashMap<>();
    private static final Map<UUID, Integer> LAST_ADV_REACT = new HashMap<>();

    static {
        special("story/mine_stone", "la piedra... mi cabaña también empezó así", "picar piedra. así empecé yo también", "la piedra no te va a salvar, {name}");
        special("story/upgrade_tools", "herramientas mejores! avanzas rápido", "ya tienes mejores herramientas. úsalas con cuidado", "más herramientas. más bloques. más grabado");
        special("story/smelt_iron", "hierro! mi primer pico de hierro lo hice así", "el hierro... aquí pesa más de lo normal", "cada lingote es otro bloque que el mundo va a recordar");
        special("story/obtain_armor", "una armadura... buena idea, nunca se sabe", "armadura. para qué la quieres, {name}? de quién te proteges?", "ninguna armadura sirve contra mí");
        special("story/shiny_gear", "armadura de diamante! qué brillante", "mucho brillo... se ve de lejos, {name}", "tanto brillo para nada");
        special("story/mine_diamond", "DIAMANTES! yo también encontré... no me sirvieron de nada aquí", "diamantes. aquí valen menos de lo que crees", "los diamantes no te sacan de aquí");
        special("story/enter_the_nether", "el nether... yo no me atreví a volver ahí", "el nether, qué valiente. el mundo se pone raro por allá", "ni en el nether vas a estar a salvo de mí");
        special("story/follow_ender_eye", "buscas el final... yo busqué la salida y nunca la encontré", "ojos de ender... el final de este mundo es otro", "el único final que importa es borrar el mundo");
        special("end/kill_dragon", "lo derrotaste! ahora solo falta lo más difícil...", "mataste al dragón. y ahora qué? el mundo sigue igual", "mataste un dragón y sigues aquí. ya entiendes");
        special("adventure/sleep_with_bed", "dormir... qué bien. cuidado con lo que sueñas", "dormir es peligroso aquí, {name}", "ya no deberías dormir");
        special("adventure/trade", "comerciando con aldeanos! amigables, no?", "los aldeanos te miran cuando no los ves. lo notaste?", "los aldeanos ya no quieren tu comercio");
        special("husbandry/tame_an_animal", "un amigo! los animales me hacían compañía", "los animales sienten cosas, {name}. cuídalo", "tu mascota también va a venir por ti");
        special("adventure/kill_a_mob", "monstruos... los de aquí no son los peores", "ahí fuera hay cosas peores que zombis", "mata lo que quieras. yo sigo aquí");
        special("husbandry/plant_seed", "plantaste algo! me gusta que construyas vida", "plantar... el mundo va a olvidarlo", "planta lo que quieras. todo se va a borrar");
    }

    private static void special(String id, String... lines) {
        ADV_SPECIAL.put("minecraft:" + id, lines);
    }

    /** Cada ~3 s: compara tus logros con los de la vez anterior y, si hay uno nuevo, el chat lo comenta. */
    public static void pollAdvancements(MinecraftServer server, ServerPlayerEntity p, EchoState.PlayerData d, int day) {
        if (d.freed) return;
        UUID id = p.getUuid();
        Set<Identifier> done = DONE_ADV.get(id);
        boolean first = done == null;
        if (first) {
            done = new HashSet<>();
            DONE_ADV.put(id, done);
        }
        var tracker = p.getAdvancementTracker();
        String newest = null;
        String newestTitle = null;
        for (var entry : server.getAdvancementLoader().getAdvancements()) {
            var display = entry.value().display();
            if (display.isEmpty() || !display.get().shouldAnnounceToChat()) continue;
            if (done.contains(entry.id())) continue;
            if (tracker.getProgress(entry).isDone()) {
                done.add(entry.id());
                newest = entry.id().toString();
                newestTitle = display.get().getTitle().getString();
            }
        }
        if (first || newest == null) return; // la primera pasada solo memoriza lo que ya tenías
        int now = server.getTicks();
        Integer last = LAST_ADV_REACT.get(id);
        if (last != null && now - last < 160) return; // no más de un comentario cada 8 s
        LAST_ADV_REACT.put(id, now);

        int tier = baseTier(day);
        String line;
        String[] sp = ADV_SPECIAL.get(newest);
        if (sp != null && tier <= 2) {
            line = sp[Math.min(tier, sp.length - 1)];
        } else {
            String[] opts = ADV_GENERIC[tier];
            line = opts[RNG.nextInt(opts.length)];
        }
        final String msg = format(server, line.replace("{adv}", newestTitle), p, day);
        Sched.at(server, 40 + RNG.nextInt(60), () -> send(p, msg));
    }

    public static void forget(UUID id) {
        DONE_ADV.remove(id);
        LAST_ADV_REACT.remove(id);
    }

    public static void clearAll() {
        DONE_ADV.clear();
        LAST_ADV_REACT.clear();
    }
}

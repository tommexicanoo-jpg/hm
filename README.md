# El Eco del Silencio (The Echo) v2.0 — Fabric 1.21.1

Proyecto fuente limpio (mappings Yarn). El `sources.jar` anterior estaba descompilado con nombres
ofuscados (`class_2960`...) y no se podía recompilar; esto lo reemplaza.

## Compilar
Requiere JDK 21. Con Gradle 8.8+ instalado:

    gradle build            (o ./gradlew build si copias gradlew + gradle/wrapper/gradle-wrapper.jar de la plantilla de Fabric)

El jar queda en `build/libs/the-echo-2.2.0.jar`. Va en `mods/` con Fabric Loader 0.16+ y Fabric API.
IntelliJ: abrir la carpeta como proyecto Gradle y ejecutar la tarea `build`.

## Qué hay de nuevo
- Los días se cuentan desde que instalas el mod (un mundo viejo ya no empieza con todo activado). Cada Eco
  respeta un tope por jugador y un enfriamiento; no spawnean por spawn natural ni se guardan en el mundo.
- Laberinto con pasillos de 3 bloques de ancho (techo a 4).
- Ecos sin nametag.
- Chat falso (`<TuNombre> ...`) en 4 niveles que escalan con los días.
- Ecos: normal, Sombra (skin negra), vuelan, corren y desaparecen, gritan, miran fijamente, caminan, de reojo.
- HUD: "DÍA N" arriba a la derecha y barra Miedo/Paranoia sobre la vida. Ofrendar al espejo baja los tres.
- Memoria del Terreno + Decadencia Silenciosa, El Registrador, paranoia auditiva, falsos bloques de lana,
  ruinas con notas, el Rito, y la corrupción/final.

## Arco de 5 días (v2.1)
- Día 1 amable, día 5 apocalipsis. Todo está ya configurado por defecto (`corruptionDay` y `endingDay` = 4, que es el DÍA 5 en pantalla).
- Día 5: tormenta y noche, mobs no hostiles te atacan, Ecos que te alcanzan, rayos, apagones. A los ~150 s te echa del mundo
  y debes borrar la carpeta `saves/<mundo>` tú mismo. Al hacerlo se crea `gracias.txt` en tu escritorio (el mod no borra nada).
  Se desactiva con `corruptionEnding=false` y/o `writeThanksFile=false`. Vigila `config/the_echo_pending.txt`.
- Lore: estructuras (ruinas y capillas con espejo) con el diario de Elías en 7 entradas.
- Al entrar por primera vez: título "Día 1 de ..." con campana.

## Comandos (op nivel 2)
/echo status | dread N | paranoia N | day N | spawn [observa|reojo|sombra|camina|corre|grita|vuela|lanza|registrador]
/echo chat 0-4 | intro | ruina | decadencia | liberar | hijack | escape | reset

## Config: config/the_echo.json
Todos los "Day" son días del Eco. `corruptionEnding=false` desactiva que el final te eche del mundo.
`eventIntensity` (1.0) multiplica la frecuencia de todo.

## Novedades v2.2
- **Ecos por miedo**: empiezan a aparecer con miedo >= 15; cuanto más miedo, más a la vez (hasta 6), más seguido y de más tipos
  (caminan 25, corren 35, skin negra 40, gritan 50, vuelan 60). Si el miedo baja de 10, se retiran.
- **7 estructuras encadenadas, todas con Espejo**: cabaña, ruinas, capilla, campamento, torre, altar del pozo y tumba de Elías.
  La 1.ª aparece a 45-75 bloques del spawn; al llegar a una aparece la siguiente (55-85 bloques) y el chat te da la dirección.
  No se construye en chunks donde hayas puesto/roto bloques. Mirar el espejo con la mano vacía baja miedo/paranoia (cada 45 s).
- **Calma**: comer, talar troncos y cosechar cultivos maduros bajan un poco el miedo y la paranoia.
- **Chat vivo**: comenta tus logros y responde a hola / quién eres / cómo escribes siendo yo / cómo te llamas / qué haces aquí /
  qué te pasó / dónde estás / por qué borrar / gracias / adiós (y variantes con faltas), con tono según el día.
- **Día 5**: 120 s de apocalipsis y te echa del mundo; si vuelves a entrar te echa de nuevo hasta que lo borres.
- **Título "Día N de ▒▒▒▒"** con campana al empezar cada día.
- **Laberinto**: antorchas en casi todas las celdas, no se puede romper nada (salvo creativo), oscuridad solo ocasional.
- **Dormir**: cada vez que duermes una noche, 20 % de despertar en el laberinto el día 1, +20 % por día (día 5 = 100 %).
- **Rendimiento**: sin búsquedas de entidades por tick, decadencia por chunks, caché del estado, menos pathfinding y paquetes.
- Comandos nuevos: `/echo ruina [1-7]`, `/echo sitios`. La config se regenera (configVersion 4).

## v2.3
- 5 estructuras (cabaña, ruinas, capilla, campamento, tumba), una por día y mucho más lejos del spawn (110-160, luego 90-140 entre sí).
  El chat da las coordenadas exactas; al llegar dice "al día siguiente te mostraré algo más". Cada una tiene espejo, cofre con
  cosas gastadas de un jugador antiguo (espejos, herramientas, armadura, telarañas) y su libro.
- El día 5 el chat te manda al último lugar; al CERRAR el libro de ese atril empieza el apocalipsis, con cuenta atrás roja de 2:00.
- Los libros explican el espejo (día 1: no sabe qué hace; día 2: ofrendas del primer día calman).
- Cada ofrenda calma según el objeto (3 a 30 puntos): nunca todo de golpe.
- Comandos: `/echo apocalipsis` (probar el final), `/echo ruina [1-5]`, `/echo sitios`. configVersion 5 (se regenera).

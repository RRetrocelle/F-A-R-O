# Faro v0.2.0

Companion de diagnostico para el modpack **coco** (Minecraft 1.20.1, Forge).

---

## Lo primero: que hace y que no

### Lo que hace de verdad

| Funcion | Como lo consigue |
|---|---|
| **Chequeo preventivo** (sin necesidad de crash) | Abre cada `.jar` de `mods/`, lee su `mods.toml`, arma el grafo de dependencias y valida rangos de version |
| Detectar mods duplicados | Dos jars declarando el mismo `modId` = crash garantizado al arrancar |
| Detectar jars de otro loader | Un `.jar` con `fabric.mod.json` y sin `mods.toml` nunca va a cargar en Forge |
| Detectar dependencias faltantes | Compara lo que cada mod pide contra lo que hay instalado |
| Detectar versiones fuera de rango | Evaluador de rangos Maven propio (`[6.0.7,)`, `[1.0,2.0)`, etc.) |
| **Detectar "compatibilidad no verificable"** | Cuando un rango sin techo acepta la version instalada solo por casualidad — el caso mas traicionero |
| Contar mods cargados vs. jars presentes | Cruza `ModList` de Forge contra el escaneo de la carpeta |
| Explicar el ultimo crash | Parsea `crash-reports/` y lo contrasta contra 18 firmas de fallos conocidos |
| Identificar al culpable | Motor de puntaje ponderado con 6 senales independientes |
| Medir rendimiento real | Cronometra cada tick del cliente, calcula promedio/p95/peor, grafica la historia |
| Avisar en vivo | Lee `latest.log` de forma incremental en un hilo aparte |
| Desactivar un mod | Mueve su `.jar` a `mods/deshabilitados_por_faro/` |
| **Destrabar el arranque** | Boton en la pantalla de error de Forge, antes de entrar al juego |

### Lo que NO hace

- **No repara mods rotos.** Ningun programa puede: cada crash tiene una causa
  distinta y arreglarla implica cambiar el codigo del mod.
- **No descarga dependencias faltantes.** Bajar e instalar un `.jar` automaticamente
  desde internet es un riesgo que no vale la pena correr sin que vos mires que se baja.
- **No adivina.** Si las senales no alcanzan, la confianza queda en `NINGUNA`, el
  boton de reparar se apaga solo y te manda al log.

La unica accion automatica es **desactivar** un mod, y solo porque es
trivialmente reversible: mueve un archivo, lo anota, y podes devolverlo a mano.

---

## El motor de sospecha

La v1 se quedaba con el primer mod del stacktrace. Eso falla seguido: el primer
frame suele ser el mod que *recibio* el golpe, no el que lo causo.

La v2 acumula evidencia de seis senales independientes y despues ordena:

| Senal | Puntos |
|---|---|
| Forge lo nombro con `-- MOD x --` | +100 |
| Una firma conocida lo capturo por nombre | +60 |
| Aparece en los primeros 5 frames del stack | +45 |
| Aparece entre los frames 5-14 | +25 |
| Genera errores en el log de esta sesion | +8 c/u, tope +32 |
| Su `.jar` cambio en las ultimas 24 h | +12 |
| Tiene una dependencia rota ya detectada | +30 |
| **Es una libreria compartida** (Curios, GeckoLib...) | **-40** |

Ese castigo a las librerias importa: aparecen en casi todos los stacktraces y
taparian al culpable real.

La confianza sale del puntaje del primero **y** de la ventaja sobre el segundo.
Si dos mods empatan, no hay ganador y se dice. Cada indicio queda guardado con
su puntaje y se ve en la pestana Crash, asi que la conclusion siempre se puede
auditar — no sale de una caja negra.

> **Sobre la palabra "IA":** esto es un sistema experto basado en reglas, no
> aprendizaje automatico. Las 18 firmas son patrones escritos a mano a partir de
> errores reales de Forge. La ventaja sobre una IA de verdad, en este caso, es
> que cada conclusion es explicable y verificable.

---

## El boton de la pantalla de error de Forge

Cuando faltan dependencias, Forge muestra su pantalla de error **despues del logo
de Mojang y antes de entrar al juego**. Ahi aparece un boton
**"Faro: destrabar arranque"**.

Al tocarlo, Faro re-escanea la carpeta y arma una lista de que archivos impiden
el arranque, con el motivo de cada uno. **Muestra la lista antes de tocar nada.**
Si aceptas, los mueve a `mods/deshabilitados_por_faro/` y te pide que reinicies.

Que propone desactivar:
- mods a los que les falta una dependencia obligatoria;
- la copia mas vieja de un mod duplicado;
- jars de otro modloader.

Si no encuentra nada de eso, lo dice y no toca nada, en vez de desactivar a ciegas.

> **Nota tecnica honesta:** esto tiene que ser un **Mixin**, porque cuando la
> resolucion de dependencias falla, Forge aborta antes de construir un solo mod:
> la clase `@Mod` nunca se instancia. Por la via normal es imposible poner un
> boton ahi.
>
> Como contrapartida, esta es la parte **menos garantizada** del mod: depende de
> que la config de mixins de Faro se registre en esa ruta de arranque. Por eso el
> `@Inject` usa `require = 0` y todo el cuerpo va en un `try/catch`: si no aplica,
> se saltea en silencio y ves la pantalla de error de Forge tal cual. Un mod de
> diagnostico jamas debe ser el que rompe el arranque.

---

## Como compilarlo

**Hace falta un JDK 17.** En esta maquina solo hay JREs, que no traen `javac`.

1. Instalar **Eclipse Temurin JDK 17** desde <https://adoptium.net/temurin/releases/?version=17>
   (elegir *JDK*, x64, instalador `.msi`).
2. En una terminal en esta carpeta:

   ```
   .\gradlew.bat build
   ```

   La primera vez baja Gradle, ForgeGradle y Minecraft: varios cientos de MB.

3. El `.jar` queda en `build\libs\faro-0.2.0.jar`. Copialo a la carpeta `mods`.

Para probar sin tocar tu instancia real: `.\gradlew.bat runClient`

---

## Uso

- **Menu principal y menu de pausa**: boton `Faro` abajo a la izquierda.
  Muestra `!!` rojo si hay problemas criticos, `!` amarillo si hay algo importante.
- **En partida**: `F6` abre la pantalla, `F7` prende/apaga el aviso.
- **Pantalla de error de Forge**: boton "Faro: destrabar arranque".
- **Config**: `config/faro-client.toml`

### Las cinco pestanas

| Pestana | Responde |
|---|---|
| Resumen | ¿esta todo bien? |
| Problemas | ¿que tengo que arreglar? |
| Mods | ¿que hay instalado y que no cargo? |
| Crash | ¿por que se cerro? |
| Rendimiento | ¿por que va lento? |

---

## Archivos que Faro crea

| Ruta | Que es |
|---|---|
| `faro/acciones.log` | Bitacora de cada archivo movido |
| `mods/deshabilitados_por_faro/` | Donde van los `.jar` desactivados |
| `mods/deshabilitados_por_faro/LEEME.txt` | Explicacion sin abrir el juego |
| `config/faro-client.toml` | Configuracion |

Para deshacer cualquier cosa: mover el `.jar` de vuelta a `mods/`. **No hace
falta el mod para revertirlo.**

---

## Estructura del codigo

```
com.coco.faro
├── Faro.java                        punto de entrada (@Mod)
├── config/ConfigFaro.java           ForgeConfigSpec
├── diag/
│   ├── RangoVersion.java            evaluador de rangos Maven
│   ├── MetadatosJar.java            lo leido de un .jar
│   ├── EscanerJars.java             parser de mods.toml por secciones
│   ├── AnalizadorDependencias.java  chequeo preventivo
│   ├── Problema.java / Severidad.java
│   ├── Firma.java                   una regla de la base
│   ├── BaseConocimiento.java        18 firmas de fallos conocidos
│   ├── Sospechoso.java              candidato con puntaje e indicios
│   ├── MotorSospecha.java           puntaje ponderado
│   ├── LectorCrashReports.java      extraccion -> reconocimiento -> atribucion
│   ├── VigilanteLog.java            lectura incremental de latest.log
│   ├── MonitorRendimiento.java      tick times, p95, memoria, picos
│   ├── InventarioMods.java          cruce ModList vs carpeta
│   ├── Diagnostico.java / Confianza.java / TipoProblema.java
│   └── MotorDiagnostico.java        orquestador con cache
├── repair/
│   ├── RegistroAcciones.java        bitacora en disco
│   └── ServicioReparacion.java      mover jar (unica escritura en mods/)
├── mixin/
│   └── MixinPantallaErrorCarga.java boton en la pantalla de error de Forge
└── client/
    ├── ClienteFaro.java             teclas, overlay, botones, medicion de ticks
    ├── PantallaFaro.java            pantalla principal con 5 pestanas
    ├── PantallaDetalle.java         stacktrace y evidencia cruda
    ├── PantallaConfirmarReparacion.java
    ├── RescateArranque.java         logica del rescate (autonoma)
    ├── PantallaRescate.java         confirmacion del rescate
    ├── OverlayFaro.java             aviso en esquina
    ├── Widgets.java                 piezas de dibujo reutilizables
    └── Paleta.java                  colores
```

### Notas de rendimiento

La maquina objetivo es un i7 de 5a generacion, asi que esto no es decorativo:

- El analisis pesado (abrir ~190 zips, grafo de dependencias, parsear el crash)
  corre **una sola vez**, en un hilo daemon de prioridad minima, y queda cacheado.
  La interfaz nunca toca el disco mientras dibuja.
- `VigilanteLog` lee **solo los bytes nuevos** de `latest.log`, cada 3 s.
- La pestana Mods **saltea las filas fuera del recorte**: con ~190 mods, dibujar
  lo que no se ve es trabajo tirado.
- El overlay no dibuja nada si no hay novedades.
- Todo se dibuja con rectangulos: ninguna funcion de `Widgets` crea objetos por frame.

---

## Estado y limitaciones conocidas

**v0.2.0, no compilada ni probada en ejecucion.** Escrita con cuidado contra la
API de Forge 1.20.1, pero hasta correr `gradlew build` no hay garantia de que
compile a la primera. Lo mas propenso a necesitar ajuste, en orden:

1. **El Mixin de la pantalla de error** — explicado arriba. Es lo mas incierto.
2. **`Minecraft.stop()`** — es lo que usa el boton "Salir" de vanilla; si la firma
   cambio, se ajusta en `PantallaConfirmarReparacion.cerrarJuego()`.
3. **`ScreenEvent.Init.Post` + `addListener`** — si el boton no aparece en el menu,
   es aca.
4. **Heuristica paquete -> modId** — asume que un segmento del paquete coincide con
   el modId (`com.simibubi.create.*` -> `create`). No siempre pasa. Cuando falla,
   devuelve vacio y baja la confianza, que es el comportamiento correcto.
5. **Las 18 firmas** cubren los casos comunes, no todos.

Ninguna de estas limitaciones hace que Faro mienta: en el peor caso dice
"no pude determinar la causa", que es un resultado honesto.

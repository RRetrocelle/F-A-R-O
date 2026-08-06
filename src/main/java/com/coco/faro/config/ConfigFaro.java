package com.coco.faro.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** Configuracion del lado cliente. Se genera en config/faro-client.toml. */
public final class ConfigFaro {

    public enum Esquina { ARRIBA_IZQUIERDA, ARRIBA_DERECHA, ABAJO_IZQUIERDA, ABAJO_DERECHA }

    public final ForgeConfigSpec.BooleanValue overlayActivo;
    public final ForgeConfigSpec.BooleanValue overlaySoloErrores;
    public final ForgeConfigSpec.EnumValue<Esquina> overlayEsquina;
    public final ForgeConfigSpec.IntValue intervaloVigilanciaMs;
    public final ForgeConfigSpec.BooleanValue botonEnMenuPrincipal;
    public final ForgeConfigSpec.IntValue segundosVisibleAviso;

    public final ForgeConfigSpec.BooleanValue hudTicksActivo;
    public final ForgeConfigSpec.EnumValue<Esquina> hudTicksEsquina;
    public final ForgeConfigSpec.BooleanValue hudTicksDetallado;

    public final ForgeConfigSpec.BooleanValue notificacionesActivas;
    public final ForgeConfigSpec.IntValue segundosNotificacion;
    public final ForgeConfigSpec.BooleanValue autoAnalizarAlAbrir;

    public final ForgeConfigSpec.BooleanValue alertasSonoras;
    public final ForgeConfigSpec.BooleanValue faroVision;

    public final ForgeConfigSpec.BooleanValue suavizarEntidades;

    public final ForgeConfigSpec.BooleanValue vigilarOpenGL;
    public final ForgeConfigSpec.BooleanValue medirNbt;
    public final ForgeConfigSpec.BooleanValue medirWorldgen;
    public final ForgeConfigSpec.BooleanValue profilerZonas;
    public final ForgeConfigSpec.BooleanValue detectarCongelamientos;

    public static final ConfigFaro INSTANCIA;
    public static final ForgeConfigSpec SPEC;

    static {
        Pair<ConfigFaro, ForgeConfigSpec> par = new ForgeConfigSpec.Builder().configure(ConfigFaro::new);
        INSTANCIA = par.getLeft();
        SPEC = par.getRight();
    }

    private ConfigFaro(ForgeConfigSpec.Builder b) {
        b.comment("Faro - companion de diagnostico").push("overlay");

        overlayActivo = b
                .comment("Mostrar el aviso chico en pantalla cuando aparecen errores nuevos en el log.")
                .define("activo", true);

        overlaySoloErrores = b
                .comment("Si esta en true, ignora los WARN y solo avisa por ERROR.")
                .define("soloErrores", false);

        overlayEsquina = b
                .comment("En que esquina se dibuja el aviso.")
                .defineEnum("esquina", Esquina.ARRIBA_DERECHA);

        segundosVisibleAviso = b
                .comment("Cuantos segundos queda visible el aviso despues de un error nuevo.")
                .defineInRange("segundosVisible", 8, 2, 60);

        b.pop().push("hud");

        hudTicksActivo = b
                .comment("Mostrar un HUD chico con el tiempo de tick y el TPS mientras jugas.",
                        "Se puede prender y apagar desde los ajustes dentro de Faro.")
                .define("ticksActivo", false);

        hudTicksEsquina = b
                .comment("En que esquina se dibuja el HUD de ticks.")
                .defineEnum("ticksEsquina", Esquina.ARRIBA_IZQUIERDA);

        hudTicksDetallado = b
                .comment("Si esta en true, agrega promedio, p95 y memoria al HUD.")
                .define("ticksDetallado", false);

        notificacionesActivas = b
                .comment("Mostrar las notificaciones estilo logro cuando se detecta algo.")
                .define("notificacionesActivas", true);

        segundosNotificacion = b
                .comment("Cuantos segundos queda visible cada notificacion.")
                .defineInRange("segundosNotificacion", 20, 5, 60);

        autoAnalizarAlAbrir = b
                .comment("Volver a analizar la instalacion cada vez que se abre la pantalla.",
                        "Apagalo si notas demora al abrir Faro con muchos mods.")
                .define("autoAnalizarAlAbrir", true);

        alertasSonoras = b
                .comment("Acompañar las notificaciones con un sonido del juego.",
                        "El sonido cambia segun la gravedad: no es el mismo aviso para un dato",
                        "informativo que para un problema critico.")
                .define("alertasSonoras", true);

        faroVision = b
                .comment("Mostrar de que mod es el bloque o la entidad que estas mirando,",
                        "junto con avisos si ese mod tiene problemas detectados.",
                        "Se activa con la tecla de Faro-Vision (por defecto F8).")
                .define("faroVision", false);

        b.pop().push("red");

        suavizarEntidades = b
                .comment("Suavizar el movimiento de las otras entidades cuando el ping es alto.",
                        "OJO: esto NO baja el ping, ningun mod del cliente puede hacerlo.",
                        "Es puramente visual, y a cambio de fluidez las entidades quedan un poco",
                        "mas atrasadas respecto de donde estan de verdad en el servidor.",
                        "En PvP eso puede jugarte en contra. Por eso viene apagado.")
                .define("suavizarEntidades", false);

        medirNbt = b
                .comment("Medir el tamano de los datos NBT que viajan por la red.",
                        "Sirve para encontrar al mod que manda paquetes gigantes y causa tirones.",
                        "Cuesta rendimiento porque el hook vive en el buffer por el que pasa todo",
                        "el trafico: prendelo para investigar y apagalo al terminar.")
                .define("medirNbt", false);

        b.pop().push("diagnostico_avanzado");

        vigilarOpenGL = b
                .comment("Consultar el estado de OpenGL cada cuadro y anotar los errores.",
                        "Es lo que convierte 'se ve raro' en un codigo de error concreto.",
                        "Cuesta rendimiento: fuerza una sincronizacion con el driver por cuadro.")
                .define("vigilarOpenGL", false);

        medirWorldgen = b
                .comment("Cronometrar cada enganche de generacion de mundo.",
                        "Dice cuanto cuesta cada mod al explorar terreno nuevo.",
                        "Solo aplica en mundos locales: en un servidor la generacion pasa alla.")
                .define("medirWorldgen", false);

        profilerZonas = b
                .comment("Muestrear el mundo cada medio segundo para encontrar las zonas",
                        "que concentran la carga (entidades y bloques con logica).")
                .define("profilerZonas", false);

        detectarCongelamientos = b
                .comment("Vigilar que el hilo principal siga respondiendo, y volcar los",
                        "stacktraces si se cuelga. Es la unica forma de diagnosticar un",
                        "congelamiento, porque no deja crash report.",
                        "Cuesta despertar un hilo cada 2 segundos: practicamente nada.")
                .define("detectarCongelamientos", true);

        b.pop().push("vigilancia");

        intervaloVigilanciaMs = b
                .comment("Cada cuantos milisegundos se revisa latest.log.",
                        "Mas alto = menos trabajo para la CPU. 3000 esta bien para equipos modestos.")
                .defineInRange("intervaloMs", 3000, 1000, 30000);

        b.pop().push("interfaz");

        botonEnMenuPrincipal = b
                .comment("Agregar el boton de Faro al menu principal.")
                .define("botonEnMenuPrincipal", true);

        b.pop();
    }
}

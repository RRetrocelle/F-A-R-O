package com.coco.faro.diag;

import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integracion OPCIONAL con otros mods de diagnostico ya instalados.
 *
 * Criterio de diseno: Faro nunca declara una dependencia obligatoria con ninguno
 * de estos. Si el mod esta, se aprovecha; si no esta, Faro funciona igual. Todas
 * las comprobaciones son contra {@link ModList}, que es la fuente confiable de
 * que cargo de verdad.
 *
 * La razon de fondo: reinventar un profiler cuando spark ya existe y es el
 * estandar de la comunidad seria peor herramienta y mas codigo que mantener.
 * Es mejor detectarlo, decir que esta, y mandar al usuario ahi para lo que spark
 * hace mejor.
 */
public final class Integraciones {

    /** Que aporta un mod compañero y como usarlo. */
    public record Companero(
            String modId,
            String nombre,
            String queAporta,
            String comoUsarlo,
            boolean recomendado) {
    }

    private static final Map<String, Companero> CATALOGO = new LinkedHashMap<>();

    static {
        reg("spark", "spark",
                "Profiler real: mide que mod consume CPU y memoria, con arbol de llamadas.",
                "Comando /spark profiler start, jugá un rato, /spark profiler stop. "
                        + "Te da un link con el analisis completo.",
                true);

        reg("crashassistant", "Crash Assistant",
                "Muestra una ventana con el analisis del crash apenas el juego se cierra, "
                        + "incluso si Faro no llego a cargar.",
                "No hay que hacer nada: aparece solo despues de un crash.",
                true);

        reg("observable", "Observable",
                "Muestra que entidades y bloques estan consumiendo tiempo de tick, por chunk.",
                "Tecla configurable para abrir el mapa de calor.",
                true);

        reg("modernfix", "ModernFix",
                "Reduce memoria y tiempos de carga. Su log de arranque dice cuanto ahorro.",
                "Ya trabaja solo. Faro lee sus mensajes del log.",
                false);

        reg("asynclogger", "Async Logger",
                "Escribe el log en otro hilo. Hace que la consola en vivo de Faro cueste menos.",
                "Ya trabaja solo.",
                false);

        reg("alltheleaks", "All The Leaks",
                "Corrige fugas de memoria. Complementa lo que Faro solo puede medir.",
                "Ya trabaja solo.",
                false);

        reg("embeddium", "Embeddium",
                "Motor de renderizado. Faro puede proponer un preset segun tu hardware.",
                "Opciones de video > Embeddium.",
                false);

        reg("servercore", "ServerCore",
                "Regula spawns y generacion segun el TPS. Faro mide el efecto.",
                "Ya trabaja solo, se configura en su .json.",
                false);

        reg("doespotatotick", "DoesPotatoTick",
                "Congela entidades lejanas. Faro lo detecta para no sugerir mods que hagan lo mismo.",
                "Ya trabaja solo.",
                false);
    }

    private static void reg(String id, String nombre, String aporta, String uso, boolean rec) {
        CATALOGO.put(id, new Companero(id, nombre, aporta, uso, rec));
    }

    private Integraciones() {
    }

    private static boolean estaCargado(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Companeros presentes y activos ahora mismo. */
    public static List<Companero> activos() {
        List<Companero> out = new ArrayList<>();
        for (Companero c : CATALOGO.values()) {
            if (estaCargado(c.modId())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Companeros recomendados que NO estan activos.
     *
     * Incluye el caso de los que estan en la carpeta como .jar.disabled: para
     * Forge estan ausentes, y para el usuario "los tiene instalados". Esa
     * distincion vale la pena decirla.
     */
    public static List<Companero> recomendadosAusentes() {
        List<Companero> out = new ArrayList<>();
        for (Companero c : CATALOGO.values()) {
            if (c.recomendado() && !estaCargado(c.modId())) {
                out.add(c);
            }
        }
        return out;
    }

    public static boolean hay(String modId) {
        return estaCargado(modId);
    }

    /** true si spark esta disponible: cambia que puede ofrecer Faro. */
    public static boolean haySpark() {
        return estaCargado("spark");
    }

    public static int cantidadConocidos() {
        return CATALOGO.size();
    }
}

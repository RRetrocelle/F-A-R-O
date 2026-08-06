package com.coco.faro.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitor de enganches de generacion de mundo, y de estructuras que llegan rotas.
 *
 * Dos cosas que van juntas porque se sufren juntas: el tiron al explorar terreno
 * nuevo, y las estructuras que aparecen a medias o directamente no aparecen.
 *
 * TIEMPO POR FEATURE — cada mod que agrega arboles, minerales, lagos o cualquier
 * cosa al terreno lo hace registrando un "placed feature". Al generar un chunk,
 * el juego recorre todos los features de ese bioma y los coloca. Un Mixin
 * cronometra cada colocacion y suma por feature. El namespace del id del feature
 * es el modId, asi que el resultado dice literalmente "el mod X se lleva N ms por
 * chunk generado". Medicion directa, sin estimacion.
 *
 * ESTRUCTURAS ROTAS — cuando una plantilla de estructura (.nbt) referencia un
 * bloque que no existe, o el archivo esta corrupto, el juego lo anota en el log y
 * sigue generando el resto. El resultado es la casa sin techo o el dungeon a
 * medias. Se detectan por las lineas que el propio juego escribe, con lo cual el
 * dato es del juego, no una interpretacion de Faro.
 *
 * Lo que se puede afirmar: cuanto cuesta cada feature, y que estructura fallo.
 * Lo que no: si esa estructura rota es "culpa" de un mod o del que la pidió — un
 * bloque faltante puede venir de haber sacado otro mod, y eso Faro lo dice como
 * hipotesis, no como conclusion.
 */
public final class MonitorWorldgen {

    /** Tiempo acumulado de un feature de generacion. */
    public record Feature(String id, String modId, double milisegundos, long colocaciones) {

        public double promedioMs() {
            return colocaciones == 0 ? 0 : milisegundos / colocaciones;
        }
    }

    /** Un fallo concreto al cargar o colocar una estructura. */
    public record EstructuraRota(String nombre, String modId, String motivo,
                                 String detalle, long momento) {
    }

    private static final Map<String, AtomicLong> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> VECES = new ConcurrentHashMap<>();

    private static final List<EstructuraRota> ROTAS = new ArrayList<>();
    private static final int MAX_ROTAS = 40;

    private static final AtomicLong chunksGenerados = new AtomicLong();
    private static volatile long inicioMs = System.currentTimeMillis();

    /**
     * Interruptor de la medicion de features.
     *
     * Apagado por defecto, y no por precaucion generica: un chunk pasa por
     * cientos de features, y un mundo generando terreno hace decenas de chunks
     * por segundo. Cronometrarlos todos siempre seria costoso justo cuando el
     * juego mas necesita la CPU. Se prende para investigar y se apaga al terminar.
     *
     * La deteccion de estructuras rotas, en cambio, va siempre: sale de leer el
     * log, que ya se lee de todos modos, y no cuesta nada extra.
     */
    private static volatile boolean midiendo = false;

    private MonitorWorldgen() {
    }

    public static boolean midiendo() {
        return midiendo;
    }

    public static void medir(boolean v) {
        midiendo = v;
        if (v) {
            inicioMs = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------- features

    /** Registra el costo de colocar un feature. La llama el mixin de worldgen. */
    public static void registrarFeature(String id, long nanos) {
        if (id == null || id.isEmpty() || nanos <= 0) {
            return;
        }
        NANOS.computeIfAbsent(id, k -> new AtomicLong()).addAndGet(nanos);
        VECES.computeIfAbsent(id, k -> new AtomicLong()).incrementAndGet();
    }

    public static void registrarChunk() {
        chunksGenerados.incrementAndGet();
    }

    public static long chunksGenerados() {
        return chunksGenerados.get();
    }

    public static boolean hayDatos() {
        return !NANOS.isEmpty();
    }

    public static List<Feature> ranking() {
        List<Feature> out = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : NANOS.entrySet()) {
            long veces = VECES.getOrDefault(e.getKey(), new AtomicLong()).get();
            out.add(new Feature(e.getKey(), namespaceDe(e.getKey()),
                    e.getValue().get() / 1_000_000.0, veces));
        }
        out.sort(Comparator.comparingDouble(Feature::milisegundos).reversed());
        return out;
    }

    /** Costo agrupado por mod, que es como uno decide que sacar. */
    public static List<Feature> porMod() {
        Map<String, double[]> acumulado = new ConcurrentHashMap<>();
        for (Feature f : ranking()) {
            double[] v = acumulado.computeIfAbsent(f.modId(), k -> new double[2]);
            v[0] += f.milisegundos();
            v[1] += f.colocaciones();
        }
        List<Feature> out = new ArrayList<>();
        for (Map.Entry<String, double[]> e : acumulado.entrySet()) {
            out.add(new Feature(e.getKey(), e.getKey(), e.getValue()[0], (long) e.getValue()[1]));
        }
        out.sort(Comparator.comparingDouble(Feature::milisegundos).reversed());
        return out;
    }

    public static double totalMs() {
        double total = 0;
        for (AtomicLong v : NANOS.values()) {
            total += v.get() / 1_000_000.0;
        }
        return total;
    }

    /** Milisegundos de generacion por chunk. Es el numero que se siente al explorar. */
    public static double msPorChunk() {
        long chunks = chunksGenerados.get();
        return chunks == 0 ? 0 : totalMs() / chunks;
    }

    private static String namespaceDe(String id) {
        int i = id.indexOf(':');
        return i <= 0 ? id : id.substring(0, i);
    }

    // ------------------------------------------------------------ estructuras

    /** Anota una estructura que no cargo bien. */
    public static void registrarRota(String nombre, String motivo, String detalle) {
        if (nombre == null || nombre.isBlank()) {
            return;
        }
        synchronized (ROTAS) {
            for (EstructuraRota r : ROTAS) {
                if (r.nombre().equals(nombre) && r.motivo().equals(motivo)) {
                    return; // ya anotada: no se duplica
                }
            }
            if (ROTAS.size() >= MAX_ROTAS) {
                ROTAS.remove(0);
            }
            ROTAS.add(new EstructuraRota(nombre, namespaceDe(nombre), motivo,
                    detalle == null ? "" : detalle, System.currentTimeMillis()));
        }
    }

    public static List<EstructuraRota> estructurasRotas() {
        synchronized (ROTAS) {
            return new ArrayList<>(ROTAS);
        }
    }

    /**
     * Reconoce en una linea de log un fallo de estructura y lo anota.
     *
     * Los mensajes son del propio Minecraft y de Forge. Se listan textuales para
     * que se pueda auditar de donde sale cada deteccion.
     */
    public static boolean examinarLineaDeLog(String origen, String mensaje) {
        if (mensaje == null || mensaje.isBlank()) {
            return false;
        }
        String bajo = mensaje.toLowerCase(Locale.ROOT);

        if (bajo.contains("failed to load structure")
                || bajo.contains("couldn't load structure")) {
            registrarRota(extraerId(mensaje), "la plantilla .nbt no se pudo leer",
                    "El archivo de la estructura falta o esta corrupto. El mod la va a "
                            + "intentar generar igual y el resultado queda incompleto.");
            return true;
        }
        if (bajo.contains("unknown block") && bajo.contains("structure")) {
            registrarRota(extraerId(mensaje), "usa un bloque que no existe",
                    "La estructura pide un bloque que ningun mod instalado provee. Suele pasar "
                            + "cuando se saco un mod pero quedo el que genera la estructura.");
            return true;
        }
        if (bajo.contains("failed to parse structure") || bajo.contains("invalid structure")) {
            registrarRota(extraerId(mensaje), "la estructura esta mal formada",
                    "El archivo existe pero su contenido no es valido para esta version del juego.");
            return true;
        }
        if (bajo.contains("missing structure piece") || bajo.contains("unknown structure piece")) {
            registrarRota(extraerId(mensaje), "le falta una pieza",
                    "La estructura se arma por partes y una de ellas no esta. Vas a ver el "
                            + "edificio cortado o con un hueco.");
            return true;
        }
        if (bajo.contains("feature") && (bajo.contains("threw an exception")
                || bajo.contains("crashed while") || bajo.contains("error while placing"))) {
            registrarRota(extraerId(mensaje), "un feature reviento al generar",
                    "Ese generador lanzo una excepcion. El chunk se termina igual pero sin eso.");
            return true;
        }
        return false;
    }

    /** Saca el primer identificador tipo 'mod:cosa' del mensaje. */
    private static String extraerId(String mensaje) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([a-z0-9_.-]+:[a-z0-9_./-]+)").matcher(mensaje.toLowerCase(Locale.ROOT));
        if (m.find()) {
            return m.group(1);
        }
        return mensaje.length() > 60 ? mensaje.substring(0, 60) : mensaje;
    }

    // --------------------------------------------------------------- lectura

    public static void reiniciar() {
        NANOS.clear();
        VECES.clear();
        chunksGenerados.set(0);
        synchronized (ROTAS) {
            ROTAS.clear();
        }
        inicioMs = System.currentTimeMillis();
    }

    public static long segundosMidiendo() {
        return (System.currentTimeMillis() - inicioMs) / 1000L;
    }

    /**
     * Veredicto en una linea.
     *
     * Los umbrales: un chunk deberia generarse en pocos milisegundos. Cuando la
     * suma de features pasa de 20 ms por chunk, explorar produce tirones visibles
     * porque varios chunks se generan en el mismo tick.
     */
    public static String veredicto() {
        if (!hayDatos()) {
            return "Todavia no se genero terreno nuevo. Caminá hacia zonas sin explorar "
                    + "para que haya algo que medir.";
        }
        double porChunk = msPorChunk();
        List<Feature> mods = porMod();
        String peor = mods.isEmpty() ? "?" : mods.get(0).modId();

        if (porChunk >= 20) {
            return String.format(Locale.ROOT,
                    "%.1f ms de generacion por chunk. Es mucho: los tirones al explorar vienen "
                            + "de aca, y el que mas pesa es '%s'.", porChunk, peor);
        }
        if (porChunk >= 8) {
            return String.format(Locale.ROOT,
                    "%.1f ms por chunk. Se nota al explorar rapido. El que mas pesa es '%s'.",
                    porChunk, peor);
        }
        return String.format(Locale.ROOT,
                "%.1f ms por chunk sobre %d chunks. La generacion no es el problema.",
                porChunk, chunksGenerados());
    }
}

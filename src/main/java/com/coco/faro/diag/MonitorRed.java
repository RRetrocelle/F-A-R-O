package com.coco.faro.diag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuanto trafico manda cada mod y cuanto pesan los NBT que viajan.
 *
 * Dos mediciones distintas que comparten pantalla porque responden la misma
 * pregunta ("¿por que se traba al moverme?"), pero salen de lugares distintos:
 *
 *   TRAFICO POR CANAL — cada mod que manda datos propios usa un canal con su
 *   nombre ({@code create:main}, {@code curios:main}...). El namespace del canal
 *   ES el modId. Se cuentan bytes y paquetes por canal. Esto es medicion directa
 *   del tamano del buffer, no una estimacion.
 *
 *   NBT — los datos estructurados que viajan dentro de los paquetes (contenido de
 *   cofres, datos de entidades, items con muchos atributos). Un NBT gigante es la
 *   causa clasica del tiron al abrir un contenedor o al acercarse a una maquina.
 *   Se mide interceptando la lectura y la escritura de NBT en el buffer de red.
 *
 * Lo que esto NO es: un medidor de ancho de banda del sistema. Solo ve lo que
 * pasa por el protocolo de Minecraft en este cliente. Y no baja el ping: la
 * latencia la define la ruta de red, no el volumen de datos de un mod. Lo que si
 * permite es señalar al mod que esta inundando la conexion, que es una causa real
 * de tirones y se confunde seguido con "mala conexion".
 */
public final class MonitorRed {

    /** Umbral desde el cual un NBT suelto se considera grande de verdad. */
    public static final int NBT_GRANDE_BYTES = 8 * 1024;

    /** Trafico acumulado de un canal. */
    public record Canal(String nombre, String modId, long bytes, long paquetes) {

        public String bytesLegibles() {
            return legible(bytes);
        }

        public double promedioBytes() {
            return paquetes == 0 ? 0 : bytes / (double) paquetes;
        }
    }

    /** Un NBT concreto que llamo la atencion por su tamano. */
    public record NbtGrande(long momento, int bytes, String contexto) {
    }

    private static final Map<String, AtomicLong> BYTES_CANAL = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> PAQUETES_CANAL = new ConcurrentHashMap<>();

    private static final AtomicLong nbtBytesTotal = new AtomicLong();
    private static final AtomicLong nbtCantidad = new AtomicLong();
    private static final AtomicLong nbtMayor = new AtomicLong();

    private static final List<NbtGrande> GRANDES = new ArrayList<>();
    private static final int MAX_GRANDES = 25;

    private static volatile long inicioMedicionMs = System.currentTimeMillis();

    /**
     * Interruptor de la medicion de NBT.
     *
     * Apagado por defecto. El hook vive en el buffer por el que pasa TODO el
     * trafico del protocolo, asi que aunque cada medicion sea barata, se ejecuta
     * miles de veces por segundo. Sin este interruptor, una herramienta de
     * diagnostico estaria costando rendimiento todo el tiempo para responder una
     * pregunta que el usuario hace una vez cada tanto.
     *
     * El conteo por canal, en cambio, va siempre: son unos pocos paquetes por
     * segundo y el dato sirve para el aviso automatico de trafico anormal.
     */
    private static volatile boolean midiendoNbt = false;

    private MonitorRed() {
    }

    public static boolean midiendoNbt() {
        return midiendoNbt;
    }

    public static void medirNbt(boolean v) {
        midiendoNbt = v;
    }

    // ------------------------------------------------------------ canales

    /** Registra un paquete de datos de mod. La llama el hook de red. */
    public static void registrarPaquete(String canal, int bytes) {
        if (canal == null || canal.isEmpty() || bytes <= 0) {
            return;
        }
        BYTES_CANAL.computeIfAbsent(canal, k -> new AtomicLong()).addAndGet(bytes);
        PAQUETES_CANAL.computeIfAbsent(canal, k -> new AtomicLong()).incrementAndGet();
    }

    public static List<Canal> canales() {
        List<Canal> out = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : BYTES_CANAL.entrySet()) {
            String canal = e.getKey();
            long paquetes = PAQUETES_CANAL.getOrDefault(canal, new AtomicLong()).get();
            out.add(new Canal(canal, namespaceDe(canal), e.getValue().get(), paquetes));
        }
        out.sort(Comparator.comparingLong(Canal::bytes).reversed());
        return out;
    }

    private static String namespaceDe(String canal) {
        int i = canal.indexOf(':');
        return i <= 0 ? canal : canal.substring(0, i);
    }

    public static long bytesTotales() {
        long total = 0L;
        for (AtomicLong v : BYTES_CANAL.values()) {
            total += v.get();
        }
        return total;
    }

    public static long paquetesTotales() {
        long total = 0L;
        for (AtomicLong v : PAQUETES_CANAL.values()) {
            total += v.get();
        }
        return total;
    }

    /** Bytes por segundo desde que arranco la medicion. */
    public static double bytesPorSegundo() {
        double segundos = Math.max(1.0, (System.currentTimeMillis() - inicioMedicionMs) / 1000.0);
        return bytesTotales() / segundos;
    }

    // ---------------------------------------------------------------- NBT

    /** Registra un NBT que paso por la red. La llama el mixin del buffer. */
    public static void registrarNbt(int bytes, String contexto) {
        if (bytes <= 0) {
            return;
        }
        nbtBytesTotal.addAndGet(bytes);
        nbtCantidad.incrementAndGet();
        nbtMayor.accumulateAndGet(bytes, Math::max);

        if (bytes >= NBT_GRANDE_BYTES) {
            synchronized (GRANDES) {
                GRANDES.add(new NbtGrande(System.currentTimeMillis(), bytes,
                        contexto == null ? "sin contexto" : contexto));
                while (GRANDES.size() > MAX_GRANDES) {
                    GRANDES.remove(0);
                }
            }
        }
    }

    public static long nbtBytes() {
        return nbtBytesTotal.get();
    }

    public static long nbtCantidad() {
        return nbtCantidad.get();
    }

    public static int nbtMayorBytes() {
        return (int) nbtMayor.get();
    }

    public static double nbtPromedioBytes() {
        long n = nbtCantidad.get();
        return n == 0 ? 0 : nbtBytesTotal.get() / (double) n;
    }

    public static List<NbtGrande> nbtGrandes() {
        synchronized (GRANDES) {
            List<NbtGrande> copia = new ArrayList<>(GRANDES);
            copia.sort(Comparator.comparingInt(NbtGrande::bytes).reversed());
            return copia;
        }
    }

    // ------------------------------------------------------------ lectura

    public static void reiniciar() {
        BYTES_CANAL.clear();
        PAQUETES_CANAL.clear();
        nbtBytesTotal.set(0);
        nbtCantidad.set(0);
        nbtMayor.set(0);
        synchronized (GRANDES) {
            GRANDES.clear();
        }
        inicioMedicionMs = System.currentTimeMillis();
    }

    public static long segundosMidiendo() {
        return (System.currentTimeMillis() - inicioMedicionMs) / 1000L;
    }

    /**
     * Canales cuyo volumen se sale de lo normal.
     *
     * El criterio es relativo, no un numero fijo: se marca el canal que se lleva
     * mas del 40% del trafico total teniendo competencia, o el que pasa de 8 KB/s
     * sostenidos. Un umbral absoluto no serviria: un pack chico y uno de 190 mods
     * mueven volumenes completamente distintos y ambos pueden estar sanos.
     */
    public static List<Canal> anormales() {
        List<Canal> todos = canales();
        if (todos.size() < 2) {
            return List.of();
        }
        long total = bytesTotales();
        double segundos = Math.max(1.0, segundosMidiendo());

        List<Canal> out = new ArrayList<>();
        for (Canal c : todos) {
            boolean dominante = total > 64 * 1024 && c.bytes() * 100.0 / total > 40.0;
            boolean torrente = (c.bytes() / segundos) > 8 * 1024;
            if (dominante || torrente) {
                out.add(c);
            }
        }
        return out;
    }

    public static String veredicto() {
        long total = bytesTotales();
        if (total == 0) {
            return "Todavia no paso trafico de mods por la conexion. En un mundo local esto "
                    + "puede quedarse en cero: no hay red de por medio.";
        }
        List<Canal> raros = anormales();
        if (raros.isEmpty()) {
            return String.format(
                    "%s en %d canales, %.1f KB/s. Ningun mod se sale de lo normal.",
                    legible(total), canales().size(), bytesPorSegundo() / 1024.0);
        }
        Canal peor = raros.get(0);
        return String.format(
                "'%s' se lleva %s (%.0f%% del total). Si sentis tirones al moverte, empeza por ahi.",
                peor.modId(), peor.bytesLegibles(), peor.bytes() * 100.0 / total);
    }

    public static String legible(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

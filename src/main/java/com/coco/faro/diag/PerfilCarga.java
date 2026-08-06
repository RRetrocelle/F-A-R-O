package com.coco.faro.diag;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cuanto tarda cada mod en inicializarse.
 *
 * De donde sale el numero: Forge le entrega los eventos del ciclo de vida a cada
 * mod llamando {@code ModContainer.acceptEvent(...)}. Un Mixin cronometra esa
 * llamada y suma el tiempo por modId. Es medicion directa, no estimacion — el
 * reloj arranca justo antes de entrar al codigo del mod y para al salir.
 *
 * Que queda AFUERA de la cuenta, y conviene tenerlo presente al leer el grafico:
 *
 *   - El tiempo de abrir el .jar y escanear sus clases. Eso pasa antes de que
 *     exista el ModContainer y lo hace ModLauncher, no el mod.
 *   - El registro de mixins, que se aplica al cargar cada clase parcheada, o sea
 *     repartido por toda la sesion en vez de concentrado en el arranque.
 *   - Todo lo que un mod haga perezosamente la primera vez que se usa.
 *
 * Por eso el total medido casi nunca coincide con el tiempo de arranque completo,
 * y la pantalla muestra los dos numeros por separado en vez de fingir que uno
 * explica al otro.
 */
public final class PerfilCarga {

    /** Lo medido para un mod. */
    public record Medicion(String modId, double milisegundos, int eventos) {
    }

    private static final Map<String, AtomicLong> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> EVENTOS = new ConcurrentHashMap<>();

    /** Momento en que el ultimo evento del ciclo de vida termino. */
    private static volatile long finCargaMs = 0L;

    private PerfilCarga() {
    }

    /**
     * Registra el tiempo de una entrega de evento. La llama el Mixin.
     *
     * Es {@code static} y con mapas concurrentes porque Forge despacha los
     * eventos del ciclo de vida en paralelo entre mods: dos hilos pueden estar
     * sumando a la vez.
     */
    public static void registrar(String modId, long nanos) {
        if (modId == null || modId.isEmpty() || nanos <= 0) {
            return;
        }
        NANOS.computeIfAbsent(modId, k -> new AtomicLong()).addAndGet(nanos);
        EVENTOS.computeIfAbsent(modId, k -> new AtomicLong()).incrementAndGet();
        finCargaMs = System.currentTimeMillis();
    }

    public static boolean hayDatos() {
        return !NANOS.isEmpty();
    }

    /** Mediciones ordenadas de la mas lenta a la mas rapida. */
    public static List<Medicion> ranking() {
        List<Medicion> out = new ArrayList<>(NANOS.size());
        for (Map.Entry<String, AtomicLong> e : NANOS.entrySet()) {
            long eventos = EVENTOS.getOrDefault(e.getKey(), new AtomicLong()).get();
            out.add(new Medicion(e.getKey(), e.getValue().get() / 1_000_000.0, (int) eventos));
        }
        out.sort(Comparator.comparingDouble(Medicion::milisegundos).reversed());
        return out;
    }

    public static double totalMedidoMs() {
        double total = 0.0;
        for (AtomicLong v : NANOS.values()) {
            total += v.get() / 1_000_000.0;
        }
        return total;
    }

    public static int modsMedidos() {
        return NANOS.size();
    }

    /**
     * Tiempo desde que arranco la JVM hasta que termino de cargar el ultimo mod.
     *
     * Es lo mas cercano a "cuanto tardo en arrancar" que se puede medir desde
     * adentro. Incluye el arranque de Java y todo el escaneo previo de Forge,
     * cosas que ningun mod controla.
     */
    public static double arranqueTotalSegundos() {
        try {
            long inicioJvm = ManagementFactory.getRuntimeMXBean().getStartTime();
            long fin = finCargaMs > 0 ? finCargaMs : System.currentTimeMillis();
            return Math.max(0.0, (fin - inicioJvm) / 1000.0);
        } catch (Throwable t) {
            return -1.0;
        }
    }

    /** Porcentaje del tiempo medido que se lleva un mod. */
    public static double porcentaje(Medicion m) {
        double total = totalMedidoMs();
        return total <= 0 ? 0 : (m.milisegundos() * 100.0 / total);
    }

    /**
     * Veredicto en una linea, con el criterio explicito.
     *
     * 500 ms es el umbral donde un solo mod ya se nota en el arranque de un pack
     * grande; 2000 ms es donde deja de ser discutible.
     */
    public static String veredicto() {
        if (!hayDatos()) {
            return "Sin datos: el mixin que mide la carga no llego a aplicarse en este arranque.";
        }
        List<Medicion> r = ranking();
        Medicion peor = r.get(0);
        if (peor.milisegundos() >= 2000) {
            return String.format(
                    "%s se llevo %.1f s el solo (%.0f%% del total medido). Es el que mas te cuesta al arrancar.",
                    peor.modId(), peor.milisegundos() / 1000.0, porcentaje(peor));
        }
        if (peor.milisegundos() >= 500) {
            return String.format(
                    "%s es el mas lento con %.0f ms. Se nota pero esta dentro de lo esperable.",
                    peor.modId(), peor.milisegundos());
        }
        return String.format(
                "Ningun mod domina la carga: el mas lento es %s con %.0f ms. "
                        + "Si el arranque igual se hace largo, el costo esta en el escaneo de "
                        + "archivos y los mixins, que no se miden aca.",
                peor.modId(), peor.milisegundos());
    }
}

package com.coco.faro.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Mide lo que realmente pasa en esta maquina, en vez de suponerlo.
 *
 * Un tick del cliente deberia durar 50 ms o menos. Si el promedio se acerca a
 * ese numero, el equipo esta al limite y agregar mods de contenido va a doler.
 * Ese dato, medido, vale mas que cualquier recomendacion generica.
 *
 * Guarda una ventana movil de los ultimos ticks para poder dibujar un grafico y
 * calcular percentiles. El p95 importa mas que el promedio: los tirones son lo
 * que se siente al jugar, y el promedio los esconde.
 */
public final class MonitorRendimiento {

    /** Cuantos ticks guardamos: 200 ticks = ~10 segundos de historia. */
    private static final int VENTANA = 200;

    /** A partir de aca consideramos que hubo un tiron. */
    private static final double UMBRAL_PICO_MS = 100.0;

    private static final int MAX_PICOS = 20;

    public record Pico(long momento, double duracionMs, String contexto) {
    }

    private final double[] muestras = new double[VENTANA];
    private int escritas = 0;
    private int posicion = 0;

    private final Deque<Pico> picos = new ArrayDeque<>();

    private long inicioTickNs = 0L;
    private long totalTicks = 0L;
    private double peorTickMs = 0.0;

    public synchronized void marcarInicioTick() {
        inicioTickNs = System.nanoTime();
    }

    public synchronized void marcarFinTick(String contexto) {
        if (inicioTickNs == 0L) {
            return;
        }
        double ms = (System.nanoTime() - inicioTickNs) / 1_000_000.0;
        inicioTickNs = 0L;
        totalTicks++;

        muestras[posicion] = ms;
        posicion = (posicion + 1) % VENTANA;
        if (escritas < VENTANA) {
            escritas++;
        }

        if (ms > peorTickMs) {
            peorTickMs = ms;
        }
        if (ms >= UMBRAL_PICO_MS) {
            picos.addLast(new Pico(System.currentTimeMillis(), ms, contexto));
            while (picos.size() > MAX_PICOS) {
                picos.removeFirst();
            }
        }
    }

    public synchronized double promedioMs() {
        if (escritas == 0) {
            return 0.0;
        }
        double suma = 0.0;
        for (int i = 0; i < escritas; i++) {
            suma += muestras[i];
        }
        return suma / escritas;
    }

    /** Percentil 95: cuanto duran los ticks malos, no los tipicos. */
    public synchronized double p95Ms() {
        if (escritas == 0) {
            return 0.0;
        }
        double[] copia = Arrays.copyOf(muestras, escritas);
        Arrays.sort(copia);
        int idx = (int) Math.floor(copia.length * 0.95);
        return copia[Math.min(idx, copia.length - 1)];
    }

    public synchronized double peorMs() {
        return peorTickMs;
    }

    public synchronized long totalTicks() {
        return totalTicks;
    }

    /** Copia de la ventana en orden cronologico, para dibujar el grafico. */
    public synchronized List<Double> historia() {
        List<Double> out = new ArrayList<>(escritas);
        if (escritas < VENTANA) {
            for (int i = 0; i < escritas; i++) {
                out.add(muestras[i]);
            }
        } else {
            for (int i = 0; i < VENTANA; i++) {
                out.add(muestras[(posicion + i) % VENTANA]);
            }
        }
        return out;
    }

    public synchronized List<Pico> picos() {
        return new ArrayList<>(picos);
    }

    public synchronized void reiniciar() {
        Arrays.fill(muestras, 0.0);
        escritas = 0;
        posicion = 0;
        totalTicks = 0;
        peorTickMs = 0.0;
        picos.clear();
    }

    // ------------------------------------------------------------------ memoria

    public static long memoriaUsadaMB() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
    }

    public static long memoriaMaximaMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    public static int porcentajeMemoria() {
        long max = memoriaMaximaMB();
        return max == 0 ? 0 : (int) (memoriaUsadaMB() * 100 / max);
    }

    /**
     * Veredicto en una linea sobre el estado del tick, para mostrar arriba de todo.
     * Los umbrales salen de que un tick dura 50 ms: si el p95 lo pasa, se nota.
     */
    public synchronized String veredicto() {
        if (escritas < 20) {
            return "Midiendo... jugá un rato para tener datos utiles.";
        }
        double p95 = p95Ms();
        if (p95 < 20) {
            return "El tick va comodo. Hay margen para mas mods.";
        }
        if (p95 < 40) {
            return "El tick va bien, con algo de margen.";
        }
        if (p95 < 50) {
            return "El tick esta justo. Evitá sumar mods pesados.";
        }
        return "El tick se pasa de 50 ms: estas perdiendo TPS y se siente al jugar.";
    }

    public synchronized int colorVeredicto() {
        if (escritas < 20) {
            return 0xFF8B98A5;
        }
        double p95 = p95Ms();
        if (p95 < 20) return 0xFF3FB950;
        if (p95 < 40) return 0xFF3FB950;
        if (p95 < 50) return 0xFFD29922;
        return 0xFFF85149;
    }
}

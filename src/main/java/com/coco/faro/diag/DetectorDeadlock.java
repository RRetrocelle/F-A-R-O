package com.coco.faro.diag;

import com.coco.faro.Faro;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detecta congelamientos: el juego deja de responder pero no se cierra.
 *
 * Es el caso mas frustrante de diagnosticar, justamente porque NO hay crash
 * report: la ventana queda en blanco o repitiendo el ultimo cuadro, Windows dice
 * "no responde", y al matar el proceso no queda rastro de nada.
 *
 * Dos deteccciones independientes, porque son fenomenos distintos:
 *
 *   1. DEADLOCK REAL — dos hilos esperandose mutuamente. La JVM lo sabe con
 *      certeza absoluta y lo dice: {@code ThreadMXBean.findDeadlockedThreads()}.
 *      Si esto dispara, no hay interpretacion posible, es un abrazo mortal.
 *
 *   2. CUELGUE DEL HILO PRINCIPAL — el hilo del cliente dejo de completar ticks
 *      hace mas de N segundos. Puede ser un bucle infinito, una lectura de disco
 *      o de red que no termina, o generacion de mundo pesada. No es un deadlock
 *      formal y la JVM no lo reporta; lo detectamos con un latido.
 *
 * En los dos casos se vuelca el stacktrace de los hilos involucrados a
 * {@code faro/congelamientos.log} DESDE UN HILO WATCHDOG APARTE. Ese detalle es
 * todo el valor de esta clase: el hilo principal esta trabado y no puede escribir
 * nada, pero el watchdog sigue vivo y deja constancia de donde se quedo.
 */
public final class DetectorDeadlock {

    /** Segundos sin latido a partir de los cuales se considera colgado. */
    private static final long UMBRAL_CUELGUE_MS = 10_000L;

    /** Cada cuanto revisa el watchdog. */
    private static final long INTERVALO_MS = 2_000L;

    public enum Tipo { DEADLOCK, CUELGUE }

    public record Incidente(Tipo tipo, long momento, long duracionMs,
                            List<String> hilos, String volcado, List<String> modsSospechosos) {

        public String titulo() {
            return tipo == Tipo.DEADLOCK
                    ? "Deadlock: dos hilos se estan esperando entre si"
                    : "El hilo principal dejo de responder";
        }
    }

    private static final DetectorDeadlock INSTANCIA = new DetectorDeadlock();

    private final AtomicLong ultimoLatido = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean corriendo = new AtomicBoolean(false);
    private final AtomicBoolean cuelgueAvisado = new AtomicBoolean(false);

    private volatile java.nio.file.Path archivoSalida;
    private final List<Incidente> incidentes = new ArrayList<>();

    private DetectorDeadlock() {
    }

    public static DetectorDeadlock get() {
        return INSTANCIA;
    }

    /** El hilo del cliente llama a esto en cada tick. Es el latido. */
    public void latir() {
        ultimoLatido.set(System.currentTimeMillis());
        // Salir de un cuelgue rearma la deteccion: si vuelve a pasar, se avisa de nuevo.
        cuelgueAvisado.set(false);
    }

    public long msSinLatido() {
        return System.currentTimeMillis() - ultimoLatido.get();
    }

    public boolean colgadoAhora() {
        return msSinLatido() > UMBRAL_CUELGUE_MS;
    }

    public List<Incidente> incidentes() {
        synchronized (incidentes) {
            return new ArrayList<>(incidentes);
        }
    }

    public java.nio.file.Path archivo() {
        return archivoSalida;
    }

    /**
     * Arranca el watchdog.
     *
     * Prioridad MAXIMA a proposito, al reves que el resto de los hilos de Faro:
     * cuando el hilo principal esta acaparando la CPU en un bucle, un watchdog de
     * prioridad minima puede no llegar nunca a ejecutarse, que es exactamente
     * cuando mas se lo necesita. Cuesta despertarse cada 2 segundos.
     */
    public void iniciar(java.nio.file.Path carpetaFaro) {
        if (!corriendo.compareAndSet(false, true)) {
            return;
        }
        this.archivoSalida = carpetaFaro.resolve("congelamientos.log");

        Thread t = new Thread(this::bucle, "Faro-Watchdog");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        Faro.LOG.info("[Faro] Watchdog de congelamientos activo.");
    }

    private void bucle() {
        while (corriendo.get()) {
            try {
                revisarDeadlock();
                revisarCuelgue();
            } catch (Throwable ignored) {
                // El watchdog no puede morir por una excepcion: es el unico que
                // sigue vivo cuando todo lo demas se traba.
            }
            try {
                Thread.sleep(INTERVALO_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void revisarDeadlock() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] ids = bean.findDeadlockedThreads();
        if (ids == null || ids.length == 0) {
            return;
        }
        ThreadInfo[] info = bean.getThreadInfo(ids, true, true);

        List<String> nombres = new ArrayList<>();
        StringBuilder volcado = new StringBuilder();
        for (ThreadInfo i : info) {
            if (i == null) {
                continue;
            }
            nombres.add(i.getThreadName());
            volcado.append(formatear(i)).append("\n");
        }
        registrar(new Incidente(Tipo.DEADLOCK, System.currentTimeMillis(), 0,
                nombres, volcado.toString(), modsEn(volcado.toString())));
    }

    private void revisarCuelgue() {
        long sinLatido = msSinLatido();
        if (sinLatido <= UMBRAL_CUELGUE_MS) {
            return;
        }
        // Un solo aviso por episodio: si el juego queda colgado 5 minutos no
        // tiene sentido escribir 150 volcados identicos.
        if (!cuelgueAvisado.compareAndSet(false, true)) {
            return;
        }

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        StringBuilder volcado = new StringBuilder();
        List<String> nombres = new ArrayList<>();

        for (ThreadInfo i : bean.dumpAllThreads(true, true)) {
            if (i == null) {
                continue;
            }
            String nombre = i.getThreadName();
            // Solo los hilos que importan para explicar un cuelgue del juego.
            if (!nombre.startsWith("Render thread")
                    && !nombre.startsWith("Server thread")
                    && !nombre.contains("Worker")
                    && !nombre.contains("Chunk")) {
                continue;
            }
            nombres.add(nombre);
            volcado.append(formatear(i)).append("\n");
        }
        registrar(new Incidente(Tipo.CUELGUE, System.currentTimeMillis(), sinLatido,
                nombres, volcado.toString(), modsEn(volcado.toString())));
    }

    private void registrar(Incidente inc) {
        synchronized (incidentes) {
            // Sin tope alto: un cuelgue repetido es informacion, pero 200 copias no.
            if (incidentes.size() >= 20) {
                incidentes.remove(0);
            }
            incidentes.add(inc);
        }
        escribir(inc);
        Faro.LOG.error("[Faro] {} — volcado en {}", inc.titulo(), archivoSalida);
    }

    private void escribir(Incidente inc) {
        if (archivoSalida == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(archivoSalida.getParent());
            String texto = "\n========================================\n"
                    + java.time.LocalDateTime.now() + "  —  " + inc.titulo() + "\n"
                    + (inc.duracionMs() > 0
                    ? "Sin responder desde hace " + (inc.duracionMs() / 1000) + " s\n" : "")
                    + (inc.modsSospechosos().isEmpty() ? ""
                    : "Mods que aparecen en el volcado: " + String.join(", ", inc.modsSospechosos()) + "\n")
                    + "----------------------------------------\n"
                    + inc.volcado() + "\n";
            java.nio.file.Files.writeString(archivoSalida, texto,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude escribir el volcado: {}", t.toString());
        }
    }

    private static String formatear(ThreadInfo i) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"").append(i.getThreadName()).append("\"  estado=").append(i.getThreadState());
        if (i.getLockName() != null) {
            sb.append("  esperando=").append(i.getLockName());
        }
        if (i.getLockOwnerName() != null) {
            sb.append("  bloqueado por=\"").append(i.getLockOwnerName()).append("\"");
        }
        sb.append("\n");
        StackTraceElement[] pila = i.getStackTrace();
        int n = Math.min(pila.length, 30);
        for (int k = 0; k < n; k++) {
            sb.append("    at ").append(pila[k]).append("\n");
        }
        if (pila.length > n) {
            sb.append("    ... ").append(pila.length - n).append(" mas\n");
        }
        return sb.toString();
    }

    /**
     * Saca modIds probables del volcado.
     *
     * Es la misma heuristica de paquete->modId que usa el motor de sospecha, con
     * la misma limitacion: acierta seguido pero no siempre, y por eso la pantalla
     * lo presenta como "aparecen en el volcado", no como culpables.
     */
    private static List<String> modsEn(String volcado) {
        List<String> out = new ArrayList<>();
        for (String linea : volcado.split("\\R")) {
            String t = linea.trim();
            if (!t.startsWith("at ")) {
                continue;
            }
            String clase = t.substring(3);
            if (clase.startsWith("net.minecraft.") || clase.startsWith("java.")
                    || clase.startsWith("jdk.") || clase.startsWith("sun.")
                    || clase.startsWith("net.minecraftforge.") || clase.startsWith("com.mojang.")) {
                continue;
            }
            String[] partes = clase.split("\\.");
            if (partes.length >= 3) {
                String candidato = partes[2].toLowerCase(java.util.Locale.ROOT);
                if (candidato.length() >= 3 && !out.contains(candidato)) {
                    out.add(candidato);
                }
            }
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }
}

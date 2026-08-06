package com.coco.faro.diag;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee logs/latest.log de forma incremental mientras el juego corre, y lleva la
 * cuenta de errores y advertencias nuevos.
 *
 * Corre en un hilo daemon aparte y solo lee los bytes nuevos desde la ultima
 * pasada, asi que no reprocesa el archivo entero ni bloquea el hilo del juego.
 * Esto importa en esta maquina: el objetivo es avisar sin costar rendimiento.
 */
public final class VigilanteLog {

    /** Formato tipico de Forge 1.20.1: [12:34:56] [Render thread/ERROR] [modid/Clase]: mensaje */
    private static final Pattern LINEA_LOG = Pattern.compile(
            "^\\[(\\d{2}:\\d{2}:\\d{2})\\]\\s*\\[([^/\\]]+)/([A-Z]+)\\](?:\\s*\\[([^\\]]+)\\])?:\\s*(.*)$");

    private static final int MAX_EVENTOS = 60;

    /**
     * Cuantas lineas completas guardamos para la consola en vivo.
     *
     * Es un buffer aparte del de errores: aquel solo retiene ERROR y WARN para el
     * diagnostico, mientras que la consola necesita ver TODO, incluido el INFO.
     * 400 lineas alcanzan para desplazarse un rato hacia atras sin que el mod
     * empiece a acumular memoria por su cuenta.
     */
    private static final int MAX_LINEAS_CONSOLA = 400;

    public record Evento(String hora, String nivel, String origen, String mensaje) {
        public boolean esError() {
            return "ERROR".equals(nivel) || "FATAL".equals(nivel);
        }
    }

    private final Path archivoLog;
    private final Deque<Evento> eventos = new ArrayDeque<>();
    private final Deque<Evento> consola = new ArrayDeque<>();
    private final Map<String, Integer> porOrigen = new HashMap<>();
    private final AtomicInteger errores = new AtomicInteger();
    private final AtomicInteger advertencias = new AtomicInteger();
    private final AtomicInteger texturasFaltantes = new AtomicInteger();
    private final AtomicBoolean hayNovedades = new AtomicBoolean(false);
    private final AtomicBoolean corriendo = new AtomicBoolean(false);

    private volatile long posicion = 0L;
    private volatile long intervaloMs = 3000L;
    private Thread hilo;

    public VigilanteLog(Path archivoLog) {
        this.archivoLog = archivoLog;
    }

    public void iniciar(long intervaloMs) {
        this.intervaloMs = Math.max(1000L, intervaloMs);
        if (!corriendo.compareAndSet(false, true)) {
            return;
        }
        // Arrancamos desde el final: solo nos interesa lo que pase de ahora en mas.
        try {
            if (Files.isRegularFile(archivoLog)) {
                posicion = Files.size(archivoLog);
            }
        } catch (IOException ignored) {
        }

        hilo = new Thread(this::bucle, "Faro-VigilanteLog");
        hilo.setDaemon(true);
        hilo.setPriority(Thread.MIN_PRIORITY);
        hilo.start();
    }

    public void detener() {
        corriendo.set(false);
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    private void bucle() {
        while (corriendo.get()) {
            try {
                leerNuevas();
            } catch (Throwable ignored) {
                // Nunca dejamos que un fallo de lectura mate el hilo ni el juego.
            }
            try {
                Thread.sleep(intervaloMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void leerNuevas() throws IOException {
        if (!Files.isRegularFile(archivoLog)) {
            return;
        }
        long tamano = Files.size(archivoLog);
        if (tamano < posicion) {
            // El log rotó (se creo uno nuevo). Volvemos a empezar.
            posicion = 0L;
        }
        if (tamano == posicion) {
            return;
        }

        List<String> nuevas = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(archivoLog.toFile(), "r")) {
            raf.seek(posicion);
            String linea;
            while ((linea = raf.readLine()) != null) {
                // readLine() de RandomAccessFile devuelve latin-1; lo reinterpretamos.
                nuevas.add(new String(linea.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
            }
            posicion = raf.getFilePointer();
        }

        for (String linea : nuevas) {
            procesar(linea);
        }
    }

    private void procesar(String linea) {
        Matcher m = LINEA_LOG.matcher(linea);
        if (!m.matches()) {
            return;
        }
        String nivel = m.group(3);

        String origenBruto = m.group(4) == null ? "?" : m.group(4);
        String origenCorto = origenBruto.contains("/")
                ? origenBruto.substring(0, origenBruto.indexOf('/'))
                : origenBruto;

        // La consola se queda con TODAS las lineas, sin filtrar por nivel.
        synchronized (consola) {
            consola.addLast(new Evento(m.group(1), nivel, origenCorto,
                    recortar(m.group(5) == null ? "" : m.group(5), 300)));
            while (consola.size() > MAX_LINEAS_CONSOLA) {
                consola.removeFirst();
            }
        }

        if (!"ERROR".equals(nivel) && !"WARN".equals(nivel) && !"FATAL".equals(nivel)) {
            return;
        }

        String origenCrudo = m.group(4) == null ? "?" : m.group(4);
        String origen = origenCrudo.contains("/")
                ? origenCrudo.substring(0, origenCrudo.indexOf('/'))
                : origenCrudo;
        String mensaje = m.group(5) == null ? "" : m.group(5);

        if ("WARN".equals(nivel)) {
            advertencias.incrementAndGet();
        } else {
            errores.incrementAndGet();
        }

        String bajo = mensaje.toLowerCase(Locale.ROOT);
        if (bajo.contains("missing texture") || bajo.contains("unable to load")
                || bajo.contains("file not found") && bajo.contains("texture")) {
            texturasFaltantes.incrementAndGet();
        }

        synchronized (eventos) {
            eventos.addLast(new Evento(m.group(1), nivel, origen, recortar(mensaje, 160)));
            while (eventos.size() > MAX_EVENTOS) {
                eventos.removeFirst();
            }
            porOrigen.merge(origen, 1, Integer::sum);
        }
        hayNovedades.set(true);
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public int errores() {
        return errores.get();
    }

    public int advertencias() {
        return advertencias.get();
    }

    public int texturasFaltantes() {
        return texturasFaltantes.get();
    }

    /** true si hubo eventos nuevos desde la ultima vez que se limpio la bandera. */
    public boolean hayNovedades() {
        return hayNovedades.get();
    }

    public void limpiarBandera() {
        hayNovedades.set(false);
    }

    /** Todas las lineas recientes del log, de la mas vieja a la mas nueva. */
    public List<Evento> lineasConsola() {
        synchronized (consola) {
            return new ArrayList<>(consola);
        }
    }

    public List<Evento> ultimosEventos(int cuantos) {
        synchronized (eventos) {
            List<Evento> lista = new ArrayList<>(eventos);
            int desde = Math.max(0, lista.size() - cuantos);
            return new ArrayList<>(lista.subList(desde, lista.size()));
        }
    }

    /**
     * Origen (normalmente un modid) que mas ruido esta metiendo, si hay alguno.
     *
     * Se sincroniza sobre 'eventos' y no sobre 'porOrigen' a proposito: ese es el
     * mismo monitor bajo el que se escribe el mapa en procesar(). Usar candados
     * distintos para escritura y lectura no protege nada.
     */
    public String origenMasRuidoso() {
        synchronized (eventos) {
            return porOrigen.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    /** Conteo de eventos por origen, ordenado de mayor a menor. */
    public List<Map.Entry<String, Integer>> rankingOrigenes(int cuantos) {
        synchronized (eventos) {
            return porOrigen.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(cuantos)
                    .map(e -> (Map.Entry<String, Integer>)
                            new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                    .toList();
        }
    }

    public void reiniciarContadores() {
        errores.set(0);
        advertencias.set(0);
        texturasFaltantes.set(0);
        synchronized (eventos) {
            eventos.clear();
            porOrigen.clear();
        }
        hayNovedades.set(false);
    }
}

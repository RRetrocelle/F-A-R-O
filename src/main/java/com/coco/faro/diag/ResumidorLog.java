package com.coco.faro.diag;

import com.coco.faro.Faro;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reductor y resumidor de latest.log.
 *
 * El problema concreto: latest.log de un pack de 190 mods son 40.000 lineas y 8
 * MB. Nadie lo lee. Y cuando pedis ayuda, pegarlo entero en un chat es
 * impracticable — se pierde justo lo que importa entre miles de lineas de
 * "Registering block X".
 *
 * Lo que hace la reduccion, en este orden:
 *
 *   1. Descarta el ruido de arranque conocido (registros, carga de assets,
 *      advertencias de deprecacion). Son lineas que aparecen SIEMPRE, tanto
 *      cuando todo anda como cuando todo falla, asi que no informan nada.
 *   2. Agrupa las lineas repetidas. Un error que aparece 4.000 veces ocupa una
 *      linea con su contador, no 4.000. Esto solo suele bajar el archivo un 90%.
 *   3. Colapsa los stacktraces: se conservan la excepcion y los primeros frames
 *      que NO son del juego, que es donde esta la informacion.
 *   4. Ordena lo que queda por gravedad y por cuantas veces se repitio.
 *
 * El resultado se puede escribir a un archivo y copiar al portapapeles. La
 * reduccion es agresiva a proposito: si hace falta el log completo, sigue estando
 * ahi. Esto es para poder mirarlo y para poder pedir ayuda sin pegar 8 MB.
 */
public final class ResumidorLog {

    private static final Pattern LINEA = Pattern.compile(
            "^\\[(\\d{2}:\\d{2}:\\d{2})\\]\\s*\\[([^/\\]]+)/([A-Z]+)\\](?:\\s*\\[([^\\]]+)\\])?:\\s*(.*)$");

    /** Patron de una linea de stacktrace. */
    private static final Pattern FRAME = Pattern.compile("^\\s+at\\s+(\\S+)\\(.*\\)$");

    /**
     * Lineas que aparecen en todos los arranques y no distinguen un log sano de
     * uno roto. Se listan explicitas para que se pueda auditar que se descarto.
     */
    private static final String[] RUIDO = {
            "registering ", "registered ", "loading registry", "applying attribute",
            "found mod file", "scanning ", "adding ", "injecting ", "building ",
            "reloading resourcepack", "loaded config", "baking ", "compiling ",
            "deprecated", "no data fixer", "unable to find a data fixer",
            "using system font", "loading texture atlas", "created:", "narrator library",
            "openal initialized", "sound engine started", "prepared ", "mixin config",
            "successfully loaded", "starting up soundsystem",
    };

    /** Un grupo de lineas equivalentes. */
    public record Grupo(String nivel, String origen, String mensaje, int veces,
                        String primeraHora, String ultimaHora, List<String> frames) {

        public boolean esError() {
            return "ERROR".equals(nivel) || "FATAL".equals(nivel);
        }

        /** Peso para ordenar: primero lo grave, y dentro de eso lo que mas se repite. */
        public int peso() {
            int base = switch (nivel) {
                case "FATAL" -> 4000;
                case "ERROR" -> 3000;
                case "WARN" -> 1000;
                default -> 0;
            };
            return base + Math.min(999, veces);
        }
    }

    public record Resumen(List<Grupo> grupos, int lineasOriginales, int lineasConservadas,
                          long bytesOriginales, long duracionMs, Path archivo,
                          List<Map.Entry<String, Integer>> porOrigen) {

        public double reduccionPorcentaje() {
            return lineasOriginales == 0 ? 0
                    : 100.0 - (lineasConservadas * 100.0 / lineasOriginales);
        }

        public int errores() {
            return grupos.stream().filter(Grupo::esError).mapToInt(Grupo::veces).sum();
        }

        public int advertencias() {
            return grupos.stream().filter(g -> "WARN".equals(g.nivel())).mapToInt(Grupo::veces).sum();
        }
    }

    private ResumidorLog() {
    }

    /** Reduce latest.log. Corre en un hilo aparte: el archivo puede ser enorme. */
    public static Resumen analizar(Path archivoLog) {
        long inicio = System.currentTimeMillis();

        if (archivoLog == null || !Files.isRegularFile(archivoLog)) {
            return new Resumen(List.of(), 0, 0, 0, 0, archivoLog, List.of());
        }

        long bytes = 0;
        List<String> lineas;
        try {
            bytes = Files.size(archivoLog);
            // ISO_8859_1 nunca falla, y despues se reinterpreta cada linea. Leer
            // como UTF-8 directo revienta si el log tiene un byte suelto raro,
            // cosa que pasa con mods que escriben nombres con acentos.
            lineas = Files.readAllLines(archivoLog, StandardCharsets.ISO_8859_1);
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude leer el log: {}", t.toString());
            return new Resumen(List.of(), 0, 0, 0, 0, archivoLog, List.of());
        }

        Map<String, Grupo> grupos = new LinkedHashMap<>();
        Map<String, Integer> porOrigen = new LinkedHashMap<>();
        int conservadas = 0;

        String claveActual = null;

        for (String cruda : lineas) {
            String linea = new String(cruda.getBytes(StandardCharsets.ISO_8859_1),
                    StandardCharsets.UTF_8);

            // Frame de stacktrace: pertenece al grupo anterior.
            Matcher frame = FRAME.matcher(linea);
            if (frame.matches() && claveActual != null) {
                Grupo g = grupos.get(claveActual);
                if (g != null && g.frames().size() < 8 && esFrameUtil(frame.group(1))) {
                    g.frames().add(frame.group(1));
                }
                continue;
            }

            Matcher m = LINEA.matcher(linea);
            if (!m.matches()) {
                claveActual = null;
                continue;
            }

            String hora = m.group(1);
            String nivel = m.group(3);
            String origenCrudo = m.group(4) == null ? "?" : m.group(4);
            String origen = origenCrudo.contains("/")
                    ? origenCrudo.substring(0, origenCrudo.indexOf('/')) : origenCrudo;
            String mensaje = m.group(5) == null ? "" : m.group(5);

            if (!"ERROR".equals(nivel) && !"WARN".equals(nivel) && !"FATAL".equals(nivel)) {
                claveActual = null;
                continue;
            }
            if (esRuido(mensaje)) {
                claveActual = null;
                continue;
            }

            conservadas++;
            porOrigen.merge(origen, 1, Integer::sum);

            String normalizado = normalizar(mensaje);
            String clave = nivel + "|" + origen + "|" + normalizado;
            claveActual = clave;

            Grupo previo = grupos.get(clave);
            if (previo == null) {
                grupos.put(clave, new Grupo(nivel, origen, recortar(mensaje, 300), 1,
                        hora, hora, new ArrayList<>()));
            } else {
                grupos.put(clave, new Grupo(previo.nivel(), previo.origen(), previo.mensaje(),
                        previo.veces() + 1, previo.primeraHora(), hora, previo.frames()));
            }
        }

        List<Grupo> ordenados = new ArrayList<>(grupos.values());
        ordenados.sort(Comparator.comparingInt(Grupo::peso).reversed());

        List<Map.Entry<String, Integer>> ranking = porOrigen.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(15)
                .map(e -> (Map.Entry<String, Integer>)
                        new java.util.AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                .toList();

        long duracion = System.currentTimeMillis() - inicio;
        MonitorHardware.get().registrarTrabajoPropio(duracion * 1_000_000L);

        return new Resumen(ordenados, lineas.size(), conservadas, bytes, duracion,
                archivoLog, ranking);
    }

    /**
     * Normaliza un mensaje para agrupar variantes del mismo error.
     *
     * Reemplaza numeros, coordenadas y UUIDs por marcadores. Sin esto, "no pude
     * cargar el chunk 45,23" y "no pude cargar el chunk 46,23" serian grupos
     * distintos y volveriamos a tener 4.000 lineas.
     */
    private static String normalizar(String mensaje) {
        return mensaje
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>")
                .replaceAll("-?\\d+\\.\\d+", "<num>")
                .replaceAll("\\b-?\\d+\\b", "<n>")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private static boolean esRuido(String mensaje) {
        String bajo = mensaje.toLowerCase(Locale.ROOT);
        for (String r : RUIDO) {
            if (bajo.startsWith(r) || bajo.contains(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Un frame sirve si NO es del juego ni de la JVM.
     *
     * Los frames de net.minecraft y de java aparecen en todos los stacktraces y
     * no distinguen nada. Los de mods son los que dicen quien estaba haciendo que.
     */
    private static boolean esFrameUtil(String clase) {
        return !clase.startsWith("java.") && !clase.startsWith("jdk.")
                && !clase.startsWith("sun.") && !clase.startsWith("net.minecraft.")
                && !clase.startsWith("com.mojang.") && !clase.startsWith("io.netty.");
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ------------------------------------------------------------- salida

    /** Arma el texto del resumen, listo para pegar. */
    public static String texto(Resumen r, int maxGrupos) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Faro — resumen de latest.log ===\n");
        sb.append(String.format(Locale.ROOT,
                "%d lineas originales (%.1f MB) -> %d relevantes -> %d grupos unicos  [-%.0f%%]\n",
                r.lineasOriginales(), r.bytesOriginales() / (1024.0 * 1024.0),
                r.lineasConservadas(), r.grupos().size(), r.reduccionPorcentaje()));
        sb.append(r.errores()).append(" errores, ").append(r.advertencias()).append(" advertencias\n\n");

        if (!r.porOrigen().isEmpty()) {
            sb.append("--- quien mas reporta ---\n");
            for (Map.Entry<String, Integer> e : r.porOrigen()) {
                sb.append(String.format("  %-28s %d%n", e.getKey(), e.getValue()));
            }
            sb.append("\n");
        }

        sb.append("--- lo que importa ---\n");
        int n = Math.min(maxGrupos, r.grupos().size());
        for (int i = 0; i < n; i++) {
            ResumidorLog.Grupo g = r.grupos().get(i);
            sb.append("[").append(g.nivel()).append("] ").append(g.origen());
            if (g.veces() > 1) {
                sb.append("  x").append(g.veces())
                        .append("  (").append(g.primeraHora()).append(" -> ")
                        .append(g.ultimaHora()).append(")");
            } else {
                sb.append("  (").append(g.primeraHora()).append(")");
            }
            sb.append("\n    ").append(g.mensaje()).append("\n");
            for (String f : g.frames()) {
                sb.append("      at ").append(f).append("\n");
            }
            sb.append("\n");
        }
        if (r.grupos().size() > n) {
            sb.append("... y ").append(r.grupos().size() - n).append(" grupos mas.\n");
        }
        return sb.toString();
    }

    /** Escribe el resumen a faro/resumen-log.txt y devuelve la ruta. */
    public static Path escribir(Resumen r, Path carpetaFaro) throws Exception {
        Files.createDirectories(carpetaFaro);
        Path destino = carpetaFaro.resolve("resumen-log.txt");
        Files.writeString(destino, texto(r, 200), StandardCharsets.UTF_8);
        return destino;
    }

    public static String veredicto(Resumen r) {
        if (r.lineasOriginales() == 0) {
            return "No pude leer latest.log.";
        }
        if (r.grupos().isEmpty()) {
            return "El log esta limpio: ningun error ni advertencia relevante en "
                    + r.lineasOriginales() + " lineas.";
        }
        Grupo peor = r.grupos().get(0);
        return String.format(Locale.ROOT,
                "%d lineas reducidas a %d grupos (-%.0f%%). Lo que mas se repite: '%s' de %s, "
                        + "%d veces.",
                r.lineasOriginales(), r.grupos().size(), r.reduccionPorcentaje(),
                recortar(peor.mensaje(), 60), peor.origen(), peor.veces());
    }
}

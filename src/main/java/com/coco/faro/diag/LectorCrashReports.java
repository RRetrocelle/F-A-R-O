package com.coco.faro.diag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analiza el crash report mas reciente.
 *
 * El flujo tiene tres etapas bien separadas, y cada una puede fallar sin romper
 * las otras:
 *
 *   1. Extraccion  — datos crudos del archivo (descripcion, excepcion, stack).
 *   2. Reconocimiento — {@link BaseConocimiento} busca firmas conocidas y de ahi
 *      sale el tipo de problema y la sugerencia concreta.
 *   3. Atribucion — {@link MotorSospecha} pondera todas las senales y ordena
 *      candidatos.
 *
 * Si la etapa 2 no reconoce nada, seguimos con un tipo generico. Si la 3 no
 * encuentra un ganador claro, la confianza queda en NINGUNA y el boton de
 * reparar se apaga solo. En ningun momento se rellena con un culpable inventado.
 */
public final class LectorCrashReports {

    private static final Pattern BLOQUE_MOD = Pattern.compile("^--\\s*MOD\\s+([A-Za-z0-9_\\-]+)\\s*--\\s*$");
    private static final Pattern LINEA_AT = Pattern.compile("^\\s*at\\s+([A-Za-z0-9_$.]+)\\.[A-Za-z0-9_$<>]+\\(");
    private static final Pattern LINEA_EXCEPCION =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_$.]*(?:Exception|Error|Throwable))(?::\\s*(.*))?$");
    private static final Pattern CAMPO_ARCHIVO_MOD = Pattern.compile("Mod File:\\s*(.+)");
    private static final Pattern CAMPO_FALLO = Pattern.compile("Failure message:\\s*(.+)");

    private static final int MAX_LINEAS_STACK = 60;

    private LectorCrashReports() {
    }

    public static Optional<Path> masReciente(Path carpetaCrashReports) {
        if (carpetaCrashReports == null || !Files.isDirectory(carpetaCrashReports)) {
            return Optional.empty();
        }
        Path mejor = null;
        long mejorFecha = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(carpetaCrashReports, "*.txt")) {
            for (Path p : stream) {
                try {
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > mejorFecha) {
                        mejorFecha = t;
                        mejor = p;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(mejor);
    }

    /**
     * @param archivo    crash report a leer
     * @param jars       metadatos de todos los .jar de la carpeta mods
     * @param problemas  problemas detectados de forma preventiva
     * @param ruidoEnLog conteo de errores por origen en la sesion actual
     */
    public static Diagnostico analizar(Path archivo,
                                       List<MetadatosJar> jars,
                                       List<Problema> problemas,
                                       List<Map.Entry<String, Integer>> ruidoEnLog) {
        List<String> lineas = leerLineas(archivo);
        if (lineas == null) {
            return Diagnostico.builder()
                    .huboCrash(true)
                    .archivoAnalizado(archivo)
                    .descripcion("No pude leer el archivo de crash.")
                    .tipo(TipoProblema.DESCONOCIDO)
                    .confianza(Confianza.NINGUNA)
                    .build();
        }

        Diagnostico.Builder b = Diagnostico.builder().huboCrash(true).archivoAnalizado(archivo);
        try {
            b.fechaCrash(Files.getLastModifiedTime(archivo).toInstant());
        } catch (IOException ignored) {
            b.fechaCrash(Instant.now());
        }

        String texto = String.join("\n", lineas);

        // ---------------------------------------------------- 1. Extraccion
        for (String linea : lineas) {
            String t = linea.trim();
            if (t.startsWith("Description:")) {
                b.descripcion(t.substring("Description:".length()).trim());
                break;
            }
        }
        b.excepcionPrincipal(buscarExcepcionPrincipal(lineas));

        List<String> clases = new ArrayList<>();
        int guardadas = 0;
        for (String linea : lineas) {
            Matcher m = LINEA_AT.matcher(linea);
            if (m.find()) {
                clases.add(m.group(1));
                if (guardadas < MAX_LINEAS_STACK) {
                    b.agregarLineaStack(linea.trim());
                    guardadas++;
                }
            }
        }

        String modNombrado = extraerModNombradoPorForge(lineas, b);

        // ------------------------------------------------ 2. Reconocimiento
        List<Firma.Coincidencia> coincidencias = BaseConocimiento.reconocer(texto);
        b.firmas(coincidencias);

        if (!coincidencias.isEmpty()) {
            Firma ganadora = coincidencias.get(0).firma();
            b.tipo(ganadora.tipo());
            b.sugerencia(ganadora.sugerencia());
            b.agregarEvidencia("Firma reconocida: " + ganadora.id() + " — " + ganadora.explicacion());
            for (Firma.Coincidencia c : coincidencias) {
                b.agregarEvidencia("[" + c.firma().id() + "] " + c.textoCoincidente());
            }
        } else {
            b.tipo(clasificarPorRespaldo(texto));
            b.agregarEvidencia("Ninguna firma conocida coincidio. "
                    + "El diagnostico se apoya solo en el stacktrace.");
        }

        // --------------------------------------------------- 3. Atribucion
        MotorSospecha.Resultado resultado = MotorSospecha.evaluar(new MotorSospecha.Entrada(
                modNombrado,
                clases,
                coincidencias,
                jars == null ? List.of() : jars,
                problemas == null ? List.of() : problemas,
                ruidoEnLog == null ? List.of() : ruidoEnLog));

        b.ranking(resultado.ranking());
        b.confianza(resultado.confianza());

        Sospechoso principal = resultado.principal();
        if (principal != null && resultado.confianza() != Confianza.NINGUNA) {
            b.modSospechoso(principal.modId());
            if (principal.jar() != null) {
                b.jarSospechoso(principal.jar());
            } else {
                resolverJarPorNombre(lineas, jars, b);
            }
            for (Sospechoso.Indicio ind : principal.indicios()) {
                b.agregarEvidencia("(+" + ind.puntos() + ") " + ind.descripcion());
            }
        } else {
            b.agregarEvidencia("Las senales no alcanzan para senalar a un mod concreto.");
        }

        return b.build();
    }

    // ------------------------------------------------------------ auxiliares

    private static List<String> leerLineas(Path archivo) {
        try {
            return Files.readAllLines(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try {
                return Files.readAllLines(archivo, StandardCharsets.ISO_8859_1);
            } catch (IOException e2) {
                return null;
            }
        }
    }

    private static String extraerModNombradoPorForge(List<String> lineas, Diagnostico.Builder b) {
        for (int i = 0; i < lineas.size(); i++) {
            Matcher m = BLOQUE_MOD.matcher(lineas.get(i).trim());
            if (!m.matches()) {
                continue;
            }
            String mod = m.group(1);
            for (int j = i + 1; j < Math.min(i + 15, lineas.size()); j++) {
                String detalle = lineas.get(j);
                if (detalle.trim().startsWith("--")) {
                    break;
                }
                Matcher mf = CAMPO_ARCHIVO_MOD.matcher(detalle);
                if (mf.find()) {
                    b.agregarEvidencia("Mod File: " + mf.group(1).trim());
                }
                Matcher ff = CAMPO_FALLO.matcher(detalle);
                if (ff.find()) {
                    b.agregarEvidencia("Failure message: " + ff.group(1).trim());
                }
            }
            return mod;
        }
        return null;
    }

    /** Ultimo recurso: el reporte nombro un archivo, lo buscamos entre los jars. */
    private static void resolverJarPorNombre(List<String> lineas, List<MetadatosJar> jars,
                                             Diagnostico.Builder b) {
        if (jars == null) {
            return;
        }
        for (String linea : lineas) {
            Matcher mf = CAMPO_ARCHIVO_MOD.matcher(linea);
            if (!mf.find()) {
                continue;
            }
            String declarado = mf.group(1).trim();
            int barra = Math.max(declarado.lastIndexOf('/'), declarado.lastIndexOf('\\'));
            String nombre = barra >= 0 ? declarado.substring(barra + 1) : declarado;
            for (MetadatosJar j : jars) {
                if (j.nombreArchivo().equalsIgnoreCase(nombre)) {
                    b.jarSospechoso(j.archivo());
                    return;
                }
            }
        }
    }

    private static String buscarExcepcionPrincipal(List<String> lineas) {
        for (String linea : lineas) {
            String t = linea.trim();
            if (t.isEmpty() || t.startsWith("at ") || t.startsWith("//")) {
                continue;
            }
            Matcher m = LINEA_EXCEPCION.matcher(t);
            if (m.matches()) {
                return t;
            }
        }
        return "";
    }

    /** Clasificacion minima cuando ninguna firma coincidio. */
    private static TipoProblema clasificarPorRespaldo(String texto) {
        String t = texto.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("exception") || t.contains("error")) {
            return TipoProblema.EXCEPCION_GENERICA;
        }
        return TipoProblema.DESCONOCIDO;
    }

    /** Lista de crash reports ordenada del mas nuevo al mas viejo. */
    public static List<Path> historial(Path carpeta, int maximo) {
        List<Path> todos = new ArrayList<>();
        if (carpeta == null || !Files.isDirectory(carpeta)) {
            return todos;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(carpeta, "*.txt")) {
            for (Path p : stream) {
                todos.add(p);
            }
        } catch (IOException e) {
            return todos;
        }
        todos.sort((a, c) -> {
            try {
                return Files.getLastModifiedTime(c).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });
        return todos.size() > maximo ? new ArrayList<>(todos.subList(0, maximo))
                : Collections.unmodifiableList(todos);
    }
}
